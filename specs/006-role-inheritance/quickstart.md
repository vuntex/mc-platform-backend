# Phase 1 — Quickstart & Definition of Done: Rollen-Vererbung

## Build-/Publish-Reihenfolge (protocol ändert sich → Pflicht)

```bash
# 1. plugin-protocol bauen + lokal publizieren (neue/erweiterte DTOs)
./gradlew :plugin-protocol:publishToMavenLocal

# 2. Backend bauen + testen
./gradlew build

# 3. Im Plugin-Repo (separat): Dependencies neu ziehen
#    ./gradlew build --refresh-dependencies
```

## Migration

`V15__role_inheritance.sql` wird beim Backend-Start von Flyway gezogen. Keine bestehende Migration
anfassen. Prüfen: `role_inheritance` existiert, FKs/CHECK/Index angelegt.

## Manuelle Verifikation (Happy Path + Fehlerpfade)

Voraussetzung: gültiges Web-JWT eines Admins (hat `*`).

```bash
# Rollen anlegen: Base (feature.a), Premium (leer)
curl -XPOST .../api/web/permission/roles -H "$AUTH" -d '{"name":"Base", ...}'
curl -XPOST .../api/web/permission/roles -H "$AUTH" -d '{"name":"Premium", ...}'
curl -XPOST .../api/web/permission/roles/{baseId}/permissions -H "$AUTH" -d '{"permission":"feature.a"}'

# Premium erbt Base
curl -XPOST .../api/web/permission/roles/{premiumId}/inheritance -H "$AUTH" -d '{"parentRoleId": <baseId>}'
# → 200, RoleResponse.inheritedRoleIds enthält baseId

# Spieler nur mit Premium → besitzt effektiv feature.a (transitiv)
curl .../api/web/permission/players/{uuid}/effective -H "$AUTH"
# → effectivePermissions enthält "feature.a"; sources zeigt {permission:"feature.a", own:false, inheritedFromRoleIds:[baseId]}

# Zyklus: Base erbt Premium → 409
curl -XPOST .../api/web/permission/roles/{baseId}/inheritance -H "$AUTH" -d '{"parentRoleId": <premiumId>}'  # → 409

# Default als child → 409 (Blatt)
curl -XPOST .../api/web/permission/roles/{defaultId}/inheritance -H "$AUTH" -d '{"parentRoleId": <baseId>}'  # → 409

# Base löschen während Premium erbt → 409
curl -XDELETE .../api/web/permission/roles/{baseId} -H "$AUTH"  # → 409 (nennt Premium)

# Kante entfernen → Spieler verliert feature.a
curl -XDELETE .../api/web/permission/roles/{premiumId}/inheritance/{baseId} -H "$AUTH"  # → 200
```

## Tests pro Schicht (Constitution §22)

- **Domain (`RoleHierarchyTest`, ohne DB):** lineare Kette, Diamant (jede Permission einmal +
  vollständige Quellenmenge), Visited-Set terminiert bei künstlichem Restzyklus, `wouldCreateCycle`
  (direkt + transitiv), nur Permissions (keine Meta-Felder).
- **Resolver-Regression (Testcontainers):** bestehende 002-Resolver-Suite läuft unverändert grün;
  expliziter Test „role_inheritance leer ⇒ Ergebnis bit-identisch zur Referenz" (FR-008/SC-002).
- **Resolver transitiv (Testcontainers):** Premium→Base, Premium→Default vs. nicht-Default (CL-1-Fall),
  0 Rollen ⇒ Default-Fallback.
- **Use-Case (Fakes):** add/remove idempotent, Gate `permission.role.edit.inherit`, 409-Zyklus,
  409-Default-als-child, 409-delete-while-inherited, Audit-Einträge geschrieben,
  `publishToRoleAndDependents` feuert an die richtige (transitive) Holder-Menge — auch bei
  `addRolePermission` (FR-020a).
- **Integration (`JooqRoleInheritanceRepositoryTest`, Testcontainers):** add/remove/directParents/
  ancestors/dependents/isInheritedByAny; `ON CONFLICT DO NOTHING`; RESTRICT-Netz.
- **E2E (`WebPermissionInheritanceE2ETest`):** alle Endpoints inkl. 401/403/404/409/422; effective mit
  `sources`.

## Definition of Done

- [ ] `./gradlew :plugin-protocol:publishToMavenLocal` grün, danach `./gradlew build` grün (Backend).
- [ ] Tests aller Schichten grün (Domain/Resolver-Regression/Use-Case/Integration/E2E inkl. Fehlerpfade).
- [ ] Bestehende Permission-Tests (002/005) unverändert grün (Regression-Beweis).
- [ ] `V15` zieht sauber; keine bestehende Migration verändert.
- [ ] PROGRESS.md nachgezogen (Vererbung als angestecktes Feature + die zwei bewussten Kern-Eingriffe
      dokumentiert); FEATURE_INVENTORY.md-Eintrag (Vererbung, Greenfield) abgehakt.
- [ ] Bestätigt: kein generischer Baustein geändert; die zwei Feature-Kern-Eingriffe (Resolver-SQL,
      `PermissionAdminService`-Fan-out) sind in plan.md/Complexity Tracking benannt + regressionsgesichert.
