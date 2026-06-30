package com.mcplatform.application.permission;

import com.mcplatform.application.permission.port.DefaultRoleProtectedException;
import com.mcplatform.application.permission.port.GrantAuditPort;
import com.mcplatform.application.permission.port.PermissionChangePublisher;
import com.mcplatform.application.permission.port.PlayerGrantRepository;
import com.mcplatform.application.permission.port.RoleNameConflictException;
import com.mcplatform.application.permission.port.RoleAuditPort;
import com.mcplatform.application.permission.port.RoleInheritanceCycleException;
import com.mcplatform.application.permission.port.RoleInheritanceRepository;
import com.mcplatform.application.permission.port.RoleInheritedException;
import com.mcplatform.application.permission.port.RoleNotFoundException;
import com.mcplatform.application.permission.port.RoleRepository;
import com.mcplatform.application.security.PermissionDeniedException;
import com.mcplatform.application.security.PermissionResolver;
import com.mcplatform.domain.permission.InvalidGrantException;
import com.mcplatform.domain.permission.PermissionChangeType;
import com.mcplatform.domain.permission.PermissionGrant;
import com.mcplatform.domain.permission.Role;
import com.mcplatform.domain.permission.RoleGrant;
import com.mcplatform.domain.permission.RoleHierarchy;
import com.mcplatform.domain.permission.RoleId;
import com.mcplatform.domain.player.PlayerId;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

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
    public static final String ROLE_EDIT_INHERIT = "permission.role.edit.inherit";
    public static final String ROLE_DELETE = "permission.role.delete";
    public static final String GRANT_ROLE = "permission.grant.role";
    public static final String GRANT_PERMISSION = "permission.grant.permission";
    public static final String READ = "permission.read";

    private final RoleRepository roles;
    private final PlayerGrantRepository grants;
    private final RoleInheritanceRepository inheritance;
    private final GrantAuditPort audit;
    private final RoleAuditPort roleAudit;
    private final PermissionChangePublisher publisher;
    private final PermissionResolver permissions;
    private final Clock clock;

    public PermissionAdminService(RoleRepository roles, PlayerGrantRepository grants,
            RoleInheritanceRepository inheritance, GrantAuditPort audit, RoleAuditPort roleAudit,
            PermissionChangePublisher publisher, PermissionResolver permissions, Clock clock) {
        this.roles = roles;
        this.grants = grants;
        this.inheritance = inheritance;
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
        // Any holder-visible change must reach online holders live (FR-007a / FR-021): (de)activation
        // changes effective rights, and the presentation fields drive each holder's RoleDisplay — used by
        // tab list, chat format and the scoreboard rank line. Without this, a displayName/color/prefix edit
        // would persist but never push, so live consumers stay stale until the player rejoins.
        if (existing.active() != saved.active() || presentationChanged(existing, saved)) {
            publishToRoleAndDependents(saved.id());
        }
        return saved;
    }

    /** Whether a holder-visible presentation field changed (drives RoleDisplay; name/isDefault excluded). */
    private static boolean presentationChanged(Role a, Role b) {
        return !java.util.Objects.equals(a.displayName(), b.displayName())
                || !java.util.Objects.equals(a.color(), b.color())
                || !java.util.Objects.equals(a.prefix(), b.prefix())
                || !java.util.Objects.equals(a.suffix(), b.suffix())
                || !java.util.Objects.equals(a.tabListColor(), b.tabListColor())
                || !java.util.Objects.equals(a.tabListIcon(), b.tabListIcon())
                || !java.util.Objects.equals(a.displayIcon(), b.displayIcon())
                || a.weight() != b.weight()
                || a.teamRank() != b.teamRank();
    }

    /** Delete a role; cascades a REVOKE for every active holder (FR-012a). Default role is protected. */
    public void deleteRole(RoleId id, java.util.UUID actor) {
        requirePermission(actor, ROLE_DELETE);
        Role role = roles.find(id).orElseThrow(() -> new RoleNotFoundException(id));
        if (role.isDefault()) {
            throw new DefaultRoleProtectedException("the default role cannot be deleted");
        }
        List<RoleId> dependents = inheritance.directChildren(id);
        if (!dependents.isEmpty()) {
            // FR-015: a role still inherited by others cannot be deleted — remove the edge there first.
            List<String> names = dependents.stream()
                    .map(d -> roles.find(d).map(Role::name).orElse("role#" + d.value())).toList();
            throw new RoleInheritedException(id, names);
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
        publishToRoleAndDependents(id);
    }

    /** Remove a permission from a role's configuration. Requires {@link #ROLE_EDIT}. */
    public void removeRolePermission(RoleId id, String permission, java.util.UUID actor) {
        requirePermission(actor, ROLE_EDIT);
        Role role = roles.find(id).orElseThrow(() -> new RoleNotFoundException(id));
        roles.removePermission(id, permission);
        roleAudit.record(RoleAuditPort.Action.ROLE_PERMISSION_REMOVE, id, role.name(), permission, actor,
                clock.instant());
        publishToRoleAndDependents(id);
    }

    // --- Inheritance (edges) ----------------------------------------------

    /**
     * Make {@code child} inherit the permissions of {@code parent} (idempotent, FR-014). Requires
     * {@link #ROLE_EDIT_INHERIT}. Rejects: {@code child} = default role (the default is a leaf, FR-013)
     * and any edge that would create a cycle (FR-010, pre-check before write → 409). Live-pushes to the
     * holders of {@code child} and everything that transitively inherits it (FR-020).
     */
    public void addInheritance(RoleId child, RoleId parent, java.util.UUID actor) {
        requirePermission(actor, ROLE_EDIT_INHERIT);
        Role c = roles.find(child).orElseThrow(() -> new RoleNotFoundException(child));
        roles.find(parent).orElseThrow(() -> new RoleNotFoundException(parent));
        if (c.isDefault()) {
            throw new DefaultRoleProtectedException(
                    "the default role is a leaf and cannot inherit other roles");
        }
        if (RoleHierarchy.wouldCreateCycle(child, parent, inheritance::directParents)) {
            throw new RoleInheritanceCycleException(child, parent);
        }
        inheritance.add(child, parent, actor);
        roleAudit.record(RoleAuditPort.Action.ROLE_INHERITANCE_ADD, child, c.name(), null, actor,
                clock.instant());
        publishToRoleAndDependents(child);
    }

    /** Remove the inheritance edge {@code child -> parent} (idempotent). Requires {@link #ROLE_EDIT_INHERIT}. */
    public void removeInheritance(RoleId child, RoleId parent, java.util.UUID actor) {
        requirePermission(actor, ROLE_EDIT_INHERIT);
        Role c = roles.find(child).orElseThrow(() -> new RoleNotFoundException(child));
        if (inheritance.remove(child, parent)) {
            roleAudit.record(RoleAuditPort.Action.ROLE_INHERITANCE_REMOVE, child, c.name(), null, actor,
                    clock.instant());
            publishToRoleAndDependents(child);
        }
    }

    // --- Grants (lifecycle) -----------------------------------------------

    /** Grant a role to a player (upsert, FR-014a). Requires {@link #GRANT_ROLE}. */
    public void grantRole(PlayerId player, RoleId roleId, Instant expiresAt, String reason,
            java.util.UUID actor) {
        requirePermission(actor, GRANT_ROLE);
        Role role = roles.find(roleId).orElseThrow(() -> new RoleNotFoundException(roleId));
        if (role.isDefault()) {
            // The default role is never granted: it is the implicit fallback that applies exactly when a
            // player holds no other active role (resolver/EffectivePermissions). Granting it is incoherent.
            throw new DefaultRoleProtectedException(
                    "the default role cannot be granted; it is the automatic fallback when a player has no other role");
        }
        Instant now = clock.instant();
        requireFutureExpiry(expiresAt, now);
        grants.upsertRoleGrant(new RoleGrant(player, roleId, actor, now, expiresAt, reason, true));
        audit.record(GrantAuditPort.Entry.role(GrantAuditPort.Action.GRANT, player, roleId, actor, reason, now));
        safePublish(player.value(), PermissionChangeType.GRANT_ADDED);
    }

    /** Revoke a player's role grant. Requires {@link #GRANT_ROLE}. */
    public void revokeRole(PlayerId player, RoleId roleId, String reason, java.util.UUID actor) {
        requirePermission(actor, GRANT_ROLE);
        // The default role is never a real grant (implicit fallback) → it cannot be revoked. Unknown roles
        // stay lenient (no-op), so only block when the role exists AND is the default.
        roles.find(roleId).filter(Role::isDefault).ifPresent(r -> {
            throw new DefaultRoleProtectedException("the default role cannot be revoked; it is the automatic fallback");
        });
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

    /**
     * Role-config / (de)activation / inheritance change → notify every affected active holder
     * (FR-020/FR-020a/FR-021): the holders of {@code id} AND of every role that transitively inherits
     * {@code id} (reverse closure), each once. With no inheritance this degrades to the prior
     * direct-holders behaviour (bit-identical fan-out).
     */
    private void publishToRoleAndDependents(RoleId id) {
        Instant now = clock.instant();
        Set<RoleId> affectedRoles = new LinkedHashSet<>();
        affectedRoles.add(id);
        affectedRoles.addAll(inheritance.dependents(id));
        Set<UUID> affectedPlayers = new LinkedHashSet<>();
        for (RoleId role : affectedRoles) {
            for (PlayerId holder : grants.activeHoldersOf(role, now)) {
                affectedPlayers.add(holder.value());
            }
        }
        for (UUID player : affectedPlayers) {
            safePublish(player, PermissionChangeType.ROLE_CONFIG_CHANGED);
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
