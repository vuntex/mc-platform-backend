package com.mcplatform.persistence;

import static com.mcplatform.persistence.jooq.Tables.WEB_LINK_TOKEN;

import com.mcplatform.application.webauth.port.LinkTokenRepository;
import com.mcplatform.domain.player.PlayerId;
import com.mcplatform.domain.webauth.TokenPurpose;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Optional;
import org.jooq.DSLContext;

/**
 * jOOQ adapter for the short-lived web-auth tokens. The raw token is never stored — only its SHA-256
 * hash (research R3); a DB read leak therefore yields no redeemable token. {@code issue} deletes any
 * existing token of the same purpose for the player and inserts the new one in one transaction
 * (one active token per (uuid, purpose), FR-013). No Spring — jOOQ drives the transaction.
 */
public final class JooqLinkTokenRepository implements LinkTokenRepository {

    private final DSLContext dsl;

    public JooqLinkTokenRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public void issue(String rawToken, PlayerId playerUuid, TokenPurpose purpose, Instant expiresAt, Instant now) {
        dsl.transaction(cfg -> {
            DSLContext tx = cfg.dsl();
            tx.deleteFrom(WEB_LINK_TOKEN)
                    .where(WEB_LINK_TOKEN.PLAYER_UUID.eq(playerUuid.value())
                            .and(WEB_LINK_TOKEN.PURPOSE.eq(purpose.name())))
                    .execute();
            tx.insertInto(WEB_LINK_TOKEN)
                    .set(WEB_LINK_TOKEN.TOKEN_HASH, sha256Hex(rawToken))
                    .set(WEB_LINK_TOKEN.PLAYER_UUID, playerUuid.value())
                    .set(WEB_LINK_TOKEN.PURPOSE, purpose.name())
                    .set(WEB_LINK_TOKEN.EXPIRES_AT, offset(expiresAt))
                    .set(WEB_LINK_TOKEN.CREATED_AT, offset(now))
                    .execute();
        });
    }

    @Override
    public Optional<Instant> lastCreatedAt(PlayerId playerUuid, TokenPurpose purpose) {
        OffsetDateTime created = dsl.select(WEB_LINK_TOKEN.CREATED_AT)
                .from(WEB_LINK_TOKEN)
                .where(WEB_LINK_TOKEN.PLAYER_UUID.eq(playerUuid.value())
                        .and(WEB_LINK_TOKEN.PURPOSE.eq(purpose.name())))
                .fetchOneInto(OffsetDateTime.class);
        return created == null ? Optional.empty() : Optional.of(created.toInstant());
    }

    @Override
    public int deleteExpired(Instant now) {
        return dsl.deleteFrom(WEB_LINK_TOKEN)
                .where(WEB_LINK_TOKEN.EXPIRES_AT.le(offset(now)))
                .execute();
    }

    /** SHA-256 hex of the raw token. The token already carries ≥128 bit of entropy, so no salt/slow hash. */
    static String sha256Hex(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static OffsetDateTime offset(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
