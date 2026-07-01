package com.mcplatform.protocol.permission;

import java.util.List;

/**
 * The catalog of all web-interface permissions, grouped by theme — used by the role editor to show
 * which permissions exist and what they do. Pure data (JDK only); field names are the wire contract.
 */
public record PermissionCatalogResponse(List<PermissionGroupResponse> groups) {
}
