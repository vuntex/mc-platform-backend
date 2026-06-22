# Phase 0 — Research: Reports

Alle Entscheidungen sind durch Spec + Clarify gedeckt; hier konsolidiert mit Begründung und
verworfenen Alternativen. **Keine offenen NEEDS CLARIFICATION.**

## R1 — Persistenz: state-stored statt event-sourced

- **Decision:** Zustandstabelle `report` (aktueller Stand) + Audit-Tabelle `report_status_history`
  (eine Zeile je Statuswechsel). Kein Event-Store, kein Folding/Replay.
- **Rationale:** Report = Anschuldigung, kein geld-/urteils-kritisches Aggregat. Der reale Audit-Bedarf
  („wer hat wann den Status geändert") ist vollständig durch die History-Tabelle gedeckt. Spiegelt das
  bestehende `server_config`/`config_audit`-Muster (state + audit). Constitution-Prinzip 6 verlangt
  eine *begründete* Wahl — diese ist begründet.
- **Alternatives considered:** (a) Event-sourced wie Punishments — verworfen: unbegründeter Ballast
  (Sequence-No-Projektion, Replay) ohne Audit-Pflicht über jede Mutation. (b) Nur `report`-Tabelle ohne
  History — verworfen: FR-011 verlangt nachvollziehbare Status-Historie (Teamler + Zeitstempel je
  Wechsel).

## R2 — Idempotenz/Dedupe: partieller Unique-Index

- **Decision:** `CREATE UNIQUE INDEX … ON report (reporter_uuid, target_uuid) WHERE status IN
  ('OPEN','IN_PROGRESS')`. Zweiter Create im offenen Fenster verletzt den Index → Adapter fängt die
  Integrity-Violation und liefert den bestehenden offenen Report zurück (idempotenter Treffer, kein
  Fehler).
- **Rationale:** Erfüllt Constitution-Prinzip 7 (Doppelzustellung wirkt nie doppelt) mit einem
  deterministischen *natürlichen* Schlüssel — kein separater Transaction-Id nötig. Die Invariante ist
  **statisch** (Statuswert), daher per Index ausdrückbar.
- **Contrast/Alternatives:** Punishments nutzt **keinen** partiellen Unique-Index, sondern
  `SELECT … FOR UPDATE` + Re-Check, weil dort die Invariante an `now()` (Ablauf) hängt — ein statischer
  Index kann „abgelaufen" nicht ausdrücken. Bei Reports gibt es kein `now()` in der Invariante → der
  Index ist das einfachere, korrekte Mittel. Separater Tx-Id verworfen (überflüssig).

## R3 — Concurrency beim Statuswechsel: Optimistic Locking

- **Decision:** `version BIGINT` auf `report`; Statuswechsel als `UPDATE report SET status=…,
  last_handled_by=…, last_status_change_at=…, version=version+1 WHERE report_id=? AND version=:expected`.
  0 betroffene Zeilen → konkurrierende Änderung → `409 Conflict` (bzw. Re-Read).
- **Rationale:** Deckt FR-014 lokal (Single-Server, Prinzip 14) ohne verteiltes Locking. Dasselbe
  OCC-Muster wie Economys `player_balance.version` — Wiederverwendung des Musters, nicht der Klasse.
- **Alternatives:** `SELECT … FOR UPDATE` — funktioniert auch, aber OCC ist hier leichter und reicht
  bei geringem Konfliktdruck.

## R4 — Cooldown pro Reporter

- **Decision:** Mindestabstand (Default **60s**) zwischen zwei Erstellungen desselben Reporters. Der
  `ReportService` liest `lastCreatedAtByReporter(reporter)` (Port-Methode) und vergleicht mit
  `clock.instant()`; Verstoß → `ReportCooldownException` → **HTTP 429**.
- **Konfiguration:** Cooldown-Dauer als Spring-Property (`mcplatform.reports.cooldown-seconds`,
  Default 60), in `ReportConfig` in den Service injiziert → ohne Code-Änderung anpassbar.
- **Rationale:** Backend-autoritativ, einfach, kein Schema nötig. Best-effort-Charakter akzeptiert (kein
  geldkritischer Pfad): ein theoretisches paralleles Doppel-Create desselben Reporters in <60s ist
  unkritisch und wird durch R2 (Dedupe) zusätzlich bei gleichem Ziel abgefangen.
- **Alternatives:** Cooldown in `server_config` (DB) — verworfen für Slice 1 (Property reicht; DB-Config
  als späterer Ausbau möglich). Quota statt Cooldown — vom Nutzer nicht gewählt.

## R5 — Chat-Kontext-Speicherung

- **Decision (umgesetzt):** Kind-Tabelle `report_chat_message` (1:N zu `report`, `ON DELETE CASCADE`,
  `ordinal` erhält die Reihenfolge), unveränderlich nach dem Create-Insert. **NICHT** JSONB.
- **Rationale (Implementierungs-Befund):** `infra-persistence` ist laut Modul-Build bewusst auf
  *jOOQ + Flyway + Postgres* beschränkt — **keine JSON-Bibliothek**. JSONB hätte entweder eine neue
  Abhängigkeit (Modul-Grenze) oder einen fragilen handgerollten JSON-Parser erfordert. Eine Kind-Tabelle
  gibt robusten Round-Trip über jOOQ-Zeilen **ohne** JSON-Lib und bleibt damit innerhalb der
  Modul-Grenze. `plugin-protocol` bleibt ohnehin JSON-frei (DTO ist `List<ChatMessage>`).
- **Bewusste Abweichung vom ursprünglichen Plan:** Der erste Entwurf (oben, JSONB) wurde zur
  Implementierungszeit revidiert; mehrere Absender (Clarify) + Größengrenzen (max. 30 Einträge,
  Text ≤ 256) bleiben unverändert, validiert im Domain-VO `ChatContext`.
- **Alternatives:** (a) JSONB + neue Jackson-Abhängigkeit in `infra-persistence` — verworfen
  (Modul-Grenzen-Abweichung). (b) JSONB + handgerollter JSON-Parser — verworfen (fragil bei Escaping/
  Unicode). (c) JSON im Contract — verboten (Prinzip 4).

## R6 — Live-Benachrichtigung ohne Event-Store

- **Decision:** Port `ReportPublisher.publish(ReportChange)`; Adapter `RedisReportEventPublisher` mappt
  auf `ReportChangedEvent` (protocol) → `MessageProtocol.encode` → `RedisCacheAdapter.publish(
  ReportChannels.CHANGED)`. Best-effort nach Commit (try/catch, loggt, schlägt die Operation nie fehl) —
  exakt der Pfad von `RedisPunishmentEventPublisher`.
- **Rationale:** Reports ist state-stored; es gibt kein „Applied**Event** aus einem Store". Der
  Publisher transportiert eine schlanke Zustandsänderungs-Notiz (`ReportChange`: CREATED |
  STATUS_CHANGED). Das Wire-Event trägt **keinen** Chat-Kontext (FR-015: nur Identität/Status/Kategorie/
  Ziel) → kleine, schnelle Nachricht.
- **Alternatives:** kein Publish (US4 weglassen) — verworfen, Live-Push ist Spec-Scope (P3). Chat im
  Event mitsenden — verworfen (FR-015, Payload-Größe, Datenschutz).

## R7 — REST-Ressourcen-Schnitt & Status-Codes

- **Decision:**
  - `POST /api/reports` (Body: reporter, target, category, detail, chatContext[]) → `ReportResponse`.
    Ein Report referenziert **zwei** Spieler → flache `/api/reports`-Ressource (nicht
    `/api/players/{uuid}/…`).
  - `GET /api/reports/open?staff={uuid}` → `ReportResponse[]` (staff für Permission/`canView`, wie
    Punishments `templates?staff=`).
  - `POST /api/reports/{id}/status` (Body: newStatus, handledBy) → `ReportResponse`.
- **Status-Codes:** 200 OK (inkl. Dedupe-Treffer, transparent); 422 `report_invalid` (Self-Report,
  leeres/zu langes Detail, ungültige Kategorie, Chat zu groß, unbekanntes Ziel); 409 `report_conflict`
  (ungültiger Statusübergang **und** OCC-Konflikt); 404 `report_not_found` (unbekannte Report-Id beim
  Statuswechsel); 429 `report_cooldown` (Cooldown-Verstoß); 403 `permission_denied` (über das
  **bestehende globale** Mapping); 400 (Bad Request) über das bestehende Economy-Mapping.
- **Rationale:** Konsistent zu `PunishmentExceptionHandler` (403/409/404/422-Schema); 429 ergänzt für
  Rate-Limiting. Dedupe gibt bewusst 200 + bestehenden Report (kein 409), weil es ein *idempotenter
  Erfolg* ist, kein Fehler.

## R8 — Berechtigungen

- **Decision:** Erstellen ist **nicht** permission-gated (Default erlaubt für jeden Spieler);
  `report.view` für die offene Liste, `report.handle` für Statuswechsel — geprüft im `ReportService`
  über `PermissionResolver.hasPermission(uuid, perm)`. Seed: `report.view` + `report.handle` → Rolle
  MODERATOR (ADMIN hat `*`).
- **Rationale:** Spec-Assumption (Melden default erlaubt). Backend-autoritativ (Prinzip 12), Reuse des
  vorhandenen Ports/Tabellen (`team_role_permission`).
- **Alternatives:** `report.create` als gated Permission — verworfen für Slice 1 (Default erlaubt);
  später leicht nachrüstbar.

## R9 — Unbekanntes Ziel / Reporter

- **Decision:** `report.reporter_uuid` und `report.target_uuid` mit **FK auf `player(uuid)`**. Ein
  unbekanntes Ziel → FK-Verletzung beim Insert → Adapter übersetzt in `ReportValidationException`
  („unknown player") → 422.
- **Rationale:** UUID-zentrisch (Spieler bekommt Zeile beim Session-Join). Kein zusätzlicher Port-/
  Existenz-Check nötig → kein bestehender Port wird angefasst. Offline-Ziel ist zulässig (FK erfüllt,
  sobald der Spieler je gejoint ist).
- **Alternatives:** expliziter `PlayerRepository`-Existenz-Check — möglich, aber würde ggf. eine
  Lese-Methode am bestehenden Port erfordern; FK + Übersetzung vermeidet jeden Eingriff in fremden Code.
