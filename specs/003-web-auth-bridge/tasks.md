---
description: "Task list — Web-Auth-Bridge"
---

# Tasks: Web-Auth-Bridge

**Input**: Design documents from `/specs/003-web-auth-bridge/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/

**Tests**: Eingeschlossen (vom Nutzer angefordert) — Schichttests als Definition of Done je Task.

**Repos**: Tasks ohne Präfix liegen im **Backend-Repo** (`mc-platform-backend`). Tasks mit `[PLUGIN]`
liegen im **separaten Plugin-Repo** (`mc-platform-plugin`) und stehen bewusst am Ende.

**Schichtreihenfolge** (wie bei Economy/Punishments/Reports): core-domain → application/Ports →
infra-persistence → api-rest → plugin-protocol (+ `publishToMavenLocal`) → Plugin.

## Format: `[ID] [P?] [Story] Beschreibung mit Dateipfad`

- **[P]**: parallelisierbar (andere Datei, keine offene Abhängigkeit)
- **[Story]**: `[US1]` (Account anlegen, P1) / `[US2]` (Passwort zurücksetzen, P2)
- **DoD global**: `./gradlew build` grün; keine generische Klasse geändert (`PlatformProtocol.create()`
  byte-identisch, `protocol/core` unberührt); bei protocol-Änderung `:plugin-protocol:publishToMavenLocal`
  (POM ohne `<dependencies>`); am Ende PROGRESS.md + FEATURE_INVENTORY.md nachziehen.

---

## Phase 1: Setup (Shared, Backend)

- [X] T001 [P] `spring-security-crypto` als Dependency **nur** in `app/build.gradle.kts` ergänzen (Version
  über die bestehende Spring-Boot-BOM, kein expliziter Pin); Version-Catalog-Eintrag falls nötig.
  **DoD**: `./gradlew :app:dependencies` löst auf; `infra-persistence` bleibt unverändert (Spring-frei).
- [X] T002 [P] Config-Keys in `app/src/main/resources/application.yml`: `mcplatform.webauth.token-ttl-minutes: 10`,
  `mcplatform.webauth.token-cooldown-seconds: 60`. **DoD**: App startet, Werte injizierbar.

---

## Phase 2: Foundational (Shared, Backend) — blockiert US1 & US2

**⚠️ Muss komplett sein, bevor US1/US2 starten.** Reihenfolge core → application → infra → protocol → app.

### core-domain (framework-frei)

- [X] T003 [P] `TokenPurpose`-Enum (`LINK`|`RESET`) in `core-domain/.../domain/webauth/TokenPurpose.java`.
- [X] T004 [P] `WebAccount`-Record (`playerUuid`, `passwordHash`, `createdAt`, `passwordUpdatedAt`) in
  `core-domain/.../domain/webauth/WebAccount.java`.
- [X] T005 [P] `LinkToken`-Record (`playerUuid`, `purpose`, `expiresAt`, `createdAt`) + `isExpired(Instant)`
  in `core-domain/.../domain/webauth/LinkToken.java`.
- [X] T006 [P] `PasswordPolicy` (rein: `validate(raw)` → 8..64 Zeichen, sonst `InvalidPasswordException`)
  + `InvalidPasswordException` in `core-domain/.../domain/webauth/`.
- [X] T007 [P] `PasswordHasher`-**Port** (`String hash(String)`, `boolean matches(String,String)`) in
  `core-domain/.../domain/webauth/PasswordHasher.java`.
- [X] T008 [P] `TokenGenerator`-**Port** (`String newToken()`) in
  `core-domain/.../domain/webauth/TokenGenerator.java`.
- [X] T009 [P] **Domain-Unit-Tests** in `core-domain/src/test/.../webauth/`: `PasswordPolicyTest`
  (7→Reject, 8→ok, 64→ok, 65→Reject), `LinkTokenTest` (`isExpired`-Grenzen mit fixem `Instant`).
  **DoD**: `./gradlew :core-domain:test` grün.

### application (Use Cases + Ports)

- [X] T010 [P] Ports `WebAccountRepository` (`boolean exists(UUID)`; `RedeemOutcome redeem(String rawToken,
  String passwordHash, Instant now)`) und `LinkTokenRepository` (`void issue(String rawToken, UUID, TokenPurpose,
  Instant expiresAt, Instant now)`; `Optional<Instant> lastCreatedAt(UUID, TokenPurpose)`; `int deleteExpired(Instant)`)
  + `RedeemOutcome`-Enum in `application/.../application/webauth/port/`.
- [X] T011 [P] Application-Exceptions `WebAccountExistsException`, `WebAccountMissingException`,
  `WebAccountConflictException`, `TokenInvalidException`, `WebAuthCooldownException` in
  `application/.../application/webauth/port/`.
- [X] T012 `WebAuthService`-Skelett + **gemeinsamer Redeem-Pfad** (`redeem(token,password)`:
  `PasswordPolicy.validate` → `hasher.hash` → `accountRepo.redeem`; mappt `RedeemOutcome`) in
  `application/.../application/webauth/WebAuthService.java`. Konstruktor: beide Ports, `PasswordHasher`,
  `TokenGenerator`, `Clock`, `Duration cooldown`, `Duration ttl`. (depends T010, T011, T003–T008)
- [X] T013 [P] **Test-Fakes** (In-Memory `WebAccountRepository`/`LinkTokenRepository`, deterministischer
  `PasswordHasher`/`TokenGenerator`) in `application/src/test/.../webauth/`.

### infra-persistence (jOOQ + Flyway)

- [X] T014 Flyway-Migration `V11__web_auth_schema.sql` (Tabellen `web_account`, `web_link_token` mit
  `uq_web_link_token_uuid_purpose` + `idx_web_link_token_expires`, `web_auth_audit`; FKs auf `player(uuid)`) in
  `infra-persistence/src/main/resources/db/migration/`. **Keine** bestehende Migration ändern.
  **DoD**: jOOQ-Codegen erzeugt die drei Tabellen.
- [X] T015 `JooqLinkTokenRepository` (`issue` = `DELETE WHERE player_uuid,purpose` → `INSERT`, 1 TX;
  `findActive(tokenHash, now)`; `lastCreatedAt`; `deleteExpired`; **SHA-256**-Hashing des Rohtokens via
  JDK `MessageDigest`) in `infra-persistence/.../persistence/JooqLinkTokenRepository.java`. (depends T010, T014)
- [X] T016 `JooqWebAccountRepository` (`exists`; `redeem` = **1 TX**: `SELECT … FOR UPDATE WHERE token_hash AND
  expires_at>now` → leer ⇒ `TokenInvalidException`; **gemeinsamer Teil**: Token-`DELETE` + `web_auth_audit`-INSERT;
  Purpose-Branch-Dispatch noch ohne Zweige) in `infra-persistence/.../persistence/JooqWebAccountRepository.java`.
  (depends T010, T014, T015)
- [X] T017 [P] **Infra-Test** `JooqLinkTokenRepositoryTest` (Testcontainers): `issue` DELETE-vor-INSERT
  (genau ein Token je uuid/purpose), `expires_at`-Filter, `deleteExpired` nur Abgelaufene, SHA-256-Roundtrip
  (Rohtoken trifft gehashte Zeile), FK-Ablehnung unbekannte UUID. **DoD**: `:infra-persistence:test` grün.

### plugin-protocol (JDK-only) + Publish

- [X] T018 [P] DTOs `TokenResponse(String token, String purpose, long expiresAtEpochMilli)` und
  `RedeemRequest(String token, String password)` in `plugin-protocol/.../protocol/webauth/`. **Kein** Hash,
  **kein** email/username.
- [X] T019 [P] `WebAuthEndpoints` (`REQUEST_LINK`, `REQUEST_RESET`, `REDEEM` via `EndpointDescriptor`) in
  `plugin-protocol/.../protocol/webauth/WebAuthEndpoints.java`. **Kein** Codec/Channel.
- [X] T020 [P] **Protocol-Test** `WebAuthEndpointsTest` (rein-JDK): `expand({uuid})` füllt korrekt, Methoden/
  Request-/Response-Typen stimmen. **DoD**: `:plugin-protocol:test` grün.
- [X] T021 `:plugin-protocol:publishToMavenLocal` ausführen; **DoD**: publizierter POM **ohne**
  `<dependencies>`; `PlatformProtocol.java` byte-identisch (kein Codec ergänzt). (depends T018, T019)

### app (Adapter + Composition Root + Redeem-Endpoint)

- [X] T022 [P] `BCryptPasswordHasher implements PasswordHasher` (spring-security-crypto `BCryptPasswordEncoder`)
  in `app/.../bootstrap/adapter/BCryptPasswordHasher.java` — **einzige** Stelle mit der Krypto-Dependency.
- [X] T023 [P] `SecureRandomTokenGenerator implements TokenGenerator` (≥128 Bit, URL-safe Base64,
  `java.security.SecureRandom`) in `app/.../bootstrap/adapter/SecureRandomTokenGenerator.java`.
- [X] T024 `PersistenceConfig` um `@Bean WebAccountRepository` + `@Bean LinkTokenRepository` ergänzen
  (additiv) in `app/.../bootstrap/config/PersistenceConfig.java`. (depends T015, T016)
- [X] T025 `WebAuthConfig` (`@Bean WebAuthService` mit Cooldown/TTL aus Config + bestehender `Clock`-Bean;
  `@Bean PasswordHasher`, `@Bean TokenGenerator`) in `app/.../bootstrap/config/WebAuthConfig.java`.
  (depends T012, T022, T023, T024)

### api-rest (Redeem-Endpoint + Mapping, von beiden Stories geteilt)

- [X] T026 [P] `WebAuthExceptionHandler` (NUR eigene Codes: 409 `web_account_exists`/`web_account_missing`/
  `web_account_conflict`, 410 `web_auth_token_invalid`, 422 `password_invalid`, 429 `web_auth_cooldown`;
  **400 NICHT** re-deklarieren) in `api-rest/.../api/rest/WebAuthExceptionHandler.java`.
- [X] T027 [P] `WebAuthMapper` (domain ↔ DTO, epoch-milli) in `api-rest/.../api/rest/support/WebAuthMapper.java`.
- [X] T028 `WebAuthController`-Skelett + **Redeem-Endpoint** `POST /api/web-auth/redeem` (→ 204) in
  `api-rest/.../api/rest/WebAuthController.java`. (depends T025, T026, T027)

**Checkpoint**: Geteilter Stack steht (Domain, Ports, Repos, Migration, Protocol publiziert, Wiring,
Redeem-Endpoint). US1/US2 ergänzen nur ihre Request-Endpoints + Redeem-Zweige.

---

## Phase 3: User Story 1 — Erstmals Web-Account anlegen (P1) 🎯 MVP

**Goal**: `/web link` → Token → Web-Redeem → `web_account` mit gesetztem Passwort.
**Independent Test**: Spieler ohne Account: `link-token` (200) → `redeem` (204) → Account existiert; zweiter
`link-token` → 409. Vollständig ohne `reset-token`-Endpoint testbar.

- [X] T029 [US1] `requestLinkToken(UUID)` in `WebAuthService` (Vorbedingung: `!exists` sonst
  `WebAccountExistsException` 409; Cooldown via `lastCreatedAt(uuid, LINK)` sonst `WebAuthCooldownException` 429;
  `issue` LINK-Token; gibt `TokenResult` zurück) in `application/.../application/webauth/WebAuthService.java`.
- [X] T030 [US1] **Redeem-LINK-Zweig** in `JooqWebAccountRepository.redeem`: bei `purpose=LINK`
  `INSERT web_account … ON CONFLICT (player_uuid) DO NOTHING` (0 Zeilen ⇒ `WebAccountConflictException` 409);
  Audit `ACCOUNT_CREATED`. In `infra-persistence/.../persistence/JooqWebAccountRepository.java`.
- [X] T031 [US1] Endpoint `POST /api/players/{uuid}/web-auth/link-token` (→ `TokenResponse`) in
  `api-rest/.../api/rest/WebAuthController.java`. (depends T029)
- [X] T032 [P] [US1] `WebAuthServiceTest` (Fakes): link ohne Account ok; Account existiert → 409; Cooldown → 429;
  redeem-LINK legt an + verbraucht Token; Replay (Token weg) → 410; Passwort 8..64 enforced; **kein
  Klartext-Log**. In `application/src/test/.../webauth/`.
- [X] T033 [P] [US1] `JooqWebAccountRepositoryTest` (Testcontainers): redeem-LINK atomar (account + token-DELETE +
  audit in 1 TX), `ON CONFLICT` → 409, Replay (Token-Zeile weg) → 410. In `infra-persistence/src/test/.../`.
- [X] T034 [US1] **E2E** `WebAuthLinkVerticalSliceTest` in `app/src/test/...`: `link-token` → `redeem` →
  `web_account` mit BCrypt-Hash (kein Klartext in DB/Logs), `web_auth_audit` = `ACCOUNT_CREATED`; zweiter
  `link-token` → 409; alter/abgelaufener Token → 410; Passwort <8/>64 → 422; Cooldown → 429. Plus DTO-`@JsonTest`
  (Feldnamen exakt, **kein** Hash/email im Wire). **DoD**: `./gradlew build` grün.

**Checkpoint**: MVP — Account-Anlage funktioniert und ist unabhängig demonstrierbar.

---

## Phase 4: User Story 2 — Passwort zurücksetzen / Recovery (P2)

**Goal**: `/web resetPassword` → Token → Web-Redeem überschreibt das Passwort.
**Independent Test**: Spieler mit Account: `reset-token` (200) → `redeem` (204) → `password_updated_at` neu;
`reset-token` ohne Account → 409.

- [X] T035 [US2] `requestResetToken(UUID)` in `WebAuthService` (Vorbedingung: `exists` sonst
  `WebAccountMissingException` 409; Cooldown via `lastCreatedAt(uuid, RESET)` → 429; `issue` RESET-Token) in
  `application/.../application/webauth/WebAuthService.java`.
- [X] T036 [US2] **Redeem-RESET-Zweig** in `JooqWebAccountRepository.redeem`: bei `purpose=RESET`
  `UPDATE web_account SET password_hash=?, password_updated_at=now() WHERE player_uuid=?` (0 Zeilen ⇒
  `WebAccountConflictException` 409); Audit `PASSWORD_RESET`. In `infra-persistence/.../JooqWebAccountRepository.java`.
- [X] T037 [US2] Endpoint `POST /api/players/{uuid}/web-auth/reset-token` (→ `TokenResponse`) in
  `api-rest/.../api/rest/WebAuthController.java`. (depends T035)
- [X] T038 [P] [US2] `WebAuthServiceTest` (Fakes) erweitern: reset mit Account ok; ohne Account → 409; Cooldown →
  429; redeem-RESET überschreibt + verbraucht Token. In `application/src/test/.../webauth/`.
- [X] T039 [P] [US2] `JooqWebAccountRepositoryTest` erweitern (Testcontainers): redeem-RESET ersetzt Hash +
  bumpt `password_updated_at`; Account inzwischen weg → 409. In `infra-persistence/src/test/.../`.
- [X] T040 [US2] **E2E** erweitern (`WebAuthResetVerticalSliceTest` oder im bestehenden Slice): `reset-token` →
  `redeem` überschreibt Passwort + Audit `PASSWORD_RESET`; `reset-token` ohne Account → 409. **DoD**: `./gradlew
  build` grün.

**Checkpoint**: US1 + US2 funktionieren unabhängig.

---

## Phase 5: Polish & Cross-Cutting (Backend)

- [X] T041 [P] **Optional/Hygiene** — Token-Purge: `@Scheduled`-Methode (über bestehende `@EnableScheduling` in
  `SchedulingConfig`) ruft `LinkTokenRepository.deleteExpired(now)`; Intervall als Config. + Test, dass nur
  Abgelaufene entfernt werden. Klar als **nicht sicherheitskritisch** kennzeichnen. (darf entfallen/verschoben werden)
- [X] T042 [P] PROGRESS.md: Abschnitt „Web-Auth-Bridge — fünftes Feature" (state-stored, kein Live-Pfad, kein
  Codec, V11, BCrypt in `app`, SHA-256-Token-at-rest) nachziehen.
- [X] T043 [P] FEATURE_INVENTORY.md: Greenfield-Infra-Notiz (kein Alt-Vorgänger; Web-Account-Bindung neu).
- [X] T044 Voller `./gradlew build` grün; **bestätigen**: `PlatformProtocol.create()` unverändert,
  `protocol/core` unberührt, POM ohne `<dependencies>`, `infra-persistence` Spring-frei. (depends alle Backend-Tasks)

---

## Phase 6: Plugin-Repo `mc-platform-plugin` (SEPARATES REPO — anderer Arbeitskontext)

> Erst nach T021 (`publishToMavenLocal`) im Backend. Kein Backend-Build hängt davon ab.

- [ ] T045 [P] [PLUGIN] `./gradlew build --refresh-dependencies` im Plugin-Repo (zieht das neue
  `plugin-protocol` mit `webauth`-Endpoints/DTOs). **DoD**: Build sieht `WebAuthEndpoints`.
- [ ] T046 [PLUGIN] `feature.web`-Modul: implementiert das `Feature`-Interface, registriert sich in der
  `FeatureRegistry` (ein Anstecken; **kein** Transport-/EventBus-Eingriff).
- [ ] T047 [PLUGIN] Commands `/web link` und `/web resetPassword`: rufen `WebAuthEndpoints.REQUEST_LINK`/
  `REQUEST_RESET` über den generischen `BackendClient` (UUID des ausführenden Spielers als Pfadvariable);
  bauen aus `TokenResponse.token` eine **anklickbare Adventure-Component** (`open_url`). **Kein** `/web unlink` (Q1).
- [ ] T048 [PLUGIN] Plugin-Tests: `BackendClient` baut die `web-auth`-URLs korrekt aus `EndpointDescriptor.expand`;
  Command-Flow (Erfolg + Ablehnungen). **DoD**: Plugin-`./gradlew build` grün.

---

## Dependencies & Execution Order

- **Phase 1 (Setup)** → **Phase 2 (Foundational)** blockiert alle Stories.
- **US1 (Phase 3)** und **US2 (Phase 4)** hängen beide nur an Phase 2; US2 ist unabhängig von US1
  testbar (eigener Endpoint + eigener Redeem-Zweig; gemeinsame Methode wird additiv erweitert).
- **Phase 5 (Polish)** nach US1 (+ US2, falls gewünscht).
- **Phase 6 (Plugin)** nach T021 (Publish); separates Repo, ganz am Ende.

### Innerhalb Phase 2 (Schichtordnung, teils parallel)

- core-domain T003–T009 [P] zuerst → application T010–T013 → infra T014→T015→T016, T017 [P] →
  protocol T018/T019 [P] → T020 [P] → T021 → app T022/T023 [P] → T024 → T025 → api-rest T026/T027 [P] → T028.

### Parallel-Beispiele

```bash
# core-domain (alle unabhängige Dateien):
T003 TokenPurpose | T004 WebAccount | T005 LinkToken | T006 PasswordPolicy | T007 PasswordHasher | T008 TokenGenerator
# US1-Tests parallel:
T032 WebAuthServiceTest(link) | T033 JooqWebAccountRepositoryTest(redeem-LINK)
```

## Implementation Strategy

- **MVP** = Phase 1 + 2 + **US1** (T001–T034): Spieler können ingame einen Web-Account anlegen. Stop &
  Validate, optional schon deploybar.
- **Inkrement** = + **US2** (Recovery), dann Polish, dann Plugin-Slice.
- **Reihenfolge-Disziplin** (Constitution §20): ein Feature komplett durch alle Schichten + Tests grün,
  bevor das nächste startet; Backend-Slice vor Plugin-Slice.

## Notes

- Tests sind eingeschlossen (angefordert) und Teil der DoD je Task/Schicht.
- `[P]` = andere Datei, keine offene Abhängigkeit.
- Geteilter Code wird nur additiv berührt (`PersistenceConfig`-Beans, neue `WebAuthConfig`, neue
  protocol-Klassen). `PlatformProtocol.create()` bleibt unverändert — bei jedem Build bestätigen.
- Entschiedene Plan-Punkte (2026-06-24): **R3** SHA-256-Token-at-rest **bestätigt**, **R4** BCrypt-Impl
  in `app` **bestätigt**, Flyway **V11** (faktische Korrektur). Keine offenen Entscheidungen mehr.
