# Implementation Plan: Web-Economy Read-Backend + SSE (Slice 1)

**Branch**: `007-web-economy-read` | **Date**: 2026-07-01 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/007-web-economy-read/spec.md`

## Summary

Read-only Economy-Backend für das Webinterface: ein Sammel-Balances-Endpunkt pro Spieler, eine
serverweite Transaktions-History (mit `source`-Filter + Keyset-Pagination), eine Transaktions-
Detailseite per `transactionId` (Transfer → zwei Legs) und eine SSE-Bridge, die den **bestehenden**
`mc:economy:balance`-Channel ans Web durchreicht. Keine Events, Writes, Locking oder Idempotenz.

Architektonischer Kern: ein neuer Outbound-Port **`EconomyReadStore`**, in den die heute im
`EconomyEventStore` falsch beheimateten reinen Projektions-Reads (`findHistory`, `circulation`) 1:1
umziehen (Muster-Leck-Behebung), plus drei neue Read-Methoden (`playerBalances`,
`findServerHistory`, `findTransaction`). Alle vier Endpunkte sind backend-autoritativ über
`permission.economy.read` gegated.

## Technical Context

**Language/Version**: Java 21 (Toolchain), Spring Boot 3.5.x

**Primary Dependencies**: jOOQ 3.21.6 (infra-persistence), Lettuce (infra-cache, framework-frei),
Spring Web/MVC (api-rest, api-realtime `SseEmitter`), Flyway (Migrationen), `plugin-protocol`
(JDK-only DTOs/Endpoints)

**Storage**: PostgreSQL — bestehende Tabellen `economy_event` (append-only Event Store),
`player_balance` (Projektion), `currency`, `player`. Read-only in diesem Slice.

**Testing**: JUnit 5 + AssertJ; Testcontainers (Postgres + Redis) für jOOQ-Integration & app-E2E;
Fakes für Application-Use-Cases; `@JsonTest` für DTO-Contract.

**Target Platform**: Linux-Server (Spring-Boot-Backend, Single-Node)

**Project Type**: Multi-Module-Backend (hexagonal/DDD) — kein Frontend in diesem Repo

**Performance Goals**: Read-Pfad für Web-Dashboard bei 200+ Spielern; serverweite History muss bei
wachsendem Event-Store sortier-effizient bleiben → neuer Index `idx_event_seq_desc`.

**Constraints**: `core-domain` framework-frei und unberührt; `plugin-protocol` JDK-only (POM ohne
`<dependencies>`); Backend-Analogie zum „Main-Thread nie blockieren" = SSE/Redis nicht blockierend
(Lettuce-Listener-Thread + `SseEmitter`); `PlatformProtocol.create()` unverändert.

**Scale/Scope**: 4 REST-/SSE-Endpunkte, 1 neuer Port, 1 neuer jOOQ-Adapter, 3 neue Use-Cases,
~4 neue protocol-DTOs + 3 Endpoint-Konstanten, 1 Flyway-Migration (V16), 1 neue Permission-Konstante.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Prinzip | Bewertung |
|---------|-----------|
| **I.1 Backend = SoT, Plugin = Client** | ✅ Reine Reads auf der Wahrheit; kein neuer Schreibpfad. |
| **I.4 `plugin-protocol` JDK-only** | ✅ Neue DTOs sind Records ohne Imports; POM bleibt ohne `<dependencies>`; `PlatformProtocol.create()` unverändert; **kein neuer Codec**. |
| **II.5 Schichten** | ✅ Read-Modelle als Application-Projektionen (keine `core-domain`-Typen, keine Invarianten); jOOQ nur in infra-persistence; Controller dünn. |
| **II.6 Persistenz-Wahl begründet** | ✅ Reiner Projektions-Read auf dem bestehenden Event-Store; **kein** Event-Sourcing/Locking für Reads. |
| **IV.10 Wiederverwenden statt neu bauen** | ✅ Limit-Clamp (`EconomyHistoryService`), Keyset-Mechanik, `EconomyMapper`-Konvention, `EconomyExceptionHandler`, `PermissionResolver`, `RedisCacheAdapter.subscribe`, `PlatformProtocol` werden wiederverwendet. |
| **V.12 Permissions backend-autoritativ** | ✅ `permission.economy.read` über `PermissionResolver`; 403 via `PermissionDeniedException`. |
| **VII.19 Vertical Slice** | ✅ (Domäne n/a)→Persistenz→Application→REST/SSE→protocol, voll getestet. |

**Bewusste, dokumentationspflichtige Struktur-Eingriffe (kein verstecktes Muster-Leck):**

1. **Port-Move `EconomyEventStore` → `EconomyReadStore`.** `findHistory` + `circulation` sind reine
   Projektions-Reads ohne Event-/Versions-/Idempotenz-Bezug und gehören nicht in den security-
   kritischen Append-/Transfer-Port. Sie ziehen **1:1** (Impl byte-gleich) um. `EconomyEventStore`
   behält nur `currentBalance`, `ensureZeroBalance`, `append`, `transfer`, `findByTransactionId`,
   `findTransfer`. **Rewiring:** `EconomyHistoryService` **und** der bereits existierende
   `EconomyStatsService` (uncommitted Stats/Alert-Arbeit auf diesem Branch) hängen ihren Port-Bezug
   auf `EconomyReadStore` um.
2. **Additive Erweiterung `EconomyEventEntry`** (protocol-DTO) um `playerUuid` + `playerName` für
   die serverweite „wer"-Spalte. Wire-/JSON-additiv (Records, unbekannte Felder ignoriert);
   Konstruktor-Aufrufer (Mapper) werden angepasst. Player-History bleibt verhaltensgleich (Top-Level
   `player` unverändert; neue Felder dort befüllt aus dem bekannten Spieler).

→ **Gate PASS.** Keine *generische* Infrastruktur (FeatureCache/EventBus/MenuBuilder/MessageEnvelope
/Codec-Routing) wird verändert. Die zwei Eingriffe oben sind bewusste, begründete, in PROGRESS.md
nachzuziehende Schnitte — kein geschmuggelter Umbau.

## Project Structure

### Documentation (this feature)

```text
specs/007-web-economy-read/
├── plan.md              # This file
├── research.md          # Phase 0 output (Entscheidungen: SSE-Bridge, Entry-Erweiterung, Index, 404)
├── data-model.md        # Phase 1 output (Read-Modelle + DTO-Felder)
├── quickstart.md        # Phase 1 output (Build/Publish/Test-Schritte, DoD)
├── contracts/
│   └── economy-read-endpoints.md   # Phase 1 output (REST + SSE Contract)
└── checklists/
    └── requirements.md  # bereits aus /speckit-specify
```

### Source Code (repository root)

```text
application/src/main/java/com/mcplatform/application/economy/
├── port/EconomyReadStore.java          # NEU — Outbound-Read-Port
├── port/EconomyEventStore.java         # GEÄNDERT — findHistory/circulation entfernt
├── port/ProjectedBalance.java          # NEU — Read-Modell (currency+display+balance)
├── port/TransactionDetail.java         # NEU — Read-Modell (kind, legs, metadata…)
├── port/TransactionLeg.java            # NEU — Read-Modell (playerUuid, name, type, balanceAfter)
├── EconomyHistoryEntry.java            # GEÄNDERT — + playerUuid/playerName
├── EconomyHistoryService.java          # GEÄNDERT — Port-Bezug → EconomyReadStore
├── EconomyStatsService.java            # GEÄNDERT — Port-Bezug → EconomyReadStore
├── PlayerBalancesQuery.java            # NEU — Use-Case A
├── ServerHistoryQuery.java             # NEU — Use-Case C (Limit-Clamp wiederverwendet)
└── TransactionDetailQuery.java         # NEU — Use-Case D (+ EconomyTransactionNotFoundException)

infra-persistence/src/main/java/com/mcplatform/persistence/
├── JooqEconomyReadStore.java           # NEU — implementiert EconomyReadStore
└── JooqEconomyRepository.java          # GEÄNDERT — findHistory/circulation raus (in ReadStore)
infra-persistence/src/main/resources/db/migration/
└── V16__economy_event_seq_index.sql    # NEU — idx_event_seq_desc

api-rest/src/main/java/com/mcplatform/api/rest/
├── PlayerBalancesController.java        # NEU — GET /api/web/economy/players/{uuid}/balances
├── ServerEconomyController.java         # NEU — GET /api/web/economy/history, /transactions/{txId}
├── support/EconomyMapper.java           # GEÄNDERT — neue Mapper-Methoden
└── EconomyExceptionHandler.java         # GEÄNDERT — + 404 economy_transaction_not_found

api-realtime/src/main/java/com/mcplatform/api/realtime/
├── EconomyStreamController.java         # NEU — GET /api/web/economy/stream[?player=]
└── EconomyStreamRegistry.java           # NEU — Emitter-Registry + Fan-out (implements Port)

app/src/main/java/com/mcplatform/bootstrap/
├── adapter/RedisEconomyStreamBridge.java # NEU — subscribe(mc:economy:balance) → decode → Registry
└── config/…                             # GEÄNDERT — Beans verdrahten (ReadStore, Bridge, Queries)

plugin-protocol/src/main/java/com/mcplatform/protocol/economy/
├── PlayerBalancesResponse.java          # NEU — { player, balances[] }
├── PlayerBalanceEntry.java              # NEU — { currencyCode, displayName, symbol, decimalPlaces, balance }
├── TransactionDetailResponse.java       # NEU — { transactionId, correlationId, kind, …, legs[] }
├── TransactionLegDto.java               # NEU — { playerUuid, playerName, eventType, balanceAfter }
├── EconomyEventEntry.java               # GEÄNDERT — + playerUuid/playerName
└── EconomyEndpoints.java                # GEÄNDERT — + LIST_BALANCES, SERVER_HISTORY, GET_TRANSACTION
```

**Structure Decision**: Bestehende hexagonale Multi-Module-Struktur (PROGRESS.md §5). Neue Dateien
folgen exakt den etablierten Paketen; dünne, getrennte Controller (ein Feature = ein Anstecken). Der
einzige Eingriff in *bestehende* Dateien ist der dokumentierte Port-Move + Mapper-/Handler-/DTO-
Erweiterung; keine neue generische Schicht.

## Schlüssel-Entscheidungen (Begründung in research.md)

- **SSE ohne neue DTOs/Codecs.** `RedisEconomyStreamBridge` (app, spiegelt
  `RedisBalanceEventPublisher`) subscribt **einmal** `mc:economy:balance`, decodiert via
  `PlatformProtocol.create().decode(wire)` zu `BalanceChangedEvent`, übergibt eine
  application-neutrale View an `EconomyStreamRegistry` (api-realtime), die zu JSON serialisiert und
  an alle (bzw. `?player=`-gefiltert) registrierten `SseEmitter` fan-out macht. **Genau eine** Redis-
  Subscription, N Web-Clients — nicht eine Verbindung pro Client.
- **Geteilte Keyset-Logik.** `findHistory` (player-gefiltert) und `findServerHistory` (serverweit)
  delegieren an **eine** private jOOQ-Helper-Methode mit optionalem player-Predicate + optionalem
  `source`-Filter. Keine duplizierte Pagination.
- **Transaktions-Detail per `transactionId`.** Event laden; bei `TRANSFER_*` Gegen-Leg über
  `metadata->>'correlation_id'` nachladen → zwei Legs (beide Player-Namen gejoint), `kind=TRANSFER`;
  sonst ein Leg, `kind=SINGLE`. Leeres Ergebnis → `EconomyTransactionNotFoundException` → 404.
- **Index V16.** `CREATE INDEX idx_event_seq_desc ON economy_event (sequence_no DESC);` —
  `idx_event_player_currency` greift für serverweite Sortierung nicht (player_uuid als Leitspalte),
  `sequence_no` (BIGSERIAL) hat allein keinen Index. `source`-Index NICHT spekulativ.

## Phase 0 / 1 Outputs

- **research.md** — Begründete Entscheidungen zu: SSE-Schicht-Verortung & Lifecycle/Backpressure,
  `EconomyEventEntry`-Erweiterung vs. eigenes Server-DTO, Reuse vs. eigenes Server-History-Response,
  Transfer-mit-fehlendem-Gegen-Leg, Index-Wahl, 404-Mapping.
- **data-model.md** — Felder aller neuen Read-Modelle (Application) + DTOs (protocol) + geänderte
  Felder; Mapping-Tabelle DB-Spalte → Read-Modell → DTO.
- **contracts/economy-read-endpoints.md** — die 4 Endpunkt-Contracts (Pfad, Query, Response-Form,
  Fehlercodes 400/403/404) + Endpoint-Descriptor-Konstanten + SSE-Frame-Format.
- **quickstart.md** — Build/Publish/Test-Reihenfolge + Definition of Done (inkl. Port-Move-Doku in
  PROGRESS.md).

## Complexity Tracking

*Keine ungerechtfertigten Constitution-Verstöße.* Die zwei Struktur-Eingriffe (Port-Move,
DTO-Erweiterung) sind oben begründet und in PROGRESS.md nachzuziehen; sie sind die *Behebung* eines
Musterlecks bzw. additive Contract-Erweiterungen, keine neue Komplexität.
