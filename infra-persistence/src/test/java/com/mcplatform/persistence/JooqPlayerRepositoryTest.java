package com.mcplatform.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.mcplatform.application.economy.port.PlayerRepository.PlayerLastSeen;
import com.mcplatform.domain.player.PlayerId;
import java.time.Instant;
import java.util.List;
import java.util.Set;
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

    @Test
    void countReflectsInsertsAndRegisteredSinceUsesCreatedAt() {
        long before = players.count();
        players.save(PlayerId.of(UUID.randomUUID()), "CntA" + suffix(), Instant.parse("2027-01-01T00:00:00Z"));
        players.save(PlayerId.of(UUID.randomUUID()), "CntB" + suffix(), Instant.parse("2027-01-02T00:00:00Z"));

        assertThat(players.count()).isEqualTo(before + 2);
        // created_at defaults to now() on insert, so both count as registered within the last hour...
        assertThat(players.countRegisteredSince(Instant.now().minusSeconds(3600))).isGreaterThanOrEqualTo(2);
        // ...and no row can have a future created_at.
        assertThat(players.countRegisteredSince(Instant.now().plusSeconds(3600))).isZero();
    }

    @Test
    void findRecentOnlineOrdersByLastSeenDescScopedToGivenUuids() {
        PlayerId a = PlayerId.of(UUID.randomUUID());
        PlayerId b = PlayerId.of(UUID.randomUUID());
        PlayerId c = PlayerId.of(UUID.randomUUID());
        players.save(a, "OnA" + suffix(), Instant.parse("2030-01-01T00:00:00Z"));
        players.save(b, "OnB" + suffix(), Instant.parse("2030-01-03T00:00:00Z")); // newest
        players.save(c, "OnC" + suffix(), Instant.parse("2030-01-02T00:00:00Z"));
        List<UUID> ids = List.of(a.value(), b.value(), c.value());

        assertThat(players.findRecentOnline(ids, 10)).extracting(PlayerLastSeen::uuid)
                .containsExactly(b.value(), c.value(), a.value());
        assertThat(players.findRecentOnline(ids, 2)).extracting(PlayerLastSeen::uuid)
                .containsExactly(b.value(), c.value());
        assertThat(players.findRecentOnline(List.of(), 10)).isEmpty();
    }

    @Test
    void findRecentExcludingSkipsExcludedAndOrdersByLastSeenDesc() {
        PlayerId x = PlayerId.of(UUID.randomUUID());
        PlayerId y = PlayerId.of(UUID.randomUUID());
        PlayerId z = PlayerId.of(UUID.randomUUID());
        players.save(x, "ExX" + suffix(), Instant.parse("2031-01-01T00:00:00Z"));
        players.save(y, "ExY" + suffix(), Instant.parse("2031-01-03T00:00:00Z"));
        players.save(z, "ExZ" + suffix(), Instant.parse("2031-01-02T00:00:00Z"));

        // Scope to my three players (a big limit + filter) so other rows in the shared DB don't matter.
        Set<UUID> mine = Set.of(x.value(), y.value(), z.value());
        List<UUID> got = players.findRecentExcluding(List.of(y.value()), 1000).stream()
                .map(PlayerLastSeen::uuid).filter(mine::contains).toList();
        assertThat(got).containsExactly(z.value(), x.value()); // y excluded, z (newer) before x
    }

    @Test
    void touchLastSeenUpdatesOnlyTheTimestamp() {
        PlayerId p = PlayerId.of(UUID.randomUUID());
        players.save(p, "TouchMe" + suffix(), Instant.parse("2026-01-01T00:00:00Z"));
        players.touchLastSeen(p, Instant.parse("2032-01-01T00:00:00Z"));

        List<PlayerLastSeen> res = players.findRecentOnline(List.of(p.value()), 1);
        assertThat(res).singleElement().satisfies(row -> {
            assertThat(row.name()).startsWith("TouchMe"); // name untouched
            assertThat(row.lastSeen()).isEqualTo(Instant.parse("2032-01-01T00:00:00Z"));
        });
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
