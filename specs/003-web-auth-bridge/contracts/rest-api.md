# Contract — REST API: Web-Auth-Bridge

Zwei Zielgruppen: **Plugin** (Token-Erzeugung, kennt die UUID aus der Session) und **Webinterface**
(Token-Einlösung, kennt nur den Token). Alle Pfade neu unter `web-auth`; dünner `WebAuthController`,
getrennt von Economy/Punishment/Report.

## Endpunkte

### 1. Link-Token anfordern (Plugin)
`POST /api/players/{uuid}/web-auth/link-token`
- **Body**: keiner.
- **Vorbedingung**: für `{uuid}` existiert **kein** `web_account`.
- **200** → `TokenResponse { token, purpose:"LINK", expiresAtEpochMilli }`.
- **409** `web_account_exists` — Account existiert bereits (Hinweis: `reset-token` nutzen).
- **429** `web_auth_cooldown` — Cooldown aktiv.

### 2. Reset-Token anfordern (Plugin)
`POST /api/players/{uuid}/web-auth/reset-token`
- **Body**: keiner.
- **Vorbedingung**: für `{uuid}` existiert **ein** `web_account`.
- **200** → `TokenResponse { token, purpose:"RESET", expiresAtEpochMilli }`.
- **409** `web_account_missing` — kein Account (Hinweis: `link-token` nutzen).
- **429** `web_auth_cooldown` — Cooldown aktiv.

### 3. Token einlösen (Webinterface)
`POST /api/web-auth/redeem`
- **Body**: `RedeemRequest { token, password }`.
- **204** — Passwort gesetzt, Token verbraucht (LINK → Account angelegt, RESET → Passwort ersetzt).
- **410** `web_auth_token_invalid` — Token unbekannt **oder** abgelaufen **oder** bereits verbraucht
  (**uniform**, leakt keine Existenz; FR-019 / SC-005).
- **422** `password_invalid` — Passwort verletzt 8..64 Zeichen.
- **409** `web_account_conflict` — Race: LINK, aber Account inzwischen vorhanden / RESET, aber Account
  inzwischen weg.

## Status-Code-Mapping (`WebAuthExceptionHandler`)

| Exception (application) | HTTP | Code |
|---|---|---|
| `WebAccountExistsException` | 409 | `web_account_exists` |
| `WebAccountMissingException` | 409 | `web_account_missing` |
| `WebAccountConflictException` | 409 | `web_account_conflict` |
| `TokenInvalidException` | 410 | `web_auth_token_invalid` |
| `InvalidPasswordException` | 422 | `password_invalid` |
| `WebAuthCooldownException` | 429 | `web_auth_cooldown` |

- **400** (Bad Request) wird **NICHT** re-deklariert — das globale Mapping (Economy-/Punishment-Handler)
  greift. Kein **403/401** in diesem Slice (kein Auth/Permission-Gate, Q1).

## Sicherheits-/Datenschutz-Hinweise
- `password` reist **nur** im Redeem-Request (Klartext über TLS), wird sofort gehasht; **nie** geloggt,
  **nie** in einer Response/Audit gespeichert (FR-020/FR-026).
- `token` ist ≥128-Bit-Zufall, als anklickbarer Link zugestellt (FR-024/FR-025); in der DB nur als
  SHA-256-Hash (R3).
- Antworten enthalten **nie** einen Passwort-Hash und **kein** `email`/`username`-Feld.
