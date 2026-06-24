package com.mcplatform.protocol.permission.web;

/**
 * Create/update a role from the web interface. Unlike {@code RoleRequest} this carries NO {@code actor}
 * field — the acting admin is derived exclusively from the JWT (FR-002/FR-020), so it cannot be forged.
 * {@code isDefault} is never set via the API. {@code displayIcon} is an opaque, plugin-interpreted icon
 * reference ({@code <type>:<payload>}); null means no icon.
 */
public record RoleWriteRequest(
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
        boolean active) {
}
