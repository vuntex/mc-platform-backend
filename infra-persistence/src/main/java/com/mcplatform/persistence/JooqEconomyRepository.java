package com.mcplatform.persistence;

import static com.mcplatform.persistence.jooq.Tables.ECONOMY_EVENT;
import static com.mcplatform.persistence.jooq.Tables.PLAYER_BALANCE;

import com.mcplatform.application.economy.port.AppendResult;
import com.mcplatform.application.economy.port.ConcurrencyConflictException;
import com.mcplatform.application.economy.port.EconomyEventStore;
import com.mcplatform.application.economy.port.TransferResult;
import com.mcplatform.domain.economy.Balance;
import com.mcplatform.domain.economy.CurrencyCode;
import com.mcplatform.domain.economy.Money;
import com.mcplatform.domain.economy.PendingEconomyEvent;
import com.mcplatform.domain.economy.TransactionId;
import com.mcplatform.domain.economy.TransferId;
import com.mcplatform.domain.player.PlayerId;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.exception.IntegrityConstraintViolationException;

/**
 * jOOQ adapter over the event-sourced economy (PROGRESS.md sections 3, 6 &amp; 7). Writes happen in a
 * single jOOQ transaction: idempotency check → insert event(s) (DB assigns sequence_no) → project
 * onto player_balance guarded by the expected version (optimistic locking). A transfer writes both
 * legs and projects both balances atomically; projections are updated in a deterministic player order
 * to avoid deadlocks between mirrored transfers. No Spring — jOOQ drives the transaction.
 */
public final class JooqEconomyRepository implements EconomyEventStore {

    private final DSLContext dsl;

    public JooqEconomyRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Balance currentBalance(PlayerId player, CurrencyCode currency) {
        var rec = dsl.select(PLAYER_BALANCE.BALANCE, PLAYER_BALANCE.VERSION)
                .from(PLAYER_BALANCE)
                .where(PLAYER_BALANCE.PLAYER_UUID.eq(player.value())
                        .and(PLAYER_BALANCE.CURRENCY_CODE.eq(currency.value())))
                .fetchOne();
        if (rec == null) {
            return Balance.initial(player, currency);
        }
        return new Balance(player, currency, Money.of(rec.value1()), rec.value2());
    }

    @Override
    public void ensureZeroBalance(PlayerId player, CurrencyCode currency) {
        // Create the projection row at 0/version 0 if absent; no event (folding zero events is 0).
        dsl.insertInto(PLAYER_BALANCE)
                .set(PLAYER_BALANCE.PLAYER_UUID, player.value())
                .set(PLAYER_BALANCE.CURRENCY_CODE, currency.value())
                .set(PLAYER_BALANCE.BALANCE, 0L)
                .set(PLAYER_BALANCE.VERSION, 0L)
                .onConflictDoNothing()
                .execute();
    }

    @Override
    public AppendResult append(PendingEconomyEvent event, long expectedVersion) {
        try {
            return dsl.transactionResult(cfg -> {
                DSLContext tx = cfg.dsl();
                AppendResult replay = lookup(tx, event.transactionId());
                if (replay != null) {
                    return replay;
                }
                Inserted inserted = insertEvent(tx, event);
                projectBalance(tx, event.player().value(), event.currency().value(),
                        event.balanceAfter().units(), inserted.sequenceNo(), expectedVersion);
                return new AppendResult(inserted.sequenceNo(), event.balanceAfter(),
                        inserted.createdAt().toInstant(), false);
            });
        } catch (IntegrityConstraintViolationException duplicate) {
            // A concurrent writer committed the same transaction_id first (uq_transaction). The
            // committed row is now visible — return it as an idempotent replay instead of erroring.
            AppendResult replay = lookup(dsl, event.transactionId());
            if (replay != null) {
                return replay;
            }
            throw duplicate; // a different integrity error (e.g. missing player/currency FK)
        }
    }

    @Override
    public TransferResult transfer(PendingEconomyEvent out, long expectedFromVersion,
            PendingEconomyEvent in, long expectedToVersion) {
        try {
            return dsl.transactionResult(cfg -> {
                DSLContext tx = cfg.dsl();

                // The two legs are written atomically, so the presence of the out leg means the whole
                // transfer already happened.
                AppendResult replayOut = lookup(tx, out.transactionId());
                if (replayOut != null) {
                    AppendResult replayIn = lookup(tx, in.transactionId());
                    return new TransferResult(replayOut, replayIn);
                }

                Inserted insertedOut = insertEvent(tx, out);
                Inserted insertedIn = insertEvent(tx, in);

                // Update projections in a deterministic order (by player uuid) to avoid deadlocks
                // between A->B and B->A transfers running concurrently.
                Leg legOut = new Leg(out.player().value(), out.currency().value(),
                        out.balanceAfter().units(), insertedOut.sequenceNo(), expectedFromVersion);
                Leg legIn = new Leg(in.player().value(), in.currency().value(),
                        in.balanceAfter().units(), insertedIn.sequenceNo(), expectedToVersion);
                for (Leg leg : orderByPlayer(legOut, legIn)) {
                    projectBalance(tx, leg.player(), leg.currency(), leg.balance(), leg.sequenceNo(), leg.expectedVersion());
                }

                return new TransferResult(
                        new AppendResult(insertedOut.sequenceNo(), out.balanceAfter(), insertedOut.createdAt().toInstant(), false),
                        new AppendResult(insertedIn.sequenceNo(), in.balanceAfter(), insertedIn.createdAt().toInstant(), false));
            });
        } catch (IntegrityConstraintViolationException duplicate) {
            // A concurrent transfer with the same correlation id committed first — re-read both legs
            // and return them as an idempotent replay.
            AppendResult replayOut = lookup(dsl, out.transactionId());
            AppendResult replayIn = lookup(dsl, in.transactionId());
            if (replayOut != null && replayIn != null) {
                return new TransferResult(replayOut, replayIn);
            }
            throw duplicate;
        }
    }

    @Override
    public Optional<AppendResult> findByTransactionId(TransactionId transactionId) {
        return Optional.ofNullable(lookup(dsl, transactionId));
    }

    @Override
    public Optional<TransferResult> findTransfer(TransferId correlationId) {
        AppendResult out = lookup(dsl, correlationId.outboundTransactionId());
        AppendResult in = lookup(dsl, correlationId.inboundTransactionId());
        return (out != null && in != null) ? Optional.of(new TransferResult(out, in)) : Optional.empty();
    }

    // --- helpers -----------------------------------------------------------

    /** Returns the recorded result for a transaction id, or null if it was never written. */
    private AppendResult lookup(DSLContext ctx, TransactionId transactionId) {
        var rec = ctx.select(ECONOMY_EVENT.SEQUENCE_NO, ECONOMY_EVENT.BALANCE_AFTER, ECONOMY_EVENT.CREATED_AT)
                .from(ECONOMY_EVENT)
                .where(ECONOMY_EVENT.TRANSACTION_ID.eq(transactionId.value()))
                .fetchOne();
        if (rec == null) {
            return null;
        }
        return new AppendResult(rec.value1(), Money.of(rec.value2()), rec.value3().toInstant(), true);
    }

    private Inserted insertEvent(DSLContext tx, PendingEconomyEvent event) {
        var rec = tx.insertInto(ECONOMY_EVENT)
                .set(ECONOMY_EVENT.PLAYER_UUID, event.player().value())
                .set(ECONOMY_EVENT.CURRENCY_CODE, event.currency().value())
                .set(ECONOMY_EVENT.EVENT_TYPE, event.type().name())
                .set(ECONOMY_EVENT.AMOUNT, event.amount().units())
                .set(ECONOMY_EVENT.BALANCE_AFTER, event.balanceAfter().units())
                .set(ECONOMY_EVENT.TRANSACTION_ID, event.transactionId().value())
                .set(ECONOMY_EVENT.SOURCE, event.source())
                .set(ECONOMY_EVENT.METADATA, metadata(event))
                .returningResult(ECONOMY_EVENT.SEQUENCE_NO, ECONOMY_EVENT.CREATED_AT)
                .fetchOne();
        return new Inserted(rec.value1(), rec.value2());
    }

    /** Only correlation_id is stored for now (set on transfer legs). UUID content needs no escaping. */
    private JSONB metadata(PendingEconomyEvent event) {
        if (event.correlationId() == null) {
            return null;
        }
        return JSONB.valueOf("{\"correlation_id\":\"" + event.correlationId().value() + "\"}");
    }

    private void projectBalance(DSLContext tx, UUID player, String currency, long balanceAfter,
            long sequenceNo, long expectedVersion) {
        if (expectedVersion == 0) {
            int inserts = tx.insertInto(PLAYER_BALANCE)
                    .set(PLAYER_BALANCE.PLAYER_UUID, player)
                    .set(PLAYER_BALANCE.CURRENCY_CODE, currency)
                    .set(PLAYER_BALANCE.BALANCE, balanceAfter)
                    .set(PLAYER_BALANCE.VERSION, sequenceNo)
                    .onConflictDoNothing()
                    .execute();
            if (inserts == 0) {
                // The row already exists AT version 0 — e.g. materialised by ensureZeroBalance (a
                // default-0 currency). That is NOT a conflict: mutate it in place, still optimistically
                // guarded on version 0. Without this, a version-0 row could never be written (every
                // append would hit the INSERT path and "conflict" forever).
                int updates = tx.update(PLAYER_BALANCE)
                        .set(PLAYER_BALANCE.BALANCE, balanceAfter)
                        .set(PLAYER_BALANCE.VERSION, sequenceNo)
                        .set(PLAYER_BALANCE.UPDATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
                        .where(PLAYER_BALANCE.PLAYER_UUID.eq(player)
                                .and(PLAYER_BALANCE.CURRENCY_CODE.eq(currency))
                                .and(PLAYER_BALANCE.VERSION.eq(0L)))
                        .execute();
                if (updates == 0) {
                    throw new ConcurrencyConflictException(
                            "version mismatch for " + player + "/" + currency + " (expected 0)");
                }
            }
        } else {
            int updates = tx.update(PLAYER_BALANCE)
                    .set(PLAYER_BALANCE.BALANCE, balanceAfter)
                    .set(PLAYER_BALANCE.VERSION, sequenceNo)
                    .set(PLAYER_BALANCE.UPDATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
                    .where(PLAYER_BALANCE.PLAYER_UUID.eq(player)
                            .and(PLAYER_BALANCE.CURRENCY_CODE.eq(currency))
                            .and(PLAYER_BALANCE.VERSION.eq(expectedVersion)))
                    .execute();
            if (updates == 0) {
                throw new ConcurrencyConflictException(
                        "version mismatch for " + player + "/" + currency + " (expected " + expectedVersion + ")");
            }
        }
    }

    private static List<Leg> orderByPlayer(Leg a, Leg b) {
        return a.player().compareTo(b.player()) <= 0 ? List.of(a, b) : List.of(b, a);
    }

    private record Inserted(long sequenceNo, OffsetDateTime createdAt) {}

    private record Leg(UUID player, String currency, long balance, long sequenceNo, long expectedVersion) {}
}
