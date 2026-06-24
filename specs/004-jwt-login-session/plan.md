# Implementation Plan: JWT-Login-Session (Web-Login gegen web_account)

**Branch**: `004-jwt-login-session` | **Date**: 2026-06-24 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/004-jwt-login-session/spec.md`

## Summary

Laufende Web-Login-Session als sechster Slice der Web-Account-Linie und direkter Folge-Slice der Web-Auth-Bridge
(`003-web-auth-bridge`). Ein Spieler mit `web_account` loggt sich im Webinterface mit aktuellem MC-Namen + Passwort
ein (Name→UUID über `player`, jüngster `last_seen`; Passwort über den bestehenden `PasswordHasher.matches`) und
erhält ein **stateless Access-JWT** (HS256, Subject = player_uuid, nur Identität) plus ein **state-stored,
rotierendes Refresh-Token**. Refresh rotiert (altes Token in einer TX entwerten, neues ausstellen); ein Replay
eines bereits rotierten Tokens gilt strikt als Diebstahl → alle Refresh-Tokens des Spielers werden invalidiert.
Autorisierung läuft ausschließlich über den bestehenden `PermissionResolver`-Port (kein zweiter Pfad); das JWT
trägt keine Rechte. Kein Pub/Sub, kein Plugin-Anteil.

**Technischer Kern (WIE):** JWT-Erzeugung/Verifikation ist ein **Port** (`TokenIssuer`/`TokenVerifier`) in
`application`; die jjwt-Impl lebt — exakt wie die BCrypt-Impl der Bridge — im `app`-Composition-Root. core-domain
und plugin-protocol bleiben JWT-frei. Der JWT-Filter + die `SecurityFilterChain` liegen in `api-rest` und kennen
nur den `TokenVerifier`-Port (jjwt sickert nicht in die Web-Schicht). **Kein neues `infra-security`-Modul.**

## Technical Context

**Language/Version**: Java 21 (Toolchain), Spring Boot 3.5.x (BOM), Gradle Kotlin DSL — wie der Rest des Backends.

**Primary Dependencies**:
- **Neu:** `io.jsonwebtoken:jjwt` (jjwt-api/impl/jackson) — **nur im `app`-Modul**, hinter `TokenIssuer`/
  `TokenVerifier`. `org.springframework.boot:spring-boot-starter-security` — **nur in `api-rest`** (Filter +
  SecurityFilterChain; bringt spring-security-web/-config/-core).
- **Wiederverwendet (keine neue Dependency):** `spring-security-crypto` (BCrypt, bereits in `app`), jOOQ + Flyway
  (infra-persistence), JDK `MessageDigest` (SHA-256 via bestehendes `JooqLinkTokenRepository.sha256Hex`).

**Storage**: PostgreSQL. Neue Tabelle `refresh_token` (state-stored, Flyway **V12**). Wiederverwendet:
`web_account` (Credential-Quelle), `player` (Name→UUID), `web_auth_audit` (append-only Audit, free-text
`event_type`).

**Testing**: JUnit 5 pro Schicht — Domain (rein), Application (Fakes + fixer `Clock`), jOOQ-Integration
(Testcontainers-Postgres), E2E (`app`, MockMvc/Spring-Test inkl. Security-Chain). Golden-/JSON-Contract-Tests im
bestehenden Stil.

**Target Platform**: Linux-Server (Single Paper-Node-Setup; Backend ist ein Spring-Boot-Prozess).

**Project Type**: Web-Service (Backend-Modul des Hexagons). Kein Frontend in diesem Slice (Next.js ist Konsument).

**Performance Goals**: Login/Refresh sind Low-Volume (interaktive Web-Aktionen, weit unter dem Economy-Pfad).
Keine besonderen Durchsatzziele; JWT-Signatur HS256 ist vernachlässigbar.

**Constraints**: Main-Thread-Blockade ist hier irrelevant (reines Backend, kein Bukkit). Refresh-Token nie im
Klartext at rest. Bestehende Slices müssen unverändert grün bleiben (SC-007).

**Scale/Scope**: ~200 Spieler-Größenordnung; pro Spieler wenige aktive Sessions (mehrere Geräte möglich).

**Unbekannte / NEEDS CLARIFICATION**: keine offen. Die Spec-Punkte D1–D5 sind entschieden; die zwei Clarify-Fragen
(strikte Rotation, all-player-Invalidierung) sind beantwortet. Die im Plan zu fixierenden technischen Punkte
(Token-Transport, Token-Hashing, Modul-Platzierung, JWT-Lib) sind in [research.md](./research.md) aufgelöst.

## Constitution Check

*GATE: vor Phase 0 bestanden; nach Phase 1 erneut geprüft (unten).*

| Prinzip | Bewertung |
|---------|-----------|
| **§1/§2 Backend = Wahrheit, Plugin = Client** | Kein Plugin-Anteil. Reines Backend-Request/Response. ✓ |
| **§3/§4 plugin-protocol JDK-only, additiv** | Neue DTOs (`LoginRequest`, `TokenPairResponse`) sind reine Records; **kein** Codec/Channel; `PlatformProtocol.create()` **unangetastet**. POM bleibt ohne `<dependencies>`. ✓ |
| **§5 Hexagonal-Schichtung** | core-domain (RefreshToken-Logik, JWT-frei) → application (Use Cases + Ports inkl. `TokenIssuer`/`TokenVerifier`) → infra-persistence (jOOQ) → api-rest (Filter/Chain/Controller) → app (jjwt-Impl, Verdrahtung). Keine Geschäftsregel in Controllern/Adaptern. ✓ |
| **§6 Persistenz begründet** | state-stored (Identitäts-/Sitzungsdatum, kein Replay-Aggregat). Analog `web_link_token`. Access-JWT bewusst zustandslos. ✓ |
| **§7 Idempotenz** | Logout idempotent (DELETE 0/1). Refresh-Rotation atomar in einer TX über `FOR UPDATE` + `rotated_at`-Marker; Replay deterministisch erkannt. ✓ |
| **§9 Ein Feature = ein Anstecken / kein Muster-Leck** | `PlatformProtocol.create()` nicht angefasst. Einziger Eingriff in bestehenden Code: additive Zeile in `JooqWebAccountRepository.resetPassword` (Feature-Adapter, **kein** generischer Baustein) für D4 — dokumentiert + getestet. Spring-Security ist eine **neue Dependency** in api-rest, kein Editieren einer generischen Klasse. ✓ |
| **§10 Wiederverwendung** | web_account, PasswordHasher.matches, TokenGenerator, sha256Hex, web_auth_audit, PlayerRepository, BCrypt-/SecureRandom-Adapter, WebAuthExceptionHandler, EndpointDescriptor — alles angesteckt. Nachweise unten. ✓ |
| **§12 Berechtigungen backend-autoritativ, ein Pfad** | JWT trägt nur Identität (Subject = uuid). Keine GrantedAuthorities/Rollen-Claims. Autorisierung ausschließlich über `PermissionResolver` zur Request-Zeit. ✓ |
| **§14 Single-Server** | HS256 (ein Aussteller = ein Verifizierer), kein verteilter Schlüsseltausch. ✓ |
| **§22 Tests/Build grün als DoD** | Tests pro Schicht; **SC-007** = bestehende Slices bleiben grün (Security-Chain permittiert Alt-Endpoints). ✓ |

**Gate-Ergebnis: PASS.** Keine Verletzung, kein Eintrag in Complexity Tracking nötig.

## Pflicht-Nachweise (aus dem Plan-Auftrag)

### Nachweis 1 — Login-Pfad reused alles aus der Bridge (keine neue Lookup-/Hash-Logik)

```
POST /api/web-auth/login {username, password}
  └─ WebSessionService.login(username, rawPassword)
       1. PlayerRepository.findUuidByName(username)        ← NEU (additive Methode), Regel aus Bridge fixiert:
          → Optional<PlayerId>                                LOWER(name) + ORDER BY last_seen DESC LIMIT 1
          (leer → InvalidCredentialsException, einheitlich)   (nutzt idx_player_name_lower)
       2. WebAccountRepository.find(playerUuid)            ← NEU (additive Methode am Bridge-Port)
          → Optional<WebAccount>                              liefert den gespeicherten password_hash
          (leer → InvalidCredentialsException, einheitlich)   (D3: kein „kein Account"-Hinweis)
       3. PasswordHasher.matches(rawPassword, hash)        ← WIEDERVERWENDET (Bridge baute matches genau hierfür)
          (false → InvalidCredentialsException, einheitlich)
       4. TokenIssuer.issue(playerUuid, accessTtl, now)    ← NEU (Port; jjwt-Impl in app)  → Access-JWT
       5. raw = TokenGenerator.newToken()                  ← WIEDERVERWENDET (SecureRandom, hochentropisch)
          hash = sha256Hex(raw)                            ← WIEDERVERWENDET (JooqLinkTokenRepository.sha256Hex)
          RefreshTokenRepository.store(hash, uuid, now, refreshExpiresAt, null)   ← NEU (Port)
          + web_auth_audit „LOGIN"                         ← WIEDERVERWENDET (bestehende Tabelle)
       6. → TokenPairResponse(accessToken, accessExp, refreshExp) + Set-Cookie(refresh)
```
**Reuse-Beweis:** Schritte 1–3 erfinden **keine** neue Identitäts-/Hash-Logik — Name→UUID-Regel, `PasswordHasher`
und der BCrypt-Adapter stammen 1:1 aus der Bridge; nur die *Lese-Einstiegspunkte* (`findUuidByName`,
`WebAccount find`) werden additiv ergänzt. Punkt 1c aus der Architektur-Prüfung gewahrt: Login ruft **keinen**
`PermissionResolver` (Login = Identität, Self-Service).

### Nachweis 2 — JWT als Port (core-domain/plugin-protocol bleiben JWT-frei)

```java
// application/webauth/port/TokenIssuer.java         (application; nur JDK-Typen)
public interface TokenIssuer {
    /** Signiertes, kurzlebiges Access-Token für die Identität; Subject = player_uuid. */
    String issue(PlayerId subject, Duration ttl, Instant now);
}

// application/webauth/port/TokenVerifier.java        (application; nur JDK-Typen)
public interface TokenVerifier {
    /** Verifiziert Signatur + Ablauf und liefert die Identität; sonst AccessTokenInvalidException. */
    PlayerId verify(String accessToken);
}
```
Die jjwt-Abhängigkeit existiert **nur** in der Impl `JwtTokenService` im `app`-Modul. `core-domain` kennt nur
„Session für UUID X mit Ablauf Y" (Wertobjekt `RefreshToken`, kein Token-String, keine Krypto). `plugin-protocol`
trägt nur Daten-DTOs, keinen JWT-Code. **Bestätigt: JWT-frei in core-domain und plugin-protocol.**

### Nachweis 3 — refresh_token-Schema + Hashing-Verfahren (begründet)

`token_hash` wird mit **SHA-256** gehasht (Hex, 64 Zeichen), **nicht** BCrypt. Begründung:
- Refresh-Tokens sind **hochentropische Zufallswerte** (`SecureRandomTokenGenerator`, ≥128 Bit). Für solche
  Tokens ist ein schneller, salt-loser kryptographischer Hash (SHA-256) Standard und ausreichend — ein
  DB-Read-Leak liefert kein einlösbares Token (Preimage-Resistenz), und ein Lookup über den PK-Hash ist O(1).
- BCrypt ist ein **langsamer Hash für Low-Entropy-Passwörter** (Brute-Force-Bremse) und hat die 72-Byte-Grenze;
  für hochentropische Tokens wäre er nur langsamer ohne Sicherheitsgewinn und bräuchte einen Lookup-fremden
  Match (kein direkter PK-Vergleich).
- **Konsistent zur Bridge:** `web_link_token` hasht ebenfalls per SHA-256; wir verwenden denselben Helfer
  `JooqLinkTokenRepository.sha256Hex` wieder (kein neuer Krypto-Code, kein neuer Dependency).

Schema-Details und der Rotations-/Replay-Mechanismus stehen in [data-model.md](./data-model.md). Kernpunkt:
Rotation entwertet das alte Token **als rotiert markiert** (`rotated_at`), **nicht** durch Löschen — sonst wäre
ein Replay nicht von „unbekannt" unterscheidbar und das Diebstahls-Signal ginge verloren.

### Nachweis 4 — Security-Verdrahtung (Feature-Controller wissen nichts von Auth; Autorisierung via Resolver)

```
HTTP Request ─► [SecurityFilterChain (api-rest, app-scanned)]
                   └─ JwtAuthenticationFilter (OncePerRequestFilter)
                        • liest Authorization: Bearer <accessToken>
                        • TokenVerifier.verify(token) → PlayerId       (nur der PORT, kein jjwt hier)
                        • setzt Authentication(principal = PlayerId, authorities = LEER) in den SecurityContext
                        • fehlt/ungültig → kein Principal gesetzt (kein throw)
                   └─ authorizeHttpRequests:
                        "/api/web/**"  → authenticated()   (Slice-6-Web-API; 401 wenn kein Principal)
                        anyRequest()   → permitAll()        (Alt-Endpoints + Plugin-Verkehr bleiben offen → SC-007)
```
Ein künftiger Web-Feature-Controller (Slice 6) liest die Identität, **nicht** Rechte, aus dem Kontext und fragt den
Resolver — das ist das hier etablierte Muster:
```java
@GetMapping("/api/web/roles")
public X list(@AuthenticationPrincipal PlayerId caller) {          // Identität aus dem JWT-Filter
    if (!permissionResolver.hasPermission(caller, "permission.view"))  // Autorisierung NUR über den Port
        throw new PermissionDeniedException(...);                   // → 403 (bestehender Handler)
    ...
}
```
**Kein zweiter Permission-Pfad:** Die Chain vergibt **leere Authorities** und nutzt **keine** `@PreAuthorize`/
Rollen-Matcher. „Authentifiziert ja/nein" ist die einzige Security-Entscheidung der Chain; „darf er das" liegt
ausschließlich beim `PermissionResolver`. Feature-Controller hängen nicht von Spring-Security ab (sie bekommen nur
die `PlayerId`). **CSRF wird für die zustandslose API global deaktiviert** (Bearer-API trägt keine ambient
credentials) — Konsequenz/Absicherung der Cookie-Endpoints siehe Nachweis 5. *Risiko-Hinweis (Punkt-0-Wächter):*
Spring-Security sichert per Default alles; deshalb ist die `anyRequest().permitAll()`-Regel + `csrf.disable()`
zwingend, damit die fünf bestehenden Slices grün bleiben (SC-007) — durch einen E2E-Regressionscheck belegt.

### Nachweis 5 — Token-Transport: Access im Body/Bearer, Refresh als httpOnly-Cookie (CORS/CSRF)

Entscheidung (aus Spec-Empfehlung, in [research.md](./research.md) fixiert):
- **Access-Token:** im JSON-Body der Login-/Refresh-Antwort, vom Web-Client im **Memory** gehalten, auf API-Calls
  als `Authorization: Bearer`. Kurzlebig (15 Min) → Memory akzeptabel; Bearer = keine ambient credentials → die
  geschützten API-Endpoints sind **nicht** CSRF-anfällig.
- **Refresh-Token:** **httpOnly, Secure, SameSite=Strict**-Cookie, `Path=/api/web-auth/session` (wird nur an
  Refresh/Logout gesendet, nicht an jeden API-Call). httpOnly → für JS unerreichbar (XSS kann es nicht
  exfiltrieren). **Nie** im JSON-Body (auch nicht in `TokenPairResponse`).
- **CORS:** Web-Origin ≠ Backend-Origin → konfigurierte **explizite** Allowed-Origin (kein `*`) mit
  `Access-Control-Allow-Credentials: true` (Cookie-Versand). Origin aus Config
  (`mcplatform.webauth.cors.allowed-origin`).
- **CSRF:** Da das Refresh-Cookie ambient ist, sind **Refresh/Logout** grundsätzlich CSRF-exponiert. Absicherung:
  (a) `SameSite=Strict` blockt Cross-Site-Sends; (b) zusätzlich ein verpflichtender Custom-Header (z. B.
  `X-Refresh: 1`) auf Refresh/Logout, den ein Cross-Site-Formular nicht setzen kann. Login (Credentials im Body,
  keine ambient Auth) und die Bearer-API sind CSRF-irrelevant.

### Nachweis 6 — plugin-protocol additiv, kein Codec/Channel, create() unangetastet

Neu in `plugin-protocol.webauth` (JDK-only Records):
- `LoginRequest(String username, String password)`
- `TokenPairResponse(String accessToken, long accessExpiresAtEpochMilli, long refreshExpiresAtEpochMilli)`
  — **bewusst ohne refreshToken-Feld**: das Refresh-Token reist ausschließlich im httpOnly-Cookie, nie im
  JSON (Abweichung vom ursprünglichen Prompt-Wortlaut „TokenPairResponse mit Token" — begründet durch die
  Transport-Entscheidung in Nachweis 5).
- **Kein `RefreshRequest`**: Refresh/Logout lesen das Token aus dem Cookie → kein Body-DTO. (Falls je ein
  Nicht-Browser-Client Body-Refresh braucht, additiv nachrüstbar.)
- `WebAuthEndpoints` um `LOGIN`/`REFRESH`/`LOGOUT` erweitert (bestehender `EndpointDescriptor`-Stil).

**Bestätigt:** Kein `MessageCodec`, kein `Channel`, **`PlatformProtocol.create()` wird NICHT angefasst** (kein
Pub/Sub — reines Request/Response). POM bleibt ohne `<dependencies>`. Nach Änderung:
`:plugin-protocol:publishToMavenLocal`.

### Nachweis 7 — Passwort-Reset invalidiert Sessions (Berührungspunkt zur Bridge)

D4: Ein RESET über die Bridge (`/web resetPassword` → `redeem` mit `TokenPurpose.RESET`) muss alle Sessions des
Spielers beenden. **Touchpoint:** in `JooqWebAccountRepository.resetPassword(tx, playerUuid, …)` — der bereits in
**einer** Transaktion das Passwort setzt — wird **eine additive Zeile** ergänzt:
```java
tx.deleteFrom(REFRESH_TOKEN).where(REFRESH_TOKEN.PLAYER_UUID.eq(playerUuid)).execute();  // D4, gleiche TX
```
**Warum hier (atomar):** Passwort-Wechsel und Session-Kill müssen zusammen committen — sonst bliebe bei einem
Crash dazwischen eine alte Session nach Passwort-Reset gültig. Die Application-Alternative (nach `redeem` löschen)
ist nicht atomar und kennt die UUID nicht (sie wird erst in der Repo-TX aufgelöst) → verworfen. Es ist der
**einzige** Eingriff in bestehenden Bridge-Code, additiv, durch einen Bridge-Regressionstest abgesichert
(`web_auth_audit` „PASSWORD_RESET" unverändert; zusätzlich: Refresh-Tokens der UUID sind danach weg).

### Nachweis 8 — Flyway: nächste freie Version, keine bestehende ändern

Belegt auf der Platte: V1–V11 vergeben (V9 permission, V10 role_display_icon, V11 web_auth_schema). **Neu:
`V12__refresh_token_schema.sql`** — eine Tabelle (`refresh_token`). Keine bestehende Migration wird angefasst.
(Der Prompt nannte „V10" — das ist bereits belegt; korrigiert auf V12.)

## Project Structure

### Documentation (this feature)

```text
specs/004-jwt-login-session/
├── plan.md              # Dieses Dokument
├── research.md          # Phase 0: Transport, Token-Hashing, Modul-Platzierung, JWT-Lib, Rotation/Replay
├── data-model.md        # Phase 1: refresh_token-Schema, RefreshToken-Domäne, Rotation/Replay-Zustandsmaschine
├── contracts/
│   ├── rest-api.md       # Login/Refresh/Logout HTTP-Contract (Header/Cookie/Status)
│   └── protocol.md       # plugin-protocol-Ergänzungen (DTOs + WebAuthEndpoints)
├── quickstart.md        # Build/Test/Definition-of-Done
├── checklists/requirements.md
└── tasks.md             # Phase 2 (/speckit-tasks — NICHT hier erzeugt)
```

### Source Code (repository root)

```text
core-domain/…/domain/webauth/
├── RefreshToken.java            # NEU: Wertobjekt (tokenHash, playerUuid, createdAt, expiresAt, rotatedAt, rotatedFrom)
└── (RefreshToken lifecycle: isExpired(now), isActive(now); reine Logik, JWT-frei)

application/…/application/webauth/
├── WebSessionService.java       # NEU: Use Cases login / refresh / logout
├── port/RefreshTokenRepository.java   # NEU: store / rotate (atomar) / deleteAllForPlayer / purgeExpired
├── port/TokenIssuer.java        # NEU: issue(uuid, ttl, now) → String
├── port/TokenVerifier.java      # NEU: verify(token) → PlayerId
├── port/WebAccountRepository.java     # ERWEITERT: + Optional<WebAccount> find(PlayerId)
└── port/InvalidCredentialsException / RefreshTokenInvalidException / RefreshTokenReuseException / AccessTokenInvalidException
application/…/application/economy/port/PlayerRepository.java   # ERWEITERT: + Optional<PlayerId> findUuidByName(String)

infra-persistence/…/persistence/
├── JooqRefreshTokenRepository.java    # NEU: jOOQ-Adapter (Rotation in EINER TX, Replay-Erkennung, Audit)
├── JooqWebAccountRepository.java      # EDIT (additiv): find(PlayerId) + D4-Refresh-Purge in resetPassword
├── JooqPlayerRepository.java          # EDIT (additiv): findUuidByName (LOWER(name)+last_seen DESC)
└── resources/db/migration/V12__refresh_token_schema.sql   # NEU

api-rest/…/api/rest/
├── WebSessionController.java          # NEU: POST login / session/refresh / session/logout (dünn, Cookie-Handling)
├── security/JwtAuthenticationFilter.java   # NEU: Bearer → TokenVerifier(port) → SecurityContext(PlayerId)
├── security/SecurityConfig.java       # NEU: SecurityFilterChain (stateless, csrf off, /api/web/** authenticated)
├── security/CurrentPlayerArgumentResolver (opt.) / @AuthenticationPrincipal-Nutzung
└── WebAuthExceptionHandler.java       # EDIT (additiv): + 401 (invalid credentials / refresh invalid / reuse)
api-rest/build.gradle.kts              # EDIT: + spring-boot-starter-security

app/…/bootstrap/
├── adapter/JwtTokenService.java       # NEU: implements TokenIssuer, TokenVerifier (jjwt, HS256, secret aus Config)
├── config/WebSessionConfig.java       # NEU: Beans (JwtTokenService, WebSessionService), CORS, Cookie-/TTL-Config
├── config/PersistenceConfig.java      # EDIT (additiv): + JooqRefreshTokenRepository-Bean
└── (optional) WebRefreshTokenPurge    # NEU: @Scheduled purge (Hygiene; bestehende @EnableScheduling)
app/build.gradle.kts                   # EDIT: + jjwt

plugin-protocol/…/protocol/webauth/
├── LoginRequest.java                  # NEU (Record)
├── TokenPairResponse.java             # NEU (Record, ohne refreshToken-Feld)
└── WebAuthEndpoints.java              # EDIT (additiv): + LOGIN / REFRESH / LOGOUT
```

**Structure Decision**: Multi-Module-Hexagon wie gehabt; dieser Slice erweitert das bestehende `webauth`-Package
über alle Schichten (Vertical Slice, §19). **Kein neues Modul** — der „infra-security vs. api-rest"-Punkt aus der
Architektur-Prüfung ist so aufgelöst: JWT-**Port** in `application`, JWT-**Impl** im `app`-Composition-Root
(spiegelt die BCrypt-Impl der Bridge), **Filter + SecurityFilterChain** in `api-rest`. Ein eigenes
`infra-security`-Modul wäre Over-Engineering (ein Filter + eine Config); die Impl in `api-rest` zu legen würde jjwt
in die Web-Schicht ziehen und die Port-Kapselung brechen — beide verworfen.

## Phasing (Umsetzungsreihenfolge — Detail in tasks.md)

1. **Domain**: `RefreshToken` + Lebenszyklus-Regeln (rein, Tests).
2. **plugin-protocol**: DTOs + `WebAuthEndpoints`-Erweiterung → `publishToMavenLocal`.
3. **application**: Ports (`TokenIssuer`/`TokenVerifier`/`RefreshTokenRepository`), `WebSessionService`, Exceptions;
   additive Port-Methoden (`PlayerRepository.findUuidByName`, `WebAccountRepository.find`). Use-Case-Tests mit Fakes.
4. **infra-persistence**: V12-Migration, `JooqRefreshTokenRepository` (Rotation/Replay/Audit), additive Edits
   (`find`, `findUuidByName`, D4-Purge). jOOQ-Integrationstests (Testcontainers).
5. **app**: `JwtTokenService` (jjwt), Beans/Config, Purge.
6. **api-rest**: `SecurityConfig` + `JwtAuthenticationFilter`, `WebSessionController` (Cookie), Handler-401.
   + `spring-boot-starter-security`.
7. **E2E** (`app`): Login→Refresh→Rotation→Replay→Logout; D4-Reset killt Sessions; **SC-007-Regression** (alle
   bestehenden Slices grün, Security-Chain permittiert Alt-Endpoints).

## Constitution Re-Check (nach Phase-1-Design)

Design hält den Gate ein: core-domain bleibt JWT-frei (RefreshToken ist pures Wertobjekt), plugin-protocol bleibt
JDK-only/codec-frei, `PlatformProtocol.create()` unangetastet, ein einziger additiver Eingriff in einen
Feature-Adapter (kein generischer Baustein), Autorisierung weiterhin nur über `PermissionResolver`, neue
Dependencies modul-konform (jjwt nur in app, spring-security nur in api-rest). **PASS — keine Komplexitäts-
Ausnahme nötig.**

## Complexity Tracking

*Keine Constitution-Verletzung — Tabelle entfällt.*
