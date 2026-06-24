# Quickstart & Definition of Done — JWT-Login-Session

## Build / Workflow
1. **plugin-protocol zuerst**: DTOs + `WebAuthEndpoints`-Erweiterung → `./gradlew :plugin-protocol:publishToMavenLocal`.
2. Backend-Module bauen: `./gradlew build` (jOOQ-Codegen regeneriert `Tables.REFRESH_TOKEN` aus V12).
3. Neue Config (Beispiel `application.yml` / Env):
   ```yaml
   mcplatform:
     webauth:
       jwt:
         secret: ${MCWEB_JWT_SECRET}          # >= 256 Bit, NIE im Repo
       access-ttl-minutes: 15
       refresh-ttl-days: 30
       cors:
         allowed-origin: ${MCWEB_ORIGIN:http://localhost:3000}
       refresh-cookie:
         name: mcweb_refresh
         path: /api/web-auth/session
       purge-interval-ms: 3600000             # Hygiene (abgelaufene Refresh-Tokens)
   ```

## Manueller Smoke (lokal)
```
# Login (web_account muss via Bridge existieren)
curl -i -X POST localhost:8080/api/web-auth/login -H 'Content-Type: application/json' \
     -d '{"username":"Vuntex","password":"<pw>"}'           # → 200 + Set-Cookie mcweb_refresh, accessToken im Body

# Refresh (Cookie + X-Refresh)
curl -i -X POST localhost:8080/api/web-auth/session/refresh -H 'X-Refresh: 1' --cookie 'mcweb_refresh=<raw>'  # → 200, neues Cookie

# Replay (altes raw erneut) → 401 web_auth_session_revoked, alle Sessions tot
# Logout → 204, Cookie gelöscht
```

## Definition of Done (Constitution §22)
- [ ] **Domain-Tests grün**: `RefreshTokenTest` (isExpired/isActive/isConsumed, fixe Instants).
- [ ] **Application-Tests grün** (Fakes + fixer `Clock`): Login (Erfolg; einheitlicher Fehler bei unbekanntem
      Namen / fehlendem Account / falschem Passwort), Refresh (Rotation, Invalid, **Replay→all-player-Kill**),
      Logout (idempotent), **kein Klartext-Token in Rückgaben/Logs**.
- [ ] **jOOQ/Testcontainers grün**: `JooqRefreshTokenRepositoryTest` (store; atomare Rotation; `rotated_at`-Marker;
      Replay erkennt konsumiertes Token + löscht alle Spieler-Tokens; `FOR UPDATE`-Serialisierung; SHA-256-Roundtrip;
      purgeExpired). `JooqPlayerRepositoryTest` erweitert (findUuidByName: LOWER + jüngster last_seen).
      `JooqWebAccountRepositoryTest` erweitert (find; **D4**: RESET-Redeem löscht alle refresh_token der UUID).
- [ ] **plugin-protocol-Tests grün** (rein-JDK): `WebAuthEndpointsTest` für LOGIN/REFRESH/LOGOUT.
- [ ] **E2E grün** (`app`, MockMvc inkl. Security-Chain): Login→Refresh→Replay→Logout; geschütztes `/api/web/**`
      ohne/mit Token (401/erlaubt); Resolver-Gate-Muster (403); JSON-Contract (Refresh-Token **nicht** im Body).
- [ ] **SC-007-Regression grün**: alle bestehenden Slices (Economy, Punishment, Report, Permission, Web-Auth-
      Bridge) laufen unverändert; Security-Chain permittiert die Alt-Endpoints, global CSRF aus.
- [ ] **`./gradlew build` grün** (Backend) inkl. jOOQ-Codegen.
- [ ] **plugin-protocol** publiziert; POM weiterhin **ohne** `<dependencies>`; **`PlatformProtocol.create()`
      unangetastet** (verifiziert).
- [ ] **Keine generische Klasse geändert** außer dem additiven D4-Edit in `JooqWebAccountRepository` (Feature-
      Adapter) + additiven Anstech-Punkten (Beans/Config, neue Dependencies jjwt@app / spring-security@api-rest).
- [ ] **PROGRESS.md** Status-Abschnitt nachgezogen; **FEATURE_INVENTORY.md** / Slice-Liste abgehakt.

## Bewusst verschoben / Lücken (aus Spec)
- Brute-Force-Schutz (D5/FR-021) — dokumentierte Lücke, später/Reverse-Proxy.
- „Alle Geräte abmelden", Access-Token-Sofort-Revocation/Blacklist, RS256, OAuth/E-Mail — out of scope.
- Onboarding-Hinweis „kein Account → /web link" NICHT über die Login-Antwort (D3, einheitlicher Fehler) — Frontend-Hilfe.
