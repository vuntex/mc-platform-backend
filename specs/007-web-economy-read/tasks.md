---
description: "Task list for Web-Economy Read-Backend + SSE (Slice 1)"
---

# Tasks: Web-Economy Read-Backend + SSE (Slice 1)

**Input**: Design documents from `/specs/007-web-economy-read/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/economy-read-endpoints.md

**Tests**: Tests are **included** — die Constitution (§22) und CLAUDE.md machen „Tests pro Schicht
grün" zur Definition of Done. Jede Schicht-Task hat ihren Test benannt.

**Organization**: Nach User Story (US1–US4). Der Port-Move ist Foundational (blockiert alle Stories).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: parallelisierbar (andere Datei, keine offene Abhängigkeit)
- **[Story]**: US1–US4 (Setup/Foundational/Polish ohne Story-Label)
- **🔁 PUBLISH**: Task ändert `plugin-protocol` → danach `:plugin-protocol:publishToMavenLocal`

## Path Conventions

Multi-Module-Backend (PROGRESS.md §5). Module: `application/`, `infra-persistence/`, `api-rest/`,
`api-realtime/`, `app/`, `plugin-protocol/` — jeweils `src/main/java/com/mcplatform/...`.

---

## Phase 1: Setup

**Purpose**: Ausgangslage sichern.

- [X] T001 Baseline absichern: `./gradlew build` läuft grün auf Branch `007-web-economy-read` (vor jeder Änderung), damit die Phase-2-Move-Regression eine saubere Vergleichsbasis hat.

---

## Phase 2: Foundational — Port-Move (Blocking Prerequisites)

**Purpose**: Den `EconomyReadStore`-Port etablieren (Muster-Leck-Fix), auf den **alle** Stories
ihre Reads stützen, plus die geteilte Permission-Konstante. In sich abgeschlossener, grün baubarer
Commit **bevor** neue Features dazukommen.

**⚠️ CRITICAL**: Keine User-Story-Arbeit beginnt, bevor diese Phase grün ist.

- [X] T002 Neuen Outbound-Port `EconomyReadStore` anlegen mit `findHistory(...)` + `circulation()` (Signaturen 1:1 aus `EconomyEventStore`) in `application/src/main/java/com/mcplatform/application/economy/port/EconomyReadStore.java`. *Fertig:* kompiliert, Interface vorhanden.
- [X] T003 `findHistory(...)` + `circulation()` aus `application/src/main/java/com/mcplatform/application/economy/port/EconomyEventStore.java` **entfernen** (verbleibt: `currentBalance`, `ensureZeroBalance`, `append`, `transfer`, `findByTransactionId`, `findTransfer`). *Fertig:* Event-Store trägt nur noch Event-/Versions-/Idempotenz-Semantik.
- [X] T004 `JooqEconomyReadStore` in `infra-persistence/src/main/java/com/mcplatform/persistence/JooqEconomyReadStore.java` anlegen; die bestehenden `findHistory`/`circulation`-Implementierungen **byte-gleich** aus `JooqEconomyRepository.java` herüberziehen und dort entfernen. *Fertig:* Impl identisch, nur verschoben.
- [X] T005 Rewiring: `EconomyHistoryService` und `EconomyStatsService` (`application/.../economy/`) sowie die Composition Root (`app/.../config/PersistenceConfig` o. ä.) auf `EconomyReadStore` statt `EconomyEventStore` umhängen. *Fertig:* Beans/Konstruktoren zeigen auf den neuen Port; App startet.
- [X] T006 **Move-Regression**: bestehende `findHistory`-/`circulation`-Tests (jOOQ-Integration + `EconomyHistoryServiceTest` + Stats-Tests) laufen **unverändert grün** nach dem Umzug. *Fertig:* kein Verhaltens-Delta, nur Port-Schnitt. *(Akzeptanz: bestehende History/Circulation-Akzeptanz bleibt erfüllt.)*
- [X] T007 [P] Permission-Konstante `permission.economy.read` einführen (Vorschlag: `application/.../economy/EconomyPermissions.java` oder analog zu `PermissionAdminService`-Konstanten) + Controller-Helper-Muster `requireEconomyRead(actor)` über `PermissionResolver`. *Fertig:* Konstante existiert, kein Endpunkt nutzt sie noch. *(FR-021)*

**Checkpoint**: Port-Move + Permission-Basis grün → Stories können starten.

---

## Phase 3: User Story 1 — Sammel-Balances eines Spielers (Priority: P1) 🎯 MVP

**Goal**: `GET /api/web/economy/players/{uuid}/balances` liefert alle Währungsstände eines Spielers in einem
Call, je Eintrag mit Display-Daten; unbekannter Spieler → leere Liste.

**Independent Test**: Spieler mit mehreren Währungen → alle Stände + Display; unbekannter Spieler →
`[]` (kein 404); ohne `permission.economy.read` → 403. *(SC-001, FR-001..FR-003, FR-021)*

- [X] T008 [P] [US1] Application-Read-Modell `ProjectedBalance` (currency, displayName, symbol, decimalPlaces, balance) in `application/.../economy/port/ProjectedBalance.java`.
- [X] T009 [P] [US1] 🔁 PUBLISH protocol-DTOs `PlayerBalancesResponse` + `PlayerBalanceEntry` (JDK-only Records) in `plugin-protocol/.../economy/`. *Fertig:* POM weiterhin ohne `<dependencies>`.
- [X] T010 [US1] 🔁 PUBLISH `EconomyEndpoints.LIST_BALANCES` (GET `/api/web/economy/players/{uuid}/balances` → `PlayerBalancesResponse`) in `plugin-protocol/.../economy/EconomyEndpoints.java`; danach `:plugin-protocol:publishToMavenLocal`.
- [X] T011 [US1] `EconomyReadStore.playerBalances(PlayerId)` deklarieren + in `JooqEconomyReadStore` implementieren (Join `player_balance × currency`). *Fertig:* liefert eine Zeile pro Währung.
- [X] T012 [US1] jOOQ-Integrationstest (Testcontainers) in `infra-persistence/src/test/...`: Mehr-Währungs-Spieler → alle Stände + korrekte Display-Felder; unbekannter Spieler → leere Liste. *(FR-001..FR-003)*
- [X] T013 [US1] Use-Case `PlayerBalancesQuery` in `application/.../economy/PlayerBalancesQuery.java` + Fake-Test (Mapping, leerer Spieler).
- [X] T014 [US1] `PlayerBalancesController` (GET `/api/web/economy/players/{uuid}/balances`) in `api-rest/.../PlayerBalancesController.java` + Mapper-Methode in `api-rest/.../support/EconomyMapper.java` + `requireEconomyRead(actor)`-Gate. *Fertig:* dünner Controller, Mapping in support.
- [X] T015 [P] [US1] `@JsonTest` in `app/.../RestDtoJsonContractTest` für `PlayerBalancesResponse`/`PlayerBalanceEntry` (Feldnamen).
- [X] T016 [US1] E2E (`app`, Testcontainers): Balances inkl. leerer Liste; **403 ohne** `permission.economy.read`. *(SC-001, SC-007)*

**Checkpoint**: US1 eigenständig lauffähig + getestet (MVP).

---

## Phase 4: User Story 2 — Serverweite Transaktionsliste (Priority: P1)

**Goal**: `GET /api/web/economy/history` liefert serverweite Events (`sequence_no DESC`), Filter
`currency`/`type`/`source`, Keyset-Pagination; jeder Eintrag mit `playerUuid` + `playerName`.

**Independent Test**: Server-History ohne Player-Filter, Filter + Pagination lücken-/überlappungsfrei,
`nextCursor` korrekt; ungültiges Limit/Filter → 400; ohne Permission → 403.
*(SC-002, SC-003, FR-004..FR-010, FR-021)*

- [X] T017 [US2] Flyway `V16__economy_event_seq_index.sql` in `infra-persistence/src/main/resources/db/migration/`: `CREATE INDEX idx_event_seq_desc ON economy_event (sequence_no DESC);`. *Fertig:* Codegen/Flyway grün, nächste freie Version V16.
- [X] T018 [US2] 🔁 PUBLISH `EconomyEventEntry` (protocol) + `EconomyHistoryEntry` (application) **additiv** um `playerUuid` + `playerName` erweitern; bestehende Player-History-Konstruktoren (Mapper `EconomyMapper.historyResponse` + `JooqEconomyRepository`/`JooqEconomyReadStore`-Entry-Bau) anpassen, sodass alles kompiliert; `@JsonTest` für die zwei neuen Felder ergänzen; danach `:plugin-protocol:publishToMavenLocal`. *Fertig:* Player-History verhält sich unverändert (Top-Level `player` bleibt), neue Felder befüllt.
- [X] T019 [US2] `EconomyReadStore.findServerHistory(currency?, type?, source?, cursorBeforeSeqNo, limit)` + Impl in `JooqEconomyReadStore` über **eine geteilte private Keyset-Helper-Methode** mit optionalem player-Predicate (von `findHistory` mitgenutzt) + optionalem `source`-Filter + `player`-Join für `playerName`. *Fertig:* **keine** duplizierte Pagination-Logik (explizit verifiziert: `findHistory` delegiert an denselben Helper). *(FR-007)*
- [X] T020 [US2] jOOQ-Integrationstest: Reihenfolge `sequence_no DESC`; Filter `currency`/`type`/`source` einzeln + kombiniert; Keyset über mehrere Seiten ohne Lücken/Überlappung + korrekter `nextCursor`; Einträge tragen `playerUuid`/`playerName`. *(SC-002, SC-003)*
- [X] T021 [US2] Use-Case `ServerHistoryQuery` in `application/.../economy/ServerHistoryQuery.java` — **Limit-Clamp wiederverwenden** (geteilte Helper aus `EconomyHistoryService`: Default 50/Max 200/≤0→`IllegalArgumentException`) + `source`-Filter-Weitergabe; Fake-Test (Clamp, Filter inkl. `source`). *(FR-008)*
- [X] T022 [US2] 🔁 PUBLISH `EconomyEndpoints.SERVER_HISTORY` (GET `/api/web/economy/history` → `EconomyHistoryResponse`) in `EconomyEndpoints.java`; `publishToMavenLocal` (kann mit T018-Publish gebündelt werden).
- [X] T023 [US2] `ServerEconomyController` (GET `/api/web/economy/history`) in `api-rest/.../ServerEconomyController.java` + Mapper (`EconomyHistoryResponse` mit `player=null`, Einträge tragen „wer") + `requireEconomyRead`-Gate; ungültiges `type`/`source`/`limit` → 400 über bestehenden `EconomyExceptionHandler`. *(FR-010)*
- [X] T024 [US2] E2E (`app`): serverweite History mit Filtern + Pagination; 400-Pfade (ungültiges Limit/Type/Source); **403 ohne** Permission. *(SC-002, SC-003, SC-005, SC-007)*

**Checkpoint**: US1 **und** US2 unabhängig lauffähig.

---

## Phase 5: User Story 3 — Transaktions-Detail per ID (Priority: P2)

**Goal**: `GET /api/web/economy/transactions/{transactionId}` → ein Leg (SINGLE) bzw. zwei Legs
(TRANSFER, beide Namen); unbekannte ID → 404.

**Independent Test**: CREDIT/DEBIT/SET → ein Leg `kind=SINGLE`; Transfer → zwei Legs `kind=TRANSFER`
+ `correlationId`; fehlendes Gegen-Leg → ein Leg, kein Fehler; unbekannte txId → 404; ohne
Permission → 403. *(SC-004, FR-011..FR-014, FR-021)*

- [X] T025 [P] [US3] Application-Read-Modelle `TransactionDetail` + `TransactionLeg` + `TransactionKind`-Enum (SINGLE/TRANSFER) in `application/.../economy/port/`.
- [X] T026 [P] [US3] 🔁 PUBLISH protocol-DTOs `TransactionDetailResponse` + `TransactionLegDto` (JDK-only) in `plugin-protocol/.../economy/`.
- [X] T027 [US3] 🔁 PUBLISH `EconomyEndpoints.GET_TRANSACTION` (GET `/api/web/economy/transactions/{transactionId}` → `TransactionDetailResponse`) in `EconomyEndpoints.java`; `publishToMavenLocal`.
- [X] T028 [US3] `EconomyReadStore.findTransaction(TransactionId)` + Impl in `JooqEconomyReadStore`: Event per `transaction_id`; bei `TRANSFER_*` Gegen-Leg über `metadata->>'correlation_id'` (bestehende Subquery-Mechanik) nachladen; Join `player` (Namen) + `currency` (Display). *Fertig:* SINGLE→1 Leg, TRANSFER→2 Legs.
- [X] T029 [US3] jOOQ-Integrationstest: SINGLE (ein Leg); TRANSFER (zwei Legs, beide Namen, `correlation_id` aus metadata); **Gegen-Leg manuell entfernt → ein Leg, `kind=TRANSFER`, kein Fehler** (Edge-Case D4); unbekannte txId → empty. *(SC-004)*
- [X] T030 [US3] `EconomyTransactionNotFoundException` (application) + Mapping in `api-rest/.../EconomyExceptionHandler.java` → **404** `{"error":"economy_transaction_not_found"}`. *(FR-014)*
- [X] T031 [US3] Use-Case `TransactionDetailQuery` in `application/.../economy/TransactionDetailQuery.java`: Single- vs. Transfer-Mapping; empty → `EconomyTransactionNotFoundException`; Fake-Test.
- [X] T032 [US3] Endpoint GET `/api/web/economy/transactions/{transactionId}` in `ServerEconomyController` (oder eigener `TransactionController`) + Mapper + `requireEconomyRead`-Gate.
- [X] T033 [P] [US3] `@JsonTest` für `TransactionDetailResponse`/`TransactionLegDto`.
- [X] T034 [US3] E2E (`app`): SINGLE/TRANSFER/404/403. *(SC-004, SC-005, SC-007)*

**Checkpoint**: US1–US3 unabhängig funktionsfähig.

---

## Phase 6: User Story 4 — Live-Balance-Updates via SSE (Priority: P2)

**Goal**: `GET /api/web/economy/stream[?player=]` pusht Balance-Changes live; `?player=` filtert
serverseitig. Genau **eine** Redis-Subscription, Fan-out an N Clients.

**Independent Test**: Balance-Change → offener Stream empfängt JSON-Frame; `?player=` verwirft
Fremd-Events serverseitig; ohne Permission → 403. *(SC-006, FR-015..FR-017, FR-020, FR-021)*

- [X] T035 [US4] `EconomyStreamRegistry` in `api-realtime/.../EconomyStreamRegistry.java`: thread-sichere Emitter-Registry (`SseEmitter` + optionaler `UUID`-Filter), Fan-out `broadcast(view)` → JSON-Serialisierung + Schreiben an passende Emitter, Dead-Emitter-Cleanup bei `IOException`; schmaler Inbound-Port, damit die Bridge `api-realtime` nicht direkt importiert. *(Decision D1/D2)*
- [X] T036 [US4] `RedisEconomyStreamBridge` in `app/.../adapter/RedisEconomyStreamBridge.java` (spiegelt `RedisBalanceEventPublisher`): **einmal** `EconomyChannels.BALANCE` über `RedisCacheAdapter.subscribe` abonnieren, Wire via `PlatformProtocol.create().decode(...)` → `BalanceChangedEvent` → Registry-`broadcast`; `AutoCloseable`-Handle in `@PreDestroy` schließen; Verhalten bei Redis-Ausfall (lazy/DOWN, kein App-Crash) dokumentiert/getestet. *(FR-017)*
- [X] T037 [US4] `EconomyStreamController` in `api-realtime/.../EconomyStreamController.java`: GET `/api/web/economy/stream[?player=]` → `new SseEmitter(0L)`, Registrierung (mit `?player=`-Filter), `onCompletion`/`onTimeout`/`onError` → Deregistrierung; `requireEconomyRead`-Gate. *(FR-015, FR-016, FR-021)*
- [X] T038 [P] [US4] `@JsonTest`/Golden-Test: SSE-Frame-Feldnamen (Felder von `BalanceChangedEvent`) pinnen. *(FR-020)*
- [X] T039 [US4] E2E (`app`, Testcontainers Postgres+Redis): echten Balance-Change über REST/Service triggern → SSE-Client empfängt Frame; `?player=`-Filter (Fremd-Event kommt nicht an); **403 ohne** Permission. *(SC-006, SC-007)*

**Checkpoint**: Alle vier Stories unabhängig funktionsfähig.

---

## Phase 7: Polish & Cross-Cutting Concerns

- [X] T040 `./gradlew build` grün (Gesamt: Codegen + alle Testschichten); `plugin-protocol`-POM weiterhin **ohne** `<dependencies>`, `PlatformProtocol.create()` unverändert.
- [X] T041 PROGRESS.md: neuen Slice-Status-Abschnitt schreiben — **inkl. dokumentiertem Port-Move** (`findHistory`/`circulation`: `EconomyEventStore` → `EconomyReadStore`, Muster-Leck behoben, `EconomyHistoryService`+`EconomyStatsService` umgehängt) und additiver `EconomyEventEntry`-Erweiterung.
- [X] T042 DoD-Checkliste (CLAUDE.md) abhaken; bestätigen: **kein generischer Baustein** geändert außer dem bewussten Phase-2-Port-Move + der additiven DTO-Erweiterung; `quickstart.md`-Validierung durchgeführt.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: sofort.
- **Foundational (Phase 2 / Port-Move)**: nach Setup — **blockiert alle Stories**.
- **US1–US4 (Phase 3–6)**: alle erst nach Phase 2. Danach grundsätzlich parallelisierbar, mit zwei
  realen Kopplungen (siehe unten).
- **Polish (Phase 7)**: nach allen gewünschten Stories.

### User Story Dependencies

- **US1 (P1)**: nur Phase 2. Vollständig unabhängig (eigener Port-Read + eigene DTOs). → MVP.
- **US2 (P1)**: nur Phase 2. T019 nutzt die geteilte Keyset-Helper (gemeinsam mit `findHistory`);
  T018 erweitert den geteilten `EconomyEventEntry` (betrifft auch die bestehende Player-History —
  additiv, verhaltensneutral).
- **US3 (P2)**: nur Phase 2. Endpoint kann in `ServerEconomyController` (US2) andocken oder eigener
  Controller → ohne US2 unabhängig baubar (eigener Controller).
- **US4 (P2)**: nur Phase 2. Komplett eigener Pfad (api-realtime + app-Bridge), keine Story-Kopplung.

### Within Each User Story

Test-First wo sinnvoll; Reihenfolge: Read-Modell/DTO → Persistenz (+Test) → Use-Case (+Fake-Test) →
REST/SSE (+Gate) → @JsonTest → E2E.

### Parallel Opportunities

- **Phase 2**: T007 [P] parallel zum Port-Move (andere Datei).
- **Innerhalb US1**: T008, T009, T015 [P]. **US3**: T025, T026, T033 [P].
- **Nach Phase 2**: US1/US2/US3/US4 von verschiedenen Personen parallel (US2-T018/T019 koordinieren,
  da geteilte Dateien `EconomyEventEntry`/`JooqEconomyReadStore`).

---

## Parallel Example: User Story 1

```bash
# Read-Modell + DTOs + JSON-Test parallel (verschiedene Dateien):
Task T008: "ProjectedBalance in application/.../economy/port/ProjectedBalance.java"
Task T009: "PlayerBalancesResponse + PlayerBalanceEntry in plugin-protocol/.../economy/"
Task T015: "@JsonTest für PlayerBalancesResponse in app/.../RestDtoJsonContractTest"
```

---

## Implementation Strategy

### MVP First (US1)

1. Phase 1 Setup → 2. Phase 2 Port-Move (kritisch, blockiert alles) → 3. Phase 3 US1 →
4. **STOP & VALIDATE**: US1 (Balances) eigenständig testen → 5. demo-fähig.

### Incremental Delivery

Setup+Foundational → US1 (MVP, Spieler-Tab) → US2 (Server-History-Page) → US3 (Detailseite) →
US4 (Live-SSE). Jede Story liefert Wert ohne die vorige zu brechen.

---

## Notes

- 🔁 PUBLISH-Tasks (T009, T010, T018, T022, T026, T027) ändern `plugin-protocol` → danach
  `:plugin-protocol:publishToMavenLocal` (mehrere können gebündelt einmal publiziert werden).
- Einziger erlaubter Eingriff in **generischen/bestehenden** Code: der Phase-2-Port-Move + die
  additive `EconomyEventEntry`-Erweiterung (beide begründet in plan.md/research.md). Erscheint ein
  weiterer Eingriff nötig → als Muster-Leck in der Task vermerken, nicht stillschweigend tun.
- Commit nach jeder Task oder logischer Gruppe; an jedem Checkpoint Story unabhängig validierbar.
