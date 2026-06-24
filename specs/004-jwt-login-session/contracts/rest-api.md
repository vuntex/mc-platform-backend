# REST-Contract — JWT-Login-Session (Web → Backend)

Alle Endpoints sind **web-facing** und (außer der Cookie-Logik) zustandslos. Sie liegen unter `/api/web-auth/…`
und sind in der `SecurityFilterChain` **permitAll** (sie validieren selbst über Credentials bzw. Cookie+DB) — die
JWT-Authentifizierung gilt nur dem künftigen geschützten Web-API-Präfix `/api/web/**`.

## POST /api/web-auth/login
Login mit MC-Name + Passwort.

- **Request** (`LoginRequest`, JSON): `{ "username": "<mc-name>", "password": "<klartext>" }`
- **200 OK** (`TokenPairResponse`, JSON):
  `{ "accessToken": "<jwt>", "accessExpiresAtEpochMilli": 0, "refreshExpiresAtEpochMilli": 0 }`
  - **Set-Cookie:** `mcweb_refresh=<rawRefresh>; HttpOnly; Secure; SameSite=Strict; Path=/api/web-auth/session; Max-Age=<30d>`
  - Der Refresh-Token-Rohwert erscheint **nur** im Cookie, **nie** im Body.
- **401 Unauthorized** (`web_auth_invalid_credentials`): falscher Name **oder** falsches Passwort **oder** kein
  `web_account` — **einheitlich** (keine Enumeration, D3).
- CSRF irrelevant (Credentials im Body, keine ambient Auth).

## POST /api/web-auth/session/refresh
Session verlängern (Rotation). Liest das Refresh-Token aus dem httpOnly-Cookie.

- **Request:** kein Body. **Cookie** `mcweb_refresh` erforderlich. **Pflicht-Header** `X-Refresh: 1` (CSRF-Schutz).
- **200 OK** (`TokenPairResponse`): neues Access-Token + neues `refreshExpiresAtEpochMilli`.
  - **Set-Cookie:** neues `mcweb_refresh=<rawRefresh'>` (rotiert; gleiche Attribute).
- **401 Unauthorized** (`web_auth_refresh_invalid`): Token unbekannt/abgelaufen/fehlt — einheitlich.
- **401 Unauthorized** (`web_auth_session_revoked`): **Replay** eines bereits rotierten Tokens → alle Sessions des
  Spielers wurden invalidiert (Diebstahls-Signal). Cookie wird gelöscht (`Max-Age=0`).
- **403 Forbidden**: fehlender `X-Refresh`-Header (CSRF-Guard).

## POST /api/web-auth/session/logout
Aktuelle Session beenden (Minimal-Variante, D1).

- **Request:** kein Body. **Cookie** `mcweb_refresh` (optional). **Pflicht-Header** `X-Refresh: 1`.
- **204 No Content**: idempotent — auch wenn das Token bereits weg/abgelaufen/unbekannt ist. Cookie wird gelöscht.
- „Alle Geräte abmelden" ist **nicht** Teil dieses Slices (Konto-Management-Slice).

## Geschützte Web-API (Muster für Slice 6 — hier nur etabliert, keine konkreten Endpoints)
- Präfix `/api/web/**` → `authenticated()`. Ohne gültiges `Authorization: Bearer <accessToken>` → **401**.
- Mit gültigem Token: Identität (`PlayerId`) steht im `SecurityContext`; der Controller fragt die konkrete
  Berechtigung über den `PermissionResolver` (→ **403** bei fehlender Permission). **Keine** Spring-Security-
  Rollen/Authorities.

## Fehler-Mapping (Erweiterung des bestehenden `WebAuthExceptionHandler`)
| Exception (application) | HTTP | Code |
|--------------------------|------|------|
| `InvalidCredentialsException` | 401 | `web_auth_invalid_credentials` |
| `RefreshTokenInvalidException` | 401 | `web_auth_refresh_invalid` |
| `RefreshTokenReuseException` | 401 | `web_auth_session_revoked` |
| `AccessTokenInvalidException` (Filter) | 401 | via Security-EntryPoint |
- Bestehende Codes der Bridge (409/410/422/429) unverändert. **400 bewusst nicht re-deklariert** (globales Mapping
  greift) — konsistent zum Stil von Economy/Punishment/Report/Permission/WebAuth.

## Sicherheits-/Transport-Header
- **CORS:** explizite Allowed-Origin (Config `mcplatform.webauth.cors.allowed-origin`), `Allow-Credentials: true`,
  erlaubte Header inkl. `Authorization`, `X-Refresh`.
- **Global CSRF deaktiviert** (stateless Bearer-API); Cookie-Endpoints via `SameSite=Strict` + `X-Refresh`-Header
  geschützt.
- **SC-007:** Bestehende Endpoints (`/api/players/**`, `/api/punishments/**`, `/api/reports/**`,
  `/api/permissions/**`, `/api/web-auth/link-token|reset-token|redeem`, `/actuator/**`) bleiben **permitAll** und
  unverändert erreichbar.
