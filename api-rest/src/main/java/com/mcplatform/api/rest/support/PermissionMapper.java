package com.mcplatform.api.rest.support;

import com.mcplatform.application.permission.PermissionQueryService.RoleDetail;
import com.mcplatform.application.permission.PlayerPermissionsView;
import com.mcplatform.domain.permission.Role;
import com.mcplatform.domain.permission.RoleId;
import com.mcplatform.protocol.permission.ActiveGrant;
import com.mcplatform.protocol.permission.PlayerPermissionsResponse;
import com.mcplatform.protocol.permission.RoleDisplay;
import com.mcplatform.protocol.permission.RoleRequest;
import com.mcplatform.protocol.permission.RoleResponse;
import java.util.List;

/** Maps between permission domain/read models and the {@code plugin-protocol} wire DTOs. */
public final class PermissionMapper {

    private PermissionMapper() {}

    /** A draft role from a create request ({@code id} is a placeholder; the DB assigns it). */
    public static Role draft(RoleRequest req) {
        return new Role(RoleId.of(0), req.name(), req.displayName(), req.color(), req.prefix(), req.suffix(),
                req.tabListColor(), req.tabListIcon(), req.displayIcon(), req.weight(), req.teamRank(),
                req.active(), false);
    }

    /** A role carrying the id from the path for an update ({@code isDefault} preserved by the service). */
    public static Role withId(RoleRequest req, long id) {
        return new Role(RoleId.of(id), req.name(), req.displayName(), req.color(), req.prefix(), req.suffix(),
                req.tabListColor(), req.tabListIcon(), req.displayIcon(), req.weight(), req.teamRank(),
                req.active(), false);
    }

    public static RoleResponse role(RoleDetail d) {
        Role r = d.role();
        return new RoleResponse(r.id().value(), r.name(), r.displayName(), r.color(), r.prefix(), r.suffix(),
                r.tabListColor(), r.tabListIcon(), r.displayIcon(), r.weight(), r.teamRank(), r.active(),
                r.isDefault(), d.permissions());
    }

    public static PlayerPermissionsResponse player(PlayerPermissionsView v) {
        List<ActiveGrant> roles = v.roles().stream().map(PermissionMapper::grant).toList();
        List<ActiveGrant> perms = v.permissions().stream().map(PermissionMapper::grant).toList();
        PlayerPermissionsView.Display d = v.display();
        RoleDisplay display = new RoleDisplay(d.displayName(), d.color(), d.prefix(), d.suffix(),
                d.tabListColor(), d.tabListIcon(), d.displayIcon());
        return new PlayerPermissionsResponse(v.player(), roles, perms,
                List.copyOf(v.effectivePermissions()), display);
    }

    private static ActiveGrant grant(PlayerPermissionsView.GrantSummary g) {
        return new ActiveGrant(g.label(),
                g.expiresAt() == null ? null : g.expiresAt().toEpochMilli(),
                g.issuedBy(), g.reason());
    }
}
