---
description: "Task list — JWT-Login-Session"
---

# Tasks: JWT-Login-Session (Web-Login gegen web_account)

**Input**: Design documents from `specs/004-jwt-login-session/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/

**Tests**: Tests sind hier **explizit gefordert** (DoD pro Schicht) — Test-Tasks sind enthalten.

**Organisation**: Phasen folgen der angeforderten Schicht-Reihenfolge (core-domain → application → infra-persistence →
JWT-Impl/Filter → api-rest → protocol-Publish). Innerhalb der Schichten sind Tasks mit der bedienten User Story
getaggt: **[US1] Login (P1)**, **[US2] Refresh+Rotation (P1)**, **[US3] Logout (P2)**.

**Reconciliation (bewusst):** (a) **Kein `infra-security`-Modul** — JWT-Impl in `app`, Filter + `SecurityFilterChain`
in `api-rest` (plan.md/R4). (b) **plugin-protocol-Quelle** (DTOs/Endpoints) liegt in Phase 2 (der Gradle-Reaktor muss
kompilieren); **`publishToMavenLocal` + Verifikation** sind die letzte Phase (wie Bridge/Permission). (c) **Flyway V12**
(V10/V11 belegt).

## Format: `[ID] [P?] [Story] Beschreibung mit Dateipfad`
- **[P]** = parallelisierbar (andere Datei, keine offene Abhängigkeit).

---

## Phase 1: Setup & Build (shared infrastructure)

**Purpose**: Dependencies, Migration, Config-Keys — Voraussetzung für jede Schicht.

- [X] T001 [P] jjwt in den Version-Catalog (`gradle/libs.versions.toml`) + `app/build.gradle.kts` aufnehmen (jjwt-api/impl/jackson, runtime). **DoD:** `./gradlew :app:dependencies` zeigt jjwt; `:app` kompiliert; jjwt taucht in keinem anderen Modul auf.
- [X] T002 [P] `spring-boot-starter-security` in `api-rest/build.gradle.kts` aufnehmen. **DoD:** `:api-rest` kompiliert; bestehende api-rest-Klassen unverändert.
- [X] T003 Flyway-Migration `infra-persistence/src/main/resources/db/migration/V12__refresh_token_schema.sql` anlegen (Tabelle `refresh_token` + `idx_refresh_token_player` + `idx_refresh_token_expires`, Schema laut data-model.md). **DoD:** jOOQ-Codegen erzeugt `Tables.REFRESH_TOKEN`; `./gradlew :infra-persistence:build` grün; **keine** bestehende Migration angefasst.
- [X] T004 [P] Session-Config-Keys in `app` (`application.yml`) ergänzen: `mcplatform.webauth.jwt.secret`, `access-ttl-minutes:15`, `refresh-ttl-days:30`, `cors.allowed-origin`, `refresh-cookie.{name,path}`, `purge-interval-ms`. **DoD:** App-Context lädt mit Defaults (Secret aus Env).

---

## Phase 2: Foundational (blocking prerequisites)

**Purpose**: Protocol-DTOs (für Kompilierung von api-rest/app), Domäne, Ports — von allen Stories geteilt. **Blockiert alle User-Story-Phasen.**

- [X] T005 [P] plugin-protocol-DTOs `LoginRequest`, `TokenPairResponse` in `plugin-protocol/src/main/java/com/mcplatform/protocol/webauth/`. **DoD:** reine Records, JDK-only; `TokenPairResponse` **ohne** refreshToken-Feld; `:plugin-protocol` kompiliert.
- [X] T006 `WebAuthEndpoints` um `LOGIN`/`REFRESH`/`LOGOUT` (`EndpointDescriptor`) erweitern in `plugin-protocol/.../webauth/WebAuthEndpoints.java`. **DoD:** kompiliert; **kein** Codec/Channel; `PlatformProtocol.create()` byte-identisch (verifiziert).
- [X] T007 [P] [US1] Contract-Test `WebAuthEndpointsTest` (rein-JDK) um LOGIN/REFRESH/LOGOUT erweitern (Methode/Pfad/Request-/Response-Typ, `expand`). **DoD:** `./gradlew :plugin-protocol:test` grün.
- [X] T008 [P] core-domain `RefreshToken` (Wertobjekt + `isExpired`/`isActive`/`isConsumed`) in `core-domain/src/main/java/com/mcplatform/domain/webauth/RefreshToken.java`. **DoD:** framework-frei, JWT-frei.
- [X] T009 [P] [US1] Domain-Test `RefreshTokenTest` (gültig/abgelaufen/rotiert/konsumiert, fixe `Instant`) in `core-domain/src/test/.../webauth/`. **DoD:** `./gradlew :core-domain:test` grün.
- [X] T010 [P] application-Ports `TokenIssuer` (`issue(uuid,ttl,now)→String`), `TokenVerifier` (`verify(token)→PlayerId`) in `application/src/main/java/com/mcplatform/application/webauth/port/`. **DoD:** nur JDK-Typen.
- [X] T011 application-Port `RefreshTokenRepository` (+ sealed `RotateResult`: Rotated/Invalid/Replay) in `application/.../webauth/port/RefreshTokenRepository.java`. **DoD:** Signaturen laut data-model.md; kompiliert.
- [X] T012 [P] application-Exceptions `InvalidCredentialsException`, `RefreshTokenInvalidException`, `RefreshTokenReuseException`, `AccessTokenInvalidException` in `application/.../webauth/port/`. **DoD:** kompiliert.
- [X] T013 [P] `WebAccountRepository`-Port additiv erweitern: `Optional<WebAccount> find(PlayerId)` in `application/.../webauth/port/WebAccountRepository.java`. **DoD:** kompiliert (Impl folgt in Phase US1).
- [X] T014 [P] `PlayerRepository`-Port additiv erweitern: `Optional<PlayerId> findUuidByName(String)` in `application/.../economy/port/PlayerRepository.java`. **DoD:** kompiliert.

**Checkpoint:** Domäne, Ports und Contract stehen — alle Stories können starten.

---

## Phase 3: User Story 1 — Login (P1) 🎯 MVP

**Goal:** Spieler loggt mit MC-Name + Passwort ein → Access-JWT + Refresh-Cookie. Identität via JWT-Filter; einheitlicher Fehler.
**Independent Test:** `web_account` via Bridge anlegen → Login mit korrektem Name+Passwort → Token-Paar; falsches Passwort/unbekannter Name/kein Account → einheitlicher 401; geschützter `/api/web/**`-Endpoint nur mit gültigem Access-Token (200) sonst 401.

- [X] T015 [US1] `WebSessionService.login(username, rawPassword)` in `application/.../webauth/WebSessionService.java` (findUuidByName → WebAccount.find → PasswordHasher.matches → TokenIssuer.issue → TokenGenerator.newToken + store + Audit LOGIN; jeder Fehlschlag → `InvalidCredentialsException`). **DoD:** nutzt nur wiederverwendete/neue Ports, keine eigene Hash-/Lookup-Logik.
- [X] T016 [US1] Application-Test `WebSessionServiceTest#login*` (Erfolg; einheitlicher Fehler bei unbekanntem Namen / fehlendem Account / falschem Passwort; **kein Klartext-Token** in Rückgabe) mit Fakes + fixem `Clock`. **DoD:** `./gradlew :application:test` grün.
- [X] T017 [P] [US1] `JooqPlayerRepository.findUuidByName` impl (`LOWER(name)` + `ORDER BY last_seen DESC LIMIT 1`, `idx_player_name_lower`) in `infra-persistence/.../JooqPlayerRepository.java` + jOOQ-Test (Mehrdeutigkeit → jüngster last_seen). **DoD:** Testcontainers-Test grün.
- [X] T018 [P] [US1] `JooqWebAccountRepository.find(PlayerId)` impl in `infra-persistence/.../JooqWebAccountRepository.java` + `JooqWebAccountRepositoryTest`-Erweiterung. **DoD:** Testcontainers-Test grün.
- [X] T019 [US1] `JooqRefreshTokenRepository` (`store`, `deleteByHash`, `purgeExpired`; SHA-256 via `JooqLinkTokenRepository.sha256Hex`; `store` schreibt Audit `LOGIN`, `deleteByHash` Audit `LOGOUT` falls Zeile vorhanden) in `infra-persistence/.../JooqRefreshTokenRepository.java` + `JooqRefreshTokenRepositoryTest` (insert, find-by-hash, `expires_at`-Filter, SHA-256-Roundtrip, **Assert `web_auth_audit`-Zeile `LOGIN`/`LOGOUT`** — nie Token/Hash im Audit). **DoD:** Testcontainers-Test grün.
- [X] T020 [US1] `JwtTokenService implements TokenIssuer, TokenVerifier` (jjwt, HS256, Secret aus Config) in `app/src/main/java/com/mcplatform/bootstrap/adapter/JwtTokenService.java` + Test (issue→verify-Roundtrip, abgelaufenes Token abgelehnt, manipulierte Signatur abgelehnt, Subject=UUID korrekt). **DoD:** Test grün; jjwt nur hier.
- [X] T021 [US1] `JwtAuthenticationFilter` (Bearer → `TokenVerifier` → `Authentication(PlayerId, leere Authorities)` in den SecurityContext; fehlt/ungültig → kein Principal) in `api-rest/.../api/rest/security/JwtAuthenticationFilter.java` + Filter-Test (gültig → Kontext gesetzt; fehlend/ungültig → kein Principal/401-Pfad). **DoD:** hängt nur am `TokenVerifier`-Port (kein jjwt); Test grün.
- [X] T022 [US1] `SecurityConfig` (`@EnableWebSecurity`, `SecurityFilterChain`: stateless, **csrf disabled**, `/api/web/**` → authenticated, `anyRequest` → permitAll, CORS-Bean mit Allowed-Origin) in `api-rest/.../api/rest/security/SecurityConfig.java`. **DoD:** Filter registriert; bestehende Pfade permitAll.
- [X] T023 [US1] `WebSessionController.login` (+ httpOnly/Secure/SameSite=Strict Refresh-Cookie, Path `/api/web-auth/session`) in `api-rest/.../api/rest/WebSessionController.java` + `WebAuthExceptionHandler` um 401 (`web_auth_invalid_credentials`) erweitern. **DoD:** kompiliert; Cookie-Attribute laut contracts/rest-api.md.
- [X] T024 [US1] app-Verdrahtung: `WebSessionConfig` (Beans `JwtTokenService`, `WebSessionService`, CORS/Cookie/TTL aus Config) + `PersistenceConfig` um `JooqRefreshTokenRepository`-Bean erweitern. **DoD:** App-Context lädt.
- [X] T025 [US1] E2E `WebLoginSliceTest` (`app`, MockMvc inkl. Security-Chain): **zuerst** einen **test-scoped** `@RestController` unter `/api/web/__probe` anlegen (nur im Testpfad, gibt die `@AuthenticationPrincipal PlayerId` als 200 zurück — Fixture, **nichts Produktives**); dann: Login → Token-Paar + Set-Cookie; Probe-Endpoint mit gültigem/fehlendem/abgelaufenem Access-Token → 200/401/401; einheitlicher 401 (kein Name-vs-Passwort-Leak); kein `web_account` → derselbe 401; JSON-Contract (kein refreshToken im Body); **SC-006 negativ belegen**: das dekodierte Access-JWT enthält **keine** Authority-/Rollen-Claims (nur Subject=UUID + exp). **DoD:** grün; Fixture nur test-scoped (kein Eintrag im Produktiv-Komponentenscan).

**Checkpoint:** Login + geschützter Zugriff funktionieren end-to-end — MVP lieferbar.

---

## Phase 4: User Story 2 — Refresh + Rotation (P1)

**Goal:** Session ohne Re-Login verlängern; Rotation; strikte Replay-Erkennung → all-player-Invalidierung.
**Independent Test:** Mit frischem Refresh-Cookie refreshen → neues Paar + altes invalidiert; abgelaufenes → Fehler; bereits rotiertes erneut → 401 + alle Sessions tot.

- [X] T026 [US2] `JooqRefreshTokenRepository.rotate(...)` atomar (eine TX: `SELECT … FOR UPDATE` → null=Invalid / `rotated_at!=null`=Replay (→`deleteAllForPlayer` + Audit `TOKEN_REUSE_DETECTED`) / expired=Invalid / aktiv → `rotated_at=now` + INSERT neu (`rotated_from`) + Audit `TOKEN_ROTATED`) + `deleteAllForPlayer` in `infra-persistence/.../JooqRefreshTokenRepository.java`. **DoD:** Testcontainers: Rotation-in-TX (alt konsumiert + neu aktiv), Replay-Erkennung (konsumiertes Token → alle Spieler-Tokens weg), `expires_at`-Filter, `FOR UPDATE`-Serialisierung, **Assert `web_auth_audit`-Zeilen `TOKEN_ROTATED` bzw. `TOKEN_REUSE_DETECTED`** (nie Token/Hash im Audit) — grün.
- [X] T027 [US2] `WebSessionService.refresh(rawRefresh)` in `application/.../webauth/WebSessionService.java` (rotate → Rotated: neues Access ausstellen + neues Refresh; Replay → `RefreshTokenReuseException`; Invalid → `RefreshTokenInvalidException`) + `WebSessionServiceTest#refresh*` (gültig→neues Paar+alt invalid; abgelaufen→Fehler; bereits rotiert→Kette tot+Fehler) mit Fakes. **DoD:** `:application:test` grün.
- [X] T028 [US2] `WebSessionController` um `POST /api/web-auth/session/refresh` erweitern (Refresh-Cookie lesen, Pflicht-Header `X-Refresh`, Cookie rotieren, Exceptions → 401 `web_auth_refresh_invalid` / `web_auth_session_revoked`, fehlender Header → 403) in `api-rest/.../WebSessionController.java`. **DoD:** kompiliert.
- [X] T029 [US2] E2E (in `WebLoginSliceTest` o. dediziert): Refresh → neues Paar + rotiertes Cookie; Replay des alten Refresh → 401 `web_auth_session_revoked` + alle Sessions tot; fehlender `X-Refresh` → 403. **DoD:** grün.

---

## Phase 5: User Story 3 — Logout (P2)

**Goal:** Aktuelle Session beenden (Minimal-Variante), idempotent.
**Independent Test:** Login → Logout → derselbe Refresh-Token kann kein neues Access mehr holen; Logout mit unbekanntem Token → 204.

- [X] T030 [US3] `WebSessionService.logout(rawRefresh)` (`deleteByHash`; idempotent; Audit `LOGOUT` falls vorhanden) in `application/.../webauth/WebSessionService.java` + `WebSessionServiceTest#logout*` (löscht vorhandenes; idempotent bei abwesend). **DoD:** `:application:test` grün.
- [X] T031 [US3] `WebSessionController` um `POST /api/web-auth/session/logout` (204, Cookie löschen `Max-Age=0`, Pflicht-Header `X-Refresh`) in `api-rest/.../WebSessionController.java`. **DoD:** kompiliert.
- [X] T032 [US3] E2E Logout: Login → Logout → Refresh mit demselben Token schlägt fehl; Logout mit unbekanntem Token → 204 (idempotent). **DoD:** grün.

---

## Phase 6: Cross-Cutting (D4-Reset, Hygiene, SC-007, Publish, Docs)

**Purpose**: Bridge-Touchpoint, Hygiene, Regression, Publikation, Dokumentation. Tasks mit [P] sind unabhängig.

- [X] T033 [P] **D4-Touchpoint**: in `JooqWebAccountRepository.resetPassword(...)` additive Zeile `DELETE FROM refresh_token WHERE player_uuid = uuid` (gleiche TX) in `infra-persistence/.../JooqWebAccountRepository.java` + Bridge-Regressionstest (RESET-Redeem löscht alle refresh_token der UUID; `PASSWORD_RESET`-Audit unverändert). **DoD:** Testcontainers-Test grün; einziger Eingriff in Bridge-Code.
- [X] T034 [P] `WebRefreshTokenPurge` (`@Scheduled` über bestehende `@EnableScheduling` → `purgeExpired`) in `app/.../bootstrap/` (Intervall aus Config). **DoD:** wiring; App-Context lädt.
- [X] T035 [P] JSON-Contract-Test `@JsonTest` für `LoginRequest`/`TokenPairResponse` (exakte Feldnamen, **refreshToken absent**) in `app/src/test/.../`. **DoD:** grün.
- [X] T036 **SC-007-Regression**: volle Suite — Economy/Punishment/Report/Permission/Web-Auth-Bridge-Slices unverändert grün unter der Security-Chain (permitAll für Alt-Endpoints, global csrf aus). **DoD:** `./gradlew build` grün.
- [X] T037 `./gradlew :plugin-protocol:publishToMavenLocal`; verifizieren: publizierter POM **ohne** `<dependencies>`; **`PlatformProtocol.create()` unangetastet**. **DoD:** publiziert; beide Checks bestanden.
- [X] T038 [P] `PROGRESS.md` (neuer Status-Abschnitt „JWT-Login-Session — sechstes Feature") + `FEATURE_INVENTORY.md`/Slice-Liste abhaken. **DoD:** Doku spiegelt den gebauten Stand.
- [X] T039 Finaler `./gradlew build` (Backend) inkl. jOOQ-Codegen; DoD-Checkliste aus `quickstart.md` komplett grün. **DoD:** grün.

---

## Dependencies & Story-Reihenfolge

- **Phase 1 → Phase 2 → (US1 → US2 → US3) → Phase 6.**
- **US1 (Login)** ist die MVP-Grundlage: sie zieht JWT-Impl, Filter, SecurityConfig und das Refresh-`store` ein, die US2/US3 voraussetzen.
- **US2 (Refresh)** hängt an US1 (Refresh-`store` + Controller + Filter) und ergänzt `rotate`.
- **US3 (Logout)** hängt an US1 (Refresh-`store`) und nutzt `deleteByHash`.
- **D4 (T033)** hängt nur an V12 (T003) + `deleteAllForPlayer`-Logik (Teil T026) — kann nach US2 laufen.
- **T037 (publish)** zuletzt; **T036/T039** Gates.

## Parallel-Beispiele
- **Phase 1:** T001, T002, T004 parallel; T003 separat (Codegen).
- **Phase 2:** T005, T008, T010, T012, T013, T014 parallel (verschiedene Dateien/Module); T006 vor T007.
- **US1:** T017/T018 parallel (verschiedene Repos); T020 (app) parallel zu T017/T018; T021→T022→T023 sequenziell (Security-Kette).
- **Phase 6:** T033/T034/T035/T038 parallel; T036→T037→T039 sequenziell (Gates).

## MVP-Scope
**Nur User Story 1 (Login, P1)** = lauffähiger MVP: Einloggen + geschützter Zugriff via Access-Token. US2 (Refresh) macht die Session langlebig; US3 (Logout) ist Komfort. D4/Hygiene/Publish runden ab.

## Test-Übersicht (DoD pro Schicht)
- **Domain:** `RefreshTokenTest` (T009).
- **Application (Fakes + Clock):** `WebSessionServiceTest` login/refresh/logout (T016/T027/T030).
- **infra-persistence (Testcontainers):** `JooqRefreshTokenRepositoryTest` (T019/T026), `JooqPlayerRepositoryTest`+ (T017), `JooqWebAccountRepositoryTest`+ (T018/T033).
- **JWT:** `JwtTokenService`-Roundtrip/Expiry/Tamper (T020); `JwtAuthenticationFilter`-Test (T021).
- **api-rest/E2E:** `WebLoginSliceTest` (T025/T029/T032), SC-007-Regression (T036).
- **Contract:** `WebAuthEndpointsTest` (T007), `@JsonTest` DTO-Roundtrip (T035).
