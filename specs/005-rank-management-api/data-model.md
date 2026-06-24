# Data Model: Rank-Management-Backend

**Feature**: 005-rank-management-api | **Date**: 2026-06-25

Dieser Slice ist überwiegend Reuse. **Genuin neu ist nur `role_audit`.** Alle anderen Entitäten existieren aus Feature 002 und werden unverändert verwendet.

## Neu: `role_audit` (append-only, Migration V13)

Append-only Audit der Rollen-Konfiguration — analog `config_audit` (Server-Config) und `grant_audit` (Spieler-Grants), aber für Rollen-Stammdaten- und Rollen-Permission-Änderungen (FR-025a). **Kein** FK auf `role`, damit der Trail eine gelöschte Rolle überlebt (gleiche Begründung wie bei `grant_audit`).

```sql
-- V13__role_audit.sql  (nächste freie Version; V11/V12 sind web_auth/refresh_token)
CREATE TABLE role_audit (
    id          BIGSERIAL    PRIMARY KEY,
    action      VARCHAR(32)  NOT NULL,   -- ROLE_CREATE|ROLE_UPDATE|ROLE_DELETE|ROLE_PERMISSION_ADD|ROLE_PERMISSION_REMOVE
    role_id     BIGINT       NOT NULL,   -- kein FK (überlebt Löschung)
    role_name   VARCHAR(32),             -- Name-Snapshot (v.a. nützlich bei ROLE_DELETE)
    permission  VARCHAR(128),            -- nur bei ROLE_PERMISSION_ADD/REMOVE gesetzt
    actor       UUID         NOT NULL,   -- ausführender Admin (= JWT-UUID); NIE aus dem Body
    at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_role_audit_role ON role_audit (role_id, at);
```

**Validierungsregeln:**
- `action` ∈ definierter Menge (Domänen-Enum in `RoleAuditPort.Action`).
- `permission` ist genau bei `ROLE_PERMISSION_ADD/REMOVE` gesetzt, sonst null.
- `actor` ist Pflicht und stammt ausschließlich aus dem verifizierten Token.
- Append-only: nur INSERT, nie UPDATE/DELETE durch die Anwendung.

**Port (application):**
```java
public interface RoleAuditPort {
    enum Action { ROLE_CREATE, ROLE_UPDATE, ROLE_DELETE, ROLE_PERMISSION_ADD, ROLE_PERMISSION_REMOVE }
    void record(Action action, RoleId role, String roleName, String permission, UUID actor, Instant at);
}
```

## Wiederverwendet (aus Feature 002, unverändert)

### `role` — Rollen-Stammdaten (state-stored)
`id` (PK), `name` (eindeutig, case-insensitiv geprüft), `display_name`, `color`, `prefix`, `suffix`, `tab_list_color`, `tab_list_icon`, `display_icon` (nullable, `<type>:<payload>`), `weight` (INT), `team_rank` (BOOL), `active` (BOOL), `is_default` (BOOL, geschützt). Seed: DEFAULT/MODERATOR/ADMIN.

### `role_permission` — Rollen→Permission-Konfiguration
`(role_id → role, permission)`. ADMIN-Seed: `*`. Idempotenter Insert (`ON CONFLICT DO NOTHING`).

### `player_role_grant` — Spieler→Rolle-Grant (state-stored, soft-active)
`uuid` (UUID, **kein FK auf player** → Grant an nie-gejointe Spieler erlaubt, FR-023), `role_id → role ON DELETE CASCADE`, `issued_by` (UUID, NOT NULL), `issued_at`, `expires_at` (nullable = permanent), `reason` (nullable), `active`. PK `(uuid, role_id)`. Upsert: max. 1 aktiver Grant je Paar; permanent verdrängt befristet.

### `player_permission_grant` — Spieler→Permission-Grant (direkt)
`uuid` (kein FK auf player), `permission`, `issued_by`, `issued_at`, `expires_at`, `reason`, `active`. PK `(uuid, permission)`.

### `grant_audit` — Spieler-Grant-Audit (append-only)
GRANT/REVOKE/EXPIRE je Spieler-Grant (role oder permission). Bleibt für Grant-Operationen zuständig; `role_audit` ergänzt den Rollen-Konfigurations-Strang.

## Entitäten-Beziehungen

```
role (1) ──< role_permission (N)
role (1) ──< player_role_grant (N)        [uuid lose, kein player-FK]
            player_permission_grant (N)    [uuid lose, kein player-FK]
role-/grant-Änderungen ──> role_audit / grant_audit   (append-only, kein FK)
PermissionChangedEvent (player-scoped) ──> mc:permission:changed   (Live-Push)
```

## Zustandsübergänge (Grants)

`(neu) → aktiv → [revoke|expire] → inaktiv`. Revoke ist idempotent (no-op wenn nicht aktiv). Expiry erledigt der bestehende `GrantExpiryService`-Sweep (nicht Teil dieses Slices).
