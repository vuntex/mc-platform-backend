package com.mcplatform.application.player;

import static org.assertj.core.api.Assertions.assertThat;

import com.mcplatform.application.economy.port.PlayerRepository;
import com.mcplatform.application.player.port.PlayerPresencePort;
import com.mcplatform.domain.player.PlayerId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Unit test for the recent-list composition (online first, then last_seen desc) and the stats math. */
class PlayerDirectoryServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-01T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final UUID a = UUID.randomUUID(); // offline, oldest last_seen, registered 10d ago
    private final UUID b = UUID.randomUUID(); // online,  registered 1d ago
    private final UUID c = UUID.randomUUID(); // offline, registered 2d ago
    private final UUID d = UUID.randomUUID(); // online,  newest last_seen, registered 20d ago

    private final FakePlayers players = new FakePlayers()
            .add(a, "Alice", 100_000L, NOW.minusSeconds(10 * 86_400))
            .add(b, "Bob", 300_000L, NOW.minusSeconds(86_400))
            .add(c, "Cara", 200_000L, NOW.minusSeconds(2 * 86_400))
            .add(d, "Dora", 400_000L, NOW.minusSeconds(20 * 86_400));
    private final FakePresence presence = new FakePresence(Set.of(b, d));
    private final PlayerDirectoryService service = new PlayerDirectoryService(players, presence, CLOCK);

    @Test
    void recentReturnsOnlineFirstThenByLastSeenDesc() {
        List<RecentPlayer> recent = service.recent(10);

        assertThat(recent).extracting(RecentPlayer::uuid).containsExactly(d, b, c, a);
        assertThat(recent).extracting(RecentPlayer::online).containsExactly(true, true, false, false);
        assertThat(recent.get(0).lastSeenEpochMilli()).isEqualTo(400_000L);
        assertThat(recent.get(0).name()).isEqualTo("Dora");
    }

    @Test
    void recentCapsAtLimitPreferringOnline() {
        // limit 1 → only the newest online player, no offline fill
        assertThat(service.recent(1)).extracting(RecentPlayer::uuid).containsExactly(d);
    }

    @Test
    void recentFillsWithOfflineWhenOnlineFewerThanLimit() {
        // 2 online + 1 offline fill
        assertThat(service.recent(3)).extracting(RecentPlayer::uuid).containsExactly(d, b, c);
    }

    @Test
    void recentClampsNonPositiveLimitToOne() {
        assertThat(service.recent(0)).extracting(RecentPlayer::uuid).containsExactly(d);
    }

    @Test
    void statsCountsTotalOnlineAndRegisteredThisWeek() {
        PlayerStats stats = service.stats();

        assertThat(stats.totalPlayers()).isEqualTo(4);
        assertThat(stats.onlineNow()).isEqualTo(2);
        assertThat(stats.newThisWeek()).isEqualTo(2); // Bob (1d) + Cara (2d); Alice (10d) & Dora (20d) excluded
    }

    // --- fakes ------------------------------------------------------------

    private static final class Row {
        final UUID uuid;
        final String name;
        final long lastSeenMillis;
        final Instant registeredAt;

        Row(UUID uuid, String name, long lastSeenMillis, Instant registeredAt) {
            this.uuid = uuid;
            this.name = name;
            this.lastSeenMillis = lastSeenMillis;
            this.registeredAt = registeredAt;
        }
    }

    private static final class FakePlayers implements PlayerRepository {
        private final List<Row> rows = new ArrayList<>();

        FakePlayers add(UUID uuid, String name, long lastSeenMillis, Instant registeredAt) {
            rows.add(new Row(uuid, name, lastSeenMillis, registeredAt));
            return this;
        }

        @Override
        public long count() {
            return rows.size();
        }

        @Override
        public long countRegisteredSince(Instant since) {
            return rows.stream().filter(r -> !r.registeredAt.isBefore(since)).count();
        }

        @Override
        public List<PlayerLastSeen> findRecentOnline(Collection<UUID> uuids, int limit) {
            Set<UUID> wanted = new LinkedHashSet<>(uuids);
            return rows.stream()
                    .filter(r -> wanted.contains(r.uuid))
                    .sorted(Comparator.comparingLong((Row r) -> r.lastSeenMillis).reversed())
                    .limit(limit)
                    .map(this::toLastSeen)
                    .toList();
        }

        @Override
        public List<PlayerLastSeen> findRecentExcluding(Collection<UUID> exclude, int limit) {
            Set<UUID> skip = new LinkedHashSet<>(exclude);
            return rows.stream()
                    .filter(r -> !skip.contains(r.uuid))
                    .sorted(Comparator.comparingLong((Row r) -> r.lastSeenMillis).reversed())
                    .limit(limit)
                    .map(this::toLastSeen)
                    .toList();
        }

        private PlayerLastSeen toLastSeen(Row r) {
            return new PlayerLastSeen(r.uuid, r.name, Instant.ofEpochMilli(r.lastSeenMillis));
        }

        // --- unused by this test ---
        @Override public void save(PlayerId player, String name, Instant seenAt) { throw new UnsupportedOperationException(); }
        @Override public java.util.Optional<PlayerId> findUuidByName(String name) { throw new UnsupportedOperationException(); }
        @Override public java.util.Optional<String> findNameByUuid(PlayerId player) { throw new UnsupportedOperationException(); }
        @Override public java.util.Map<UUID, String> findNamesByUuids(Collection<UUID> uuids) { throw new UnsupportedOperationException(); }
        @Override public List<PlayerNameMatch> searchByNamePrefix(String prefix, int limit) { throw new UnsupportedOperationException(); }
        @Override public boolean upsertReturningWhetherNew(PlayerId player, String name, Instant seenAt) { throw new UnsupportedOperationException(); }
        @Override public void touchLastSeen(PlayerId player, Instant seenAt) { throw new UnsupportedOperationException(); }
    }

    private static final class FakePresence implements PlayerPresencePort {
        private final Set<UUID> online;

        FakePresence(Set<UUID> online) {
            this.online = new LinkedHashSet<>(online);
        }

        @Override public void markOnline(PlayerId player) { online.add(player.value()); }
        @Override public void markOffline(PlayerId player) { online.remove(player.value()); }
        @Override public boolean isOnline(PlayerId player) { return online.contains(player.value()); }
        @Override public Set<UUID> onlinePlayers() { return Set.copyOf(online); }
        @Override public long onlineCount() { return online.size(); }
    }
}
