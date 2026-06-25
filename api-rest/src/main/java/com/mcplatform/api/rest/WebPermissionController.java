package com.mcplatform.api.rest;

import com.mcplatform.api.rest.support.PermissionMapper;
import com.mcplatform.api.rest.support.WebPermissionMapper;
import com.mcplatform.application.economy.port.PlayerRepository;
import com.mcplatform.application.permission.PermissionAdminService;
import com.mcplatform.application.permission.PermissionQueryService;
import com.mcplatform.application.permission.PlayerPermissionsView;
import com.mcplatform.application.security.PermissionDeniedException;
import com.mcplatform.application.security.PermissionResolver;
import com.mcplatform.domain.permission.Role;
import com.mcplatform.domain.permission.RoleId;
import com.mcplatform.domain.player.PlayerId;
import com.mcplatform.protocol.permission.ActiveGrant;
import com.mcplatform.protocol.permission.PlayerPermissionsResponse;
import com.mcplatform.protocol.permission.RoleResponse;
import com.mcplatform.protocol.permission.web.GrantPermissionWriteRequest;
import com.mcplatform.protocol.permission.web.GrantRoleWriteRequest;
import com.mcplatform.protocol.permission.web.RolePermissionWriteRequest;
import com.mcplatform.protocol.permission.web.RoleWriteRequest;
import com.mcplatform.protocol.permission.web.RevokePermissionWriteRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * JWT-gated web surface for rank/permission management ({@code /api/web/permission/**}), used by the web
 * interface. Behind the Spring Security {@code /api/web/**} chain (slice 4): a missing/invalid token is
 * rejected with 401 before this controller runs. The acting admin is the {@link PlayerId} from the token
 * principal — NEVER taken from the request body (FR-002/FR-020). Writes are gated backend-authoritatively
 * inside {@link PermissionAdminService} (granular {@code permission.*}); reads are gated here against
 * {@code permission.read}. All gating goes through the {@link PermissionResolver} — no Spring Security
 * roles (Constitution §12). Delegates to the existing 002 use cases; this is the entry surface only.
 */
@RestController
public class WebPermissionController {

    private final PermissionAdminService admin;
    private final PermissionQueryService query;
    private final PermissionResolver resolver;
    private final PlayerRepository playerRepository;
    private final Clock clock;

    public WebPermissionController(PermissionAdminService admin, PermissionQueryService query,
            PermissionResolver resolver, PlayerRepository playerRepository, Clock clock) {
        this.admin = admin;
        this.query = query;
        this.resolver = resolver;
        this.playerRepository = playerRepository;
        this.clock = clock;
    }

    // --- roles ------------------------------------------------------------

    @GetMapping("/api/web/permission/roles")
    public RoleResponse[] listRoles(@AuthenticationPrincipal PlayerId actor) {
        requireRead(actor);
        return query.allRoles().stream().map(PermissionMapper::role).toArray(RoleResponse[]::new);
    }

    @GetMapping("/api/web/permission/roles/{id}")
    public RoleResponse getRole(@AuthenticationPrincipal PlayerId actor, @PathVariable long id) {
        requireRead(actor);
        return PermissionMapper.role(query.roleDetail(RoleId.of(id)));
    }

    @PostMapping("/api/web/permission/roles")
    public RoleResponse createRole(@AuthenticationPrincipal PlayerId actor, @RequestBody RoleWriteRequest req) {
        var role = admin.createRole(WebPermissionMapper.draft(req), actor.value());
        return PermissionMapper.role(query.roleDetail(role.id()));
    }

    @PutMapping("/api/web/permission/roles/{id}")
    public RoleResponse updateRole(@AuthenticationPrincipal PlayerId actor, @PathVariable long id,
            @RequestBody RoleWriteRequest req) {
        var role = admin.updateRole(WebPermissionMapper.withId(req, id), actor.value());
        return PermissionMapper.role(query.roleDetail(role.id()));
    }

    @DeleteMapping("/api/web/permission/roles/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteRole(@AuthenticationPrincipal PlayerId actor, @PathVariable long id) {
        admin.deleteRole(RoleId.of(id), actor.value());
    }

    // --- role permissions -------------------------------------------------

    @GetMapping("/api/web/permission/roles/{id}/permissions")
    public RoleResponse listRolePermissions(@AuthenticationPrincipal PlayerId actor, @PathVariable long id) {
        requireRead(actor);
        return PermissionMapper.role(query.roleDetail(RoleId.of(id)));
    }

    @PostMapping("/api/web/permission/roles/{id}/permissions")
    public RoleResponse addRolePermission(@AuthenticationPrincipal PlayerId actor, @PathVariable long id,
            @RequestBody RolePermissionWriteRequest req) {
        admin.addRolePermission(RoleId.of(id), req.permission(), actor.value());
        return PermissionMapper.role(query.roleDetail(RoleId.of(id)));
    }

    @DeleteMapping("/api/web/permission/roles/{id}/permissions")
    public RoleResponse removeRolePermission(@AuthenticationPrincipal PlayerId actor, @PathVariable long id,
            @RequestBody RolePermissionWriteRequest req) {
        admin.removeRolePermission(RoleId.of(id), req.permission(), actor.value());
        return PermissionMapper.role(query.roleDetail(RoleId.of(id)));
    }

    // --- player grants ----------------------------------------------------

    @PostMapping("/api/web/permission/players/{uuid}/roles")
    public PlayerPermissionsResponse grantRole(@AuthenticationPrincipal PlayerId actor,
            @PathVariable UUID uuid, @RequestBody GrantRoleWriteRequest req) {
        admin.grantRole(PlayerId.of(uuid), RoleId.of(req.roleId()), expiry(req.expiresInSeconds()),
                req.reason(), actor.value());
        return effectiveView(uuid);
    }

    @DeleteMapping("/api/web/permission/players/{uuid}/roles/{roleId}")
    public PlayerPermissionsResponse revokeRole(@AuthenticationPrincipal PlayerId actor,
            @PathVariable UUID uuid, @PathVariable long roleId,
            @RequestParam(required = false) String reason) {
        admin.revokeRole(PlayerId.of(uuid), RoleId.of(roleId), reason, actor.value());
        return effectiveView(uuid);
    }

    @PostMapping("/api/web/permission/players/{uuid}/permissions")
    public PlayerPermissionsResponse grantPermission(@AuthenticationPrincipal PlayerId actor,
            @PathVariable UUID uuid, @RequestBody GrantPermissionWriteRequest req) {
        admin.grantPermission(PlayerId.of(uuid), req.permission(), expiry(req.expiresInSeconds()),
                req.reason(), actor.value());
        return effectiveView(uuid);
    }

    @DeleteMapping("/api/web/permission/players/{uuid}/permissions")
    public PlayerPermissionsResponse revokePermission(@AuthenticationPrincipal PlayerId actor,
            @PathVariable UUID uuid, @RequestBody RevokePermissionWriteRequest req) {
        admin.revokePermission(PlayerId.of(uuid), req.permission(), req.reason(), actor.value());
        return effectiveView(uuid);
    }

    @GetMapping("/api/web/permission/players/{uuid}/effective")
    public PlayerPermissionsResponse effective(@AuthenticationPrincipal PlayerId actor, @PathVariable UUID uuid) {
        requireRead(actor);
        return effectiveView(uuid);
    }

    // --- helpers ----------------------------------------------------------

    /** Read gate (FR-004): the query use cases are ungated, so the web surface enforces {@code permission.read}. */
    private void requireRead(PlayerId actor) {
        if (!resolver.hasPermission(actor.value(), PermissionAdminService.READ)) {
            throw new PermissionDeniedException(actor.value(), PermissionAdminService.READ);
        }
    }

    private Instant expiry(Long expiresInSeconds) {
        return expiresInSeconds == null ? null : clock.instant().plusSeconds(expiresInSeconds);
    }

    /**
     * The player's effective view with grant-issuer UUIDs resolved to display names (batched, no N+1).
     * When the player holds no active rank grant, the implicit DEFAULT fallback is surfaced as a synthetic
     * (display-only, no DB row, no issuer) entry in {@code roles}, so the management UI always shows the
     * player's current rank instead of an empty list.
     */
    private PlayerPermissionsResponse effectiveView(UUID uuid) {
        PlayerPermissionsView view = query.effectiveFor(PlayerId.of(uuid));
        Set<UUID> issuers = new HashSet<>();
        view.roles().forEach(g -> addIssuer(issuers, g));
        view.permissions().forEach(g -> addIssuer(issuers, g));
        PlayerPermissionsResponse resp = PermissionMapper.player(view, playerRepository.findNamesByUuids(issuers));
        if (resp.roles().isEmpty()) {
            Role fallback = query.primaryRoleOf(PlayerId.of(uuid)); // = the DEFAULT role here
            List<ActiveGrant> withDefault = new ArrayList<>();
            withDefault.add(new ActiveGrant(fallback.name(), null, null, null, null));
            resp = new PlayerPermissionsResponse(resp.player(), List.copyOf(withDefault), resp.permissions(),
                    resp.effectivePermissions(), resp.display());
        }
        return resp;
    }

    private static void addIssuer(Set<UUID> issuers, PlayerPermissionsView.GrantSummary g) {
        if (g.issuedBy() != null) {
            issuers.add(g.issuedBy());
        }
    }
}
