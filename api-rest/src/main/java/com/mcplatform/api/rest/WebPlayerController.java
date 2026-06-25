package com.mcplatform.api.rest;

import com.mcplatform.application.economy.port.PlayerRepository;
import com.mcplatform.application.permission.PermissionAdminService;
import com.mcplatform.application.security.PermissionDeniedException;
import com.mcplatform.application.security.PermissionResolver;
import com.mcplatform.domain.player.PlayerId;
import com.mcplatform.protocol.player.PlayerSummary;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Player lookup for the web management UI ({@code GET /api/web/players/search?name=}). Behind the JWT chain
 * ({@code /api/web/**}) — deliberately NOT on the permitAll internal {@code /api/players/**} surface, so
 * player names are not enumerable without authentication. Read-gated via the {@link PermissionResolver}
 * ({@code permission.read}); returns display data only (uuid + cached name), never roles/permissions (§12).
 */
@RestController
public class WebPlayerController {

    /** Hard cap for the result size — a search-as-you-type picker needs few rows (default 20, see param). */
    private static final int MAX_LIMIT = 50;

    private final PlayerRepository players;
    private final PermissionResolver resolver;

    public WebPlayerController(PlayerRepository players, PermissionResolver resolver) {
        this.players = players;
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

    private void requireRead(PlayerId actor) {
        if (!resolver.hasPermission(actor.value(), PermissionAdminService.READ)) {
            throw new PermissionDeniedException(actor.value(), PermissionAdminService.READ);
        }
    }
}
