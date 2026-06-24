package com.mcplatform.application.permission;

import com.mcplatform.application.permission.port.DefaultRoleProtectedException;
import com.mcplatform.application.permission.port.GrantAuditPort;
import com.mcplatform.application.permission.port.PermissionChangePublisher;
import com.mcplatform.application.permission.port.PlayerGrantRepository;
import com.mcplatform.application.permission.port.RoleNameConflictException;
import com.mcplatform.application.permission.port.RoleAuditPort;
import com.mcplatform.application.permission.port.RoleNotFoundException;
import com.mcplatform.application.permission.port.RoleRepository;
import com.mcplatform.application.security.PermissionDeniedException;
import com.mcplatform.application.security.PermissionResolver;
import com.mcplatform.domain.permission.InvalidGrantException;
import com.mcplatform.domain.permission.PermissionChangeType;
import com.mcplatform.domain.permission.PermissionGrant;
import com.mcplatform.domain.permission.Role;
import com.mcplatform.domain.permission.RoleGrant;
import com.mcplatform.domain.permission.RoleId;
import com.mcplatform.domain.player.PlayerId;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Application use case for permission administration. Every mutating path checks the required permission
 * FIRST and BEFORE any write (backend-authoritative, mirrors {@code PunishmentService}); publishing the
 * live-update happens AFTER the write and is best-effort. The {@link PermissionResolver} port (the read
 * contract Punishments/Reports depend on) is reused unchanged as the gate.
 */
public final class PermissionAdminService {

    private static final Logger LOG = System.getLogger(PermissionAdminService.class.getName());

    public static final String ROLE_CREATE = "permission.role.create";
    public static final String ROLE_EDIT = "permission.role.edit";
    public static final String ROLE_DELETE = "permission.role.delete";
    public static final String GRANT_ROLE = "permission.grant.role";
    public static final String GRANT_PERMISSION = "permission.grant.permission";
    public static final String READ = "permission.read";

    private final RoleRepository roles;
    private final PlayerGrantRepository grants;
    private final GrantAuditPort audit;
    private final RoleAuditPort roleAudit;
    private final PermissionChangePublisher publisher;
    private final PermissionResolver permissions;
    private final Clock clock;

    public PermissionAdminService(RoleRepository roles, PlayerGrantRepository grants, GrantAuditPort audit,
            RoleAuditPort roleAudit, PermissionChangePublisher publisher, PermissionResolver permissions,
            Clock clock) {
        this.roles = roles;
        this.grants = grants;
        this.audit = audit;
        this.roleAudit = roleAudit;
        this.publisher = publisher;
        this.permissions = permissions;
        this.clock = clock;
    }

    // --- Roles (master data) ----------------------------------------------

    /** Create a role. {@code draft.id()} is ignored (DB-assigned). Requires {@link #ROLE_CREATE}. */
    public Role createRole(Role draft, java.util.UUID actor) {
        requirePermission(actor, ROLE_CREATE);
        roles.findByNameIgnoreCase(draft.name()).ifPresent(r -> {
            throw new RoleNameConflictException(draft.name());
        });
        Role saved = roles.create(draft, actor);
        roleAudit.record(RoleAuditPort.Action.ROLE_CREATE, saved.id(), saved.name(), null, actor, clock.instant());
        return saved;
    }

    /** Update name/display/weight/teamRank/active. The default role can be renamed but not deactivated. */
    public Role updateRole(Role role, java.util.UUID actor) {
        requirePermission(actor, ROLE_EDIT);
        Role existing = roles.find(role.id()).orElseThrow(() -> new RoleNotFoundException(role.id()));
        if (existing.isDefault() && !role.active()) {
            throw new DefaultRoleProtectedException("the default role cannot be deactivated");
        }
        roles.findByNameIgnoreCase(role.name())
                .filter(other -> !other.id().equals(role.id()))
                .ifPresent(other -> {
                    throw new RoleNameConflictException(role.name());
                });
        // isDefault is immutable here — preserve the stored value.
        Role toSave = withIsDefault(role, existing.isDefault());
        Role saved = roles.update(toSave);
        roleAudit.record(RoleAuditPort.Action.ROLE_UPDATE, saved.id(), saved.name(), null, actor, clock.instant());
        if (existing.active() != saved.active()) {
            // (de)activation changes effective rights of all holders (FR-007a / FR-021).
            publishToHolders(saved.id());
        }
        return saved;
    }

    /** Delete a role; cascades a REVOKE for every active holder (FR-012a). Default role is protected. */
    public void deleteRole(RoleId id, java.util.UUID actor) {
        requirePermission(actor, ROLE_DELETE);
        Role role = roles.find(id).orElseThrow(() -> new RoleNotFoundException(id));
        if (role.isDefault()) {
            throw new DefaultRoleProtectedException("the default role cannot be deleted");
        }
        Instant now = clock.instant();
        List<PlayerId> holders = grants.activeHoldersOf(id, now);
        roles.delete(id); // cascades player_role_grant + role_permission rows
        roleAudit.record(RoleAuditPort.Action.ROLE_DELETE, id, role.name(), null, actor, now);
        for (PlayerId holder : holders) {
            audit.record(GrantAuditPort.Entry.role(GrantAuditPort.Action.REVOKE, holder, id, actor,
                    "role deleted", now));
            safePublish(holder.value(), PermissionChangeType.GRANT_REVOKED);
        }
    }

    /** Add a permission to a role's configuration. Requires {@link #ROLE_EDIT}. */
    public void addRolePermission(RoleId id, String permission, java.util.UUID actor) {
        requirePermission(actor, ROLE_EDIT);
        com.mcplatform.domain.permission.PermissionString.validate(permission); // FR-014 → 422
        Role role = roles.find(id).orElseThrow(() -> new RoleNotFoundException(id));
        roles.addPermission(id, permission, actor);
        roleAudit.record(RoleAuditPort.Action.ROLE_PERMISSION_ADD, id, role.name(), permission, actor,
                clock.instant());
        publishToHolders(id);
    }

    /** Remove a permission from a role's configuration. Requires {@link #ROLE_EDIT}. */
    public void removeRolePermission(RoleId id, String permission, java.util.UUID actor) {
        requirePermission(actor, ROLE_EDIT);
        Role role = roles.find(id).orElseThrow(() -> new RoleNotFoundException(id));
        roles.removePermission(id, permission);
        roleAudit.record(RoleAuditPort.Action.ROLE_PERMISSION_REMOVE, id, role.name(), permission, actor,
                clock.instant());
        publishToHolders(id);
    }

    // --- Grants (lifecycle) -----------------------------------------------

    /** Grant a role to a player (upsert, FR-014a). Requires {@link #GRANT_ROLE}. */
    public void grantRole(PlayerId player, RoleId roleId, Instant expiresAt, String reason,
            java.util.UUID actor) {
        requirePermission(actor, GRANT_ROLE);
        roles.find(roleId).orElseThrow(() -> new RoleNotFoundException(roleId));
        Instant now = clock.instant();
        requireFutureExpiry(expiresAt, now);
        grants.upsertRoleGrant(new RoleGrant(player, roleId, actor, now, expiresAt, reason, true));
        audit.record(GrantAuditPort.Entry.role(GrantAuditPort.Action.GRANT, player, roleId, actor, reason, now));
        safePublish(player.value(), PermissionChangeType.GRANT_ADDED);
    }

    /** Revoke a player's role grant. Requires {@link #GRANT_ROLE}. */
    public void revokeRole(PlayerId player, RoleId roleId, String reason, java.util.UUID actor) {
        requirePermission(actor, GRANT_ROLE);
        Instant now = clock.instant();
        if (grants.revokeRoleGrant(player, roleId)) {
            audit.record(GrantAuditPort.Entry.role(GrantAuditPort.Action.REVOKE, player, roleId, actor, reason, now));
            safePublish(player.value(), PermissionChangeType.GRANT_REVOKED);
        }
    }

    /** Grant a single permission directly to a player (upsert). Requires {@link #GRANT_PERMISSION}. */
    public void grantPermission(PlayerId player, String permission, Instant expiresAt, String reason,
            java.util.UUID actor) {
        requirePermission(actor, GRANT_PERMISSION);
        com.mcplatform.domain.permission.PermissionString.validate(permission); // FR-014 → 422
        Instant now = clock.instant();
        requireFutureExpiry(expiresAt, now);
        grants.upsertPermissionGrant(new PermissionGrant(player, permission, actor, now, expiresAt, reason, true));
        audit.record(GrantAuditPort.Entry.permission(GrantAuditPort.Action.GRANT, player, permission, actor, reason, now));
        safePublish(player.value(), PermissionChangeType.GRANT_ADDED);
    }

    /** Revoke a player's direct permission grant. Requires {@link #GRANT_PERMISSION}. */
    public void revokePermission(PlayerId player, String permission, String reason, java.util.UUID actor) {
        requirePermission(actor, GRANT_PERMISSION);
        Instant now = clock.instant();
        if (grants.revokePermissionGrant(player, permission)) {
            audit.record(GrantAuditPort.Entry.permission(GrantAuditPort.Action.REVOKE, player, permission, actor, reason, now));
            safePublish(player.value(), PermissionChangeType.GRANT_REVOKED);
        }
    }

    // --- helpers ----------------------------------------------------------

    private void requirePermission(java.util.UUID actor, String permission) {
        if (!permissions.hasPermission(actor, permission)) {
            throw new PermissionDeniedException(actor, permission);
        }
    }

    private static void requireFutureExpiry(Instant expiresAt, Instant now) {
        if (expiresAt != null && !expiresAt.isAfter(now)) {
            throw new InvalidGrantException("expiry must be in the future: " + expiresAt);
        }
    }

    /** Role-permission/(de)activation change → notify every current active holder (R7 / FR-021). */
    private void publishToHolders(RoleId id) {
        for (PlayerId holder : grants.activeHoldersOf(id, clock.instant())) {
            safePublish(holder.value(), PermissionChangeType.ROLE_CONFIG_CHANGED);
        }
    }

    private static Role withIsDefault(Role r, boolean isDefault) {
        return new Role(r.id(), r.name(), r.displayName(), r.color(), r.prefix(), r.suffix(),
                r.tabListColor(), r.tabListIcon(), r.displayIcon(), r.weight(), r.teamRank(), r.active(), isDefault);
    }

    private void safePublish(java.util.UUID player, PermissionChangeType type) {
        try {
            publisher.publish(player, type);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "permission change publish failed (non-fatal)", e);
        }
    }
}
