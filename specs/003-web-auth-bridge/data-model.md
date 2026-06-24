# Phase 1 — Data Model: Web-Auth-Bridge

State-stored (kein Event-Store). Drei Tabellen in **Flyway `V11__web_auth_schema.sql`**. Keine
State-Machine — `web_account` ist CRUD, der Token-Lebenszyklus ist „existiert → verbraucht/abgelaufen
→ weg".

## Entitäten (Domäne)

### WebAccount  *(core-domain record, state-stored)*
| Feld | Typ | Regel |
|---|---|---|
| `playerUuid` | `PlayerId` (UUID) | PK; FK → `player(uuid)`; genau ein Account je Identität (FR-001/002) |
| `passwordHash` | `String` | BCrypt-Hash; **nie** Klartext; **nie** in DTO/Log (FR-020) |
| `createdAt` | `Instant` | Anlage-Zeitpunkt |
| `passwordUpdatedAt` | `Instant` | letzte Passwortänderung (= createdAt bei Anlage) |

### LinkToken  *(core-domain record)*
| Feld | Typ | Regel |
|---|---|---|
| `playerUuid` | `PlayerId` (UUID) | FK → `player(uuid)` |
| `purpose` | `TokenPurpose` | `LINK` \| `RESET` |
| `expiresAt` | `Instant` | `now + TTL` (Default 10 Min, FR-011); `isExpired(now)` |
| `createdAt` | `Instant` | für Cooldown-Anchor (FR-022) |
*(Der Rohtoken selbst ist transient — nur bei Erzeugung im Speicher, danach nur als Hash persistiert, R3.)*

### TokenPurpose  *(enum)* — `LINK`, `RESET`.

### WebAuthAuditEntry  *(append-only, config_audit-Stil)*
| Feld | Typ | Regel |
|---|---|---|
| `playerUuid` | UUID | betroffene Identität |
| `eventType` | `ACCOUNT_CREATED` \| `PASSWORD_RESET` | Lebenszyklus-Ereignis (FR-026) |
| `at` | `Instant` | Zeitpunkt |
*(Nie Passwort/Hash, FR-026.)*

## Validierungsregeln (core-domain, framework-frei)

- **PasswordPolicy**: `8 ≤ len(raw) ≤ 64` → sonst `InvalidPasswordException` (422). Keine Zeichenklassen
  (Q4). 64er-Grenze < 72-Byte-BCrypt → kein stilles Abschneiden.
- **LinkToken.isExpired(now)** = `!now.isBefore(expiresAt)`.
- **Vorbedingungen (im Service, gegen `WebAccountRepository.exists`)**: `LINK` nur ohne Account
  (`WebAccountExistsException` 409), `RESET` nur mit Account (`WebAccountMissingException` 409).

## DDL-Skizze (V11)

```sql
-- Web-Account: genau einer je Spieler-Identität (UUID-Anker)
CREATE TABLE web_account (
    player_uuid          UUID PRIMARY KEY REFERENCES player(uuid),
    password_hash        VARCHAR(100) NOT NULL,           -- BCrypt ~60 Zeichen, Reserve
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    password_updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Kurzlebiger, einmal verwendbarer Token; höchstens einer je (uuid, purpose)
CREATE TABLE web_link_token (
    token_hash    VARCHAR(64) PRIMARY KEY,                -- SHA-256 hex des Rohtokens (R3)
    player_uuid   UUID NOT NULL REFERENCES player(uuid),
    purpose       VARCHAR(16) NOT NULL,                   -- LINK | RESET
    expires_at    TIMESTAMPTZ NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_web_link_token_uuid_purpose UNIQUE (player_uuid, purpose)
);
CREATE INDEX idx_web_link_token_expires ON web_link_token (expires_at);   -- Purge-Hygiene

-- Append-only Audit der Account-Lebenszyklus-Ereignisse (config_audit-Stil)
CREATE TABLE web_auth_audit (
    id            BIGSERIAL PRIMARY KEY,
    player_uuid   UUID NOT NULL,
    event_type    VARCHAR(32) NOT NULL,                   -- ACCOUNT_CREATED | PASSWORD_RESET
    at            TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_web_auth_audit_player ON web_auth_audit (player_uuid, at);
```

### Begründung der Constraints
- **`uq_web_link_token_uuid_purpose`**: macht „ein aktiver Token je (uuid, purpose)" zur DB-Invariante;
  `issue` setzt sie per `DELETE WHERE player_uuid=? AND purpose=?` vor dem `INSERT` durch (FR-013).
- **`token_hash` PK**: Lookup beim Redeem über den gehashten präsentierten Token; eindeutig (R3).
- **FK auf `player(uuid)`**: garantiert, dass nur bekannte Identitäten Accounts/Token haben (FR-001).
- **Kein** `version`/OCC nötig: pro Account schreibt nur der Eigentümer-Pfad; Token-Race ist über
  `SELECT … FOR UPDATE` in der Redeem-TX abgedeckt (Single-Server, §14).

## Transaktions-Verhalten

- **`issue` (Token erzeugen)** — 1 TX: `DELETE web_link_token WHERE player_uuid=? AND purpose=?` →
  `INSERT` neue Zeile (`token_hash`, `expires_at = now+TTL`).
- **`redeem`** — 1 TX (Atomarität FR-018): `SELECT … FOR UPDATE WHERE token_hash=? AND expires_at>now`
  → keine Zeile → `TokenInvalidException` (410). Sonst je `purpose`:
  - `LINK`: `INSERT web_account (...) ON CONFLICT (player_uuid) DO NOTHING`; 0 Zeilen → `WebAccountConflictException` (409).
  - `RESET`: `UPDATE web_account SET password_hash=?, password_updated_at=now() WHERE player_uuid=?`; 0 Zeilen → `WebAccountConflictException` (409).
  - dann `DELETE web_link_token WHERE token_hash=?` (single-use, FR-012) + `INSERT web_auth_audit (...)`.
- **`deleteExpired(now)`** (optional Hygiene): `DELETE web_link_token WHERE expires_at <= ?`.
