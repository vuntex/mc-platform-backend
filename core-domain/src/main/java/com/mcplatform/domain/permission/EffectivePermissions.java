package com.mcplatform.domain.permission;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The effective permission set of a player: the union of all permissions from the player's active rank
 * grants (on active roles) plus all active direct permission grants. If the player holds no active rank
 * grant, the default role's permissions apply instead (implicit fallback, FR-005). Purely additive — no
 * permission can revoke another (FR-002/FR-004).
 *
 * <p>This is a pure function: callers (the resolver / query service) pre-filter to ACTIVE grants on
 * ACTIVE roles (FR-006/FR-007a) and pass the resulting permission strings in. Wildcard semantics are
 * applied by {@link PermissionMatcher} in {@link #allows(String)}.
 */
public final class EffectivePermissions {

    private final Set<String> permissions;

    private EffectivePermissions(Set<String> permissions) {
        this.permissions = Set.copyOf(permissions);
    }

    /**
     * Builds the effective set.
     *
     * @param activeRolePermissions permissions of each active role the player holds via an active grant;
     *                              if empty, the default role applies
     * @param defaultRolePermissions the default role's permissions (used iff {@code activeRolePermissions}
     *                              is empty)
     * @param directPermissions permissions from active direct grants (always added)
     */
    public static EffectivePermissions resolve(
            Map<RoleId, List<String>> activeRolePermissions,
            Collection<String> defaultRolePermissions,
            Collection<String> directPermissions) {
        Set<String> union = new HashSet<>();
        if (activeRolePermissions.isEmpty()) {
            union.addAll(defaultRolePermissions);
        } else {
            activeRolePermissions.values().forEach(union::addAll);
        }
        union.addAll(directPermissions);
        return new EffectivePermissions(union);
    }

    /** The flattened union of permission strings (may contain wildcards). */
    public Set<String> permissions() {
        return permissions;
    }

    /** Whether the concrete {@code query} permission is allowed under wildcard/union semantics. */
    public boolean allows(String query) {
        return PermissionMatcher.matches(permissions, query);
    }
}
