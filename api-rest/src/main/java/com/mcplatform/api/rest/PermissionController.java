package com.mcplatform.api.rest;

import com.mcplatform.api.rest.support.PermissionMapper;
import com.mcplatform.application.permission.PermissionAdminService;
import com.mcplatform.application.permission.PermissionQueryService;
import com.mcplatform.domain.permission.RoleId;
import com.mcplatform.domain.player.PlayerId;
import com.mcplatform.protocol.permission.GrantPermissionRequest;
import com.mcplatform.protocol.permission.GrantRoleRequest;
import com.mcplatform.protocol.permission.PlayerPermissionsResponse;
import com.mcplatform.protocol.permission.RolePermissionRequest;
import com.mcplatform.protocol.permission.RoleRequest;
import com.mcplatform.protocol.permission.RoleResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thin REST surface for the permission/rank system. All mutating calls are gated backend-authoritatively
 * inside {@link PermissionAdminService} (403 → mapped globally); this controller only maps DTOs and
 * delegates. After a player mutation it returns the recomputed effective view.
 */
@RestController
public class PermissionController {

    private final PermissionAdminService admin;
    private final PermissionQueryService query;
    private final Clock clock;

    public PermissionController(PermissionAdminService admin, PermissionQueryService query, Clock clock) {
        this.admin = admin;
        this.query = query;
        this.clock = clock;
    }

    // --- roles ------------------------------------------------------------

    @GetMapping("/api/permission/roles")
    public RoleResponse[] listRoles() {
        return query.allRoles().stream().map(PermissionMapper::role).toArray(RoleResponse[]::new);
    }

    @PostMapping("/api/permission/roles")
    public RoleResponse createRole(@RequestBody RoleRequest req) {
        var role = admin.createRole(PermissionMapper.draft(req), req.actor());
        return PermissionMapper.role(query.roleDetail(role.id()));
    }

    @PutMapping("/api/permission/roles/{id}")
    public RoleResponse updateRole(@PathVariable long id, @RequestBody RoleRequest req) {
        var role = admin.updateRole(PermissionMapper.withId(req, id), req.actor());
        return PermissionMapper.role(query.roleDetail(role.id()));
    }

    @DeleteMapping("/api/permission/roles/{id}")
    public void deleteRole(@PathVariable long id, @RequestParam UUID actor) {
        admin.deleteRole(RoleId.of(id), actor);
    }

    @PostMapping("/api/permission/roles/{id}/permissions")
    public RoleResponse addRolePermission(@PathVariable long id, @RequestBody RolePermissionRequest req) {
        admin.addRolePermission(RoleId.of(id), req.permission(), req.actor());
        return PermissionMapper.role(query.roleDetail(RoleId.of(id)));
    }

    @DeleteMapping("/api/permission/roles/{id}/permissions")
    public RoleResponse removeRolePermission(@PathVariable long id, @RequestBody RolePermissionRequest req) {
        admin.removeRolePermission(RoleId.of(id), req.permission(), req.actor());
        return PermissionMapper.role(query.roleDetail(RoleId.of(id)));
    }

    // --- player grants ----------------------------------------------------

    @PostMapping("/api/permission/players/{uuid}/roles")
    public PlayerPermissionsResponse grantRole(@PathVariable UUID uuid, @RequestBody GrantRoleRequest req) {
        admin.grantRole(PlayerId.of(uuid), RoleId.of(req.roleId()), expiry(req.expiresInSeconds()),
                req.reason(), req.actor());
        return PermissionMapper.player(query.effectiveFor(PlayerId.of(uuid)));
    }

    @DeleteMapping("/api/permission/players/{uuid}/roles/{roleId}")
    public PlayerPermissionsResponse revokeRole(@PathVariable UUID uuid, @PathVariable long roleId,
            @RequestParam UUID actor, @RequestParam(required = false) String reason) {
        admin.revokeRole(PlayerId.of(uuid), RoleId.of(roleId), reason, actor);
        return PermissionMapper.player(query.effectiveFor(PlayerId.of(uuid)));
    }

    @PostMapping("/api/permission/players/{uuid}/permissions")
    public PlayerPermissionsResponse grantPermission(@PathVariable UUID uuid,
            @RequestBody GrantPermissionRequest req) {
        admin.grantPermission(PlayerId.of(uuid), req.permission(), expiry(req.expiresInSeconds()),
                req.reason(), req.actor());
        return PermissionMapper.player(query.effectiveFor(PlayerId.of(uuid)));
    }

    @DeleteMapping("/api/permission/players/{uuid}/permissions")
    public PlayerPermissionsResponse revokePermission(@PathVariable UUID uuid,
            @RequestBody com.mcplatform.protocol.permission.RevokePermissionRequest req) {
        admin.revokePermission(PlayerId.of(uuid), req.permission(), req.reason(), req.actor());
        return PermissionMapper.player(query.effectiveFor(PlayerId.of(uuid)));
    }

    @GetMapping("/api/permission/players/{uuid}/effective")
    public PlayerPermissionsResponse effective(@PathVariable UUID uuid) {
        return PermissionMapper.player(query.effectiveFor(PlayerId.of(uuid)));
    }

    private Instant expiry(Long expiresInSeconds) {
        return expiresInSeconds == null ? null : clock.instant().plusSeconds(expiresInSeconds);
    }
}
