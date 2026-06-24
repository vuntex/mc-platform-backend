package com.mcplatform.persistence;

import static com.mcplatform.persistence.jooq.Tables.REFRESH_TOKEN;
import static com.mcplatform.persistence.jooq.Tables.WEB_AUTH_AUDIT;
import static org.assertj.core.api.Assertions.assertThat;

import com.mcplatform.application.webauth.port.RotateResult;
import com.mcplatform.domain.player.PlayerId;
import java.time.Instant;
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

/** Integration test for the rotating refresh-token jOOQ adapter (store / rotate / replay / logout / purge). */
@Testcontainers
class JooqRefreshTokenRepositoryTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    static DSLContext dsl;
    static JooqRefreshTokenRepository refreshTokens;
    static JooqPlayerRepository players;

    private final Instant now = Instant.parse("2026-06-24T12:00:00Z");
    private final Instant refreshExpiry = now.plusSeconds(2_592_000); // 30 days

    @BeforeAll
    static void migrateAndWire() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(PG.getJdbcUrl());
        ds.setUser(PG.getUsername());
        ds.setPassword(PG.getPassword());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        dsl = DSL.using(ds, SQLDialect.POSTGRES);
        refreshTokens = new JooqRefreshTokenRepository(dsl);
        players = new JooqPlayerRepository(dsl);
    }

    private PlayerId newPlayer() {
        PlayerId p = PlayerId.of(UUID.randomUUID());
        players.save(p, "Steve", Instant.now());
        return p;
    }

    private int rows(PlayerId p) {
        return dsl.fetchCount(REFRESH_TOKEN, REFRESH_TOKEN.PLAYER_UUID.eq(p.value()));
    }

    private long audits(PlayerId p, String type) {
        return dsl.fetchCount(WEB_AUTH_AUDIT,
                WEB_AUTH_AUDIT.PLAYER_UUID.eq(p.value()).and(WEB_AUTH_AUDIT.EVENT_TYPE.eq(type)));
    }

    private boolean rowExistsForRaw(String raw) {
        return dsl.fetchExists(dsl.selectFrom(REFRESH_TOKEN)
                .where(REFRESH_TOKEN.TOKEN_HASH.eq(JooqLinkTokenRepository.sha256Hex(raw))));
    }

    @Test
    void storePersistsOnlyTheHashAndAuditsLogin() {
        PlayerId p = newPlayer();
        refreshTokens.store("raw-login", p, now, refreshExpiry);

        // stored value is the SHA-256 hash, never the raw token
        String storedHash = dsl.select(REFRESH_TOKEN.TOKEN_HASH).from(REFRESH_TOKEN)
                .where(REFRESH_TOKEN.PLAYER_UUID.eq(p.value())).fetchOneInto(String.class);
        assertThat(storedHash).isEqualTo(JooqLinkTokenRepository.sha256Hex("raw-login"));
        assertThat(storedHash).isNotEqualTo("raw-login");
        assertThat(audits(p, "LOGIN")).isEqualTo(1);
    }

    @Test
    void rotateConsumesOldAndInsertsSuccessor() {
        PlayerId p = newPlayer();
        refreshTokens.store("rot-A", p, now, refreshExpiry);

        RotateResult result = refreshTokens.rotate("rot-A", "rot-B", now.plusSeconds(60), refreshExpiry);

        assertThat(result).isInstanceOf(RotateResult.Rotated.class);
        assertThat(((RotateResult.Rotated) result).player()).isEqualTo(p);
        // old row consumed (rotated_at set), successor active
        var oldRotatedAt = dsl.select(REFRESH_TOKEN.ROTATED_AT).from(REFRESH_TOKEN)
                .where(REFRESH_TOKEN.TOKEN_HASH.eq(JooqLinkTokenRepository.sha256Hex("rot-A")))
                .fetchOneInto(java.time.OffsetDateTime.class);
        assertThat(oldRotatedAt).isNotNull();
        assertThat(rowExistsForRaw("rot-B")).isTrue();
        assertThat(audits(p, "TOKEN_ROTATED")).isEqualTo(1);
    }

    @Test
    void replayOfConsumedTokenKillsAllPlayerTokens() {
        PlayerId p = newPlayer();
        refreshTokens.store("rep-A", p, now, refreshExpiry);
        refreshTokens.rotate("rep-A", "rep-B", now.plusSeconds(60), refreshExpiry); // rep-A now consumed
        assertThat(rows(p)).isEqualTo(2); // consumed rep-A + active rep-B

        RotateResult result = refreshTokens.rotate("rep-A", "rep-C", now.plusSeconds(120), refreshExpiry);

        assertThat(result).isInstanceOf(RotateResult.Replay.class);
        assertThat(rows(p)).isZero(); // entire family wiped
        assertThat(rowExistsForRaw("rep-C")).isFalse(); // no successor inserted on replay
        assertThat(audits(p, "TOKEN_REUSE_DETECTED")).isEqualTo(1);
    }

    @Test
    void rotateUnknownTokenIsInvalid() {
        RotateResult result = refreshTokens.rotate("never-existed", "x", now, refreshExpiry);
        assertThat(result).isInstanceOf(RotateResult.Invalid.class);
    }

    @Test
    void rotateExpiredTokenIsInvalidAndDoesNotInsertSuccessor() {
        PlayerId p = newPlayer();
        refreshTokens.store("exp-A", p, now.minusSeconds(120), now.minusSeconds(1)); // already expired

        RotateResult result = refreshTokens.rotate("exp-A", "exp-B", now, refreshExpiry);

        assertThat(result).isInstanceOf(RotateResult.Invalid.class);
        assertThat(rowExistsForRaw("exp-B")).isFalse();
    }

    @Test
    void deleteByRawTokenIsIdempotentAndAuditsLogout() {
        PlayerId p = newPlayer();
        refreshTokens.store("logout-A", p, now, refreshExpiry);

        assertThat(refreshTokens.deleteByRawToken("logout-A")).isTrue();
        assertThat(rowExistsForRaw("logout-A")).isFalse();
        assertThat(audits(p, "LOGOUT")).isEqualTo(1);
        // second call: nothing to delete → idempotent false, no extra audit
        assertThat(refreshTokens.deleteByRawToken("logout-A")).isFalse();
        assertThat(audits(p, "LOGOUT")).isEqualTo(1);
    }

    @Test
    void deleteAllForPlayerRemovesEveryToken() {
        PlayerId p = newPlayer();
        refreshTokens.store("all-A", p, now, refreshExpiry);
        refreshTokens.store("all-B", p, now, refreshExpiry);

        assertThat(refreshTokens.deleteAllForPlayer(p)).isEqualTo(2);
        assertThat(rows(p)).isZero();
    }

    @Test
    void purgeExpiredDeletesOnlyExpiredRows() {
        // Clear leftover rows from other tests so the global purge count is deterministic.
        dsl.deleteFrom(REFRESH_TOKEN).execute();
        PlayerId p = newPlayer();
        refreshTokens.store("purge-live", p, now, now.plusSeconds(600));
        refreshTokens.store("purge-dead", p, now.minusSeconds(600), now.minusSeconds(1));

        int purged = refreshTokens.purgeExpired(now);

        assertThat(purged).isEqualTo(1);
        assertThat(rowExistsForRaw("purge-live")).isTrue();
        assertThat(rowExistsForRaw("purge-dead")).isFalse();
    }
}
