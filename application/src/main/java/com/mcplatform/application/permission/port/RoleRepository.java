package com.mcplatform.application.permission.port;

import com.mcplatform.domain.permission.Role;
import com.mcplatform.domain.permission.RoleId;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port for role master data + role-permission configuration (state-stored, not event-sourced).
 * Implemented by the jOOQ adapter. The default role is guaranteed to exist (seeded by the migration).
 */
public interface RoleRepository {

    /** Persists a new role (with audit {@code createdBy}); returns it with its assigned {@link RoleId}. */
    Role create(Role role, UUID createdBy);

    /** Updates name/display/weight/teamRank/active of an existing role. */
    Role update(Role role);

    /** Hard-deletes a role; the DB cascades its {@code role_permission} and {@code player_role_grant} rows. */
    void delete(RoleId id);

    Optional<Role> find(RoleId id);

    Optional<Role> findByNameIgnoreCase(String name);

    List<Role> findAll();

    /** The single default role (FR-012). */
    Role findDefault();

    /** Active roles among the given ids (deactivated roles are excluded, FR-007a). */
    List<Role> findActiveByIds(Collection<RoleId> ids);

    /** The permission strings configured on a role (master data, no expiry). */
    List<String> permissionsOf(RoleId id);

    /** Adds a permission to a role's configuration (idempotent), with audit {@code addedBy}. */
    void addPermission(RoleId id, String permission, UUID addedBy);

    /** Removes a permission from a role's configuration. */
    void removePermission(RoleId id, String permission);
}
