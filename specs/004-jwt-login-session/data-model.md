# Phase 1 Data Model — JWT-Login-Session

## Domäne (core-domain/webauth)

### RefreshToken (Wertobjekt, framework-frei, JWT-frei)
Repräsentiert ein persistiertes Refresh-Token **als Datum**, nicht den Rohstring.

| Feld | Typ | Bedeutung |
|------|-----|-----------|
| `tokenHash` | `String` | SHA-256-Hex des Rohtokens (Identität der Zeile, nie Klartext) |
| `playerUuid` | `PlayerId` | Besitzer |
| `createdAt` | `Instant` | Ausstellung |
| `expiresAt` | `Instant` | Ablauf |
| `rotatedAt` | `Instant` (nullable) | null = aktiv; gesetzt = bereits rotiert/konsumiert |
| `rotatedFrom` | `String` (nullable) | Vorgänger-`tokenHash` (Lineage-Breadcrumb, **nicht** für Invalidierungs-Scope) |

**Reine Logik (unit-getestet):**
- `isExpired(Instant now)` = `!now.isBefore(expiresAt)`
- `isActive(Instant now)` = `rotatedAt == null && !isExpired(now)`
- `isConsumed()` = `rotatedAt != null`

Das **Access-Token** ist kein Domänen-Objekt — es ist ein Infra-Detail hinter `TokenIssuer`/`TokenVerifier`. Die
Domäne kennt nur „Session-Identität (UUID) + Ablauf".

## Persistenz (infra-persistence, Flyway V12)

### `refresh_token` (state-stored, NEU)
```sql
CREATE TABLE refresh_token (
    token_hash    VARCHAR(64)  PRIMARY KEY,                          -- SHA-256-Hex des Rohtokens
    player_uuid   UUID         NOT NULL REFERENCES player(uuid),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at    TIMESTAMPTZ  NOT NULL,
    rotated_at    TIMESTAMPTZ,                                        -- NULL = aktiv; gesetzt = rotiert/konsumiert
    rotated_from  VARCHAR(64)                                         -- Vorgänger-token_hash (Lineage; kein FK)
);
CREATE INDEX idx_refresh_token_player ON refresh_token (player_uuid);  -- all-player-Invalidierung + Listing
CREATE INDEX idx_refresh_token_expires ON refresh_token (expires_at);  -- Purge-Hygiene
```
- **Kein `version`/OCC**: pro Token schreibt der Refresh-Pfad serialisiert über `SELECT … FOR UPDATE` auf die eine
  Zeile (Muster wie `web_account`-Redeem der Bridge). All-player-Operationen (Replay-Kill, D4-Reset) sind einfache
  `DELETE WHERE player_uuid`.
- **`rotated_from` ohne FK**: bewusst nur Breadcrumb; kein Self-FK, damit der Purge abgelaufener Zeilen ohne
  FK-Reihenfolge-Sorgen löschen kann. Invalidierungs-Scope ist `player_uuid` (Clarification), nicht die Lineage.
- **Kein Klartext, keine PII**: nur Hash + UUID + Zeitstempel.

### Wiederverwendete Tabellen (unverändert, außer dem einen D4-Edit im Adapter)
- `web_account` — Credential-Quelle (`password_hash`). **Adapter** ergänzt eine Lese-Methode `find(PlayerId)`.
- `player` — Name→UUID (`LOWER(name)` + `last_seen DESC`, `idx_player_name_lower`).
- `web_auth_audit` — append-only Audit; neue `event_type`-Werte (`LOGIN`/`TOKEN_ROTATED`/`TOKEN_REUSE_DETECTED`/
  `LOGOUT`), keine Schema-Änderung.

## Zustands-/Ablauf-Maschine

### Login
```
findUuidByName(name) → uuid?            (leer → InvalidCredentials, einheitlich)
web_account.find(uuid) → account?       (leer → InvalidCredentials, einheitlich)   [D3]
matches(pw, account.hash) → bool        (false → InvalidCredentials, einheitlich)
issue access JWT(uuid, 15min)
raw = newToken(); store(sha256(raw), uuid, now, now+30d, rotated_from=null); audit LOGIN
→ TokenPairResponse(access, accessExp, refreshExp) + Set-Cookie(raw)
```

### Refresh (eine TX im Repo: `rotate(presentedHash, newHash, now, newExpiresAt)`)
```
row = SELECT … WHERE token_hash=presentedHash FOR UPDATE
  row == null                     → INVALID            → RefreshTokenInvalidException (einheitlich)
  row.rotated_at != null          → REPLAY  → DELETE WHERE player_uuid=row.player; audit TOKEN_REUSE_DETECTED
                                              → RefreshTokenReuseException (alle Sessions tot)
  row expired                     → INVALID  (optional DELETE der Zeile)  → RefreshTokenInvalidException
  else (aktiv)                    → UPDATE rotated_at=now; INSERT(newHash, player, now, newExpiresAt, presentedHash)
                                    audit TOKEN_ROTATED → ROTATED(player)
```
Application: bei `ROTATED(player)` neues Access-JWT ausstellen, neues Cookie setzen, `TokenPairResponse` zurück.

### Logout (idempotent)
```
DELETE FROM refresh_token WHERE token_hash = sha256(cookieToken)   (0 oder 1 Zeile)
audit LOGOUT (falls vorhanden gewesen)
→ 204, Cookie löschen (Set-Cookie max-age=0)
```

### Passwort-Reset (Bridge-Touchpoint, D4 — additive Zeile in JooqWebAccountRepository.resetPassword, gleiche TX)
```
UPDATE web_account SET password_hash=…, password_updated_at=now WHERE player_uuid=uuid   (bestehend)
DELETE FROM refresh_token WHERE player_uuid = uuid                                        (NEU, additiv)
audit PASSWORD_RESET (bestehend)
```

## Ports (application/webauth)

```java
interface RefreshTokenRepository {
    void store(String tokenHash, PlayerId player, Instant createdAt, Instant expiresAt, String rotatedFromHash);
    RotateResult rotate(String presentedHash, String newHash, Instant now, Instant newExpiresAt); // atomar (eine TX)
    boolean deleteByHash(String tokenHash);          // Logout (idempotent: true wenn gelöscht)
    int deleteAllForPlayer(PlayerId player);         // (intern auch vom rotate-REPLAY genutzt)
    int purgeExpired(Instant now);                   // Hygiene
}
// RotateResult = sealed: Rotated(PlayerId) | Invalid | Replay(PlayerId)

interface TokenIssuer   { String issue(PlayerId subject, Duration ttl, Instant now); }
interface TokenVerifier { PlayerId verify(String accessToken); /* throws AccessTokenInvalidException */ }

// WebAccountRepository (ERWEITERT): + Optional<WebAccount> find(PlayerId player);
// PlayerRepository      (ERWEITERT): + Optional<PlayerId> findUuidByName(String name);
```

## Validierungs-/Invarianten-Regeln
- Access-JWT: Subject = `player_uuid` (UUID-String), `exp` gesetzt, **keine** Rollen/Authority-Claims.
- Refresh-Token-Rohwert: hochentropisch (`SecureRandomTokenGenerator`), nie geloggt, nie at-rest im Klartext.
- Genau eine aktive Zeile pro ausgegebenem Refresh-Token; mehrere aktive Zeilen pro Spieler erlaubt (Geräte).
- Replay ⇒ all-player-Invalidierung (über `player_uuid`).
- Einheitliche Fehlerantworten: Login-Fehler (Name unbekannt / kein Account / falsches Passwort) und
  Refresh-Invalid sind außen ununterscheidbar (keine Enumeration).
