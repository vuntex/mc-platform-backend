package com.mcplatform.protocol.permission;

import java.util.UUID;

/**
 * Create/update a role. {@code actor} is the staff UUID performing the action (source of audit /
 * created_by; the later auth feature will derive it from the session). On create, {@code active} is
 * typically true; {@code isDefault} is never set via the API. {@code displayIcon} is an opaque,
 * plugin-interpreted icon reference ({@code <type>:<payload>}, e.g. {@code material:DIAMOND_SWORD});
 * null means no icon.
 */
public record RoleRequest(
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
        UUID actor) {
}
