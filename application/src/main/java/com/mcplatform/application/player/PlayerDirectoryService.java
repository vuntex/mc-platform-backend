package com.mcplatform.application.player;

import com.mcplatform.application.economy.port.PlayerRepository;
import com.mcplatform.application.economy.port.PlayerRepository.PlayerLastSeen;
import com.mcplatform.application.player.port.PlayerPresencePort;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Read use case for the web player dashboard. Combines live presence (Redis, via
 * {@link PlayerPresencePort}) with player master data (Postgres, via {@link PlayerRepository}) — the two
 * live in different stores, so the ordering ("online first, then last_seen desc") is composed here.
 */
public final class PlayerDirectoryService {

    /** Registrations counted for "new this week". */
    private static final Duration WEEK = Duration.ofDays(7);

    /** Hard cap for the recent list — a dashboard widget needs few rows. */
    static final int MAX_RECENT = 50;

    private final PlayerRepository players;
    private final PlayerPresencePort presence;
    private final Clock clock;

    public PlayerDirectoryService(PlayerRepository players, PlayerPresencePort presence, Clock clock) {
        this.players = players;
        this.presence = presence;
        this.clock = clock;
    }

    /**
     * The most relevant players for the dashboard: online players first (each ordered by last_seen desc),
     * then offline players by last_seen desc, capped at {@code limit}. Never returns a player twice.
     */
    public List<RecentPlayer> recent(int limit) {
        int capped = Math.max(1, Math.min(limit, MAX_RECENT));
        Set<UUID> online = presence.onlinePlayers();

        List<RecentPlayer> result = new ArrayList<>(capped);
        if (!online.isEmpty()) {
            for (PlayerLastSeen p : players.findRecentOnline(online, capped)) {
                result.add(toRecent(p, true));
            }
        }
        if (result.size() < capped) {
            for (PlayerLastSeen p : players.findRecentExcluding(online, capped - result.size())) {
                result.add(toRecent(p, false));
            }
        }
        return result;
    }

    /** Server-wide counters: total players, currently online, and registrations in the last 7 days. */
    public PlayerStats stats() {
        long total = players.count();
        long onlineNow = presence.onlineCount();
        long newThisWeek = players.countRegisteredSince(clock.instant().minus(WEEK));
        return new PlayerStats(total, onlineNow, newThisWeek);
    }

    private static RecentPlayer toRecent(PlayerLastSeen p, boolean online) {
        long lastSeen = p.lastSeen() == null ? 0L : p.lastSeen().toEpochMilli();
        return new RecentPlayer(p.uuid(), p.name(), online, lastSeen);
    }
}
