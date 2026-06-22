---
description: "Task list — Reports (Moderation-Modul)"
---

# Tasks: Reports (Moderation — Spieler-Meldungen)

**Input**: Design-Dokumente aus `/specs/001-reports/` (plan.md, spec.md, research.md, data-model.md,
contracts/)

**Tests**: **PFLICHT** — Constitution-Prinzip 22 (Tests pro Schicht: Domain, Use-Case, jOOQ-Integration,
Protocol-Codec, app-E2E) ist Teil der Definition of Done. Tests werden je Story VOR der Implementierung
geschrieben und müssen zunächst fehlschlagen.

**Organisation**: Tasks nach User Story gruppiert. Reihenfolge = Build-Abhängigkeit
(core-domain/protocol → application → infra-persistence → api-rest → app).

**Pfad-Konvention**: Multi-Module-Backend; Java unter `<modul>/src/main/java/com/mcplatform/…`,
Tests unter `<modul>/src/test/java/com/mcplatform/…`. Package-Präfixe gemäß plan.md.

## Format: `[ID] [P?] [Story] Beschreibung`
- **[P]**: parallelisierbar (andere Datei, keine offene Abhängigkeit)
- **[Story]**: US1–US4 (nur in Story-Phasen)

---

## Phase 1: Setup

**Purpose**: Vorbereitung; keine neuen Gradle-Dependencies nötig (alle Module bestehen).

- [x] T001 Feature-Packages anlegen: `com.mcplatform.domain.report` (core-domain),
  `com.mcplatform.application.report(.port)` (application), `com.mcplatform.protocol.report`
  (plugin-protocol); bestätigen, dass keine neue Modul-Dependency erforderlich ist (api-rest hängt
  bereits an plugin-protocol; infra-persistence an application).

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Schema, Domain-Kern, Ports und Contract-DTOs, die ALLE Stories brauchen.

**⚠️ CRITICAL**: Keine Story-Arbeit vor Abschluss dieser Phase.

- [x] T002 Flyway-Migration `V7__report_schema.sql` (Tabellen `report` + `report_status_history`,
  `CHECK reporter<>target`, **partieller Unique-Index** `uq_report_open (reporter_uuid,target_uuid)
  WHERE status IN ('OPEN','IN_PROGRESS')`, Indizes `idx_report_open`/`idx_report_reporter_created`/
  `idx_report_history_report`) in `infra-persistence/src/main/resources/db/migration/V7__report_schema.sql`
- [x] T003 Flyway-Migration `V8__seed_report_permissions.sql` (`report.view`, `report.handle` →
  Rolle `MODERATOR`; ADMIN via `*`) in `infra-persistence/src/main/resources/db/migration/V8__seed_report_permissions.sql`
- [x] T004 [P] `ReportStatus`-Enum + Übergangslogik (`canTransitionTo`) gemäß Matrix in
  `core-domain/src/main/java/com/mcplatform/domain/report/ReportStatus.java`
- [x] T005 [P] `ReportCategory`-Enum (CHEATING, BELEIDIGUNG, SPAM_WERBUNG, TEAMING_BUG_ABUSE, SONSTIGES)
  in `core-domain/src/main/java/com/mcplatform/domain/report/ReportCategory.java`
- [x] T006 [P] `ReportId` Value-Object (UUID) in
  `core-domain/src/main/java/com/mcplatform/domain/report/ReportId.java`
- [x] T007 [P] `ChatContextEntry` (record: sender/PlayerId, text, at) + `ChatContext` VO mit Validierung
  (≤30 Einträge, Text ≤256, leer erlaubt) in
  `core-domain/src/main/java/com/mcplatform/domain/report/ChatContext.java` (+ `ChatContextEntry.java`)
- [x] T008 [P] Domain-Exceptions `ReportValidationException` + `InvalidStatusTransitionException` in
  `core-domain/src/main/java/com/mcplatform/domain/report/`
- [x] T009 `Report`-Aggregat (record + Factory mit Self-Report-Guard + `transitionTo(newStatus,handler,
  now)`) in `core-domain/src/main/java/com/mcplatform/domain/report/Report.java` (hängt an T004–T008)
- [x] T010 [P] `ReportChange`-Notifikations-Record (CREATED|STATUS_CHANGED, ohne Chat-Kontext) in
  `core-domain/src/main/java/com/mcplatform/domain/report/ReportChange.java`
- [x] T011 [P] Port `ReportRepository` + `ReportNotFoundException` + `ReportCooldownException` in
  `application/src/main/java/com/mcplatform/application/report/port/`
- [x] T012 [P] Port `ReportPublisher` (`publish(ReportChange)`) in
  `application/src/main/java/com/mcplatform/application/report/port/ReportPublisher.java`
- [x] T013 [P] Contract-DTOs `ChatMessage`, `CreateReportRequest`, `ChangeStatusRequest`,
  `ReportResponse` (JDK-only records) in `plugin-protocol/src/main/java/com/mcplatform/protocol/report/`
- [x] T014 [P] `ReportChannels` (`Channels.of("report","changed")`) + `ReportEndpoints` (CREATE,
  LIST_OPEN, CHANGE_STATUS via `EndpointDescriptor`) in
  `plugin-protocol/src/main/java/com/mcplatform/protocol/report/`

**Checkpoint**: Schema + Domain-Kern + Ports + Contract-DTOs stehen — Stories können beginnen.

---

## Phase 3: User Story 1 — Spieler meldet einen Mitspieler (Priority: P1) 🎯 MVP

**Goal**: Report erstellen (Reporter, Ziel, Kategorie, Detail, optionaler Chat-Kontext), persistent,
mit Dedupe, Cooldown, Self-Report-Sperre.

**Independent Test**: `POST /api/reports` legt einen `OPEN`-Report dauerhaft an; zweiter Create
(gleicher Reporter+Ziel) liefert denselben Report; Self-Report→422, Cooldown→429; Chat-Kontext bleibt
unverändert — auch nach Neustart.

### Tests for User Story 1 (zuerst, müssen fehlschlagen)

- [x] T015 [P] [US1] Domain-Test: Self-Report abgelehnt, **Detail-Validierung (nicht leer/whitespace,
  ≤256 Zeichen → `ReportValidationException`)**, `ChatContext`-Validierung (Größe/Textlänge) in
  `core-domain/src/test/java/com/mcplatform/domain/report/ReportTest.java`
- [x] T016 [P] [US1] Use-Case-Test (Fakes): `ReportService.create` — Dedupe liefert bestehenden Report,
  Cooldown→`ReportCooldownException`, Publish-once (CREATED), Validierungsfehler, in
  `application/src/test/java/com/mcplatform/application/report/ReportServiceTest.java`
- [x] T017 [P] [US1] jOOQ-Integration (Testcontainers): create schreibt `report`+History-Zeile,
  partieller Unique-Index dedupliziert (paralleler Doppel-Create → genau ein offener Report),
  `chat_context`-Round-Trip, FK-Ablehnung unbekanntes Ziel, in
  `infra-persistence/src/test/java/com/mcplatform/persistence/JooqReportRepositoryTest.java`
- [x] T018 [P] [US1] app-E2E: `POST /api/reports` → 200 + Postgres-Zeile; 422 Self-Report; 429 Cooldown;
  Chat-Kontext JSON-Round-Trip, in `app/src/test/java/com/mcplatform/.../ReportVerticalSliceTest.java`

### Implementation for User Story 1

- [x] T019 [US1] `ReportService.create(...)` (kein Permission-Gate; Cooldown via `Clock` +
  `lastCreatedAtByReporter`; Domain bauen; `repo.create`; `publisher.publish` CREATED) in
  `application/src/main/java/com/mcplatform/application/report/ReportService.java` (hängt an T009,T011,T012)
- [x] T020 [US1] `JooqReportRepository.create` + `lastCreatedAtByReporter` (1 TX: INSERT report +
  History; Unique-Verletzung → bestehenden offenen Report zurück; FK-Verletzung →
  `ReportValidationException`; `chat_context` als JSONB) in
  `infra-persistence/src/main/java/com/mcplatform/persistence/JooqReportRepository.java` (hängt an T002,T011)
- [x] T021 [US1] `ReportMapper` (`CreateReportRequest`→Domain, `Report`→`ReportResponse`,
  `ChatMessage`↔`ChatContextEntry`, Kategorie parsen, epoch-milli) in
  `api-rest/src/main/java/com/mcplatform/api/rest/support/ReportMapper.java`
- [x] T022 [US1] `ReportController` `POST /api/reports` + `ReportExceptionHandler` (NUR
  `ReportValidationException`→422, `ReportCooldownException`→429; 403/400 NICHT re-deklarieren) in
  `api-rest/src/main/java/com/mcplatform/api/rest/ReportController.java` (+ `ReportExceptionHandler.java`)
- [x] T023 [US1] App-Wiring: `PersistenceConfig` +`@Bean ReportRepository`; neue `ReportConfig`
  +`@Bean ReportService` (+ Property `mcplatform.reports.cooldown-seconds`, Default 60) + vorläufige
  **No-op `ReportPublisher`-Bean** (bis US4); bestehende `Clock`-Bean wiederverwenden (NICHT neu
  definieren), in `app/src/main/java/com/mcplatform/bootstrap/config/` (`ReportConfig.java`,
  `PersistenceConfig.java`)

**Checkpoint**: US1 eigenständig lauffähig/testbar (Erstellen + Persistenz + Dedupe + Cooldown).

---

## Phase 4: User Story 2 — Team sieht offene Reports (Priority: P1)

**Goal**: Berechtigte Teamler rufen die Liste der offenen Reports ab; Nicht-Team wird abgewiesen.

**Independent Test**: `GET /api/reports/open?staff=` liefert `OPEN`+`IN_PROGRESS` (inkl. Chat-Kontext);
ohne `report.view` → 403; abgeschlossene Reports fehlen.

### Tests for User Story 2

- [x] T024 [P] [US2] Use-Case-Test (Fakes): `ReportService.listOpen` — `report.view` erzwungen (403-Pfad),
  nur `OPEN`+`IN_PROGRESS`, in `application/src/test/java/com/mcplatform/application/report/ReportServiceTest.java`
- [x] T025 [P] [US2] app-E2E: `GET /api/reports/open?staff=` liefert offene Liste inkl. Chat-Kontext;
  ohne Permission → 403; abgeschlossene ausgeschlossen, in
  `app/src/test/java/com/mcplatform/.../ReportVerticalSliceTest.java`

### Implementation for User Story 2

- [x] T026 [US2] `ReportRepository.findOpen()` + `JooqReportRepository.findOpen` (Status∈{OPEN,
  IN_PROGRESS}, älteste zuerst, nutzt `idx_report_open`) in
  `infra-persistence/src/main/java/com/mcplatform/persistence/JooqReportRepository.java`
- [x] T027 [US2] `ReportService.listOpen(viewer)` mit `PermissionResolver.hasPermission(viewer,
  "report.view")` in `application/src/main/java/com/mcplatform/application/report/ReportService.java`
- [x] T028 [US2] `ReportController` `GET /api/reports/open?staff=` → `ReportResponse[]` (über
  `ReportMapper`) in `api-rest/src/main/java/com/mcplatform/api/rest/ReportController.java`

**Checkpoint**: US1 + US2 unabhängig funktionsfähig.

---

## Phase 5: User Story 3 — Team bearbeitet eine Meldung (Status-Lebenszyklus) (Priority: P2)

**Goal**: Teamler führt Statusübergänge durch; jeder Wechsel hält Teamler + Zeitstempel fest;
unzulässige Übergänge/konkurrierende Änderungen werden abgewiesen.

**Independent Test**: `OPEN→IN_PROGRESS→RESOLVED` setzt je Schritt handler+timestamp + History-Zeile;
`RESOLVED→OPEN`→409; unbekannte Id→404; ohne `report.handle`→403; konkurrierender Wechsel→409.

### Tests for User Story 3

- [x] T029 [P] [US3] Domain-Test: `ReportStatus`-Übergangsmatrix (erlaubt + verboten inkl. terminal) in
  `core-domain/src/test/java/com/mcplatform/domain/report/ReportStatusTest.java`
- [x] T030 [P] [US3] Use-Case-Test (Fakes): `ReportService.changeStatus` — `report.handle` erzwungen,
  ungültiger Übergang→Exception, setzt handler+timestamp, Publish-once (STATUS_CHANGED), not-found,
  OCC-Konflikt, in `application/src/test/java/com/mcplatform/application/report/ReportServiceTest.java`
- [x] T031 [P] [US3] jOOQ-Integration: `changeStatus` per OCC (konkurrierend → Konflikt), hängt
  History-Zeile an, in `infra-persistence/src/test/java/com/mcplatform/persistence/JooqReportRepositoryTest.java`
- [x] T032 [P] [US3] app-E2E: Statuswechsel-Happy-Path; 409 ungültiger Übergang; 404 unbekannt; 403 ohne
  `report.handle`, in `app/src/test/java/com/mcplatform/.../ReportVerticalSliceTest.java`

### Implementation for User Story 3

- [x] T033 [US3] `ReportRepository.find(id)` + `changeStatus` (1 TX: `UPDATE … WHERE version=:expected`,
  0 Zeilen→Konflikt-Signal; History-Insert) + `JooqReportRepository`-Impl in
  `infra-persistence/src/main/java/com/mcplatform/persistence/JooqReportRepository.java`
- [x] T034 [US3] `ReportService.changeStatus(id,newStatus,handledBy)` — `report.handle`-Check, laden,
  `report.transitionTo(...)`, `repo.changeStatus`, `publisher.publish` STATUS_CHANGED in
  `application/src/main/java/com/mcplatform/application/report/ReportService.java`
- [x] T035 [US3] `ReportController` `POST /api/reports/{id}/status` + `ReportExceptionHandler` um
  `InvalidStatusTransitionException`→409, `ReportNotFoundException`→404, OCC-Konflikt→409 erweitern, in
  `api-rest/src/main/java/com/mcplatform/api/rest/` (`ReportController.java`, `ReportExceptionHandler.java`)

**Checkpoint**: US1–US3 unabhängig funktionsfähig (voller Moderations-Workflow ohne Live-Push).

---

## Phase 6: User Story 4 — Online-Team wird live informiert (Priority: P3)

**Goal**: Erstellung und Statuswechsel veröffentlichen genau ein `ReportChangedEvent` auf
`mc:report:changed`; Wire trägt keinen Chat-Kontext.

**Independent Test**: create + Statuswechsel publishen je genau ein über `PlatformProtocol` dekodierbares
Event auf dem Report-Channel (Test-Subscriber); Event ohne Chat-Kontext.

### Tests for User Story 4

- [x] T036 [P] [US4] Protocol-Codec-Test (rein-JDK): `ReportChangedEventCodec`-Roundtrip
  (Delimiter/Unicode) + Golden-Wire + Envelope, in
  `plugin-protocol/src/test/java/com/mcplatform/protocol/report/ReportChangedEventCodecTest.java`
- [x] T037 [P] [US4] app-E2E: create + Statuswechsel publishen je genau ein Event auf
  `mc:report:changed`, dekodiert via `PlatformProtocol.create()`; kein Chat-Kontext im Event, in
  `app/src/test/java/com/mcplatform/.../ReportVerticalSliceTest.java`

### Implementation for User Story 4

- [x] T038 [P] [US4] `ReportChangedEvent` record (reportId, reporter, target, category, status,
  changeType, timestampEpochMilli) in
  `plugin-protocol/src/main/java/com/mcplatform/protocol/report/ReportChangedEvent.java`
- [x] T039 [US4] `ReportChangedEventCodec implements MessageCodec` (messageType `report.changed`,
  7-Feld-Pipe-Wire, URL-encoded) in
  `plugin-protocol/src/main/java/com/mcplatform/protocol/report/ReportChangedEventCodec.java` (hängt an T038)
- [x] T040 [US4] **Einziger geteilter Eingriff**: `ReportChangedEventCodec.INSTANCE` in
  `PlatformProtocol.create()` registrieren (eine Zeile) in
  `plugin-protocol/src/main/java/com/mcplatform/protocol/PlatformProtocol.java`
- [x] T041 [US4] `RedisReportEventPublisher` (`ReportChange`→`ReportChangedEvent`→`protocol.encode`→
  `RedisCacheAdapter.publish(ReportChannels.CHANGED)`, best-effort nach Commit) in
  `app/src/main/java/com/mcplatform/bootstrap/adapter/RedisReportEventPublisher.java`
- [x] T042 [US4] `ReportConfig`: No-op-Publisher-Bean durch `RedisReportEventPublisher`-Bean ersetzen in
  `app/src/main/java/com/mcplatform/bootstrap/config/ReportConfig.java`
- [x] T043 [US4] `:plugin-protocol:publishToMavenLocal` ausführen und bestätigen, dass der publizierte
  POM weiterhin **keinen** `<dependencies>`-Block hat

**Checkpoint**: Alle vier Stories unabhängig funktionsfähig.

---

## Phase 7: Polish & Cross-Cutting

- [x] T044 [P] PROGRESS.md: Reports-Statusabschnitt im bestehenden Stil nachziehen (was steht / bewusste
  Grenzen / Tests grün) in `PROGRESS.md`
- [x] T045 [P] FEATURE_INVENTORY.md: #47 als migriert markieren (Slice-Umfang notieren) in
  `FEATURE_INVENTORY.md`
- [x] T046 `./gradlew build` grün (alle Module + Codegen + Tests) + manuelle Quickstart-Probe gemäß
  `specs/001-reports/quickstart.md`
- [x] T047 Review-Bestätigung: keine generische Klasse geändert außer der `PlatformProtocol.create()`-
  Zeile (T040) + additiver `PersistenceConfig`-Bean (T023); `PermissionDeniedException`→403 NICHT
  re-deklariert (Muster-Lecks-Abschnitt im Plan abgehakt)

---

## Dependencies & Execution Order

### Phasen
- **Setup (P1)** → **Foundational (P2)** blockt alle Stories → **US1 → US2 → US3 → US4** → **Polish**.
- US-Reihenfolge folgt Priorität; sie teilen sich wachsende Dateien (ReportService, JooqReportRepository,
  ReportController, ReportExceptionHandler, ReportMapper) → bewusst **sequentiell**, nicht story-parallel.

### Innerhalb einer Story
- Tests zuerst (müssen fehlschlagen) → Persistenz → Service → Controller/Wiring.
- Build-Reihenfolge: core-domain/protocol → application → infra-persistence → api-rest → app.

### Parallele Möglichkeiten
- **Foundational**: T004–T008, T010, T011, T012, T013, T014 sind [P] (verschiedene Dateien). T002/T003
  (Migrationen) parallel zu den Java-Foundational-Tasks. T009 nach T004–T008.
- **Pro Story**: die mit [P] markierten Test-Tasks laufen parallel; Implementierungs-Tasks innerhalb
  einer Story sind überwiegend sequentiell (gemeinsame Dateien).
- US4-DTO/Codec (T038/T039/T036) sind [P] gegenüber dem app-Wiring (T041/T042).

## Parallel Example: Foundational
```bash
# Domain-Kern + Ports + Contract parallel (verschiedene Dateien):
T004 ReportStatus | T005 ReportCategory | T006 ReportId | T007 ChatContext(+Entry)
T008 Exceptions   | T010 ReportChange   | T011 ReportRepository-Port | T012 ReportPublisher-Port
T013 Contract-DTOs | T014 ReportChannels/Endpoints
# danach: T009 Report-Aggregat (hängt an T004–T008)
```

## Implementation Strategy

### MVP (nur US1)
1. Phase 1 Setup → 2. Phase 2 Foundational → 3. Phase 3 US1 → **STOPP & validieren** (Erstellen +
   Persistenz + Dedupe + Cooldown end-to-end). Demo-fähig.

### Inkrementell
- + US2 (Team-Liste) → + US3 (Status-Lebenszyklus) → + US4 (Live-Push). Jede Story testbar, ohne die
  vorherige zu brechen. US4 liefert FR-015 (Live-Push) und den einzigen geteilten Eingriff
  (PlatformProtocol-Registrierung).

## Notes
- [P] = andere Datei, keine offene Abhängigkeit.
- Bis US4 läuft eine No-op-`ReportPublisher`-Bean; `ReportService` ruft den Port ab US1 (Use-Case-Tests
  prüfen „publish-once" mit Fake). US4 ersetzt die Bean durch den Redis-Adapter.
- Definition of Done je Story: zugehörige Schicht-Tests grün; Gesamt-`./gradlew build` grün am Ende.
- Bei protocol-Änderung (US4): `publishToMavenLocal`, dann im Plugin `build --refresh-dependencies`.
- Keine generische Klasse ändern außer T040 (+ additive Bean T023) — sonst STOPP/Muster-Leck melden.
