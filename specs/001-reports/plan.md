# Implementation Plan: Reports (Moderation — Spieler-Meldungen)

**Branch**: `001-reports` | **Date**: 2026-06-22 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/001-reports/spec.md`

## Summary

Reports ist ein **neues, eigenständiges Moderation-Modul** (Constitution-Prinzip 16: Anschuldigung ≠
Urteil — strikt getrennt von Punishments). Ein Spieler meldet einen Mitspieler mit Kategorie +
Freitext (+ optionalem öffentlichem Chat-Kontext-Schnappschuss); das Team sieht offene Reports und
arbeitet sie über einen Status-Lebenszyklus ab. Das Modul wird nach dem etablierten Muster
(„ein Feature = ein Anstecken") angesteckt: `core-domain → application/Ports → infra-persistence
(jOOQ + Flyway) → api-rest → plugin-protocol`.

**Zentraler technischer Ansatz — und bewusster Unterschied zu Economy/Punishments:** Reports werden
**state-stored** gehalten (eine Zustandstabelle `report` + Audit-Tabelle `report_status_history`),
**nicht event-sourced**. Begründung gegen Constitution-Prinzip 6 siehe unten. Wiederverwendet werden
ausschließlich bestehende generische Bausteine (PermissionResolver-Port, MessageEnvelope/MessageCodec/
EndpointDescriptor, Optimistic-Locking-Muster, config_audit-artiges Audit-Muster, globale
Exception-Handler-Mappings). Der **einzige** Eingriff in geteilten Code ist die eine Registry-Zeile in
`PlatformProtocol.create()` — exakt wie bei Punishments.

## Technical Context

**Language/Version**: Java 21 (Toolchain), Spring Boot 3.5.x (nur in `api-rest`/`app`), jOOQ 3.21.6
**Primary Dependencies**: jOOQ + Flyway (infra-persistence), Lettuce (infra-cache, nur für Publish),
Spring Web (api-rest), `plugin-protocol` (JDK-only Contract)
**Storage**: PostgreSQL (Source of Truth) — neue Tabellen `report`, `report_status_history`; Redis
nur als Pub/Sub-Transport für das Live-Event (kein Report-State in Redis)
**Testing**: JUnit-Schichttests — Domain (rein), Use-Case (Fakes), jOOQ-Integration (Testcontainers
Postgres), E2E (`app`, REST → Postgres → Pub/Sub), Codec-Roundtrip (`plugin-protocol`)
**Target Platform**: Single Paper-Node + Spring-Boot-Backend (Constitution-Prinzip 14)
**Project Type**: Multi-Module-Backend (hexagonal/DDD) + geteilter Contract
**Performance Goals**: niedriges Volumen (Moderations-Meldungen); Live-Event beim Team in „wenigen
Sekunden" (SC-007), keine Geld-/Hochdurchsatz-Anforderung
**Constraints**: Main-Thread-Blockade ist Plugin-Thema; Backend-seitig: protocol bleibt dependency-frei;
keine generische Klasse ändern (außer Registry-Zeile)
**Scale/Scope**: Single-Server, überschaubares Report-Aufkommen; keine Sharding-/Cross-Server-Themen

## Constitution Check

*GATE: Muss vor Phase 0 bestehen. Nach Phase 1 erneut prüfen.*

| Prinzip | Anforderung | Status im Plan |
|---|---|---|
| **1** Backend = SoT, Plugin = Client | Report-State nur im Backend; Plugin liefert nur Request + Snapshot | ✅ Backend hält `report`; Plugin schreibt via REST, liest Live via Pub/Sub |
| **4** protocol dependency-frei | DTOs/Events/Codec JDK-only, kein JSON-Framework im Contract | ✅ Records + Pipe-Codec; Chat-Liste als Record-Liste (keine JSON-Lib); JSON-Mapping nur in api-rest/infra |
| **5** Schichtung | core-domain → application → infra → api-rest → app | ✅ exakt wie Economy/Punishments |
| **6** Persistenz-Wahl begründen | event-sourced **oder** state-stored, begründet | ✅ **state-stored** gewählt + begründet (siehe „Persistenz-Entscheidung") |
| **7** Idempotenz per Constraint | Doppelzustellung wirkt nie doppelt | ✅ **partieller Unique-Index** `(reporter, target) WHERE status ∈ {OPEN, IN_PROGRESS}` → Dedupe; OCC-`version` für Statuswechsel |
| **9** ein Feature = ein Anstecken | keine generische Klasse ändern | ✅ einziger geteilter Touch: `PlatformProtocol.create()`-Registry-Zeile (siehe „Muster-Lecks") |
| **10** Wiederverwenden statt neu bauen | bestehende Bausteine nutzen | ✅ PermissionResolver, MessageEnvelope/Codec/EndpointDescriptor, OCC-Muster, Audit-Muster, globale Handler-Mappings |
| **12** Berechtigungen backend-autoritativ | über PermissionResolver-Port | ✅ `report.view`/`report.handle` im Service geprüft; UI-Gate nur Komfort |
| **14** Single-Server | kein Distributed-Lock | ✅ konkurrierender Statuswechsel über lokales OCC, kein verteiltes Locking |
| **16** Reports ≠ Punishments | getrennt, kein Coupling | ✅ eigenes Modul/Tabellen; **keine** FK/Tx-Id zu Punishments; Report erzeugt nie eine Strafe |
| **18** Verhalten 1:1, Technik nicht | benennen, was wegfällt | ✅ RAM-Haltung, manuelles Inventory, §-Codes fallen weg |
| **19–22** Vertical Slice + Tests/Build grün | Tests pro Schicht | ✅ Test-Plan je Schicht (siehe Phase 1) |

**Gate-Ergebnis: BESTANDEN** (keine ungerechtfertigten Verstöße; Complexity Tracking bleibt leer).

### Persistenz-Entscheidung (gegen Prinzip 6 begründet)

**Gewählt: state-stored CRUD + Status-Feld + separate Audit-Tabelle** (`report` + `report_status_history`),
analog dem `server_config`/`config_audit`-Muster — **nicht** event-sourced wie Economy/Punishments.

- **Warum nicht event-sourced:** Economy ist event-sourced wegen Geld-Audit-Pflicht, Idempotenz und
  Concurrency über *jede* Mutation; Punishments wegen funktional lückenlosem Strafverlauf. Ein Report
  ist eine **Anschuldigung**, kein geld-/urteils-kritisches Aggregat. Der einzige reale Audit-Bedarf —
  „wer hat wann den Status geändert" — wird vollständig durch `report_status_history` gedeckt. Ein
  voller Event-Store (Event-Folding, Sequence-No-Projektion, Replay) wäre hier unbegründeter Ballast.
- **Concurrency-Sicherheit trotzdem:** Optimistic Locking über eine `version`-Spalte auf `report`
  (UPDATE … WHERE version = :expected) deckt FR-014 (konkurrierender Statuswechsel) ohne Event-Store ab
  — dasselbe OCC-Muster wie Economys `player_balance`.
- **Idempotenz trotzdem (Prinzip 7):** Der **partielle Unique-Index** `(reporter_uuid, target_uuid)
  WHERE status IN ('OPEN','IN_PROGRESS')` ist der natürliche Dedupe-/Idempotenz-Schlüssel. Bewusster
  Kontrast zu Punishments, das *keinen* partiellen Unique-Index nutzt, weil dort die Invariante an
  `now()` (Ablauf) hängt; hier hängt sie an einem **statischen Statuswert** → ein partieller
  Unique-Index ist exakt das richtige, einfachere Mittel.
- **Rule-of-three bleibt unberührt:** Da Reports *kein* drittes event-sourced Geschwister ist, wird die
  in PROGRESS.md notierte Extraktion (TransactionId/PunishmentTxId + Insert-in-Tx+Projektion) hier
  nicht ausgelöst.

## Wiederverwendung (EXPLIZIT — nicht neu bauen)

| Bestehender Baustein | Ort | Verwendung in Reports | Änderung nötig? |
|---|---|---|---|
| **PermissionResolver** (Port) | `application/.../security/PermissionResolver.java` — `hasPermission(UUID, String)` | Team-Gate `report.view`/`report.handle` | **Nein** — nur injizieren |
| **PermissionDeniedException** | `application/.../security/` | wird im ReportService geworfen | **Nein** — wiederverwenden |
| **PermissionDeniedException → 403** | bestehendes **globales** `@RestControllerAdvice` (in `PunishmentExceptionHandler`) | greift global auch für ReportController | **Nein** — NICHT re-deklarieren (siehe Muster-Lecks) |
| **IllegalArgumentException → 400** | globales Mapping in `EconomyExceptionHandler` | Fallback Bad Request | **Nein** — NICHT re-deklarieren |
| **MessageEnvelope / MessageCodec / MessageProtocol** | `protocol/core` | Wire-Framing/-Routing fürs Live-Event | **Nein** — `ReportChangedEventCodec implements MessageCodec` |
| **Channels.of(feature, topic)** | `protocol/core` | `mc:report:changed` | **Nein** — nur aufrufen |
| **EndpointDescriptor / HttpMethod** | `protocol/core` | `ReportEndpoints`-Konstanten | **Nein** — nur instanziieren |
| **Optimistic-Locking-Muster** | wie Economy `player_balance.version` | OCC auf `report.version` | **Nein** — Muster nachbauen, keine Klasse teilen |
| **Audit-Muster** | wie `server_config`/`config_audit` | `report_status_history` | **Nein** — Muster nachbauen |
| **Clock-Bean** | definiert in `PunishmentConfig` (`Clock.systemUTC()`) | `ReportService` injiziert `Clock` | **Nein** — bestehende Bean wiederverwenden, NICHT neu definieren (sonst Duplikat-Bean-Konflikt) |
| **RedisCacheAdapter.publish** | `infra-cache` | Live-Event-Transport | **Nein** — nur aufrufen |
| **Flyway/jOOQ-Codegen-Pipeline** | `infra-persistence` | neue Migration → generierte Tabellen | **Nein** — neue `V7`-Migration ergänzen |
| **PlayerRepository / FK auf `player(uuid)`** | bestehend (UUID-zentrisch) | Reporter/Ziel müssen bekannte Spieler sein | **Nein** — FK-Constraint nutzen (unbekanntes Ziel → Ablehnung) |

## Muster-Lecks (STOPP-Marker: wo eine generische/geteilte Klasse berührt würde)

1. **`PlatformProtocol.create()`** (`plugin-protocol/.../protocol/PlatformProtocol.java`) — es wird
   **eine Zeile** ergänzt: `ReportChangedEventCodec.INSTANCE` in die Codec-Liste. **Kein Leck** —
   Constitution-Prinzip 9 erlaubt genau diesen Registry-Eintrag explizit; identisch zu wie Punishments
   angesteckt wurde. Es wird **keine** Logik dieser Klasse verändert.

2. **`PersistenceConfig`** (`app/.../bootstrap/config/PersistenceConfig.java`) — es wird **eine
   `@Bean`-Methode** `ReportRepository` ergänzt (dort ist bereits `PunishmentEventStore` gewireter).
   **Kein Leck** — die Composition-Root-Config ist der vorgesehene Ort fürs „Anstecken"; additive
   Bean-Ergänzung, keine generische Logik geändert. (Muster bewusst gespiegelt: Repo-Beans in
   `PersistenceConfig`, Service/Publisher in der Feature-Config `ReportConfig`.)

3. **Globaler 403-/400-Mapping-Sitz** — *Beobachtung, kein erzwungener Eingriff.* `PermissionDeniedException
   → 403` lebt heute **global** in `PunishmentExceptionHandler` (Handler sind **nicht** controller-scoped,
   verifiziert). Reports nutzt es **ungeändert** mit. Würde `ReportExceptionHandler` dasselbe
   `@ExceptionHandler(PermissionDeniedException)` erneut deklarieren, entstünde ein **ambiguer
   Mapping-Konflikt** → das *wäre* ein Leck. Konsequenz: `ReportExceptionHandler` deklariert **nur**
   report-eigene Exceptions. **Optionaler späterer Refactor (Rule-of-three, NICHT dieser Slice):**
   `PermissionDeniedException → 403` in einen neutralen `SecurityExceptionHandler` heben — bewusster
   Vor-Refactor an geteiltem Code, separat zu begründen, nicht ins Feature geschmuggelt.

→ Außer (1) und (2) — beide additiv und vom Muster ausdrücklich vorgesehen — wird **keine** bestehende
Klasse geändert. Kein echtes Muster-Leck.

## Project Structure

### Documentation (this feature)

```text
specs/001-reports/
├── spec.md              # /speckit.specify (+ clarify) Output
├── plan.md              # Dieses Dokument
├── research.md          # Phase 0
├── data-model.md        # Phase 1
├── quickstart.md        # Phase 1
├── contracts/           # Phase 1
│   ├── rest-api.md
│   └── protocol.md
└── checklists/
    └── requirements.md
```

### Source Code (neue Dateien, gespiegelt an Punishments)

```text
core-domain/src/main/java/com/mcplatform/domain/report/
├── Report.java                       # Aggregat (record, state-stored): id, reporter, target,
│                                     #   category, detail, status, chatContext, createdAt,
│                                     #   lastHandledBy, lastStatusChangeAt, version
├── ReportId.java                     # Value Object (UUID)
├── ReportStatus.java                 # enum OPEN|IN_PROGRESS|RESOLVED|REJECTED + erlaubte Übergänge
├── ReportCategory.java               # enum CHEATING|BELEIDIGUNG|SPAM_WERBUNG|TEAMING_BUG_ABUSE|SONSTIGES
├── ChatContextEntry.java             # record: sender (PlayerId), text (String), at (Instant)
├── ChatContext.java                  # Value Object: List<ChatContextEntry> + Größen-/Längen-Validierung
├── ReportChange.java                 # kleines Notifikations-Record (CREATED|STATUS_CHANGED) für den Publisher
├── ReportValidationException.java    # Self-Report, leeres/zu langes Detail, ungültige Kategorie, Chat zu groß
└── InvalidStatusTransitionException.java

application/src/main/java/com/mcplatform/application/report/
├── ReportService.java                # Use Cases: create / listOpen / changeStatus
└── port/
    ├── ReportRepository.java         # create(dedupe), findOpen, find(id), changeStatus(OCC), lastCreatedAtByReporter
    ├── ReportPublisher.java          # publish(ReportChange)  — reine Live-Benachrichtigung, KEIN Event-Store
    ├── ReportNotFoundException.java
    └── ReportCooldownException.java

infra-persistence/src/main/java/com/mcplatform/persistence/
└── JooqReportRepository.java         # implements ReportRepository (state-stored + OCC + Audit-Insert, 1 TX)
infra-persistence/src/main/resources/db/migration/
├── V7__report_schema.sql             # report + report_status_history (+ partieller Unique-Index)
└── V8__seed_report_permissions.sql   # report.view/report.handle → MODERATOR (ADMIN via '*')

api-rest/src/main/java/com/mcplatform/api/rest/
├── ReportController.java             # POST /api/reports, GET /api/reports/open, POST /api/reports/{id}/status
├── ReportExceptionHandler.java       # NUR report-eigene Exceptions (422/409/404/429)
└── support/ReportMapper.java         # domain ↔ protocol DTOs (epoch-milli, parse category, ChatContext)

plugin-protocol/src/main/java/com/mcplatform/protocol/report/
├── ReportChannels.java               # CHANGED = Channels.of("report","changed")
├── ReportEndpoints.java              # CREATE, LIST_OPEN, CHANGE_STATUS (EndpointDescriptor)
├── CreateReportRequest.java          # reporter, target, category, detail, List<ChatMessage> chatContext
├── ChatMessage.java                  # sender (UUID), text, timestampEpochMilli  (DTO im Request/Response)
├── ChangeStatusRequest.java          # newStatus, handledBy
├── ReportResponse.java               # id, reporter, target, category, detail, status, createdAtEpochMilli,
│                                     #   lastHandledBy, lastStatusChangeAtEpochMilli, chatContext[], version
├── ReportChangedEvent.java           # reportId, reporter, target, category, status, changeType, ts (OHNE Chat)
└── ReportChangedEventCodec.java      # messageType "report.changed", Pipe-Wire

plugin-protocol/src/main/java/com/mcplatform/protocol/PlatformProtocol.java   # +1 Zeile: ReportChangedEventCodec.INSTANCE

app/src/main/java/com/mcplatform/bootstrap/
├── config/ReportConfig.java          # @Bean ReportService (+ Cooldown-Config), @Bean ReportPublisher
├── config/PersistenceConfig.java     # +@Bean ReportRepository (additive Ergänzung)
└── adapter/RedisReportEventPublisher.java   # ReportChange → ReportChangedEvent → protocol.encode → redis.publish
```

**Structure Decision**: Multi-Module-hexagonal (bestehend). Reports ist ein **paralleles Geschwister**
zu Punishments mit identischer Schichtung, aber **state-stored** statt event-sourced. Alle neuen Klassen
liegen in feature-eigenen Packages `…report`; geteilter Code wird nur über die zwei additiven
Anstech-Punkte (PlatformProtocol-Zeile, PersistenceConfig-Bean) und die Wiederverwendung der Ports/Core
berührt.

## Phase 0 — Research

Siehe [research.md](./research.md): Persistenz-Wahl, Dedupe via partiellem Unique-Index (vs
Punishments-Lock), Cooldown-Durchsetzung & -Konfiguration, Chat-Kontext als JSONB
(Serialisierungs-Ort), Status-Historie-Tabelle, Publisher-Benennung (Live-Event ohne Event-Store),
REST-Ressourcen-Schnitt & Status-Codes. **Keine offenen NEEDS CLARIFICATION** (alle vier Spec-Fragen +
zwei Clarify-Fragen beantwortet).

## Phase 1 — Design & Contracts

- **Datenmodell** → [data-model.md](./data-model.md): Entitäten, Tabellen-DDL-Skizze, Statusübergangs-
  Matrix, Validierungsregeln, Indizes (inkl. partiellem Unique-Index).
- **Contracts** → [contracts/rest-api.md](./contracts/rest-api.md) (Endpunkte + Status-Codes) und
  [contracts/protocol.md](./contracts/protocol.md) (DTOs, ReportChannels/ReportEndpoints,
  ReportChangedEvent-Wire-Format).
- **Agent-Context**: CLAUDE.md-Plan-Referenz aktualisieren (sofern SPECKIT-Marker vorhanden) bzw. via
  optionalem `agent-context.update`-Hook.

### Test-Plan je Schicht (Definition of Done, Prinzip 22)

- **Domain (rein):** `ReportStatus`-Übergangsmatrix (erlaubt/verboten), `ChatContext`-Validierung
  (Größe/Textlänge), `Report`-Self-Report-Regel, Statuswechsel setzt handler+timestamp.
- **Use-Case (Fakes):** `ReportService` — Permission-Gate (view/handle → 403-Pfad), Cooldown-Ablehnung,
  Dedupe liefert bestehenden Report, Publish-once bei create + Statuswechsel, ungültiger Übergang.
- **jOOQ-Integration (Testcontainers):** Dedupe über partiellen Unique-Index (paralleler Doppel-Create →
  genau ein offener Report), OCC bei konkurrierendem Statuswechsel, Audit-Zeile je Wechsel,
  Chat-Kontext unverändert gespeichert/gelesen, FK-Ablehnung unbekanntes Ziel.
- **Protocol (rein-JDK):** `ReportChangedEventCodec`-Roundtrip (inkl. Delimiter/Unicode + Golden-Wire),
  `EndpointDescriptor.expand`.
- **E2E (`app`):** REST create → Postgres → Pub/Sub-Event; offene Liste mit/ohne Permission (403);
  Statuswechsel-Pfad; 422/409/429-Fehlerpfade; Chat-Kontext Round-Trip über echtes JSON.

## Complexity Tracking

> Keine Constitution-Verstöße — Tabelle bleibt leer.

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| — | — | — |
