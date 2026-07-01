# Phase 1 — Data Model: Autoritäts-Grenzen

Keine Persistenz-Änderung. „Modell" = die Autoritäts-Konzepte + die neuen Typen/Prädikate und ihre
Einhängung. Alles baut auf bestehenden Daten (`role.weight`, aktive Rollen-Grants) auf.

## 1. Pure Domain — `RoleAuthority` (core-domain, framework-frei)

Reine, unit-testbare Vergleichslogik (kein Datenzugriff):

```java
public final class RoleAuthority {
    // ceiling: non-top strikt <, Top-Tier ≤
    static boolean canManageWeight(int targetWeight, int authorityWeight, boolean topTier);
    static boolean canManageTarget(int targetAuthority, int authorityWeight, boolean topTier);
    // neue/angehobene Rolle nie über der eigenen Autorität (auch Top-Tier nicht)
    static boolean weightWithinCeiling(int newWeight, int authorityWeight);
    // Wildcard-Erkennung fürs Delegations-Subset
    static boolean isWildcard(String permission); // "*" oder endet ".*"
}
```

- `canManageWeight(t, a, top)` = `top ? t <= a : t < a`.
- `canManageTarget(t, a, top)` = analog (Ziel-Autorität statt Rollen-Weight).
- `weightWithinCeiling(n, a)` = `n <= a`.

## 2. Application — `PermissionAuthorityService`

Orchestriert Datenzugriff + `RoleAuthority`; Abhängigkeiten: `RoleRepository`, `PlayerGrantRepository`,
`RoleInheritanceRepository`, `PermissionResolver`, `Clock`.

| Methode | Semantik |
|---------|----------|
| `int authorityWeight(UUID actor)` | max `weight` über reachable Rollen (aktive Grants + transitive Vererbung via `RoleHierarchy.reachable`); 0 wenn keine (Default). |
| `int topWeight()` | max `weight` über alle aktiven Rollen. |
| `boolean isTopTier(UUID actor)` | `authorityWeight(actor) == topWeight()`. |
| `void requireCanManageRole(UUID actor, Role role)` | sonst `InsufficientAuthorityException`. |
| `void requireWeightWithinCeiling(UUID actor, int newWeight)` | Anlegen/Anheben-Deckel. |
| `void requireCanManageTarget(UUID actor, PlayerId target)` | Ziel-Autoritäts-Ceiling. |
| `void requireCanDelegate(UUID actor, String permission)` | Subset/Wildcard (D3). |
| `void requireNotLastTopTier(<simulierte Operation>)` | sonst `LastTopTierException` (409). |
| `List<Role> visibleRoles(UUID actor)` | alle Rollen, gefiltert per `canManageWeight`. |
| `boolean canViewRole(UUID actor, Role role)` | Einzel-Rollen-Read-Gate (FR-009a): `canManageWeight(role.weight, …)`. |
| `boolean canViewTarget(UUID actor, PlayerId target)` | für den `effective()`-Gate (403 wenn false). |

**Lockout-Berechnung (`requireNotLastTopTier`):** `top = topWeight()`; `topRoles` = Rollen mit
`weight == top`; `holders` = Union `activeHoldersOf(r, now)`; simuliere die Operation (entferne
Holder / entferne Rolle / senke Weight) und prüfe, ob `holders` danach leer wäre → dann 409.

## 3. Guards je `PermissionAdminService`-Methode (nach `requirePermission`)

| Methode | eingefügte Guards |
|---------|-------------------|
| `createRole(draft, actor)` | `requireWeightWithinCeiling(actor, draft.weight)` + (non-top: strikt < via `canManageWeight`) |
| `updateRole(role, actor)` | `requireCanManageRole(actor, bestehende Rolle)` **und** `requireWeightWithinCeiling(actor, neuer weight)`; falls Top-Rolle-Weight-Absenkung → `requireNotLastTopTier` |
| `deleteRole(id, actor)` | `requireCanManageRole` + `requireNotLastTopTier` (falls Top-Rolle) |
| `addRolePermission(id, perm, actor)` | `requireCanManageRole` + `requireCanDelegate(actor, perm)` |
| `removeRolePermission(id, perm, actor)` | `requireCanManageRole` |
| `addInheritance(child, parent, actor)` | `requireCanManageRole(child)` + `requireCanManageRole(parent)` (Eltern nicht über Ceiling) |
| `removeInheritance(child, parent, actor)` | `requireCanManageRole(child)` |
| `grantRole(player, roleId, …, actor)` | `requireCanManageRole(role)` + `requireCanManageTarget(actor, player)` |
| `revokeRole(player, roleId, …, actor)` | `requireCanManageRole(role)` + `requireCanManageTarget(actor, player)` + `requireNotLastTopTier` (falls Top-Rolle) |
| `grantPermission(player, perm, …, actor)` | `requireCanManageTarget(actor, player)` + `requireCanDelegate(actor, perm)` |
| `revokePermission(player, perm, …, actor)` | `requireCanManageTarget(actor, player)` |

Bestehende Schutzmechanismen (Default-Rolle, Zyklus, Inheritance-Dependency) bleiben unverändert und
laufen zusätzlich.

## 4. Read-Gate/Filter (`WebPermissionController`)

| Read | Änderung |
|------|----------|
| `GET /api/web/permission/roles` (+ Picker-Reads) | Ergebnis = `visibleRoles(actor)` (weight-gefiltert). |
| `GET /api/web/permission/roles/{id}` (+ `/permissions`, `/inheritance`) | 403, wenn `!canViewRole(actor, role)` (FR-009a). |
| `GET /api/web/permission/players/{uuid}/effective` | 403, wenn `!canViewTarget(actor, uuid)`. |
| `GET /api/web/permission/catalog` (008) | unverändert (informativ, nicht gefiltert). |
| `/api/web/players/**` (Suche/Stammdaten), `/api/web/me` | unverändert/ungefiltert (FR-010a/FR-011). |

## 5. Neue Typen / Fehler

- `InsufficientAuthorityException` (application.permission) → **403** `authority_ceiling`.
- `LastTopTierException` (application.permission) → **409** `last_top_tier`.
- Mapping in `PermissionExceptionHandler` (bestehend, `@RestControllerAdvice`).

## 6. Keine Änderungen

- **Kein** Flyway/Schema (weight, player_role_grant existieren).
- **Kein** `plugin-protocol` (keine neuen DTOs/Endpoints; gefilterte Liste nutzt `RoleResponse`).
- `PermissionQueryService`, `PermissionResolver`, `RoleHierarchy` bleiben unverändert (nur konsumiert).
