package com.mcplatform.protocol.permission;

import java.util.List;

/**
 * A role with its configured (own) permissions and the ids of the roles it DIRECTLY inherits
 * ({@code inheritedRoleIds}, FR-001). Permissions here are the role's own master data; the transitive
 * effective set is exposed via the effective views. Pure data (JDK only).
 */
public record RoleResponse(
        long id,
        String name,
        String displayName,
        String color,
        String prefix,
        String suffix,
        String tabListColor,
        String tabListIcon,
        String displayIcon,
        int weight,
        boolean teamRank,
        boolean active,
        boolean isDefault,
        List<String> permissions,
        List<Long> inheritedRoleIds) {
}
