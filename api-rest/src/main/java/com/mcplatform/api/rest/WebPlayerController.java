package com.mcplatform.api.rest;

import com.mcplatform.application.economy.port.PlayerRepository;
import com.mcplatform.application.permission.PermissionAdminService;
import com.mcplatform.application.player.PlayerDirectoryService;
import com.mcplatform.application.player.PlayerNotFoundException;
import com.mcplatform.application.player.PlayerStats;
import com.mcplatform.application.player.RecentPlayer;
import com.mcplatform.application.security.PermissionDeniedException;
import com.mcplatform.application.security.PermissionResolver;
import com.mcplatform.domain.player.PlayerId;
import com.mcplatform.protocol.player.PlayerStatsResponse;
import com.mcplatform.protocol.player.PlayerSummary;
import com.mcplatform.protocol.player.RecentPlayerSummary;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Player-facing read surface for the web management UI ({@code GET /api/web/players/**}): name search,
 * the "online / recently active" list, and server-wide stats. Behind the JWT chain ({@code /api/web/**})
 * — deliberately NOT on the permitAll internal {@code /api/players/**} surface, so player data is not
 * enumerable without authentication. Read-gated via the {@link PermissionResolver} ({@code permission.read});
 * returns display data only (uuid, name, presence), never roles/permissions (§12).
 */
@RestController
public class WebPlayerController {

    /** Hard cap for the result size — a search-as-you-type picker needs few rows (default 20, see param). */
    private static final int MAX_LIMIT = 50;

    /** Default size of the recent-players widget when the client sends no {@code limit}. */
    private static final int DEFAULT_RECENT_LIMIT = 12;

    private final PlayerRepository players;
    private final PlayerDirectoryService directory;
    private final PermissionResolver resolver;

    public WebPlayerController(PlayerRepository players, PlayerDirectoryService directory,
            PermissionResolver resolver) {
        this.players = players;
        this.directory = directory;
        this.resolver = resolver;
    }

    @GetMapping("/api/web/players/search")
    public PlayerSummary[] search(@AuthenticationPrincipal PlayerId actor,
            @RequestParam("name") String name,
            @RequestParam(name = "limit", defaultValue = "20") int limit) {
        requireRead(actor);
        String query = name == null ? "" : name.strip();
        if (query.isEmpty()) {
            return new PlayerSummary[0]; // nothing to match yet (lenient for type-ahead)
        }
        int capped = Math.max(1, Math.min(limit, MAX_LIMIT));
        return players.searchByNamePrefix(query, capped).stream()
                .map(m -> new PlayerSummary(m.uuid(), m.name()))
                .toArray(PlayerSummary[]::new);
    }

    /**
     * Authoritative UUID → name resolution ({@code GET /api/web/players/{uuid}}). Same cached name source
     * as {@link #search} (the {@code player} row's name), so names stay consistent. Not authority-filtered
     * — any known UUID resolves, including higher ranks. An unknown UUID → 404 {@code player_not_found};
     * a syntactically invalid UUID in the path fails the standard path validation (400) before this runs.
     * The literal {@code /search}, {@code /recent} and {@code /stats} mappings take precedence over this
     * variable path, so they are never shadowed.
     */
    @GetMapping("/api/web/players/{uuid}")
    public PlayerSummary get(@AuthenticationPrincipal PlayerId actor, @PathVariable UUID uuid) {
        requireRead(actor);
        String name = players.findNameByUuid(PlayerId.of(uuid))
                .orElseThrow(() -> new PlayerNotFoundException(uuid));
        return new PlayerSummary(uuid, name);
    }

    /**
     * Online / recently active players for the dashboard widget — online first, then by last_seen desc.
     * An empty list ({@code 200 []}) is normal (no players yet). The service caps {@code limit} server-side.
     */
    @GetMapping("/api/web/players/recent")
    public RecentPlayerSummary[] recent(@AuthenticationPrincipal PlayerId actor,
            @RequestParam(name = "limit", defaultValue = "" + DEFAULT_RECENT_LIMIT) int limit) {
        requireRead(actor);
        return directory.recent(limit).stream()
                .map(WebPlayerController::toRecentSummary)
                .toArray(RecentPlayerSummary[]::new);
    }

    /** Server-wide player counters for the dashboard stats line. */
    @GetMapping("/api/web/players/stats")
    public PlayerStatsResponse stats(@AuthenticationPrincipal PlayerId actor) {
        requireRead(actor);
        PlayerStats s = directory.stats();
        return new PlayerStatsResponse(s.totalPlayers(), s.onlineNow(), s.newThisWeek());
    }

    private static RecentPlayerSummary toRecentSummary(RecentPlayer p) {
        return new RecentPlayerSummary(p.uuid(), p.name(), p.online(), p.lastSeenEpochMilli());
    }

    private void requireRead(PlayerId actor) {
        if (!resolver.hasPermission(actor.value(), PermissionAdminService.READ)) {
            throw new PermissionDeniedException(actor.value(), PermissionAdminService.READ);
        }
    }
}
