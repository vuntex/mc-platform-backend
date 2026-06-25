# Contract: `/api/web/permission/**` (Web → Backend)

**Feature**: 005-rank-management-api | **Date**: 2026-06-25

Alle Endpunkte liegen hinter dem JWT-Filter (`SecurityConfig`-Wildcard `/api/web/**`). **Auth**: `Authorization: Bearer <access-jwt>` (Feature 004). Fehlt/ungültig → **401** (vor jeder Rechteprüfung). Der **Akteur** = `PlayerId` aus dem Token-Principal; **kein `actor` im Body** (FR-002/FR-020). Rechteprüfung backend-autoritativ über `PermissionResolver`.

Consumer ist das **Next.js/TypeScript-Frontend** (kein Java-Plugin) → JSON-Contract hier, **keine** `EndpointDescriptor`-Konstanten (research R9).

## Rollen

| Methode | Pfad | Gate (`permission.*`) | Body | Erfolg |
| --- | --- | --- | --- | --- |
| GET | `/api/web/permission/roles` | `permission.read` | – | 200 `RoleResponse[]` |
| GET | `/api/web/permission/roles/{id}` | `permission.read` | – | 200 `RoleResponse` |
| POST | `/api/web/permission/roles` | `permission.role.create` | `RoleWriteRequest` | 200 `RoleResponse` |
| PUT | `/api/web/permission/roles/{id}` | `permission.role.edit` | `RoleWriteRequest` | 200 `RoleResponse` |
| DELETE | `/api/web/permission/roles/{id}` | `permission.role.delete` | – | 204 (kaskadiert) |

## Rollen-Permissions

| Methode | Pfad | Gate | Body | Erfolg |
| --- | --- | --- | --- | --- |
| GET | `/api/web/permission/roles/{id}/permissions` | `permission.read` | – | 200 `RoleResponse` (enthält permissions) |
| POST | `/api/web/permission/roles/{id}/permissions` | `permission.role.edit` | `RolePermissionWriteRequest` | 200 `RoleResponse` |
| DELETE | `/api/web/permission/roles/{id}/permissions` | `permission.role.edit` | `RolePermissionWriteRequest` | 200 `RoleResponse` |

## Grants (Spieler)

| Methode | Pfad | Gate | Body | Erfolg |
| --- | --- | --- | --- | --- |
| GET | `/api/web/permission/players/{uuid}/effective` | `permission.read` | – | 200 `PlayerPermissionsResponse` |
| POST | `/api/web/permission/players/{uuid}/roles` | `permission.grant.role` | `GrantRoleWriteRequest` | 200 `PlayerPermissionsResponse` |
| DELETE | `/api/web/permission/players/{uuid}/roles/{roleId}` | `permission.grant.role` | – | 200 `PlayerPermissionsResponse` |
| POST | `/api/web/permission/players/{uuid}/permissions` | `permission.grant.permission` | `GrantPermissionWriteRequest` | 200 `PlayerPermissionsResponse` |
| DELETE | `/api/web/permission/players/{uuid}/permissions` | `permission.grant.permission` | `RevokePermissionWriteRequest` | 200 `PlayerPermissionsResponse` |

Schreibende Grant-/Rollen-Operationen lösen **nach Commit** einen player-scoped Live-Push aus (`mc:permission:changed`, betroffene UUID(s)).

## Request-DTOs (neu, `plugin-protocol`, JDK-only, OHNE actor)

```java
record RoleWriteRequest(String name, String displayName, String color, String prefix, String suffix,
        String tabListColor, String tabListIcon, String displayIcon, int weight,
        boolean teamRank, boolean active) {}          // isDefault NIE über die API

record RolePermissionWriteRequest(String permission) {}

record GrantRoleWriteRequest(long roleId, Long expiresInSeconds, String reason) {}   // null = permanent

record GrantPermissionWriteRequest(String permission, Long expiresInSeconds, String reason) {}

record RevokePermissionWriteRequest(String permission, String reason) {}
```

Response-DTOs wiederverwendet: `RoleResponse`, `PlayerPermissionsResponse`.

## Fehlercodes (über bestehende Handler, kein neuer Handler)

| Code | Bedeutung |
| --- | --- |
| 401 | kein/ungültiges JWT |
| 403 | fehlt das zuständige `permission.*` / `permission.read` |
| 404 | unbekannte Rolle (`role_not_found`) |
| 409 | Default-Rang gelöscht/deaktiviert (`default_role_protected`) · Name-Kollision (`role_name_conflict`) |
| 422 | ungültige Permission-Syntax / `expires_at` in Vergangenheit (`permission_invalid`) |
| 400 | sonstige ungültige Eingabe |
| 200, leere Liste | Spieler ohne Grants (kein 404) |

## Beispiel-Fluss (issued_by nicht fälschbar)

```
POST /api/web/permission/players/<uuid>/roles
Authorization: Bearer <jwt von Admin A>
{ "roleId": 2, "expiresInSeconds": 604800, "reason": "Probezeit" }

→ Controller: actor = PlayerId(A) aus Token-Principal   (Body hat KEIN actor-Feld)
→ admin.grantRole(player=<uuid>, role=2, expiry=now+7d, reason, actor=A)
     → requirePermission(A, "permission.grant.role")   // 403 falls fehlt
     → upsertRoleGrant(... issued_by=A ...)              // issued_by = A, garantiert
     → grantAudit.record(GRANT, <uuid>, role=2, actor=A, reason, now)
     → publisher.publish(<uuid>, GRANT_ADDED)            // best-effort, nach Commit
→ 200 PlayerPermissionsResponse (neuer Ist-Zustand)
```
