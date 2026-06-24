# Phase 1 — Data Model: Permission-/Rank-System

State-stored (Constitution §6). Flyway-Migration **V9** (nächste freie Nummer nach `V8`). jOOQ-Klassen
werden vom jooq-docker-Plugin automatisch aus dieser Migration generiert (kein Build-Eingriff).

## Domänen-Entitäten (core-domain, framework-frei)

### `Role`
Flache Rolle. Felder: `RoleId id`, `String name`, **Darstellung**: `displayName`, `color`, `prefix`,
`suffix`, `tabListColor`, `tabListIcon`; `int weight`, `boolean teamRank`, `boolean active`,
`boolean isDefault`. Keine Vererbung, kein Serverbezug. Methoden: reine Validierung (Name nicht leer).

### `RoleId`
Value Object über `long` (DB-`BIGSERIAL`). `of(long)`.

### `RoleGrant` (Player→Role)
`PlayerId player`, `RoleId role`, `Instant issuedAt`, `UUID issuedBy`, `Instant expiresAt` (nullable),
`String reason` (nullable), `boolean active`. Methode `boolean isActive(Instant now)` =
`active && (expiresAt == null || now.isBefore(expiresAt))`. **Reiner Kern**, gegen den der SQL-Filter
getestet wird.

### `PermissionGrant` (Player→Permission)
Wie `RoleGrant`, aber `String permission` statt `RoleId`. Gleiches `isActive(now)`.

### `PermissionMatcher` (reine Funktion)
`static boolean matches(Collection<String> granted, String query)` — Union-Semantik + Wildcards:
`*` matcht alles; `feature.*` matcht jedes `query`, das mit `feature.` beginnt; sonst exakter
Vergleich. Keine Negation. **Der testbare Kern (R6).**

### `EffectivePermissions` (reine Funktion / Wertobjekt)
Bildet aus aktiven `RoleGrant`s **auf aktive Rollen** (`role.active = true`, FR-007a; → deren
`role_permission`-Strings), der Default-Rolle (falls keine aktive Rang-Mitgliedschaft auf eine aktive
Rolle) und aktiven `PermissionGrant`s die **Union** der Permission-Strings. `boolean allows(String
query)` delegiert an `PermissionMatcher`. Eine deaktivierte Rolle trägt nichts bei, ihr Grant bleibt
aber bestehen.

### `RankDisplay` (reine Funktion)
`static Optional<Role> choose(Collection<Role> activeRoles)` — Eingabe sind nur **aktive** Rollen
(`role.active = true`, FR-007a), die der Spieler über aktive Grants hält. Tie-Break **`teamRank` desc →
`weight` desc → `RoleId` asc** (FR-019). Liefert die Default-Rolle, wenn keine solche Rolle vorliegt.

### `PermissionChangeType` (Domänen-Enum)
`GRANT_ADDED | GRANT_REVOKED | GRANT_EXPIRED | ROLE_CONFIG_CHANGED`. **Domänentyp** (kein
`plugin-protocol`-Import in der `application`-Schicht, analog `ReportChange`); der `app`-Publisher
mappt `name()` auf den Wire-String von `PermissionChangedEvent.changeType`.

### Domänen-Exceptions
`RoleValidationException`, `InvalidGrantException` (z. B. `expires_at` in der Vergangenheit).

---

## Tabellen (V9)

### `role` — Rollen-Stammdaten (löst `team_role_*` ab)
| Spalte | Typ | Notiz |
| --- | --- | --- |
| `id` | `BIGSERIAL PK` | RoleId |
| `name` | `VARCHAR(32) NOT NULL` | `UNIQUE` (case-insensitive via `CREATE UNIQUE INDEX … (lower(name))`) |
| `display_name` | `VARCHAR(64) NOT NULL` | Adventure-tauglich |
| `color` | `VARCHAR(32)` | |
| `prefix` | `VARCHAR(100)` | |
| `suffix` | `VARCHAR(100)` | |
| `tab_list_color` | `VARCHAR(32)` | |
| `tab_list_icon` | `VARCHAR(50)` | |
| `weight` | `INT NOT NULL DEFAULT 0` | nur Darstellung |
| `team_rank` | `BOOLEAN NOT NULL DEFAULT false` | nur Darstellung |
| `active` | `BOOLEAN NOT NULL DEFAULT true` | |
| `is_default` | `BOOLEAN NOT NULL DEFAULT false` | genau eine `true` |
| `created_by` | `UUID` | Akteur (nullable für Seeds/System) |
| `created_at` | `TIMESTAMPTZ NOT NULL DEFAULT now()` | |

Constraints: `CREATE UNIQUE INDEX uq_role_name_ci ON role (lower(name))`; partielles Unique
`CREATE UNIQUE INDEX uq_role_single_default ON role (is_default) WHERE is_default` (max. eine
Default-Rolle). Die Default-Rolle wird nicht gelöscht/deaktiviert (im Service erzwungen, FR-012).

### `role_permission` — Rollen-Permission-Konfiguration (Stammdaten, kein Ablauf)
| Spalte | Typ | Notiz |
| --- | --- | --- |
| `role_id` | `BIGINT NOT NULL REFERENCES role(id) ON DELETE CASCADE` | |
| `permission` | `VARCHAR(128) NOT NULL` | Wildcards erlaubt |
| `added_by` | `UUID` | Akteur |
| `added_at` | `TIMESTAMPTZ NOT NULL DEFAULT now()` | |
| | | `PRIMARY KEY (role_id, permission)` |
Index: `idx_role_permission_role ON role_permission(role_id)`.

### `player_role_grant` — Rang-Grants (Spieler→Rolle), genau eine Zeile je Paar
| Spalte | Typ | Notiz |
| --- | --- | --- |
| `uuid` | `UUID NOT NULL` | Spieler (Snapshot-Identität; **keine** FK auf `player`, da Grant auch offline/vorab möglich) |
| `role_id` | `BIGINT NOT NULL REFERENCES role(id)` | |
| `issued_by` | `UUID NOT NULL` | Akteur-UUID |
| `issued_at` | `TIMESTAMPTZ NOT NULL DEFAULT now()` | |
| `expires_at` | `TIMESTAMPTZ` | `null` = permanent |
| `reason` | `VARCHAR(256)` | nullable (R10) |
| `active` | `BOOLEAN NOT NULL DEFAULT true` | soft-Status (R5) |
| | | `PRIMARY KEY (uuid, role_id)` |
Indizes: `idx_prg_role ON player_role_grant(role_id) WHERE active` (Halter einer Rolle, R7-Fanout +
Lösch-Kaskade FR-012a); `idx_prg_expiry ON player_role_grant(expires_at) WHERE active AND expires_at
IS NOT NULL` (Sweep R4).

### `player_permission_grant` — direkte Permission-Grants
| Spalte | Typ | Notiz |
| --- | --- | --- |
| `uuid` | `UUID NOT NULL` | Spieler |
| `permission` | `VARCHAR(128) NOT NULL` | Wildcards erlaubt |
| `issued_by` | `UUID NOT NULL` | |
| `issued_at` | `TIMESTAMPTZ NOT NULL DEFAULT now()` | |
| `expires_at` | `TIMESTAMPTZ` | `null` = permanent |
| `reason` | `VARCHAR(256)` | nullable |
| `active` | `BOOLEAN NOT NULL DEFAULT true` | |
| | | `PRIMARY KEY (uuid, permission)` |
Index: `idx_ppg_expiry ON player_permission_grant(expires_at) WHERE active AND expires_at IS NOT NULL`.

### `grant_audit` — append-only Lebenszyklus-Historie (analog `config_audit`)
| Spalte | Typ | Notiz |
| --- | --- | --- |
| `id` | `BIGSERIAL PK` | |
| `action` | `VARCHAR(16) NOT NULL` | `GRANT \| REVOKE \| EXPIRE` |
| `grant_type` | `VARCHAR(16) NOT NULL` | `ROLE \| PERMISSION` |
| `player_uuid` | `UUID NOT NULL` | betroffener Spieler |
| `role_id` | `BIGINT` | gesetzt bei `grant_type=ROLE` |
| `permission` | `VARCHAR(128)` | gesetzt bei `grant_type=PERMISSION` |
| `actor_uuid` | `UUID NOT NULL` | handelnder Akteur (Sentinel-UUID bei EXPIRE, R8) |
| `reason` | `VARCHAR(256)` | nullable |
| `at` | `TIMESTAMPTZ NOT NULL DEFAULT now()` | |
Index: `idx_grant_audit_player ON grant_audit(player_uuid, at)`.

---

## Daten-Migration in V9 (Erhalt der geseedeten Permissions, R2)

1. Tabellen `role`, `role_permission`, `player_role_grant`, `player_permission_grant`, `grant_audit`
   anlegen.
2. Rollen seeden: `DEFAULT` (`is_default=true`, leeres Permission-Set — FR-012),
   `MODERATOR` (`team_rank=true`), `ADMIN` (`team_rank=true`, höchstes `weight`).
3. `role_permission` aus den bisherigen `team_role_permission`-Seeds befüllen: ADMIN→`*`,
   MODERATOR→(`punishment.spam`, `punishment.warn`, `punishment.revoke`, `punishment.issue.warn`,
   `punishment.issue.chatban`, `report.view`, `report.handle`). Idempotent (`ON CONFLICT DO NOTHING`).
4. `DROP TABLE team_role_member; DROP TABLE team_role_permission;` (member ist leer → kein Verlust).

> Folge-Migration optional: Permission-Stammdaten als eigene Tabelle gibt es bewusst **nicht** —
> Permissions sind frei definierte Strings (einheitliche Welt), die Quelle der Wahrheit ist ihr
> Vorkommen in `role_permission` / `player_permission_grant`.

---

## Zustandsübergänge

**Rang-Grant**: `(none) --grant--> active --revoke/expire--> inactive --grant--> active` (Upsert auf
derselben Zeile, R5). **Rolle**: `active <-> deactivated`, `--delete--> (kaskadiert REVOKE aller
aktiven Grants, FR-012a)`. Default-Rolle: keine Übergänge nach `deactivated`/`deleted`.
