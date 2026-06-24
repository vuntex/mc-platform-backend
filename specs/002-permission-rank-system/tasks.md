---
description: "Task list for Permission-/Rank-System (Foundation, Phase 1)"
---

# Tasks: Permission-/Rank-System (Foundation, Phase 1)

**Input**: Design documents from `/specs/002-permission-rank-system/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/permission-contracts.md

**Tests**: Test-Tasks sind enthalten (explizit angefordert). Schwerpunkte: Union über mehrere Ränge,
abgelaufener Rang fällt raus, Wildcard-Matching, Darstellungs-Auswahl, Live-Ablauf = genau ein Event,
bestehende Konsumenten grün.

## Ordering-Hinweis (Constitution „erst lauffähig, dann in die Breite")

Dieses Foundation-Feature wird **schichtweise** gebaut (core-domain → application → infra → protocol →
api-rest/Composition), nicht pro User-Story — die Stories teilen sich denselben Kern. **Jede Phase
endet mit grünen Tests, bevor die nächste beginnt.** Story-Labels (`[US1]`..`[US5]`) dienen der
Rückverfolgbarkeit zur spec.md; schichtübergreifende Infra-/Protocol-Tasks tragen kein Story-Label.

**Abweichung von der wörtlichen Reihenfolge**: `plugin-protocol` steht VOR `api-rest`, weil sowohl die
Controller (DTOs/Endpoints) als auch der `app`-Publisher (Event/Codec) gegen die Protocol-Klassen
kompilieren. `publishToMavenLocal` (Handoff ins separate Plugin-Repo) bleibt letzter Schritt.

**Story-Mapping**: US1 = effektive Auflösung · US2 = Rollen-CRUD/Config · US3 = Rang-Grants ·
US4 = Permission-Grants · US5 = Live-Entzug.

---

## Phase 1: Setup

- [x] T001 Baseline verifizieren: `./gradlew build` grün auf Branch `002-permission-rank-system` (Ausgangszustand festhalten, bevor Code entsteht).

---

## Phase 2: core-domain — reiner Auflösungskern (framework-frei) 🎯 MVP-Kern

**Goal**: Die testbare Wahrheit der Permission-Welt: Union, Wildcards, `isActive`, Anzeige-Tie-Break —
ohne jOOQ/Spring. **Independent Test**: ausschließlich `./gradlew :core-domain:test`.

- [x] T002 [P] [US1] `RoleId` (Value Object über `long`) in `core-domain/src/main/java/com/mcplatform/domain/permission/RoleId.java`
- [x] T003 [P] [US1] `Role` (Name + Darstellungsfelder, `weight`, `teamRank`, `active`, `isDefault`) in `core-domain/.../domain/permission/Role.java`
- [x] T004 [P] [US3] `RoleGrant` mit `isActive(Instant now)` in `core-domain/.../domain/permission/RoleGrant.java`
- [x] T005 [P] [US4] `PermissionGrant` mit `isActive(Instant now)` in `core-domain/.../domain/permission/PermissionGrant.java`
- [x] T006 [P] [US1] `PermissionMatcher.matches(Collection<String>, String)` — `*`, `feature.*`-Präfix, exakt, KEINE Negation, in `core-domain/.../domain/permission/PermissionMatcher.java`
- [x] T007 [US1] `EffectivePermissions` — Union aktiver Rollen-Permissions + Default-Fallback (keine aktive Mitgliedschaft) + aktive direkte Grants; `allows(String)` delegiert an `PermissionMatcher`; in `core-domain/.../domain/permission/EffectivePermissions.java` (hängt an T003–T006)
- [x] T008 [P] [US3] `RankDisplay.choose(Collection<Role>)` — Tie-Break `teamRank` desc → `weight` desc → `RoleId` asc; Default-Rolle bei leerer aktiver Menge; in `core-domain/.../domain/permission/RankDisplay.java`
- [x] T009 [P] [US2] `RoleValidationException` + `InvalidGrantException` in `core-domain/.../domain/permission/`
- [x] T009a [P] [US5] `PermissionChangeType`-Enum (`GRANT_ADDED|GRANT_REVOKED|GRANT_EXPIRED|ROLE_CONFIG_CHANGED`) — **Domänentyp**, damit die `application`-Schicht `plugin-protocol`-frei bleibt; in `core-domain/.../domain/permission/PermissionChangeType.java`

### Tests (zuerst schreiben, müssen fehlschlagen)

- [x] T010 [P] [US1] `PermissionMatcherTest` — Wildcard-Matching: `*` matcht alles; `report.*` matcht `report.view`/`report.x.y`, nicht `reporting`; exakter Treffer; Nicht-Treffer; kein Negations-Effekt. (`core-domain/src/test/.../permission/PermissionMatcherTest.java`)
- [x] T011 [P] [US1] `EffectivePermissionsTest` — **Union über mehrere aktive Ränge** (Premium+Epic → beide Permission-Mengen); **Default-Fallback** ohne Grant; rein additiv (keine implizite Zusatz-Permission). 
- [x] T012 [P] [US3] `RoleGrantTest`/`PermissionGrantTest` — **abgelaufener Grant fällt aus `isActive(now)`**; permanent (`expiresAt=null`) bleibt aktiv; `expiresAt` in Vergangenheit = sofort inaktiv.
- [x] T013 [P] [US3] `RankDisplayTest` — **Team-Flag schlägt weight**; bei gleichem `teamRank` höchstes `weight`; bei gleichem `weight` kleinste `RoleId`; Default bei leerer Menge.
- [x] T014 [US1] **PHASE-GATE**: `./gradlew :core-domain:test` grün.

**Checkpoint**: Reiner Kern bewiesen — Auflösungslogik unabhängig von Persistenz testbar.

---

## Phase 3: application — Use Cases + Ports (mit Fakes)

**Goal**: Schreibpfade (Rollen-CRUD/Config, Grants) gaten **vor** dem Schreiben via Port
(`PunishmentService`-Muster); Sweep-Logik für Live-Ablauf. **Independent Test**:
`./gradlew :application:test` (In-Memory-Fakes der Ports).

- [x] T015 [P] `RoleRepository`-Port in `application/src/main/java/com/mcplatform/application/permission/port/RoleRepository.java`
- [x] T016 [P] `PlayerGrantRepository`-Port (Rang- + Permission-Grants, Upsert, set-inactive, find-expired, holders-of-role) in `application/.../permission/port/PlayerGrantRepository.java`
- [x] T017 [P] `GrantAuditPort` (append GRANT/REVOKE/EXPIRE) in `application/.../permission/port/GrantAuditPort.java`
- [x] T018 [P] `PermissionChangePublisher`-Port (`publish(UUID player, PermissionChangeType type)` — Domänentyp aus T009a, **kein** protocol-Import) in `application/.../permission/port/PermissionChangePublisher.java`
- [x] T019 [P] App-Exceptions `RoleNotFoundException`, `RoleNameConflictException`, `DefaultRoleProtectedException` in `application/.../permission/port/`
- [x] T020 [US2] `PermissionAdminService` — Rollen-CRUD + `role_permission`-Config; Permission-Check vor Schreiben; Default-Rolle nicht löschbar/deaktivierbar; case-insensitive Namens-Duplikat; **kaskadierender Löschpfad (FR-012a)**; in `application/.../permission/PermissionAdminService.java` (hängt an T015–T019, core-domain). Permission-Konstanten (`permission.role.create/edit/delete`, `permission.grant.role`, `permission.grant.permission`, `permission.read`).
- [x] T021 [US3] `PermissionAdminService.grantRole/revokeRole` — **Upsert: max. eine aktive Zeile je (uuid, role), permanent schlägt befristet (FR-014a)**; mehrere distinkte Ränge koexistieren; GRANT/REVOKE-Audit; Publish (gleiche Datei wie T020)
- [x] T022 [US4] `PermissionAdminService.grantPermission/revokePermission` — direkte Grants, gleiche Audit-/Publish-Mechanik (gleiche Datei)
- [x] T023 [US1] `PermissionQueryService` — effektive Permissions + Anzeige-Wahl für eine UUID (nutzt `EffectivePermissions`/`RankDisplay`; berücksichtigt nur **aktive** Rollen, FR-007a) in `application/.../permission/PermissionQueryService.java`
- [x] T024 [US5] `GrantExpiryService.sweep()` — findet abgelaufene aktive Grants, setzt inaktiv, schreibt `grant_audit(EXPIRE)` (Sentinel-UUID), publiziert **pro betroffener UUID genau ein Event**; in `application/.../permission/GrantExpiryService.java`
- [x] T025 [US5] Rollen-Permission-Änderung publiziert **ein Event je aktivem Halter** der Rolle (in `PermissionAdminService`, nutzt `PlayerGrantRepository.holdersOf(roleId)`)

### Tests (Fakes)

- [x] T026 [P] [US2] `PermissionAdminServiceTest` — Rollen-CRUD; **403-Pfad** (`PermissionDeniedException`, wenn Akteur die Gate-Permission fehlt); Default-Rolle geschützt; Namens-Duplikat → Conflict.
- [x] T027 [P] [US2] Kaskaden-Lösch-Test (FR-012a): Löschen einer Rolle entzieht allen Haltern den Grant (REVOKE-Audit); Spieler ohne Restgrant → Default-Fallback.
- [x] T028 [P] [US3] Grant-Upsert-Test (FR-014a): zweiter Grant derselben Rolle aktualisiert **eine** Zeile (permanent schlägt befristet); zwei verschiedene Ränge bleiben parallel aktiv.
- [x] T029 [P] [US4] Direkter Permission-Grant-Test: Grant/Revoke wirkt; Audit korrekt.
- [x] T030 [P] [US5] `GrantExpiryServiceTest` — **Live-Ablauf löst genau ein Pub/Sub-Event pro betroffener UUID aus** (Fake-Publisher zählt); EXPIRE-Audit mit Sentinel-UUID; bereits inaktive Grants lösen kein erneutes Event aus.
- [x] T031 [P] [US5] Rollen-Config-Änderung → genau ein Event je aktivem Halter.
- [x] T032 [US1] **PHASE-GATE**: `./gradlew :application:test` grün.

**Checkpoint**: Use Cases inkl. Gating, Upsert, Kaskade und Live-Push gegen Fakes bewiesen.

---

## Phase 4: infra-persistence — Flyway V9 + jOOQ + Testcontainers ⚠️ KRITISCHER CHECKPOINT

**Goal**: Echte Persistenz + der **port-berührende** Resolver-Umbau. **Independent Test**:
Testcontainers-Postgres.

- [x] T033 V9-Migration `infra-persistence/src/main/resources/db/migration/V9__permission_schema.sql` — Tabellen `role`, `role_permission`, `player_role_grant`, `player_permission_grant`, `grant_audit` (+ Indizes, partielles Default-Unique, case-insensitive Namens-Unique); **Seed** DEFAULT (leer)/MODERATOR/ADMIN; **Migration** der `team_role_permission`-Seeds nach `role_permission`; **DROP** `team_role_member` + `team_role_permission` (siehe data-model.md).
- [x] T034 ⚠️ **[KRITISCH] [US1] Resolver-Umbau** — `JooqPermissionResolver` neu: Union über aktive Rang-Grants (`grant.active AND (expires_at IS NULL OR expires_at > now())`, **JOIN nur auf `role.active = true`** — FR-007a) + Default-Rollen-Fallback + aktive direkte Permission-Grants + Wildcard-SQL (`= query OR = '*' OR (perm LIKE '%.*' AND query startsWith prefix)`). **Port-Signatur `hasPermission(UUID,String)` byte-identisch.** (`infra-persistence/.../persistence/JooqPermissionResolver.java`)
- [x] T035 [P] `JooqRoleRepository` (Rollen-CRUD + `role_permission`-Config) in `infra-persistence/.../persistence/JooqRoleRepository.java`
- [x] T036 [P] `JooqPlayerGrantRepository` (Grants-Upsert via `ON CONFLICT`, set-inactive, find-expired, holdersOf + `grant_audit`-Writes) in `infra-persistence/.../persistence/JooqPlayerGrantRepository.java`
- [x] T037 Neue Repos als `@Bean` in `app/src/main/java/com/mcplatform/bootstrap/config/PersistenceConfig.java` verdrahten (Resolver-Bean bleibt, zeigt auf neue Impl).
- [x] T038 **[SC-001]** Test-Grant-Helfer auf neues Modell umstellen + gemeinsamen Helfer `grantPermission(dsl, uuid, permission)` einführen; betrifft `infra-persistence/.../JooqPermissionResolverTest.java`, `app/.../PunishmentVerticalSliceTest.java`, `app/.../ReportVerticalSliceTest.java`.

### Tests (Testcontainers)

- [x] T039 [P] [US1] `JooqPermissionResolverTest` erweitert — **Union mehrerer Ränge**; **abgelaufener Rang fällt raus** (echtes `now()`); **Wildcard** `feature.*`/`*`; **Default-Fallback** für UUID ohne Grant.
- [x] T040 [P] V9-Migrations-Test — ADMIN behält `*`, MODERATOR behält Punishment-Subset **+** `report.view`/`report.handle`; `team_role_*`-Tabellen existieren nicht mehr; DEFAULT/ADMIN/MODERATOR-Rollen vorhanden.
- [x] T041 [P] [US3] `JooqRoleRepositoryTest` + `JooqPlayerGrantRepositoryTest` — Upsert-Unique (eine Zeile je Paar), set-inactive, find-expired, `grant_audit`-Zeilen.
- [x] T042 ⚠️ **[KRITISCHER CHECKPOINT] PHASE-GATE**: `./gradlew build` grün — insbesondere **`PunishmentVerticalSliceTest`, `ReportVerticalSliceTest` und der bestehende Resolver-Vertrag unverändert grün** (Konsumenten merken nichts, SC-001).

**Checkpoint**: Resolver lebt auf echtem Postgres; bestehende Features nachweislich unberührt.

---

## Phase 5: plugin-protocol — Contract-Ergänzungen (JDK-only)

**Goal**: Wire-Contract für Live-Event + REST. **Independent Test**: `./gradlew :plugin-protocol:test`.
Muss vor api-rest/`app`-Publisher stehen (Compile-Abhängigkeit).

- [x] T043 [P] `PermissionChannels.CHANGED = Channels.of("permission","changed")` in `plugin-protocol/src/main/java/com/mcplatform/protocol/permission/PermissionChannels.java`
- [x] T044 [P] `PermissionChangedEvent(UUID playerUuid, String changeType, long timestampEpochMilli)` + `PermissionChangedEventCodec` (`MESSAGE_TYPE="permission.changed"`, pipe-delimited 3 Teile, URL-encoded) in `plugin-protocol/.../permission/`
- [x] T045 [P] DTO-Records (JDK-only): `RoleRequest`, `RoleResponse`, `RolePermissionsRequest`, `GrantRoleRequest`, `GrantPermissionRequest`, `RevokePermissionRequest`, `PlayerPermissionsResponse`, `ActiveGrant`, `RoleDisplay` in `plugin-protocol/.../permission/`
- [x] T046 [P] `PermissionEndpoints` (EndpointDescriptors gemäß contracts/permission-contracts.md) in `plugin-protocol/.../permission/PermissionEndpoints.java`
- [x] T047 **Codec registrieren** — `PermissionChangedEventCodec.INSTANCE` in `plugin-protocol/.../protocol/PlatformProtocol.create()` (die EINE erlaubte Zeile geteilten Codes).

### Tests

- [x] T048 [P] `PermissionChangedEventCodecTest` — Encode/Decode-Round-Trip + Parts-Validierung (nach `ReportChangedEventCodecTest`).
- [x] T049 **PHASE-GATE**: `./gradlew :plugin-protocol:test` grün.

**Checkpoint**: Contract steht und routet; bereit für Backend-Adapter.

---

## Phase 6: api-rest + Composition-Root + Live-Verdrahtung

**Goal**: Dünne REST-Schicht + Pub/Sub-Adapter + Scheduler. **Independent Test**: E2E im `app`-Modul.

- [x] T050 [P] `PermissionMapper` (Domain ↔ Protocol-DTOs) in `api-rest/src/main/java/com/mcplatform/api/rest/support/PermissionMapper.java`
- [x] T051 `PermissionController` (dünn; alle Endpoints aus T046) in `api-rest/.../api/rest/PermissionController.java` (hängt an T050, Services, Protocol)
- [x] T052 [P] `PermissionExceptionHandler` — nur eigene Exceptions (404 `role_not_found`, 409 `role_name_conflict`/`default_role_protected`, 422 `permission_invalid`); **403/400 NICHT erneut mappen** (bereits global) — in `api-rest/.../api/rest/PermissionExceptionHandler.java`
- [x] T053 `RedisPermissionEventPublisher` (implementiert `PermissionChangePublisher`, **mappt `PermissionChangeType.name()` → `PermissionChangedEvent.changeType`**, bridged auf `mc:permission:changed`, spiegelt `RedisReportEventPublisher`) in `app/src/main/java/com/mcplatform/bootstrap/adapter/RedisPermissionEventPublisher.java`
- [x] T054 `PermissionConfig` (Composition-Root: Services, Repos, Publisher, Sentinel-UUID-Property `mcplatform.permission.system-actor-uuid`) in `app/.../bootstrap/config/PermissionConfig.java`
- [x] T055 [US5] `SchedulingConfig` — `@EnableScheduling` + `@Scheduled(fixedDelayString ≤ 60s)` ruft `GrantExpiryService.sweep()` in `app/.../bootstrap/config/SchedulingConfig.java`

### Tests (E2E)

- [x] T056 [P] [US2] `PermissionVerticalSliceTest` (`app/src/test/.../PermissionVerticalSliceTest.java`) — Rollen-CRUD + Grant/Revoke über REST; **403-Pfad**; `GET …/effective` liefert korrekte Union.
- [x] T057 [P] [US5] Live-Ablauf-E2E — abgelaufener Grant nach Sweep: **genau ein `mc:permission:changed`-Event** publiziert (Redis-Subscriber/Spy zählt).
- [x] T058 **PHASE-GATE**: `./gradlew build` grün (gesamtes Backend, alle Module).

**Checkpoint**: Vollständiger Vertical Slice lauffähig.

---

## Phase 7: Polish, Handoff & Constitution-Nachweis

- [x] T059 `./gradlew :plugin-protocol:publishToMavenLocal` — Contract-Handoff für das separate Plugin-Repo (dort später `build --refresh-dependencies`).
- [x] T060 [P] PROGRESS.md Status-Abschnitt nachziehen (neuer Stand: Permission-/Rank-System).
- [x] T061 [P] FEATURE_INVENTORY.md-Eintrag abhaken.
- [x] T062 quickstart.md Smoke-Pfad manuell durchspielen.
- [x] T063 Pattern-Leak-Ledger bestätigen: nur `PlatformProtocol` +1 Zeile, `@EnableScheduling` (Composition-Root), `team_role_*`-Ablösung — keine generische Klasse geändert.

---

## Dependencies & Execution Order

### Phasen-Abhängigkeiten (strikt sequenziell, jede endet grün)

1. **Phase 1 Setup** → 2. **core-domain** (T014-Gate) → 3. **application** (T032-Gate) →
4. **infra-persistence** (T042 ⚠️ KRITISCHER Gate) → 5. **plugin-protocol** (T049-Gate) →
6. **api-rest/Composition** (T058-Gate) → 7. **Polish/Handoff**.

> Protocol (Phase 5) vor api-rest (Phase 6): Compile-Abhängigkeit von Controller & Publisher.
> `publishToMavenLocal` (T059) ist bewusst der letzte, cross-repo-Schritt.

### Kritische Tasks

- **T034** (Resolver-Umbau) + **T042** (Konsumenten-Gate): der Port-berührende Checkpoint. Hier wird
  bewiesen, dass Punishments/Reports unverändert grün bleiben (SC-001).

### Parallele Möglichkeiten

- Phase 2: T002–T006, T009 parallel; Tests T010–T013 parallel (nach ihren Quellen).
- Phase 3: Ports T015–T019 parallel; Service-Tasks T020–T022 seriell (gleiche Datei); Tests T026–T031 parallel.
- Phase 4: T035/T036 parallel; T034 zuerst stabilisieren (Port). Tests T039–T041 parallel.
- Phase 5: T043–T046 parallel; T047 danach; T048 parallel zu Doku.
- Phase 6: T050/T052 parallel; T051/T053/T054/T055 nach ihren Deps; E2E T056/T057 parallel.

---

## Implementation Strategy

### MVP (Foundation-Kern)

Phase 1 → 2 → 3 → **4 (bis T042-Gate)** ergibt den lauffähigen, backend-autoritativen Resolver auf dem
neuen Modell — der eigentliche Foundation-Wert, den Punishments/Reports bereits nutzen. Stop & Validate
am kritischen Checkpoint.

### Inkrementell

Danach Phase 5 (Contract) → Phase 6 (REST + Live-Push) liefert Verwaltung über die API und den
Live-Entzug. Phase 7 zieht Doku/Inventar nach und übergibt den Contract ans Plugin.

---

## Notes

- `[P]` = andere Datei, keine offene Abhängigkeit. `[US#]` = Rückverfolgbarkeit zur spec.md.
- Tests vor Implementierung schreiben und fehlschlagen sehen.
- Nach jedem Task / logischer Gruppe committen.
- An jedem PHASE-GATE stoppen und Tests grün bestätigen, bevor die nächste Phase startet.
- jOOQ-Klassen für V9 entstehen automatisch beim Build (jooq-docker) — kein manueller Codegen.
