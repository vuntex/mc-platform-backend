package com.mcplatform.protocol.permission;

/** The presentation chosen for a player (FR-019): the display fields of the winning role. */
public record RoleDisplay(
        String displayName,
        String color,
        String prefix,
        String suffix,
        String tabListColor,
        String tabListIcon,
        String displayIcon) {
}
