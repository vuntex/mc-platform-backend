# Phase 0 Research — JWT-Login-Session

Alle technischen Unbekannten werden hier aufgelöst. Format pro Punkt: Decision / Rationale / Alternatives.

## R1 — JWT-Bibliothek & Wohnort

- **Decision:** `io.jsonwebtoken:jjwt` (jjwt-api + jjwt-impl + jjwt-jackson, runtime), **nur im `app`-Modul**, als
  Impl `JwtTokenService` der Ports `TokenIssuer`/`TokenVerifier`. HS256.
- **Rationale:** jjwt ist zweckgebaut, minimal, ohne Spring-Kopplung — passt zum selbst-ausgestellten,
  symmetrischen Token. Wohnort `app` spiegelt exakt die BCrypt-Impl der Bridge (`spring-security-crypto` +
  `BCryptPasswordHasher` liegen ebenfalls in `app`). So bleibt jjwt aus `core-domain`, `application`,
  `plugin-protocol` und sogar `api-rest` heraus (der Filter sieht nur den `TokenVerifier`-Port).
- **Alternatives:** `spring-security-oauth2-jose`/resource-server — für OAuth2/OIDC + JWKS gedacht, Overkill und
  drängt über `JwtAuthenticationConverter` in den GrantedAuthorities-Antipattern (zweiter Permission-Pfad);
  verworfen. `nimbus-jose-jwt` — low-level JOSE, mehr Zeremonie als nötig; verworfen.

## R2 — Signatur-Verfahren: HS256 (symmetrisch)

- **Decision:** HS256, Secret aus Config (`mcplatform.webauth.jwt.secret`, Env-injizierbar), min. 256 Bit.
- **Rationale:** Single-Server (§14): **derselbe** Backend-Prozess stellt aus und verifiziert. Kein separater
  Verifizier-Dienst → kein Bedarf an asymmetrischen Schlüsseln. HS256 ist einfacher (ein Secret, kein
  Schlüsselpaar/JWKS) und schnell.
- **Alternatives:** RS256/ES256 — nötig, wenn ein Dritter unabhängig (ohne das Secret) verifizieren müsste; das
  Web verifiziert aber **nicht** selbst, es spricht REST gegen das Backend (Backend = SoT). Verschoben, bis ein
  zweiter Verifizierer existiert. Der Wechsel beträfe nur `JwtTokenService` (Port unverändert).

## R3 — Token-at-rest-Hashing (Refresh-Token): SHA-256

- **Decision:** Refresh-Tokens werden als **SHA-256-Hex** (`token_hash`, 64 Zeichen, PK) gespeichert; Klartext nie
  persistiert. Wiederverwendung von `JooqLinkTokenRepository.sha256Hex`.
- **Rationale:** Refresh-Tokens sind hochentropische Zufallswerte (`SecureRandomTokenGenerator`, ≥128 Bit). Ein
  schneller, salt-loser kryptographischer Hash ist Standard: Preimage-Resistenz schützt vor DB-Read-Leaks,
  PK-Lookup ist O(1), kein Salt/Slow-Hash nötig. Identisch zum `web_link_token`-Verfahren der Bridge → ein
  Verfahren, ein Helfer, kein neuer Krypto-Code/Dependency.
- **Alternatives:** BCrypt — für Low-Entropy-**Passwörter** (Brute-Force-Bremse), hat die 72-Byte-Grenze und
  erlaubt keinen direkten PK-Vergleich (jeder Hash mit eigenem Salt → Lookup unmöglich). Für Zufallstokens nur
  langsamer ohne Sicherheitsgewinn; verworfen. (Das **Passwort** im `web_account` bleibt natürlich BCrypt — andere
  Eingabeklasse.)

## R4 — Modul-Platzierung (Auflösung der „infra-security vs. api-rest"-Frage)

- **Decision:** **Kein neues `infra-security`-Modul.** Ports (`TokenIssuer`/`TokenVerifier`) in `application`;
  jjwt-Impl in `app`; `JwtAuthenticationFilter` + `SecurityConfig` in `api-rest` (depend nur auf den
  `TokenVerifier`-Port). `spring-boot-starter-security` als Dependency in `api-rest`; jjwt in `app`.
- **Rationale:** Die Constitution-Schichtliste kennt kein `infra-security`; ein Modul für *einen Filter + eine
  Config* ist Over-Engineering (§10). Die Security-Konfiguration ist eine HTTP-Request-Handling-Sache → gehört in
  die Web-Schicht `api-rest`, neben die Controller, die sie schützt. jjwt bleibt über den Port aus `api-rest`
  draußen. Das spiegelt 1:1 das etablierte BCrypt-Muster (Port in core/app, Impl in app).
- **Alternatives:** Neues `infra-security`-Modul (verworfen: Overhead ohne Nutzen). jjwt-Impl direkt in `api-rest`
  (verworfen: zöge die JWT-Lib in die Web-Schicht, bräche die Port-Kapselung).

## R5 — Token-Transport zum Browser

- **Decision:** Access-Token im JSON-Body (Client hält es im Memory, sendet `Authorization: Bearer` an die API);
  Refresh-Token als **httpOnly, Secure, SameSite=Strict**-Cookie mit `Path=/api/web-auth/session`.
- **Rationale:** Refresh-Token ist das langlebige, hochwertige Geheimnis → httpOnly schützt vor XSS-Exfiltration.
  Access-Token kurzlebig (15 Min) → Memory akzeptabel; Bearer-Header = keine ambient credentials → API ist nicht
  CSRF-anfällig. Cookie-Pfad-Scoping hält das Cookie von normalen API-Calls fern.
- **CORS:** explizite Allowed-Origin (kein `*`) + `Allow-Credentials: true` (Config:
  `mcplatform.webauth.cors.allowed-origin`).
- **CSRF:** Refresh/Logout tragen ein ambient Cookie → CSRF-exponiert. Schutz: `SameSite=Strict` + Pflicht-Custom-
  Header (`X-Refresh`) auf diesen beiden Endpoints. Global CSRF in Spring Security **deaktiviert** (stateless
  Bearer-API), sonst bräche der Default die bestehenden POST-Endpoints (SC-007).
- **Alternatives:** Beide Tokens als Bearer/JS-gehalten (verworfen: Refresh dann XSS-exfiltrierbar). Beide als
  Cookies (verworfen: macht jede API-Call-CSRF-relevant). Server-Session-Cookie statt JWT (verworfen: zustands-
  behaftet, widerspricht dem stateless-Access-Token-Ziel).

## R6 — Rotation & Replay-Erkennung (strikt, all-player)

- **Decision:** Beim Refresh wird das vorgelegte Token in **einer** TX über `SELECT … FOR UPDATE` geprüft und bei
  Gültigkeit als rotiert markiert (`rotated_at = now`, **nicht** gelöscht); ein neues Token wird eingefügt
  (`rotated_from` = Vorgänger-Hash). Ein vorgelegtes Token mit gesetztem `rotated_at` = **Replay** → in derselben
  TX werden **alle** `refresh_token`-Zeilen der `player_uuid` gelöscht (Diebstahls-Signal) + Audit; danach Fehler.
- **Rationale:** Clarification 2026-06-24: strikt (kein Grace/No-op) + Invalidierung über `player_uuid` (kein
  Familien-Feld). „Markiert statt gelöscht" ist nötig, damit ein Replay von „unbekannt/abgelaufen" unterscheidbar
  bleibt — sonst ginge das Diebstahls-Signal verloren. `FOR UPDATE` serialisiert konkurrierende Refreshes desselben
  Tokens; der Verlierer sieht `rotated_at != null` → wird (akzeptierter Fehlalarm) als Replay behandelt.
- **Logout** hingegen **löscht** das vorgelegte Token (DELETE 0/1 → idempotent); ein späterer Replay sieht
  „unbekannt" → schlichte Ablehnung (kein Fehlalarm), korrekt, weil Logout eine bewusste Beendigung ist.
- **Alternatives:** Grace-Fenster / idempotenter No-op (in Clarify verworfen). Löschen statt Markieren bei Rotation
  (verworfen: Replay nicht erkennbar).

## R7 — Name→UUID-Auflösung (Reuse der Bridge-Regel)

- **Decision:** Additive Methode `PlayerRepository.findUuidByName(String)` → `SELECT uuid FROM player WHERE
  LOWER(name)=LOWER(?) ORDER BY last_seen DESC LIMIT 1` (nutzt `idx_player_name_lower`).
- **Rationale:** Die Regel ist in der Bridge festgenagelt, aber erst hier zu implementieren. Reuse des bestehenden
  Player-Ports statt eines parallelen Player-Zugriffs. (Hinweis: der Port liegt paket-historisch unter
  `economy.port` — ein vorbestehender Misnomer, den dieser Slice nicht umbaut.)
- **Alternatives:** Neuer dedizierter `PlayerNameResolver`-Port (verworfen: dupliziert Player-Zugriff, gegen §10).

## R8 — Audit (Reuse web_auth_audit)

- **Decision:** Session-Ereignisse als append-only Zeilen in `web_auth_audit` (free-text `event_type`):
  `LOGIN`, `TOKEN_ROTATED`, `TOKEN_REUSE_DETECTED`, `LOGOUT`. Nie Token/Hash/Passwort.
- **Rationale:** Die Bridge-Tabelle trägt bereits `player_uuid`/`event_type`/`at`; neue Event-Typen brauchen keine
  Schema-Änderung. Konsistent zum bestehenden `ACCOUNT_CREATED`/`PASSWORD_RESET`.
- **Alternatives:** Eigene Session-Audit-Tabelle (verworfen: unnötig, §10).
