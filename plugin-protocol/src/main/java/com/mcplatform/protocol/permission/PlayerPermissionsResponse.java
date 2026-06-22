package com.mcplatform.protocol.permission;

import java.util.List;
import java.util.UUID;

/**
 * A player's full permission state: active rank grants, active direct permission grants, the flattened
 * effective permission set and the chosen display. Pure data (JDK only).
 */
public record PlayerPermissionsResponse(
        UUID player,
        List<ActiveGrant> roles,
        List<ActiveGrant> permissions,
        List<String> effectivePermissions,
        RoleDisplay display) {
}
