package com.mcplatform.api.rest;

import com.mcplatform.application.economy.port.PlayerRepository;
import com.mcplatform.application.permission.PermissionQueryService;
import com.mcplatform.domain.permission.Role;
import com.mcplatform.domain.player.PlayerId;
import com.mcplatform.protocol.webauth.MeResponse;
import com.mcplatform.protocol.webauth.PrimaryRole;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tells the web client who it is logged in as, for display ({@code GET /api/web/me}). Behind the JWT chain
 * ({@code /api/web/**}): the identity is the {@link PlayerId} from the token principal — backend-authoritative,
 * not the name the user happened to type at login. Resolves the display name from the player master data
 * (cached {@code player.name}) and the caller's OWN effective permissions (for client-side button gating).
 * No permission gate — any authenticated user may see their own identity and rights; the response carries
 * the caller's resolved permission set, never anyone else's, and the real check stays backend-authoritative
 * (403 on a forbidden write, Constitution §12).
 */
@RestController
public class WebMeController {

    private final PlayerRepository players;
    private final PermissionQueryService permissions;

    public WebMeController(PlayerRepository players, PermissionQueryService permissions) {
        this.players = players;
        this.permissions = permissions;
    }

    @GetMapping("/api/web/me")
    public MeResponse me(@AuthenticationPrincipal PlayerId caller) {
        String name = players.findNameByUuid(caller).orElse(null);
        List<String> effective = List.copyOf(permissions.effectiveFor(caller).effectivePermissions());
        Role pr = permissions.primaryRoleOf(caller);
        PrimaryRole primaryRole = new PrimaryRole(pr.name(), pr.displayName(), pr.color(), pr.weight());
        return new MeResponse(caller.value(), name, effective, primaryRole);
    }
}
