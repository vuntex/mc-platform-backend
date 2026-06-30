package com.mcplatform.api.rest;

import com.mcplatform.api.rest.support.EconomyMapper;
import com.mcplatform.application.economy.EconomyPermissions;
import com.mcplatform.application.economy.PlayerBalancesQuery;
import com.mcplatform.application.security.PermissionDeniedException;
import com.mcplatform.application.security.PermissionResolver;
import com.mcplatform.domain.player.PlayerId;
import com.mcplatform.protocol.economy.PlayerBalancesResponse;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Web-interface-only read surface for a player's balances ({@code GET /api/web/economy/players/{uuid}/balances},
 * spec 007 US1). Behind the {@code /api/web/**} JWT chain: a missing/invalid token is rejected with 401
 * before this runs. Backend-authoritatively gated against {@code permission.economy.read} via the
 * {@link PermissionResolver} (Constitution §12) — a caller without it gets 403. Read-only; an unknown
 * player yields an empty balances list, never a 404.
 */
@RestController
public class PlayerBalancesController {

    private final PlayerBalancesQuery query;
    private final PermissionResolver resolver;

    public PlayerBalancesController(PlayerBalancesQuery query, PermissionResolver resolver) {
        this.query = query;
        this.resolver = resolver;
    }

    @GetMapping("/api/web/economy/players/{uuid}/balances")
    public PlayerBalancesResponse balances(@AuthenticationPrincipal PlayerId actor, @PathVariable UUID uuid) {
        requireEconomyRead(actor);
        return EconomyMapper.playerBalances(uuid, query.balances(PlayerId.of(uuid)));
    }

    private void requireEconomyRead(PlayerId actor) {
        if (!resolver.hasPermission(actor.value(), EconomyPermissions.READ)) {
            throw new PermissionDeniedException(actor.value(), EconomyPermissions.READ);
        }
    }
}
