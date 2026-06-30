package com.mcplatform.protocol.permission;

import java.util.List;

/**
 * Provenance of one effective permission (FR-022a). {@code own} = held directly (a direct permission
 * grant for a player, or the role's own configured permission for a role view); {@code inheritedFromRoleIds}
 * = the FULL set of role ids that supply it via inheritance (empty when purely own). With pure union
 * semantics every source is equally valid, so the complete set is reported — not an arbitrary "nearest".
 * Pure data (JDK only). The flat {@code effectivePermissions} list stays the authoritative allow/deny set.
 */
public record EffectivePermissionEntry(String permission, boolean own, List<Long> inheritedFromRoleIds) {
}
