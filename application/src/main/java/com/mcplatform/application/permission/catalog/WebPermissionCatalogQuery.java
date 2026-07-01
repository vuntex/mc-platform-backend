package com.mcplatform.application.permission.catalog;

import java.util.Comparator;
import java.util.List;

/**
 * Read-only use case: the catalog of all web-interface permissions, grouped by theme, for the role
 * editor. Aggregates every {@link PermissionCatalogContributor} (one per feature) and returns the
 * groups in a stable order (by display name). Static metadata — no DB, no mutation.
 */
public final class WebPermissionCatalogQuery {

    private final List<PermissionCatalogContributor> contributors;

    public WebPermissionCatalogQuery(List<PermissionCatalogContributor> contributors) {
        this.contributors = List.copyOf(contributors);
    }

    public List<PermissionCatalogGroup> groups() {
        return contributors.stream()
                .map(PermissionCatalogContributor::group)
                .sorted(Comparator.comparing(PermissionCatalogGroup::displayName))
                .toList();
    }
}
