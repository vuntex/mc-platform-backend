# Phase 1 — Contracts: Permission-/Rank-System

Zwei Vertragsflächen: (1) REST (`api-rest` ↔ Web/Plugin), abgebildet als `EndpointDescriptor`-
Konstanten in `plugin-protocol`; (2) ein Redis-Pub/Sub-Event (`MessageCodec`). Alle DTOs sind
**JDK-only** (keine JSON-Annotationen im Contract — JSON-Mapping lebt in Backend/Plugin).

## REST — `PermissionEndpoints` (neu, in `plugin-protocol/permission`)

Backend-autoritativ: jeder Schreibpfad prüft im Service eine Permission **vor** dem Schreiben
(`PermissionDeniedException` → 403, global gemappt). Gating-Permissions (einheitliche Welt):

| Aktion | erforderliche Permission |
| --- | --- |
| Rolle anlegen | `permission.role.create` |
| Rolle bearbeiten / Permissions konfigurieren | `permission.role.edit` |
| Rolle löschen | `permission.role.delete` |
| Rang-Grant vergeben/entziehen | `permission.grant.role` |
| Permission-Grant vergeben/entziehen | `permission.grant.permission` |
| Rollen/Grants lesen | `permission.read` |

| Konstante | Methode / Pfad | Request → Response |
| --- | --- | --- |
| `LIST_ROLES` | `GET /api/permission/roles` | `Void` → `RoleResponse[]` |
| `CREATE_ROLE` | `POST /api/permission/roles` | `RoleRequest` → `RoleResponse` |
| `UPDATE_ROLE` | `PUT /api/permission/roles/{id}` | `RoleRequest` → `RoleResponse` |
| `DELETE_ROLE` | `DELETE /api/permission/roles/{id}` | `Void` → `Void` (kaskadiert REVOKE, FR-012a) |
| `SET_ROLE_PERMISSIONS` | `PUT /api/permission/roles/{id}/permissions` | `RolePermissionsRequest` → `RoleResponse` |
| `GRANT_ROLE` | `POST /api/permission/players/{uuid}/roles` | `GrantRoleRequest` → `PlayerPermissionsResponse` |
| `REVOKE_ROLE` | `DELETE /api/permission/players/{uuid}/roles/{roleId}` | `Void` → `PlayerPermissionsResponse` |
| `GRANT_PERMISSION` | `POST /api/permission/players/{uuid}/permissions` | `GrantPermissionRequest` → `PlayerPermissionsResponse` |
| `REVOKE_PERMISSION` | `DELETE /api/permission/players/{uuid}/permissions` | `RevokePermissionRequest` → `PlayerPermissionsResponse` |
| `EFFECTIVE` | `GET /api/permission/players/{uuid}/effective` | `Void` → `PlayerPermissionsResponse` |

Alle mutierenden Requests tragen `actor` (UUID des handelnden Team-Mitglieds) — Quelle des
`issued_by`/Audit-Akteurs (Übergang vom späteren Auth-Feature; bis dahin client-geliefert wie heute
`?staff=`/`issuedBy` bei Reports/Punishments).

### DTO-Skizzen (records, JDK-only)
- `RoleRequest(String name, String displayName, String color, String prefix, String suffix,
  String tabListColor, String tabListIcon, int weight, boolean teamRank, UUID actor)`
- `RolePermissionsRequest(List<String> permissions, UUID actor)`
- `RoleResponse(long id, String name, String displayName, String color, String prefix, String suffix,
  String tabListColor, String tabListIcon, int weight, boolean teamRank, boolean active,
  boolean isDefault, List<String> permissions)`
- `GrantRoleRequest(long roleId, Long expiresInSeconds /*null=permanent*/, String reason, UUID actor)`
- `GrantPermissionRequest(String permission, Long expiresInSeconds, String reason, UUID actor)`
- `RevokePermissionRequest(String permission, UUID actor)`
- `PlayerPermissionsResponse(UUID player, List<ActiveGrant> roles, List<ActiveGrant> permissions,
  List<String> effectivePermissions, RoleDisplay display)`
  - `ActiveGrant(String label /*roleName|permission*/, Long expiresAtEpochMilli, UUID issuedBy,
    String reason)`
  - `RoleDisplay(String displayName, String color, String prefix, String suffix, String tabListColor,
    String tabListIcon)` — gewählt per Tie-Break FR-019.

## Fehler-Codes — `PermissionExceptionHandler` (nur eigene Exceptions)

403 `PermissionDeniedException` und 400 `IllegalArgumentException` sind **bereits global** gemappt
(Economy/Punishment-Advices) → hier NICHT erneut deklarieren (sonst ambiguous mapping, wie im
`ReportExceptionHandler`-Kommentar).

| Exception | Status | `error` |
| --- | --- | --- |
| `RoleNotFoundException` | 404 | `role_not_found` |
| `RoleNameConflictException` | 409 | `role_name_conflict` |
| `DefaultRoleProtectedException` | 409 | `default_role_protected` |
| `RoleValidationException` / `InvalidGrantException` | 422 | `permission_invalid` |

## Pub/Sub — `mc:permission:changed` (neu)

- Channel: `PermissionChannels.CHANGED = Channels.of("permission", "changed")` → `mc:permission:changed`.
- Event: `PermissionChangedEvent(UUID playerUuid, String changeType, long timestampEpochMilli)`.
- Codec: `PermissionChangedEventCodec` (`MESSAGE_TYPE = "permission.changed"`), pipe-delimited,
  3 Teile, String URL-encoded — exakt nach `ReportChangedEventCodec`.
- Registrierung: **eine Zeile** in `PlatformProtocol.create()` → `PermissionChangedEventCodec.INSTANCE`
  (die laut Kommentar vorgesehene Stelle; einzige Änderung an geteiltem Code).
- `changeType` ∈ `GRANT_ADDED | GRANT_REVOKED | GRANT_EXPIRED | ROLE_CONFIG_CHANGED`. Quelle ist das
  **Domänen-Enum** `PermissionChangeType` (core-domain); der `app`-Publisher mappt `name()` auf diesen
  Wire-String. Die `application`-Schicht bleibt dadurch `plugin-protocol`-frei (wie bei `ReportChange`).
  Bei `ROLE_CONFIG_CHANGED` publiziert das Backend ein Event je aktivem Halter der Rolle (R7).

## Invarianter Vertrag (unverändert!)

`PermissionResolver.hasPermission(UUID staffUuid, String permission)` — Signatur **byte-identisch**.
Konsumenten `PunishmentService`/`ReportService` bleiben unangetastet (SC-001).
