package com.mcplatform.application.permission;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Read model of a player's permission state — protocol-agnostic (the api-rest mapper turns it into the
 * wire DTO). Active rank grants and direct permission grants are flattened to {@link GrantSummary}; the
 * effective set is the union (+ default fallback + direct); {@code display} is the chosen presentation
 * (FR-019), defaulting to the default role when no active rank applies.
 */
public record PlayerPermissionsView(
        UUID player,
        List<GrantSummary> roles,
        List<GrantSummary> permissions,
        Set<String> effectivePermissions,
        List<PermissionOrigin> sources,
        Display display) {

    /** One active grant; {@code label} is the role name (rank) or the permission string (direct). */
    public record GrantSummary(String label, Instant expiresAt, UUID issuedBy, String reason) {
    }

    /**
     * Provenance of one effective permission (FR-022a): {@code own} = held via a direct permission grant;
     * {@code inheritedFromRoleIds} = the full set of role ids (direct or transitively inherited) that
     * supply it. Protocol-free (plain {@code long}s); the api-rest mapper turns it into the wire DTO.
     */
    public record PermissionOrigin(String permission, boolean own, List<Long> inheritedFromRoleIds) {
    }

    /** The chosen display fields (FR-019). {@code displayIcon} is an opaque, plugin-interpreted ref. */
    public record Display(String displayName, String color, String prefix, String suffix,
            String tabListColor, String tabListIcon, String displayIcon) {
    }
}
