# Implementation Plan: Web-Auth-Bridge

**Branch**: `003-web-auth-bridge` | **Date**: 2026-06-24 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/003-web-auth-bridge/spec.md`

## Summary

Die Web-Auth-Bridge ist **Greenfield-Infrastruktur fürs Webinterface** (kein Altplugin-Import). Ein
Spieler verbindet ingame seine Minecraft-Identität (UUID) mit einem Web-Account: `/web link` erzeugt
einen kurzlebigen, einmal verwendbaren Token, der als anklickbarer Web-Link ins Spiel gepusht wird; im
Web setzt der Spieler ein Passwort. `/web resetPassword` ist derselbe Mechanismus für Recovery. Die
laufende JWT-Login-Session ist ein **separater Folge-Slice** (nicht hier).

Das Feature wird nach dem etablierten **state-stored**-Muster (Reports) angesteckt: `core-domain →
application/Ports → infra-persistence (jOOQ + Flyway) → api-rest`, plus additive `plugin-protocol`-DTOs
und `-Endpoints`. **Besonderheit gegenüber allen bisherigen Features:** Es gibt **kein** Live-Event —
also **keinen** `MessageCodec`/`Channel` und damit **keinen** Eingriff in `PlatformProtocol.create()`.
Der einzige geteilte Contract-Zuwachs sind neue, rein datentragende Endpoint-/DTO-Klassen.

## Technical Context

**Language/Version**: Java 21 (records, `System.Logger`), Gradle multi-module.
**Primary Dependencies**: jOOQ + Flyway (`infra-persistence`), Spring Web (`api-rest`), Spring Boot
(`app`), `plugin-protocol` (JDK-only). **Neu:** `spring-security-crypto` (BCrypt) — ausschließlich im
`app`-Modul (siehe R4). **Kein** Redis/Lettuce für dieses Feature.
**Storage**: PostgreSQL (state-stored) — neue Tabellen `web_account`, `web_link_token`, `web_auth_audit`
in **Flyway V11**. Kein Redis-State, kein Pub/Sub.
**Testing**: JUnit-Schichttests — Domain (rein), Use-Case (Fakes), jOOQ-Integration (Testcontainers
Postgres), E2E (`app`, REST → Postgres), Protocol-DTO-JSON-Contract-Test (`app`, `@JsonTest`).
**Target Platform**: Single Paper-Node + Spring-Boot-Backend (Constitution §14).
**Project Type**: Multi-Module-Backend (hexagonal/DDD) + geteilter `plugin-protocol`-Contract.
**Performance Goals**: sehr niedriges Volumen (Account-Anlage/Reset sind seltene Aktionen); kein
Hot-Path, kein Live-Push. SC-001: Spieler in < 2 Min von `/web link` zum gesetzten Passwort.
**Constraints**: core-domain & application framework-frei; `plugin-protocol` JDK-only & POM ohne
`<dependencies>`; `infra-persistence` bleibt **Spring-frei** (treibt R4); keine generische Klasse ändern.
**Scale/Scope**: ein Web-Account je Identität; Token-Tabelle in der Größenordnung „aktuell offene
Link-/Reset-Vorgänge" (klein, kurzlebig).

## Constitution Check

*GATE: vor Phase-0-Research geprüft; nach Phase-1-Design erneut.*

| Prinzip | Anforderung | Status im Plan |
|---|---|---|
| **1** Backend = SoT, Plugin = Client | Account/Token nur im Backend; Plugin schreibt via REST | ✅ Plugin hält nichts; `/web`-Commands rufen REST |
| **4** protocol dependency-frei | DTOs JDK-only, kein JSON-Framework | ✅ reine Records + `EndpointDescriptor`; JSON nur in api-rest/Plugin |
| **5** Schichtung | core → application → infra → api-rest → app | ✅ exakt wie Reports |
| **6** Persistenz-Wahl begründen | event-sourced **oder** state-stored, begründet | ✅ **state-stored** + Audit-Tabelle (R1) |
| **7** Idempotenz per Constraint | Doppelzustellung wirkt nie doppelt | ✅ Token single-use: `unique(token_hash)` + DELETE-in-TX; Replay → 410 (R2) |
| **9** ein Feature = ein Anstecken | keine generische Klasse ändern | ✅ **`PlatformProtocol.create()` NICHT angefasst** (kein Codec); nur additive Beans (R7, Ledger) |
| **10** Wiederverwenden statt neu bauen | bestehende Bausteine nutzen | ✅ `EndpointDescriptor`, `BackendClient`, FK auf `player(uuid)`, Cooldown-Muster, Audit-Muster, Clock-Bean, `@EnableScheduling` (R12) |
| **12** Berechtigung backend-autoritativ | über `PermissionResolver` | ✅ **n/a in Slice 1** — Self-Service (Server-Login beweist Identität); unlink/Staff-Pfade verschoben (Q1) |
| **13** Geld maximal absichern | — | n/a (kein Geld) |
| **14** Single-Server | kein Distributed-Lock | ✅ Token-Race über DB-TX (`FOR UPDATE`), kein verteiltes Locking |
| **18** Verhalten 1:1, Technik nicht | benennen, was wegfällt | ✅ Greenfield; kein Altcode; Was-wegfällt in spec.md „Out of Scope" |
| **19–22** Vertical Slice + Tests/Build grün | Tests pro Schicht | ✅ Test-Plan je Schicht (Phase 1) |

**Gate-Ergebnis: BESTANDEN.** Drei bewusst dokumentierte Punkte im Pattern-Leak-Ledger — keiner ist ein
verbotener Eingriff in eine generische Klasse.

### Persistenz-Entscheidung (gegen Prinzip 6 begründet)

**Gewählt: state-stored** (`web_account` + `web_link_token` + `web_auth_audit`), wie Reports — **nicht**
event-sourced. Begründung: Ein Web-Account ist config-/identitätsartig, **kein** geld-/urteilskritisches
Aggregat. Der einzige Audit-Bedarf (wer hat wann Account angelegt / Passwort gesetzt) deckt die
append-only `web_auth_audit`-Tabelle (`config_audit`-Stil). Ein Event-Store wäre unbegründeter Ballast;
das Passwort-Geheimnis wird in-place ersetzt — alte Hashes aufzubewahren (was Event-Sourcing täte) wäre
ein Sicherheits-Anti-Pattern. **Rule-of-three-Extraktion bleibt unberührt** (kein drittes event-sourced
Geschwister).

## Wiederverwendung (EXPLIZIT — nicht neu bauen)

| Bestehender Baustein | Ort | Verwendung hier | Änderung nötig? |
|---|---|---|---|
| **FK auf `player(uuid)`** | `player`-Tabelle (V1, UUID-zentrisch) | `web_account.player_uuid` / `web_link_token.player_uuid` FK | **Nein** — FK nutzen |
| **`idx_player_name_lower`** | `player (LOWER(name))` (V1) | Name→UUID-Auflösung **erst im Login-Slice** | **Nein** — Index existiert; kein Lookup-Code in diesem Slice (R5) |
| **`EndpointDescriptor` / `HttpMethod`** | `protocol/core` | `WebAuthEndpoints`-Konstanten | **Nein** — nur instanziieren |
| **Genereller `BackendClient`** (Plugin) | Plugin-`transport` | `/web`-Commands rufen `WebAuthEndpoints` | **Nein** — nur aufrufen |
| **`FeatureRegistry` / `Feature`** (Plugin) | Plugin-`platform` | `feature.web` als ein Anstecken | **Nein** — registrieren |
| **Cooldown-Muster** | `ReportService` (`Duration` + `Clock`, Config-Key) | Token-Erzeugungs-Cooldown (FR-022) | **Nein** — Muster nachbauen |
| **Audit-Muster** | `config_audit` / `report_status_history` | `web_auth_audit` | **Nein** — Muster nachbauen |
| **`Clock`-Bean** | bestehende Bean (PunishmentConfig) | Ablauf/Cooldown deterministisch testbar | **Nein** — bestehende Bean injizieren, NICHT neu definieren |
| **`@EnableScheduling`** | `SchedulingConfig` (Permissions-Feature) | optionaler Token-Purge (Hygiene, FR-023) | **Nein** — bestehende Aktivierung mitnutzen |
| **Flyway/jOOQ-Codegen-Pipeline** | `infra-persistence` | V11-Migration → generierte Tabellen | **Nein** — neue `V11`-Migration ergänzen |
| **Globaler 400-Mapping-Stil** | `EconomyExceptionHandler` u. a. | eigener `WebAuthExceptionHandler` nur für eigene Codes | **Nein** — 400 NICHT re-deklarieren |

**Bewusst NICHT wiederverwendet:** `PermissionResolver` (kein Gate in Slice 1, Q1), `RedisCacheAdapter`/
Pub/Sub (kein Live-Pfad), `PlatformProtocol.create()` (kein Codec).

## Muster-Lecks (STOPP-Marker) & Ledger

1. **`PlatformProtocol.create()` — NICHT angefasst.** Da es **kein** Live-Event gibt, wird **kein**
   `MessageCodec`/`Channel` registriert. Dies ist das erste Feature, das den geteilten Routing-Aufbau
   **gar nicht** berührt. Die `plugin-protocol`-Ergänzungen (`WebAuthEndpoints`, DTOs) sind **neue,
   additive Klassen** (reine Daten) — kein Edit an geteiltem Code. **Kein Leck.**

2. **`PersistenceConfig` + neue `@Bean`-Methoden** (Repo-Beans) und eine **neue Feature-Config**
   `WebAuthConfig` (Service + Adapter-Beans). Additiv, vom „ein Anstecken"-Muster ausdrücklich
   vorgesehen (exakt wie `ReportConfig`/`PersistenceConfig` bei Reports). **Kein Leck.**

3. **Neue Dependency `spring-security-crypto` im `app`-Modul** (BCrypt). Begründet & isoliert: der
   `PasswordHasher`-**Port** liegt in core-domain (framework-frei); die einzige Impl
   `BCryptPasswordHasher` liegt als Adapter im **`app`**-Composition-Root (das bereits Spring nutzt) —
   **nicht** in `infra-persistence`, damit dieses Modul **Spring-frei bleibt** (Constitution §5). Keine
   generische Klasse geändert; eine bewusste, dokumentierte erste Krypto-Abhängigkeit. (Siehe R4 —
   **Abweichung von der Prompt-Vorgabe „Impl in infra", begründet.**)

→ Außer (2) — additiv, vom Muster vorgesehen — und (3) — isolierte neue Dependency — wird **keine**
bestehende Klasse geändert. Kein echtes Muster-Leck.

## Korrekturen an den Prompt-Vorgaben (bewusst, begründet)

- **Flyway-Version V9 → V11.** Die Prompt nahm „nächste freie nach V8 (Reports) → V9" an; tatsächlich
  hat das Permission-/Rank-Feature bereits **V9** (`permission_schema`) **und V10** (`role_display_icon`)
  belegt. Nächste freie Version ist **V11**. Keine bestehende Migration wird angefasst. (R6)
- **BCrypt-Impl in `app` statt `infra-persistence`.** `infra-persistence` ist heute strikt Spring-frei
  (nur jOOQ/Flyway/Postgres). Die BCrypt-Impl lebt daher im `app`-Modul; `infra-persistence` erhält
  **keine** neue Dependency. Der Token-at-rest-Hash (SHA-256, reines JDK) bleibt in `infra-persistence`.
  (R4)
- **Token-at-rest = SHA-256-Hash statt Rohwert** (R3): **entschieden** (2026-06-24) — DB-Leak-Härtung,
  gratis (Token ≥128 Bit, kein Salt nötig). `token_hash` ist PK.

## Project Structure

### Documentation (this feature)

```text
specs/003-web-auth-bridge/
├── spec.md              # /speckit.specify (+ clarify) Output
├── plan.md              # Dieses Dokument
├── research.md          # Phase 0 — R1..R12
├── data-model.md        # Phase 1 — Entitäten + V11-Tabellen
├── quickstart.md        # Phase 1 — Build/Test/DoD
├── contracts/
│   ├── rest-api.md       # Endpunkte + Status-Codes
│   └── protocol.md       # DTOs + WebAuthEndpoints (KEIN Codec/Channel)
└── checklists/
    └── requirements.md
```

### Source Code (neue Dateien, gespiegelt an Reports)

```text
core-domain/src/main/java/com/mcplatform/domain/webauth/
├── WebAccount.java                  # record (state-stored): playerUuid, passwordHash, createdAt, passwordUpdatedAt
├── TokenPurpose.java                # enum LINK | RESET
├── LinkToken.java                   # record: playerUuid, purpose, expiresAt, createdAt; isExpired(now)
├── PasswordPolicy.java              # reine Validierung: 8..64 Zeichen → wirft InvalidPasswordException
├── PasswordHasher.java              # PORT (framework-frei): String hash(String raw); boolean matches(String raw, String hash)
├── TokenGenerator.java              # PORT: String newToken()  (≥128 Bit, URL-safe) — Impl in app
└── InvalidPasswordException.java
# (Token-„ungültig/abgelaufen/verbraucht" ist eine application-Exception: TokenInvalidException → 410,
#  uniform; LinkToken.isExpired liefert die reine Domain-Prüfung.)

application/src/main/java/com/mcplatform/application/webauth/
├── WebAuthService.java              # Use Cases: requestLinkToken / requestResetToken / redeem
└── port/
    ├── WebAccountRepository.java    # exists(uuid); redeem(rawToken, passwordHash, now) -> RedeemOutcome  [atomar, 1 TX]
    ├── LinkTokenRepository.java     # issue(rawToken, uuid, purpose, expiresAt, now) [DELETE-vor-INSERT, 1 TX]; lastCreatedAt(uuid, purpose); deleteExpired(now)
    ├── WebAccountExistsException.java     # 409 (link, obwohl Account existiert)
    ├── WebAccountMissingException.java    # 409 (reset, obwohl kein Account)
    ├── WebAccountConflictException.java   # 409 (redeem-Race)
    ├── TokenInvalidException.java         # 410 (Token unbekannt/abgelaufen/verbraucht — uniform)
    └── WebAuthCooldownException.java      # 429

infra-persistence/src/main/java/com/mcplatform/persistence/
├── JooqWebAccountRepository.java    # web_account-CRUD + atomarer redeem (account-op + token-DELETE + audit-INSERT, 1 TX)
└── JooqLinkTokenRepository.java     # web_link_token: issue (DELETE-vor-INSERT), lastCreatedAt, deleteExpired; SHA-256 token-hash (JDK)
infra-persistence/src/main/resources/db/migration/
└── V11__web_auth_schema.sql         # web_account + web_link_token + web_auth_audit (eine Migration, drei Tabellen)

api-rest/src/main/java/com/mcplatform/api/rest/
├── WebAuthController.java           # POST .../link-token, .../reset-token (Plugin), POST /api/web-auth/redeem (Web)
├── WebAuthExceptionHandler.java     # NUR eigene Codes: 409 / 410 / 422 / 429  (400 NICHT re-deklarieren)
└── support/WebAuthMapper.java       # domain ↔ protocol DTOs (epoch-milli)

plugin-protocol/src/main/java/com/mcplatform/protocol/webauth/
├── WebAuthEndpoints.java            # REQUEST_LINK, REQUEST_RESET (Plugin), REDEEM (Web)  — EndpointDescriptor
├── TokenResponse.java               # token (String), purpose (String), expiresAtEpochMilli (long)  — NIE ein Hash
└── RedeemRequest.java               # token (String), password (String)
# plugin-protocol/.../protocol/PlatformProtocol.java   # UNVERÄNDERT (kein Codec) — explizit bestätigt

app/src/main/java/com/mcplatform/bootstrap/
├── config/WebAuthConfig.java        # @Bean WebAuthService (+ Cooldown/TTL-Config), @Bean PasswordHasher, @Bean TokenGenerator
├── config/PersistenceConfig.java    # + @Bean WebAccountRepository, + @Bean LinkTokenRepository (additiv)
├── adapter/BCryptPasswordHasher.java       # implements PasswordHasher (spring-security-crypto) — NUR hier liegt die Krypto-Dep
└── adapter/SecureRandomTokenGenerator.java # implements TokenGenerator (java.security.SecureRandom, URL-safe Base64, ≥128 Bit)

# Plugin (separates Repo mc-platform-plugin — hier NUR contract-seitig beschrieben)
feature.web/  → /web link|resetPassword  als ein Feature (FeatureRegistry); ruft WebAuthEndpoints via BackendClient;
               Adventure-Component baut den klickbaren Link aus TokenResponse.token. KEIN unlink (Q1). Kein Transport-Eingriff.

# Build
app/build.gradle.kts                 # + spring-security-crypto (einzige neue Dependency, nur hier)
```

**Structure Decision**: Multi-Module-hexagonal (bestehend). Web-Auth ist ein **paralleles Geschwister**
zu Reports (state-stored), aber **ohne** Live-Event/Codec und **ohne** Permission-Gate. Alle neuen
Klassen liegen in feature-eigenen `…webauth`-Packages; geteilter Code wird nur über additive Beans und
neue (nicht editierte) Contract-Klassen berührt.

## Live-/Atomaritäts-Verortung (Spec-Anforderungen)

- **Single-use + Atomarität (FR-012/FR-018):** Der Redeem ist **eine** jOOQ-Transaktion in
  `JooqWebAccountRepository.redeem(...)`: `SELECT … FOR UPDATE` auf die Token-Zeile (Hash, `expires_at >
  now`) → bei LINK `INSERT web_account` (ON CONFLICT → `WebAccountConflictException`), bei RESET `UPDATE
  web_account … WHERE player_uuid=?` (0 Zeilen → `WebAccountConflictException`) → `DELETE` der Token-Zeile
  → `INSERT web_auth_audit`. Entweder alles oder nichts.
- **Ein aktiver Token je (uuid, purpose) (FR-013):** `JooqLinkTokenRepository.issue(...)` macht in **einer**
  TX `DELETE … WHERE player_uuid=? AND purpose=?` gefolgt von `INSERT`.
- **Ablauf maßgeblich beim Einlösen (FR-014):** Der `expires_at > now`-Filter in der Redeem-Query ist die
  Sicherheitsgrenze — unabhängig von jedem Purge-Lauf.
- **Cooldown (FR-022):** `WebAuthService` liest `LinkTokenRepository.lastCreatedAt(uuid, purpose)` und
  lehnt innerhalb des konfigurierten Cooldowns mit `WebAuthCooldownException` (429) ab, **bevor** ein
  neuer Token erzeugt wird (Anchor = `created_at` des aktuell lebenden Tokens; nach Redeem/Ablauf kein
  Anchor → erlaubt). Default `mcplatform.webauth.token-cooldown-seconds`.
- **TTL (FR-011):** `mcplatform.webauth.token-ttl-minutes` (Default 10); `expires_at = now + TTL` bei der
  Erzeugung.
- **Purge (FR-023, optional Hygiene):** falls gebaut, ein `@Scheduled`-Aufruf von
  `LinkTokenRepository.deleteExpired(now)` über die **bestehende** `@EnableScheduling`-Aktivierung —
  ausdrücklich als nicht-sicherheitskritisch markiert.

## [NEEDS CLARIFICATION] aus der Spec — Status

| ID | Thema | Status im Plan |
|---|---|---|
| Q1 | `/web unlink` | **Verschoben** (eigener Konto-Management-Slice); kein `UNLINK`-Endpoint, kein Permission-Gate hier |
| Q2 | Ban-Interaktion | **Aufgelöst**: gesperrt = kein Server = kein `/web` → kein Web-Zugang (gewollt); kein Sonderpfad |
| Q3 | Name-Recycling | **Verschoben** (greift erst im Login-Slice); Regel „LOWER(name) + jüngster `last_seen`" in R5 fixiert, kein Code hier |
| Q4 | Passwort-Policy | **Aufgelöst**: 8..64 Zeichen, backend-autoritativ (`PasswordPolicy` in core-domain) |
| Q5 | Rate-Limit | **Aufgelöst**: Cooldown pro (uuid, purpose), Config-Key, Reports-Muster |
| Q6 | Token-Hygiene | **Aufgelöst**: Einlöse-Check ist die Grenze; optionaler Purge-Job (Hygiene) über bestehenden Scheduler |

## Phase 0 — Research

Siehe [research.md](./research.md): R1 state-stored-Begründung, R2 Token-in-DB (single-use/ein-pro-
Purpose), R3 Token-at-rest-Hashing (SHA-256, **bestätigt**), R4 PasswordHasher-Port + BCrypt-Impl-
Ort (`app`), R5 Name→UUID nur im Login-Slice, R6 Flyway V11, R7 kein Codec/PlatformProtocol-Eingriff,
R8 REST-Ressourcen & Status-Codes, R9 Cooldown-Semantik, R10 TTL-Config, R11 Purge-Hygiene, R12
Reuse-Inventar. **Keine offenen NEEDS CLARIFICATION** (alle Spec-Punkte beantwortet/verschoben).

## Phase 1 — Design & Contracts

- **Datenmodell** → [data-model.md](./data-model.md): `web_account`, `web_link_token`, `web_auth_audit`
  (V11-DDL-Skizze), Validierungsregeln, Indizes, **keine** State-Transitions (state-stored CRUD).
- **Contracts** → [contracts/rest-api.md](./contracts/rest-api.md) (Endpunkte + Status-Codes) und
  [contracts/protocol.md](./contracts/protocol.md) (DTOs + `WebAuthEndpoints`; **explizit: kein Codec/
  Channel, `PlatformProtocol.create()` unverändert**).
- **Agent-Context**: CLAUDE.md-Plan-Referenz zwischen den SPECKIT-Markern auf diesen Plan setzen.

### Test-Plan je Schicht (Definition of Done, Prinzip 22)

- **Domain (rein):** `PasswordPolicy` (7 Zeichen → Reject, 8 → ok, 64 → ok, 65 → Reject), `LinkToken.
  isExpired`, `TokenPurpose`-Branching-Invarianten.
- **Use-Case (Fakes):** `WebAuthService` — link bei vorhandenem Account → 409; reset ohne Account → 409;
  Cooldown-Ablehnung (429); redeem-LINK legt an + verbraucht Token; redeem-RESET ersetzt Passwort +
  verbraucht Token; redeem mit ungültigem/abgelaufenem Token → 410; redeem-Race (Account inzwischen
  da/weg) → 409; Passwort 8..64 enforced; Audit-Eintrag je erfolgreichem Redeem; **Klartext nie geloggt**.
- **jOOQ-Integration (Testcontainers):** `issue` macht DELETE-vor-INSERT (genau ein Token je uuid/purpose);
  `redeem` atomar (account + token-DELETE + audit in 1 TX), Replay → kein zweiter Effekt (Token weg →
  410); `expires_at`-Filter (abgelaufener Token nicht einlösbar, auch ohne Purge); FK-Ablehnung
  unbekannte UUID; SHA-256-Hash-Roundtrip (Rohtoken trifft gehashte Zeile); `deleteExpired` entfernt nur
  Abgelaufene.
- **Protocol (rein-JDK):** `WebAuthEndpoints` `EndpointDescriptor.expand({uuid})`/Typen; DTO-Form (keine
  Hash-/email-Felder). **Kein** Codec-Test (es gibt keinen).
- **E2E (`app`):** `/web link` → TokenResponse → `/api/web-auth/redeem` → `web_account` existiert mit
  BCrypt-Hash (kein Klartext in DB/Logs); zweiter `/web link` → 409; `resetPassword`-Pfad; 410 (alter/
  abgelaufener Token), 422 (zu kurzes/langes Passwort), 429 (Cooldown). JSON-Contract der DTOs
  (`@JsonTest`): exakte Feldnamen, kein Hash im Wire. Bestehende Slices unverändert grün (Regression).

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|---|---|---|
| Neue Dependency `spring-security-crypto` (in `app`) | Passwörter müssen mit anerkanntem, langsamem Salt-Hash (BCrypt) gespeichert werden (FR-020); JDK bietet keinen Passwort-Hash | Eigenbau (PBKDF2 von Hand) wäre fehleranfälliger; BCrypt ist Standard. Isoliert hinter `PasswordHasher`-Port, nur `app` sieht die Dep. |
