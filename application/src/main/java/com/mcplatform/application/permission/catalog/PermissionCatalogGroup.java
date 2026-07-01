package com.mcplatform.application.permission.catalog;

import java.util.List;

/**
 * A themed group of web-interface permissions (e.g. Economy, Rollen &amp; Permissions) for the
 * permission-catalog overview. Each group and each permission carries a German description so the
 * role editor is self-explanatory. Pure data, no framework.
 */
public record PermissionCatalogGroup(String key, String displayName, String description,
        List<PermissionCatalogEntry> permissions) {

    public PermissionCatalogGroup {
        permissions = List.copyOf(permissions);
    }
}
