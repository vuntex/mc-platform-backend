package com.mcplatform.application.permission.catalog;

/**
 * Each feature contributes its own group of web-interface permissions to the catalog ("ein Feature =
 * ein Anstecken", Constitution §9) — no central god-list. The contributor lives in the owning feature's
 * package and references that feature's permission constants, so the catalog cannot drift from the keys
 * actually gated. The composition root registers each contributor as a bean.
 */
public interface PermissionCatalogContributor {

    PermissionCatalogGroup group();
}
