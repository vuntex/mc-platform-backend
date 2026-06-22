# Phase 1 — Data Model: Reports

State-stored (kein Event-Sourcing). Zwei neue Tabellen; Domain-Typen framework-frei in
`core-domain/report`.

## Entitäten (Domain)

### Report (Aggregat, `record`)
| Feld | Typ | Notiz |
|---|---|---|
| `id` | `ReportId` (UUID) | PK |
| `reporter` | `PlayerId` (UUID) | meldender Spieler; FK → player |
| `target` | `PlayerId` (UUID) | gemeldeter Spieler; FK → player; `reporter ≠ target` (FR-003) |
| `category` | `ReportCategory` | festes Enum (FR-002) |
| `detail` | `String` | Pflicht, nicht leer, ≤ 256 Zeichen (FR-007) |
| `chatContext` | `ChatContext` | optional (leer erlaubt), unveränderlich (FR-018/019) |
| `status` | `ReportStatus` | Lebenszyklus |
| `createdAt` | `Instant` | Erstellzeit |
| `lastHandledBy` | `PlayerId` \| null | zuletzt bearbeitender Teamler (null solange OPEN) |
| `lastStatusChangeAt` | `Instant` \| null | Zeit des letzten Wechsels |
| `version` | `long` | OCC (FR-014) |

### ReportStatus (enum) + Übergangsmatrix (FR-010)
`OPEN`, `IN_PROGRESS`, `RESOLVED`, `REJECTED`

> **Benennungs-Mapping (kanonisch vs. Anzeige):** Enum-Konstanten, Wire-/Pub-Sub-Werte und DB-Spalten
> verwenden ausschließlich die **englischen** Werte oben. Die in der Spec verwendeten deutschen Begriffe
> (`OFFEN`, `IN_BEARBEITUNG`, `ERLEDIGT`, `ABGELEHNT`) sind reine **Anzeige-Labels** (Plugin-Menü) und
> werden NICHT persistiert/übertragen. Mapping: OFFEN=`OPEN`, IN_BEARBEITUNG=`IN_PROGRESS`,
> ERLEDIGT=`RESOLVED`, ABGELEHNT=`REJECTED`. Gleiches Prinzip gilt für die Kategorie-Werte (Enum/Wire
> = die in FR-002 genannten Konstanten; Anzeige-Labels sind Plugin-seitig).

| von \ nach | OPEN | IN_PROGRESS | RESOLVED | REJECTED |
|---|---|---|---|---|
| **OPEN** | – | ✅ | – | ✅ |
| **IN_PROGRESS** | – | – | ✅ | ✅ |
| **RESOLVED** | – | – | – | – |
| **REJECTED** | – | – | – | – |

- „Offen" für die Team-Liste (FR-008/013) = `{OPEN, IN_PROGRESS}`.
- `RESOLVED`/`REJECTED` = terminal (FR-013). Jeder andere Übergang → `InvalidStatusTransitionException`
  (409). Übergangslogik lebt in der Domäne (`ReportStatus.canTransitionTo` bzw. `Report.transitionTo`).

### ReportCategory (enum, FR-002)
`CHEATING`, `BELEIDIGUNG`, `SPAM_WERBUNG`, `TEAMING_BUG_ABUSE`, `SONSTIGES`. Unbekannt → 422.

### ChatContext (Value Object) + ChatContextEntry (record)
- `ChatContext` = geordnete `List<ChatContextEntry>`; Validierung: ≤ 30 Einträge; je Eintrag Text ≤ 256
  Zeichen; leer erlaubt. Kann Einträge **mehrerer Absender** enthalten (Clarify).
- `ChatContextEntry(PlayerId sender, String text, Instant at)`.

### ReportChange (Notifikations-Record für Publisher)
`reportId, reporter, target, category, status, changeType (CREATED|STATUS_CHANGED), occurredAt` —
trägt **keinen** Chat-Kontext (FR-015).

## Tabellen (Flyway `V7__report_schema.sql`, DDL-Skizze)

```sql
CREATE TABLE report (
    report_id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    reporter_uuid        UUID NOT NULL REFERENCES player(uuid),
    target_uuid          UUID NOT NULL REFERENCES player(uuid),
    category             VARCHAR(32)  NOT NULL,            -- ReportCategory
    detail               VARCHAR(256) NOT NULL,
    -- Chat-Kontext liegt in der Kind-Tabelle report_chat_message (s.u.), NICHT als JSONB (research R5).
    status               VARCHAR(16)  NOT NULL DEFAULT 'OPEN',       -- ReportStatus
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_handled_by      UUID         REFERENCES player(uuid),       -- null bis erster Wechsel
    last_status_change_at TIMESTAMPTZ,
    version              BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT chk_report_not_self CHECK (reporter_uuid <> target_uuid)
);

-- Dedupe/Idempotenz (Prinzip 7): höchstens EIN offener Report je (Reporter, Ziel)
CREATE UNIQUE INDEX uq_report_open
    ON report (reporter_uuid, target_uuid)
    WHERE status IN ('OPEN','IN_PROGRESS');

-- Team-Liste offener Reports
CREATE INDEX idx_report_open ON report (status, created_at) WHERE status IN ('OPEN','IN_PROGRESS');
-- Cooldown-Lookup (jüngster Report eines Reporters)
CREATE INDEX idx_report_reporter_created ON report (reporter_uuid, created_at DESC);

-- Chat-Kontext-Schnappschuss als Kind-Tabelle (kein JSONB → keine JSON-Lib in infra-persistence).
CREATE TABLE report_chat_message (
    id          BIGSERIAL PRIMARY KEY,
    report_id   UUID NOT NULL REFERENCES report(report_id) ON DELETE CASCADE,
    ordinal     INT  NOT NULL,                 -- erhält Reihenfolge
    sender_uuid UUID NOT NULL,                 -- Snapshot-Daten, bewusst KEIN FK
    text        VARCHAR(256) NOT NULL,
    sent_at     TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_report_chat_report ON report_chat_message (report_id, ordinal);

CREATE TABLE report_status_history (
    id           BIGSERIAL PRIMARY KEY,
    report_id    UUID NOT NULL REFERENCES report(report_id),
    old_status   VARCHAR(16),                 -- null beim Anlegen
    new_status   VARCHAR(16) NOT NULL,
    changed_by   UUID NOT NULL REFERENCES player(uuid),  -- Reporter bei CREATE, Teamler bei Wechsel
    changed_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_report_history_report ON report_status_history (report_id, changed_at);
```

`V8__seed_report_permissions.sql`: `INSERT … team_role_permission` → `report.view`, `report.handle`
für Rolle `MODERATOR` (ADMIN hat `*`).

## Schreibpfade (alle in EINER jOOQ-Transaktion)

- **create:** INSERT `report` (status OPEN) → INSERT `report_status_history` (old=null, new=OPEN,
  changed_by=reporter). Bei Verletzung von `uq_report_open` → SELECT bestehenden offenen Report,
  zurückgeben (idempotenter Dedupe-Treffer). Bei FK-Verletzung (unbekanntes Ziel/Reporter) →
  `ReportValidationException`.
- **changeStatus:** Domäne prüft Übergang → `UPDATE report SET status,last_handled_by,
  last_status_change_at,version=version+1 WHERE report_id=? AND version=:expected`; 0 Zeilen → 409
  (OCC). → INSERT `report_status_history` (old,new,changed_by=Teamler).

## Validierungsregeln (Herkunft)
- Self-Report verboten (FR-003) — Domäne + DB-CHECK.
- Detail nicht leer, ≤ 256 (FR-007) — Domäne.
- Kategorie ∈ Enum (FR-002) — Mapper/Domäne.
- Chat ≤ 30 Einträge, Text ≤ 256 (Assumptions) — `ChatContext`-VO.
- Übergänge nur laut Matrix (FR-010) — Domäne.
- Dedupe (FR-004) — partieller Unique-Index.
- Cooldown (FR-005) — Service + Clock + `lastCreatedAtByReporter`.
- Permissions (FR-009/012) — Service + PermissionResolver.
