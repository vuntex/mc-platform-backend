package com.mcplatform.persistence;

import static com.mcplatform.persistence.jooq.Tables.WEB_ACCOUNT;
import static com.mcplatform.persistence.jooq.Tables.WEB_AUTH_AUDIT;
import static com.mcplatform.persistence.jooq.Tables.WEB_LINK_TOKEN;

import com.mcplatform.application.webauth.port.RedeemOutcome;
import com.mcplatform.application.webauth.port.TokenInvalidException;
import com.mcplatform.application.webauth.port.WebAccountConflictException;
import com.mcplatform.application.webauth.port.WebAccountRepository;
import com.mcplatform.domain.player.PlayerId;
import com.mcplatform.domain.webauth.TokenPurpose;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;

/**
 * jOOQ adapter over the state-stored {@code web_account}. The atomic {@link #redeem} performs the whole
 * single-use redemption in ONE transaction (spec FR-018): lock the matching unexpired token row, apply
 * the purpose's account write, delete the token (single-use, FR-012), and append an audit row. No
 * optimistic-locking {@code version} column: a web account is written only by its owner's redemption
 * path, and concurrent redemptions of the same token serialize on the {@code SELECT … FOR UPDATE} of the
 * single token row — so OCC would add nothing (plan/data-model). No Spring — jOOQ drives the transaction.
 */
public final class JooqWebAccountRepository implements WebAccountRepository {

    private static final String EVENT_ACCOUNT_CREATED = "ACCOUNT_CREATED";
    private static final String EVENT_PASSWORD_RESET = "PASSWORD_RESET";

    private final DSLContext dsl;

    public JooqWebAccountRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public boolean exists(PlayerId playerUuid) {
        return dsl.fetchExists(
                dsl.selectFrom(WEB_ACCOUNT).where(WEB_ACCOUNT.PLAYER_UUID.eq(playerUuid.value())));
    }

    @Override
    public RedeemOutcome redeem(String rawToken, String passwordHash, Instant now) {
        String tokenHash = JooqLinkTokenRepository.sha256Hex(rawToken);
        OffsetDateTime ts = offset(now);
        return dsl.transactionResult(cfg -> {
            DSLContext tx = cfg.dsl();

            // Lock the matching, unexpired token row. Unknown / expired / already-used all look identical.
            Record token = tx.select(WEB_LINK_TOKEN.PLAYER_UUID, WEB_LINK_TOKEN.PURPOSE)
                    .from(WEB_LINK_TOKEN)
                    .where(WEB_LINK_TOKEN.TOKEN_HASH.eq(tokenHash)
                            .and(WEB_LINK_TOKEN.EXPIRES_AT.gt(ts)))
                    .forUpdate()
                    .fetchOne();
            if (token == null) {
                throw new TokenInvalidException("token unknown, expired or already used");
            }
            UUID playerUuid = token.get(WEB_LINK_TOKEN.PLAYER_UUID);
            TokenPurpose purpose = TokenPurpose.valueOf(token.get(WEB_LINK_TOKEN.PURPOSE));

            RedeemOutcome outcome = switch (purpose) {
                case LINK -> createAccount(tx, playerUuid, passwordHash, ts);
                case RESET -> resetPassword(tx, playerUuid, passwordHash, ts);
            };

            // Single-use: consume the token in the same transaction as the account write.
            tx.deleteFrom(WEB_LINK_TOKEN).where(WEB_LINK_TOKEN.TOKEN_HASH.eq(tokenHash)).execute();
            return outcome;
        });
    }

    private RedeemOutcome createAccount(DSLContext tx, UUID playerUuid, String passwordHash, OffsetDateTime ts) {
        int inserted = tx.insertInto(WEB_ACCOUNT)
                .set(WEB_ACCOUNT.PLAYER_UUID, playerUuid)
                .set(WEB_ACCOUNT.PASSWORD_HASH, passwordHash)
                .set(WEB_ACCOUNT.CREATED_AT, ts)
                .set(WEB_ACCOUNT.PASSWORD_UPDATED_AT, ts)
                .onConflictDoNothing()
                .execute();
        if (inserted == 0) {
            throw new WebAccountConflictException("web account already exists for this identity");
        }
        audit(tx, playerUuid, EVENT_ACCOUNT_CREATED, ts);
        return RedeemOutcome.LINK_CREATED;
    }

    private RedeemOutcome resetPassword(DSLContext tx, UUID playerUuid, String passwordHash, OffsetDateTime ts) {
        int updated = tx.update(WEB_ACCOUNT)
                .set(WEB_ACCOUNT.PASSWORD_HASH, passwordHash)
                .set(WEB_ACCOUNT.PASSWORD_UPDATED_AT, ts)
                .where(WEB_ACCOUNT.PLAYER_UUID.eq(playerUuid))
                .execute();
        if (updated == 0) {
            throw new WebAccountConflictException("no web account for this identity");
        }
        audit(tx, playerUuid, EVENT_PASSWORD_RESET, ts);
        return RedeemOutcome.RESET_DONE;
    }

    private void audit(DSLContext tx, UUID playerUuid, String eventType, OffsetDateTime ts) {
        tx.insertInto(WEB_AUTH_AUDIT)
                .set(WEB_AUTH_AUDIT.PLAYER_UUID, playerUuid)
                .set(WEB_AUTH_AUDIT.EVENT_TYPE, eventType)
                .set(WEB_AUTH_AUDIT.AT, ts)
                .execute();
    }

    private static OffsetDateTime offset(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
