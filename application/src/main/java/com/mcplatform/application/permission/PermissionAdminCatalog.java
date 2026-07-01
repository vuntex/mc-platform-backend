package com.mcplatform.application.permission;

import com.mcplatform.application.permission.catalog.PermissionCatalogContributor;
import com.mcplatform.application.permission.catalog.PermissionCatalogEntry;
import com.mcplatform.application.permission.catalog.PermissionCatalogGroup;
import java.util.List;

/**
 * Catalog contribution for the rank/permission web feature. References the {@link PermissionAdminService}
 * constants directly, so the catalog stays in lockstep with the keys actually gated.
 */
public final class PermissionAdminCatalog implements PermissionCatalogContributor {

    @Override
    public PermissionCatalogGroup group() {
        return new PermissionCatalogGroup(
                "permissions",
                "Rollen & Permissions",
                "Verwaltung von Rollen, deren Permissions und der Rollen-/Permission-Zuweisungen an Spieler im Webinterface.",
                List.of(
                        new PermissionCatalogEntry(PermissionAdminService.READ,
                                "Lesezugriff auf Rollen, Spieler-Permissions und die Spielersuche im Webinterface."),
                        new PermissionCatalogEntry(PermissionAdminService.ROLE_CREATE,
                                "Neue Rollen anlegen."),
                        new PermissionCatalogEntry(PermissionAdminService.ROLE_EDIT,
                                "Rollen bearbeiten (Anzeigename, Farbe, Gewicht, Permissions der Rolle)."),
                        new PermissionCatalogEntry(PermissionAdminService.ROLE_EDIT_INHERIT,
                                "Rollen-Vererbung bearbeiten (Eltern-Rollen hinzufügen oder entfernen)."),
                        new PermissionCatalogEntry(PermissionAdminService.ROLE_DELETE,
                                "Rollen löschen."),
                        new PermissionCatalogEntry(PermissionAdminService.GRANT_ROLE,
                                "Spielern Rollen zuweisen oder entziehen."),
                        new PermissionCatalogEntry(PermissionAdminService.GRANT_PERMISSION,
                                "Spielern einzelne Permissions zuweisen oder entziehen.")));
    }
}
