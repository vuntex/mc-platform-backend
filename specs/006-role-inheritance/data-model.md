# Phase 1 — Data Model: Rollen-Vererbung

## Persistenz (PostgreSQL, V15)

### Tabelle `role_inheritance` (neu)

Gerichtete M:N-Kante „Rolle *child* erbt von Rolle *parent*". State-stored CRUD; der Verlauf liegt im
bestehenden `role_audit`.

| Spalte | Typ | Constraints | Bedeutung |
|---|---|---|---|
| `role_id` | BIGINT | NOT NULL, FK → `role(id)` ON DELETE **CASCADE**, PK-Teil | die **erbende** (child) Rolle |
| `inherited_role_id` | BIGINT | NOT NULL, FK → `role(id)` ON DELETE **RESTRICT**, PK-Teil | die **geerbte** (parent) Rolle |
| `created_by` | UUID | nullable | Audit: wer die Kante setzte |
| `created_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() | Audit: wann |

- **PK** `(role_id, inherited_role_id)` → idempotentes Setzen (FR-014).
- **CHECK** `role_id <> inherited_role_id` → keine Selbstkante (FR-010, letzte Bastion).
- **INDEX** `idx_role_inheritance_parent (inherited_role_id)` → schnelle Reverse-Closure `dependents()`.
- CASCADE auf `role_id`: child gelöscht ⇒ ausgehende Kanten weg. RESTRICT auf `inherited_role_id`:
  parent kann nicht gelöscht werden solange geerbt (FR-015 DB-Netz; Use-Case liefert 409 vorher).

### Bestehende Tabellen — unverändert

`role`, `role_permission`, `player_role_grant`, `player_permission_grant`, `grant_audit`, `role_audit`
bleiben strukturell unangetastet. `role_audit.action` bekommt zwei neue **Werte** (kein DDL):
`ROLE_INHERITANCE_ADD`, `ROLE_INHERITANCE_REMOVE`.

## Domain (core-domain, framework-frei)

### `RoleHierarchy` (neu) — reine transitive Auflösung

Keine DB-Imports. Operiert auf übergebenen Adjazenz-/Permissions-Strukturen (vom Query-Service aus den
Repos befüllt). Verantwortung:

```
RoleHierarchy.reachable(
    Set<RoleId> startRoles,
    Function<RoleId, List<RoleId>> directParentsOf)        // role → direkte Eltern
  → Set<RoleId>                                            // transitive Hülle, Visited-Set, terminiert bei Restzyklus

RoleHierarchy.resolveWithProvenance(
    Set<RoleId> startRoles,
    Function<RoleId, List<RoleId>> directParentsOf,
    Function<RoleId, List<String>> permissionsOf,
    Set<String> directPlayerPermissions)                   // optional, für die Spieler-Sicht
  → Map<String, Provenance>                                // permission → {own, Set<RoleId> sources}

RoleHierarchy.wouldCreateCycle(
    RoleId child, RoleId parent,
    Function<RoleId, List<RoleId>> directParentsOf)
  → boolean                                                // true, wenn child aus parent erreichbar oder child==parent
```

- `Provenance` = `{ boolean own, Set<RoleId> sources }`. Mengensemantik → jede Permission einmal, mit
  vollständiger Quell-Rollenmenge (FR-022a). Reihenfolge irrelevant (FR-004).
- Visited-Set garantiert Terminierung (FR-010a).
- **Vererbt nur Permissions** — `RoleHierarchy` kennt keine Meta-Felder (FR-002).

### `EffectivePermissions` (unverändert) — Regression-Anker

Bleibt die reine Union-Funktion über eine fertige `Map<RoleId, List<String>>`. Für die flache
effektive Menge wird ihr weiterhin die — jetzt transitiv expandierte — Rollen-Permissions-Map
übergeben. Kein Code-Change.

## Application

### `RoleInheritanceRepository` (neuer Port)

```
void add(RoleId child, RoleId parent, UUID actor)     // idempotent (ON CONFLICT DO NOTHING)
boolean remove(RoleId child, RoleId parent)           // true, wenn eine Kante entfernt wurde
List<RoleId> directParents(RoleId child)              // direkte Eltern (für US4-Liste + Domain-Adjazenz)
List<RoleId> ancestors(RoleId role)                   // transitive Eltern (Zyklus-Vorabcheck)
List<RoleId> dependents(RoleId role)                  // transitive Kinder/Reverse-Closure (Live-Push-Fan-out)
boolean isInheritedByAny(RoleId role)                 // für deleteRole-Vorabprüfung (FR-015)
```

### `PermissionAdminService` (erweitert)

- `String ROLE_EDIT_INHERIT = "permission.role.edit.inherit"` (neue Gate-Konstante, FR-019).
- `addInheritance(child, parent, actor)`: Gate `ROLE_EDIT_INHERIT`; lehnt ab wenn `child` = Default
  (FR-013, `DefaultRoleProtectedException`), wenn `child==parent` oder Zyklus
  (`RoleInheritanceCycleException` → 409); `repo.add`; `roleAudit.record(ROLE_INHERITANCE_ADD, …)`;
  `publishToRoleAndDependents(child)`.
- `removeInheritance(child, parent, actor)`: Gate; `repo.remove`; Audit `ROLE_INHERITANCE_REMOVE`;
  `publishToRoleAndDependents(child)`.
- `deleteRole(...)` (erweitert): vor `roles.delete` prüfen `repo.isInheritedByAny(id)` → bei true 409
  (`RoleInheritedException` mit abhängigen Namen, FR-015).
- `publishToHolders(id)` → ersetzt durch `publishToRoleAndDependents(id)`: Fan-out an Holder von
  `{id} ∪ dependents(id)` (FR-020/FR-020a) — wirkt auch auf `addRolePermission`/`removeRolePermission`.

### `PermissionQueryService` (erweitert)

- `effectiveFor(player)`: baut `startRoles` aus den aktiven direkten Grants, expandiert via
  `RoleHierarchy` (transitiv), übergibt die expandierte Permissions-Map an `EffectivePermissions`
  (flach) UND berechnet `Map<String, Provenance>` für die `sources` (FR-022a).
- `roleDetail(id)` / neue `effectiveRolePermissions(id)`: liefert eigene + transitiv geerbte
  Permissions mit Provenienz; `directParents(id)` für `RoleResponse.inheritedRoleIds`.

## Wire / DTO (plugin-protocol, additiv)

| DTO | Änderung |
|---|---|
| `web/InheritanceWriteRequest` | **neu**: `record(long parentRoleId)` |
| `RoleResponse` | **+** `List<Long> inheritedRoleIds` (direkte Eltern) |
| `EffectivePermissionEntry` | **neu**: `record(String permission, boolean own, List<Long> inheritedFromRoleIds)` |
| `PlayerPermissionsResponse` | **+** `List<EffectivePermissionEntry> sources` (flat `effectivePermissions` bleibt) |
| `PermissionEndpoints` | **+** `LIST_INHERITANCE`, `ADD_INHERITANCE`, `REMOVE_INHERITANCE` |

## Validierungs-/Geschäftsregeln (Zusammenfassung → FR-Mapping)

| Regel | Quelle |
|---|---|
| Nur Permissions vererben, keine Meta-Felder | FR-002 |
| Transitive Union, Mengensemantik, reihenfolgeunabhängig | FR-003/FR-004 |
| Wildcards wirken auf geerbte wie eigene Permissions | FR-005 |
| Spieler-Union über alle direkten Rollen + transitive Hülle + Direkt-Grants | FR-006/FR-007 |
| Leerer Graph ⇒ bit-identisch zu heute | FR-008/SC-002 |
| Zyklus beim Setzen ⇒ 409 | FR-010 |
| Restzyklus ⇒ Auflösung terminiert (Visited-Set/UNION) | FR-010a |
| Default fließt nur bei expliziter Vererbung ein; Fallback exklusiv | FR-011 |
| Keine Default-Falle-Hilfe | FR-012 |
| Default ist Blatt (erbt nichts) | FR-013 |
| Idempotente Kante | FR-014 |
| Löschen geerbter Rolle ⇒ 409 | FR-015 |
| active der Parent-Rolle irrelevant für Vererbung | FR-016 |
| Audit je Kanten-Änderung | FR-017 |
| Gates: read=permission.read, write=permission.role.edit.inherit | FR-018/FR-019 |
| Live-Push an Reverse-Closure-Holder; auch role-permission-Edits | FR-020/FR-020a |
| Bestehender Pub/Sub-Pfad, kein neues Signal | FR-021 |
| Provenienz je Permission (alle Quellen + own) | FR-022/FR-022a |
