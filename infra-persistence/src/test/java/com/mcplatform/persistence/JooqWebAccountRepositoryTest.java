package com.mcplatform.persistence;

import static com.mcplatform.persistence.jooq.Tables.REFRESH_TOKEN;
import static com.mcplatform.persistence.jooq.Tables.WEB_ACCOUNT;
import static com.mcplatform.persistence.jooq.Tables.WEB_AUTH_AUDIT;
import static com.mcplatform.persistence.jooq.Tables.WEB_LINK_TOKEN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mcplatform.application.webauth.port.RedeemOutcome;
import com.mcplatform.application.webauth.port.TokenInvalidException;
import com.mcplatform.application.webauth.port.WebAccountConflictException;
import com.mcplatform.domain.player.PlayerId;
import com.mcplatform.domain.webauth.TokenPurpose;
import com.mcplatform.domain.webauth.WebAccount;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Integration test for the atomic redeem path of the jOOQ web-account adapter. */
@Testcontainers
class JooqWebAccountRepositoryTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    static DSLContext dsl;
    static JooqWebAccountRepository accounts;
    static JooqLinkTokenRepository tokens;
    static JooqPlayerRepository players;
    static JooqRefreshTokenRepository refreshTokens;

    private final Instant now = Instant.parse("2026-06-24T12:00:00Z");

    @BeforeAll
    static void migrateAndWire() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(PG.getJdbcUrl());
        ds.setUser(PG.getUsername());
        ds.setPassword(PG.getPassword());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        dsl = DSL.using(ds, SQLDialect.POSTGRES);
        accounts = new JooqWebAccountRepository(dsl);
        tokens = new JooqLinkTokenRepository(dsl);
        players = new JooqPlayerRepository(dsl);
        refreshTokens = new JooqRefreshTokenRepository(dsl);
    }

    private PlayerId newPlayer() {
        PlayerId p = PlayerId.of(UUID.randomUUID());
        players.save(p, "Steve", Instant.now());
        return p;
    }

    private String hash(PlayerId p) {
        return dsl.select(WEB_ACCOUNT.PASSWORD_HASH).from(WEB_ACCOUNT)
                .where(WEB_ACCOUNT.PLAYER_UUID.eq(p.value())).fetchOneInto(String.class);
    }

    private long audits(PlayerId p, String type) {
        return dsl.fetchCount(WEB_AUTH_AUDIT,
                WEB_AUTH_AUDIT.PLAYER_UUID.eq(p.value()).and(WEB_AUTH_AUDIT.EVENT_TYPE.eq(type)));
    }

    @Test
    void existsReflectsAccountPresence() {
        PlayerId p = newPlayer();
        assertThat(accounts.exists(p)).isFalse();
        tokens.issue("t-exists", p, TokenPurpose.LINK, now.plusSeconds(600), now);
        accounts.redeem("t-exists", "hash-1", now);
        assertThat(accounts.exists(p)).isTrue();
    }

    @Test
    void redeemLinkCreatesAccountConsumesTokenAndAudits() {
        PlayerId p = newPlayer();
        tokens.issue("raw-create", p, TokenPurpose.LINK, now.plusSeconds(600), now);

        RedeemOutcome outcome = accounts.redeem("raw-create", "hash-1", now);

        assertThat(outcome).isEqualTo(RedeemOutcome.LINK_CREATED);
        assertThat(hash(p)).isEqualTo("hash-1");
        // single-use: token row gone
        assertThat(dsl.fetchCount(WEB_LINK_TOKEN, WEB_LINK_TOKEN.PLAYER_UUID.eq(p.value()))).isZero();
        assertThat(audits(p, "ACCOUNT_CREATED")).isEqualTo(1);
    }

    @Test
    void redeemReplayFailsBecauseTokenIsGone() {
        PlayerId p = newPlayer();
        tokens.issue("raw-replay", p, TokenPurpose.LINK, now.plusSeconds(600), now);
        accounts.redeem("raw-replay", "hash-1", now);

        assertThatThrownBy(() -> accounts.redeem("raw-replay", "hash-2", now))
                .isInstanceOf(TokenInvalidException.class);
    }

    @Test
    void redeemLinkConflictsWhenAccountAlreadyExists() {
        PlayerId p = newPlayer();
        tokens.issue("first", p, TokenPurpose.LINK, now.plusSeconds(600), now);
        accounts.redeem("first", "hash-1", now);
        // a second LINK token for the same identity (issue is purpose-scoped) → redeem must conflict
        tokens.issue("second", p, TokenPurpose.LINK, now.plusSeconds(600), now);

        assertThatThrownBy(() -> accounts.redeem("second", "hash-2", now))
                .isInstanceOf(WebAccountConflictException.class);
    }

    @Test
    void redeemResetOverwritesPasswordAndAudits() {
        PlayerId p = newPlayer();
        tokens.issue("link-ok", p, TokenPurpose.LINK, now.plusSeconds(600), now);
        accounts.redeem("link-ok", "old-hash", now);

        tokens.issue("reset-ok", p, TokenPurpose.RESET, now.plusSeconds(600), now);
        RedeemOutcome outcome = accounts.redeem("reset-ok", "new-hash", now.plusSeconds(10));

        assertThat(outcome).isEqualTo(RedeemOutcome.RESET_DONE);
        assertThat(hash(p)).isEqualTo("new-hash");
        assertThat(audits(p, "PASSWORD_RESET")).isEqualTo(1);
    }

    @Test
    void redeemResetConflictsWhenNoAccount() {
        PlayerId p = newPlayer();
        tokens.issue("reset-noacct", p, TokenPurpose.RESET, now.plusSeconds(600), now);

        assertThatThrownBy(() -> accounts.redeem("reset-noacct", "hash", now))
                .isInstanceOf(WebAccountConflictException.class);
    }

    @Test
    void expiredTokenIsNotRedeemable() {
        PlayerId p = newPlayer();
        tokens.issue("raw-expired", p, TokenPurpose.LINK, now.minusSeconds(1), now.minusSeconds(601));

        assertThatThrownBy(() -> accounts.redeem("raw-expired", "hash", now))
                .isInstanceOf(TokenInvalidException.class);
    }

    @Test
    void findReturnsAccountWithHashOrEmpty() {
        PlayerId p = newPlayer();
        assertThat(accounts.find(p)).isEmpty();

        tokens.issue("find-link", p, TokenPurpose.LINK, now.plusSeconds(600), now);
        accounts.redeem("find-link", "the-hash", now);

        Optional<WebAccount> found = accounts.find(p);
        assertThat(found).isPresent();
        assertThat(found.get().playerUuid()).isEqualTo(p);
        assertThat(found.get().passwordHash()).isEqualTo("the-hash");
    }

    @Test
    void passwordResetInvalidatesAllRefreshTokensOfThePlayer() {
        // D4: a RESET redeem must delete every refresh token of the player in the same transaction.
        PlayerId p = newPlayer();
        tokens.issue("d4-link", p, TokenPurpose.LINK, now.plusSeconds(600), now);
        accounts.redeem("d4-link", "old-hash", now);
        // two live sessions for this player
        refreshTokens.store("d4-rt-1", p, now, now.plusSeconds(86400));
        refreshTokens.store("d4-rt-2", p, now, now.plusSeconds(86400));
        assertThat(dsl.fetchCount(REFRESH_TOKEN, REFRESH_TOKEN.PLAYER_UUID.eq(p.value()))).isEqualTo(2);

        tokens.issue("d4-reset", p, TokenPurpose.RESET, now.plusSeconds(600), now);
        accounts.redeem("d4-reset", "new-hash", now.plusSeconds(10));

        assertThat(dsl.fetchCount(REFRESH_TOKEN, REFRESH_TOKEN.PLAYER_UUID.eq(p.value()))).isZero();
        assertThat(audits(p, "PASSWORD_RESET")).isEqualTo(1);
    }
}
