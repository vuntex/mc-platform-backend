package com.mcplatform.protocol.permission;

import java.util.List;

/**
 * A themed group of web-interface permissions in the catalog (e.g. Economy, Rollen &amp; Permissions),
 * with its own description and the permissions it contains. Pure data (JDK only); field names are the
 * wire contract.
 */
public record PermissionGroupResponse(String key, String displayName, String description,
        List<PermissionInfoResponse> permissions) {
}
