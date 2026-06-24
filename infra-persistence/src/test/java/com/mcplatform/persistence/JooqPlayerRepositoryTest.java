package com.mcplatform.persistence;

import static org.assertj.core.api.Assertions.assertThat;

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

/** Integration test for name→UUID resolution (LOWER(name) + most-recent last_seen). */
@Testcontainers
class JooqPlayerRepositoryTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    static DSLContext dsl;
    static JooqPlayerRepository players;

    @BeforeAll
    static void migrateAndWire() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(PG.getJdbcUrl());
        ds.setUser(PG.getUsername());
        ds.setPassword(PG.getPassword());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        dsl = DSL.using(ds, SQLDialect.POSTGRES);
        players = new JooqPlayerRepository(dsl);
    }

    @Test
    void resolvesCaseInsensitively() {
        PlayerId p = PlayerId.of(UUID.randomUUID());
        players.save(p, "Vuntex", Instant.parse("2026-06-24T10:00:00Z"));

        assertThat(players.findUuidByName("vuntex")).contains(p);
        assertThat(players.findUuidByName("VUNTEX")).contains(p);
        assertThat(players.findUuidByName("Vuntex")).contains(p);
    }

    @Test
    void unknownNameIsEmpty() {
        assertThat(players.findUuidByName("nobody-" + UUID.randomUUID())).isEmpty();
    }

    @Test
    void ambiguousNameResolvesToMostRecentlySeen() {
        // Same name reused by two different identities over time; the most recent last_seen wins.
        String name = "Reused" + UUID.randomUUID().toString().substring(0, 6);
        PlayerId older = PlayerId.of(UUID.randomUUID());
        PlayerId newer = PlayerId.of(UUID.randomUUID());
        players.save(older, name, Instant.parse("2024-01-01T00:00:00Z"));
        players.save(newer, name, Instant.parse("2026-06-24T00:00:00Z"));

        assertThat(players.findUuidByName(name)).contains(newer);
    }
}
