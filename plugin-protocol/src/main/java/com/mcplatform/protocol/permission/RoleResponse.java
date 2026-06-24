package com.mcplatform.protocol.permission;

import java.util.List;

/** A role with its configured permissions (master data). Pure data (JDK only). */
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
        List<String> permissions) {
}
