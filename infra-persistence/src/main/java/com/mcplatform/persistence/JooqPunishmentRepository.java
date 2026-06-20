package com.mcplatform.persistence;

import static com.mcplatform.persistence.jooq.Tables.PLAYER;
import static com.mcplatform.persistence.jooq.Tables.PUNISHMENT;
import static com.mcplatform.persistence.jooq.Tables.PUNISHMENT_EVENT;

import com.mcplatform.application.punishment.port.PunishmentEventStore;
import com.mcplatform.application.punishment.port.PunishmentNotFoundException;
import com.mcplatform.domain.player.PlayerId;
import com.mcplatform.domain.punishment.PendingPunishmentEvent;
import com.mcplatform.domain.punishment.Punishment;
import com.mcplatform.domain.punishment.PunishmentCategory;
import com.mcplatform.domain.punishment.PunishmentConflictException;
import com.mcplatform.domain.punishment.PunishmentId;
import com.mcplatform.domain.punishment.PunishmentTxId;
import com.mcplatform.domain.punishment.PunishmentType;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.exception.IntegrityConstraintViolationException;

/**
 * jOOQ adapter over the event-sourced punishment store. Writes happen in a single transaction:
 * idempotency check → (for exclusive categories) lock the player row and re-check the coexistence rule
 * → insert the event (DB assigns sequence_no) → project onto {@code punishment}. The per-player
 * {@code SELECT ... FOR UPDATE} serialises concurrent issues for one player, which is the right
 * concurrency guard here: the invariant is "≤1 active punishment per exclusive category", and it
 * depends on {@code now()} (expiry) so a static unique index cannot express it. No Spring — jOOQ drives
 * the transaction.
 */
public final class JooqPunishmentRepository implements PunishmentEventStore {

    private final DSLContext dsl;

    public JooqPunishmentRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public List<Punishment> activeForPlayer(PlayerId player, Instant now) {
        return dsl.selectFrom(PUNISHMENT)
                .where(PUNISHMENT.PLAYER_UUID.eq(player.value())
                        .and(PUNISHMENT.REVOKED_AT.isNull())
                        .and(activeExpiry(now)))
                .fetch(JooqPunishmentRepository::toPunishment);
    }

    @Override
    public Optional<Punishment> find(PunishmentId id) {
        Record rec = dsl.selectFrom(PUNISHMENT)
                .where(PUNISHMENT.PUNISHMENT_ID.eq(id.value()))
                .fetchOne();
        return rec == null ? Optional.empty() : Optional.of(toPunishment(rec));
    }

    @Override
    public Optional<Punishment> findByTransactionId(PunishmentTxId transactionId) {
        return Optional.ofNullable(lookupByTx(dsl, transactionId));
    }

    @Override
    public Punishment issue(PendingPunishmentEvent event, Instant now) {
        try {
            return dsl.transactionResult(cfg -> {
                DSLContext tx = cfg.dsl();
                Punishment replay = lookupByTx(tx, event.transactionId());
                if (replay != null) {
                    return replay;
                }
                PunishmentCategory category = event.type().category();
                if (category.isExclusive()) {
                    lockPlayer(tx, event.player().value());
                    if (hasActiveInCategory(tx, event.player().value(), category, now)) {
                        throw new PunishmentConflictException(event.player(), category);
                    }
                }
                long sequenceNo = insertEvent(tx, event);
                tx.insertInto(PUNISHMENT)
                        .set(PUNISHMENT.PUNISHMENT_ID, event.punishmentId().value())
                        .set(PUNISHMENT.PLAYER_UUID, event.player().value())
                        .set(PUNISHMENT.TYPE, event.type().name())
                        .set(PUNISHMENT.CATEGORY, category.name())
                        .set(PUNISHMENT.REASON, event.reason())
                        .set(PUNISHMENT.ISSUED_BY, event.actor().value())
                        .set(PUNISHMENT.ISSUED_AT, offset(event.occurredAt()))
                        .set(PUNISHMENT.EXPIRES_AT, offset(event.expiresAt()))
                        .set(PUNISHMENT.VERSION, sequenceNo)
                        .execute();
                return new Punishment(event.punishmentId(), event.player(), event.type(), event.reason(),
                        event.actor(), event.occurredAt(), event.expiresAt(), null, null, sequenceNo);
            });
        } catch (IntegrityConstraintViolationException duplicate) {
            // A concurrent writer committed the same transaction_id first — return it as a replay.
            Punishment replay = lookupByTx(dsl, event.transactionId());
            if (replay != null) {
                return replay;
            }
            throw duplicate; // a different integrity error (e.g. missing player FK)
        }
    }

    @Override
    public Punishment revoke(PendingPunishmentEvent event) {
        try {
            return dsl.transactionResult(cfg -> {
                DSLContext tx = cfg.dsl();
                Punishment replay = lookupByTx(tx, event.transactionId());
                if (replay != null) {
                    return replay;
                }
                Record rec = tx.selectFrom(PUNISHMENT)
                        .where(PUNISHMENT.PUNISHMENT_ID.eq(event.punishmentId().value()))
                        .forUpdate()
                        .fetchOne();
                if (rec == null) {
                    throw new PunishmentNotFoundException(event.punishmentId());
                }
                Punishment current = toPunishment(rec);
                if (current.isRevoked()) {
                    throw new PunishmentConflictException(
                            "punishment already revoked: " + event.punishmentId().value());
                }
                long sequenceNo = insertEvent(tx, event);
                tx.update(PUNISHMENT)
                        .set(PUNISHMENT.REVOKED_BY, event.actor().value())
                        .set(PUNISHMENT.REVOKED_AT, offset(event.occurredAt()))
                        .set(PUNISHMENT.VERSION, sequenceNo)
                        .set(PUNISHMENT.UPDATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
                        .where(PUNISHMENT.PUNISHMENT_ID.eq(event.punishmentId().value()))
                        .execute();
                return new Punishment(current.id(), current.player(), current.type(), current.reason(),
                        current.issuedBy(), current.issuedAt(), current.expiresAt(),
                        event.actor(), event.occurredAt(), sequenceNo);
            });
        } catch (IntegrityConstraintViolationException duplicate) {
            Punishment replay = lookupByTx(dsl, event.transactionId());
            if (replay != null) {
                return replay;
            }
            throw duplicate;
        }
    }

    // --- helpers -----------------------------------------------------------

    private long insertEvent(DSLContext tx, PendingPunishmentEvent event) {
        return tx.insertInto(PUNISHMENT_EVENT)
                .set(PUNISHMENT_EVENT.PUNISHMENT_ID, event.punishmentId().value())
                .set(PUNISHMENT_EVENT.PLAYER_UUID, event.player().value())
                .set(PUNISHMENT_EVENT.EVENT_TYPE, event.eventType().name())
                .set(PUNISHMENT_EVENT.PUNISHMENT_TYPE, event.type() == null ? null : event.type().name())
                .set(PUNISHMENT_EVENT.REASON, event.reason())
                .set(PUNISHMENT_EVENT.ACTOR_UUID, event.actor().value())
                .set(PUNISHMENT_EVENT.EXPIRES_AT, offset(event.expiresAt()))
                .set(PUNISHMENT_EVENT.TRANSACTION_ID, event.transactionId().value())
                .set(PUNISHMENT_EVENT.SOURCE, event.source())
                .returningResult(PUNISHMENT_EVENT.SEQUENCE_NO)
                .fetchOne()
                .value1();
    }

    private void lockPlayer(DSLContext tx, UUID player) {
        // Serialises concurrent issues for this player so the exclusivity re-check below is race-free.
        tx.select(PLAYER.UUID).from(PLAYER).where(PLAYER.UUID.eq(player)).forUpdate().fetch();
    }

    private boolean hasActiveInCategory(DSLContext tx, UUID player, PunishmentCategory category, Instant now) {
        return tx.fetchExists(
                tx.selectOne().from(PUNISHMENT)
                        .where(PUNISHMENT.PLAYER_UUID.eq(player)
                                .and(PUNISHMENT.CATEGORY.eq(category.name()))
                                .and(PUNISHMENT.REVOKED_AT.isNull())
                                .and(activeExpiry(now))));
    }

    private Punishment lookupByTx(DSLContext ctx, PunishmentTxId tx) {
        UUID punishmentId = ctx.select(PUNISHMENT_EVENT.PUNISHMENT_ID)
                .from(PUNISHMENT_EVENT)
                .where(PUNISHMENT_EVENT.TRANSACTION_ID.eq(tx.value()))
                .fetchOne(PUNISHMENT_EVENT.PUNISHMENT_ID);
        if (punishmentId == null) {
            return null;
        }
        Record rec = ctx.selectFrom(PUNISHMENT).where(PUNISHMENT.PUNISHMENT_ID.eq(punishmentId)).fetchOne();
        return rec == null ? null : toPunishment(rec);
    }

    private static org.jooq.Condition activeExpiry(Instant now) {
        return PUNISHMENT.EXPIRES_AT.isNull().or(PUNISHMENT.EXPIRES_AT.gt(offset(now)));
    }

    private static Punishment toPunishment(Record r) {
        OffsetDateTime expiresAt = r.get(PUNISHMENT.EXPIRES_AT);
        UUID revokedBy = r.get(PUNISHMENT.REVOKED_BY);
        OffsetDateTime revokedAt = r.get(PUNISHMENT.REVOKED_AT);
        return new Punishment(
                PunishmentId.of(r.get(PUNISHMENT.PUNISHMENT_ID)),
                PlayerId.of(r.get(PUNISHMENT.PLAYER_UUID)),
                PunishmentType.valueOf(r.get(PUNISHMENT.TYPE)),
                r.get(PUNISHMENT.REASON),
                PlayerId.of(r.get(PUNISHMENT.ISSUED_BY)),
                r.get(PUNISHMENT.ISSUED_AT).toInstant(),
                expiresAt == null ? null : expiresAt.toInstant(),
                revokedBy == null ? null : PlayerId.of(revokedBy),
                revokedAt == null ? null : revokedAt.toInstant(),
                r.get(PUNISHMENT.VERSION));
    }

    private static OffsetDateTime offset(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
