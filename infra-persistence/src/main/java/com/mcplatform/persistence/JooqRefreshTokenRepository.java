package com.mcplatform.persistence;

import static com.mcplatform.persistence.jooq.Tables.REFRESH_TOKEN;
import static com.mcplatform.persistence.jooq.Tables.WEB_AUTH_AUDIT;

import com.mcplatform.application.webauth.port.RefreshTokenRepository;
import com.mcplatform.application.webauth.port.RotateResult;
import com.mcplatform.domain.player.PlayerId;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;

/**
 * jOOQ adapter over the state-stored {@code refresh_token}. The raw token is never stored — only its
 * SHA-256 hash (reusing {@link JooqLinkTokenRepository#sha256Hex} is the caller's job; this adapter
 * already receives hashes). Rotation runs in ONE transaction: it locks the presented row
 * ({@code FOR UPDATE}), and depending on its state marks it consumed + inserts the successor, or — on a
 * replay of an already-consumed token — deletes ALL of the player's tokens (theft signal, research R6).
 * Session lifecycle events are appended to the bridge's {@code web_auth_audit} (never token/hash). No
 * Spring — jOOQ drives the transaction.
 */
public final class JooqRefreshTokenRepository implements RefreshTokenRepository {

    private static final String EVENT_LOGIN = "LOGIN";
    private static final String EVENT_ROTATED = "TOKEN_ROTATED";
    private static final String EVENT_REUSE = "TOKEN_REUSE_DETECTED";
    private static final String EVENT_LOGOUT = "LOGOUT";

    private final DSLContext dsl;

    public JooqRefreshTokenRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public void store(String rawToken, PlayerId player, Instant createdAt, Instant expiresAt) {
        String tokenHash = JooqLinkTokenRepository.sha256Hex(rawToken);
        dsl.transaction(cfg -> {
            DSLContext tx = cfg.dsl();
            tx.insertInto(REFRESH_TOKEN)
                    .set(REFRESH_TOKEN.TOKEN_HASH, tokenHash)
                    .set(REFRESH_TOKEN.PLAYER_UUID, player.value())
                    .set(REFRESH_TOKEN.CREATED_AT, offset(createdAt))
                    .set(REFRESH_TOKEN.EXPIRES_AT, offset(expiresAt))
                    .execute();
            audit(tx, player.value(), EVENT_LOGIN);
        });
    }

    @Override
    public RotateResult rotate(String presentedRawToken, String newRawToken, Instant now, Instant newExpiresAt) {
        String presentedHash = JooqLinkTokenRepository.sha256Hex(presentedRawToken);
        String newTokenHash = JooqLinkTokenRepository.sha256Hex(newRawToken);
        return dsl.transactionResult(cfg -> {
            DSLContext tx = cfg.dsl();
            Record row = tx.select(REFRESH_TOKEN.PLAYER_UUID, REFRESH_TOKEN.EXPIRES_AT, REFRESH_TOKEN.ROTATED_AT)
                    .from(REFRESH_TOKEN)
                    .where(REFRESH_TOKEN.TOKEN_HASH.eq(presentedHash))
                    .forUpdate()
                    .fetchOne();
            if (row == null) {
                return new RotateResult.Invalid();
            }
            UUID player = row.get(REFRESH_TOKEN.PLAYER_UUID);

            // Already consumed → strict replay = theft signal: kill ALL of the player's tokens.
            if (row.get(REFRESH_TOKEN.ROTATED_AT) != null) {
                tx.deleteFrom(REFRESH_TOKEN).where(REFRESH_TOKEN.PLAYER_UUID.eq(player)).execute();
                audit(tx, player, EVENT_REUSE);
                return new RotateResult.Replay(PlayerId.of(player));
            }

            // Expired (boundary inclusive) → invalid, nothing changes.
            if (!row.get(REFRESH_TOKEN.EXPIRES_AT).toInstant().isAfter(now)) {
                return new RotateResult.Invalid();
            }

            // Active → consume the presented token and insert its successor in the same transaction.
            tx.update(REFRESH_TOKEN)
                    .set(REFRESH_TOKEN.ROTATED_AT, offset(now))
                    .where(REFRESH_TOKEN.TOKEN_HASH.eq(presentedHash))
                    .execute();
            tx.insertInto(REFRESH_TOKEN)
                    .set(REFRESH_TOKEN.TOKEN_HASH, newTokenHash)
                    .set(REFRESH_TOKEN.PLAYER_UUID, player)
                    .set(REFRESH_TOKEN.CREATED_AT, offset(now))
                    .set(REFRESH_TOKEN.EXPIRES_AT, offset(newExpiresAt))
                    .set(REFRESH_TOKEN.ROTATED_FROM, presentedHash)
                    .execute();
            audit(tx, player, EVENT_ROTATED);
            return new RotateResult.Rotated(PlayerId.of(player));
        });
    }

    @Override
    public boolean deleteByRawToken(String rawToken) {
        String tokenHash = JooqLinkTokenRepository.sha256Hex(rawToken);
        return dsl.transactionResult(cfg -> {
            DSLContext tx = cfg.dsl();
            UUID player = tx.select(REFRESH_TOKEN.PLAYER_UUID)
                    .from(REFRESH_TOKEN)
                    .where(REFRESH_TOKEN.TOKEN_HASH.eq(tokenHash))
                    .fetchOne(REFRESH_TOKEN.PLAYER_UUID);
            if (player == null) {
                return false; // idempotent: nothing to do
            }
            tx.deleteFrom(REFRESH_TOKEN).where(REFRESH_TOKEN.TOKEN_HASH.eq(tokenHash)).execute();
            audit(tx, player, EVENT_LOGOUT);
            return true;
        });
    }

    @Override
    public int deleteAllForPlayer(PlayerId player) {
        return dsl.deleteFrom(REFRESH_TOKEN).where(REFRESH_TOKEN.PLAYER_UUID.eq(player.value())).execute();
    }

    @Override
    public int purgeExpired(Instant now) {
        return dsl.deleteFrom(REFRESH_TOKEN).where(REFRESH_TOKEN.EXPIRES_AT.le(offset(now))).execute();
    }

    /** Append-only audit; {@code at} defaults to DB now(). Never stores token/hash. */
    private void audit(DSLContext tx, UUID player, String eventType) {
        tx.insertInto(WEB_AUTH_AUDIT)
                .set(WEB_AUTH_AUDIT.PLAYER_UUID, player)
                .set(WEB_AUTH_AUDIT.EVENT_TYPE, eventType)
                .execute();
    }

    private static OffsetDateTime offset(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
