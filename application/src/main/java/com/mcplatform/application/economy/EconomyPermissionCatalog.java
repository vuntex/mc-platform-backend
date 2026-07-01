package com.mcplatform.application.economy;

import com.mcplatform.application.permission.catalog.PermissionCatalogContributor;
import com.mcplatform.application.permission.catalog.PermissionCatalogEntry;
import com.mcplatform.application.permission.catalog.PermissionCatalogGroup;
import java.util.List;

/**
 * Catalog contribution for the economy web feature (spec 007). References {@link EconomyPermissions} so
 * the catalog stays in lockstep with the gated key.
 */
public final class EconomyPermissionCatalog implements PermissionCatalogContributor {

    @Override
    public PermissionCatalogGroup group() {
        return new PermissionCatalogGroup(
                "economy",
                "Economy",
                "Lesezugriff auf Economy-Daten im Webinterface.",
                List.of(new PermissionCatalogEntry(EconomyPermissions.READ,
                        "Economy-Daten lesen: Spieler-Guthaben, serverweite Transaktionsliste, "
                                + "Transaktions-Detail und Live-Stream.")));
    }
}
