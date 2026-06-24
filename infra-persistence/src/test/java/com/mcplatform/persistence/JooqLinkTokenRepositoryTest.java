package com.mcplatform.persistence;

import static com.mcplatform.persistence.jooq.Tables.WEB_LINK_TOKEN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mcplatform.domain.player.PlayerId;
import com.mcplatform.domain.webauth.TokenPurpose;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Integration test for the jOOQ link-token adapter against a real, Flyway-migrated Postgres. */
@Testcontainers
class JooqLinkTokenRepositoryTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    static DSLContext dsl;
    static JooqLinkTokenRepository tokens;
    static JooqPlayerRepository players;

    private final Instant now = Instant.parse("2026-06-24T12:00:00Z");

    @BeforeAll
    static void migrateAndWire() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(PG.getJdbcUrl());
        ds.setUser(PG.getUsername());
        ds.setPassword(PG.getPassword());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        dsl = DSL.using(ds, SQLDialect.POSTGRES);
        tokens = new JooqLinkTokenRepository(dsl);
        players = new JooqPlayerRepository(dsl);
    }

    private PlayerId newPlayer() {
        PlayerId p = PlayerId.of(UUID.randomUUID());
        players.save(p, "Steve", Instant.now());
        return p;
    }

    private long count(PlayerId p, TokenPurpose purpose) {
        return dsl.fetchCount(WEB_LINK_TOKEN,
                WEB_LINK_TOKEN.PLAYER_UUID.eq(p.value()).and(WEB_LINK_TOKEN.PURPOSE.eq(purpose.name())));
    }

    @Test
    void issueReplacesExistingTokenForSameUuidAndPurpose() {
        PlayerId p = newPlayer();
        tokens.issue("raw-1", p, TokenPurpose.LINK, now.plusSeconds(600), now);
        tokens.issue("raw-2", p, TokenPurpose.LINK, now.plusSeconds(600), now.plusSeconds(5));

        assertThat(count(p, TokenPurpose.LINK)).isEqualTo(1); // DELETE-vor-INSERT
        String stored = dsl.select(WEB_LINK_TOKEN.TOKEN_HASH).from(WEB_LINK_TOKEN)
                .where(WEB_LINK_TOKEN.PLAYER_UUID.eq(p.value())).fetchOneInto(String.class);
        assertThat(stored).isEqualTo(JooqLinkTokenRepository.sha256Hex("raw-2")); // only the latest survives
        assertThat(stored).isNotEqualTo("raw-2"); // never the raw value at rest
    }

    @Test
    void differentPurposesCoexist() {
        PlayerId p = newPlayer();
        tokens.issue("l", p, TokenPurpose.LINK, now.plusSeconds(600), now);
        tokens.issue("r", p, TokenPurpose.RESET, now.plusSeconds(600), now);
        assertThat(count(p, TokenPurpose.LINK)).isEqualTo(1);
        assertThat(count(p, TokenPurpose.RESET)).isEqualTo(1);
    }

    @Test
    void lastCreatedAtTracksTheLiveToken() {
        PlayerId p = newPlayer();
        assertThat(tokens.lastCreatedAt(p, TokenPurpose.LINK)).isEmpty();
        tokens.issue("raw", p, TokenPurpose.LINK, now.plusSeconds(600), now);
        assertThat(tokens.lastCreatedAt(p, TokenPurpose.LINK)).contains(now);
    }

    @Test
    void deleteExpiredRemovesOnlyExpired() {
        PlayerId alive = newPlayer();
        PlayerId dead = newPlayer();
        tokens.issue("alive", alive, TokenPurpose.LINK, now.plusSeconds(600), now);
        tokens.issue("dead", dead, TokenPurpose.LINK, now.minusSeconds(1), now.minusSeconds(601));

        int deleted = tokens.deleteExpired(now);

        assertThat(deleted).isEqualTo(1);
        assertThat(count(alive, TokenPurpose.LINK)).isEqualTo(1);
        assertThat(count(dead, TokenPurpose.LINK)).isZero();
    }

    @Test
    void unknownPlayerIsRejectedByForeignKey() {
        PlayerId unknown = PlayerId.of(UUID.randomUUID()); // never saved
        assertThatThrownBy(() -> tokens.issue("raw", unknown, TokenPurpose.LINK, now.plusSeconds(600), now))
                .isInstanceOf(DataAccessException.class);
    }
}
