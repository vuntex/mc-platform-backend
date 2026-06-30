# Feature Specification: Web-Economy Read-Backend + SSE (Slice 1)

**Feature Branch**: `007-web-economy-read`

**Created**: 2026-07-01

**Status**: Draft

**Input**: User description: "Web-Economy Read-Backend + SSE (Slice 1): read-only REST-Projektionen für Spieler-Balances, serverweite Transaktions-History, Transaktions-Detail per ID, plus SSE-Bridge für Live-Balance-Änderungen."

## Migrieren wir das — und in welchem Umfang? *(mandatory)*

Ja. Dies ist **kein** Legacy-Import, sondern die Daten-Grundlage für den Economy-Teil des
Webinterfaces (Spieler-Economy-Tab + serverweite Economy-Page). Reiner **read-only** Slice:
keine neuen Events, keine Writes, kein Optimistic Locking, keine Idempotenz. Lese-Pfad auf
bestehenden Tabellen (`player_balance`, `economy_event`, `currency`, `player`) plus eine
SSE-Bridge, die den **bereits existierenden** `mc:economy:balance`-Channel ans Webinterface
durchreicht.

**Bewusst NICHT in diesem Slice** (verschoben):

- Admin-Writes (Credit/Debit/Set übers Web) — Slice 2.
- Top-Holder-Leaderboard je Währung.
- Zeitreihe „Umlauf über Zeit".
- CSV-Export der gefilterten Liste.
- `playerName` im Wire-`BalanceChangedEvent` (Namen löst das Web client-seitig auf).
- Lookup über `event_id`/`sequenceNo` — Detail/Suche läuft allein über `transactionId`.

## Clarifications

### Session 2026-07-01

- Q: Wer darf die Economy-Read- und SSE-Endpunkte aufrufen? → A: Alle vier Endpunkte werden
  backend-seitig über den `PermissionResolver`-Port gegen eine dedizierte Read-Permission
  (`permission.economy.read`) gegated; fehlt sie → 403. Inhaber (Staff/Admin-Web-Sessions) dürfen
  **jeden** Spieler lesen (keine Self-only-Einschränkung).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Spieler-Economy-Tab: alle Balances auf einen Blick (Priority: P1)

Eine Web-Nutzerin öffnet den Economy-Tab eines Spielers und sieht in einem einzigen Aufruf
sämtliche Währungsstände dieses Spielers — jeweils mit Anzeigename, Symbol und Nachkommastellen,
damit das UI Beträge korrekt formatieren kann, ohne Zusatz-Calls je Währung.

**Why this priority**: Kleinster, in sich geschlossener Wert-Slice: er macht den Spieler-Economy-Tab
allein lauffähig und ersetzt das bisherige pro-Währung-Abfragen durch einen Sammel-Call.

**Independent Test**: Über `GET /api/web/economy/players/{uuid}/balances` testbar — für einen Spieler mit
mehreren Währungen kommen alle Stände + Display-Daten zurück; für einen unbekannten Spieler eine
leere Liste statt eines Fehlers.

**Acceptance Scenarios**:

1. **Given** ein Spieler mit Beständen in mehreren Währungen, **When** seine Balances abgefragt
   werden, **Then** liefert die Antwort genau einen Eintrag pro Währung, jeder mit Betrag,
   Anzeigename, Symbol und Nachkommastellen.
2. **Given** ein unbekannter Spieler oder ein Spieler ohne Balance-Zeilen, **When** seine Balances
   abgefragt werden, **Then** ist die `balances`-Liste leer und es wird **kein** 404 zurückgegeben.

---

### User Story 2 - Serverweite Transaktionsliste mit Filtern & Pagination (Priority: P1)

Eine Web-Nutzerin öffnet die serverweite Economy-Page und blättert durch alle Geld-Bewegungen des
Servers — neueste zuerst, optional gefiltert nach Währung, Event-Typ und Quelle (z. B. nur
`WEBSHOP`), seitenweise per Cursor.

**Why this priority**: Zweiter eigenständiger Kern-Slice: macht die Server-Economy-Page allein
lauffähig. Spiegelt die bestehende Spieler-History, nur serverweit.

**Independent Test**: Über `GET /api/web/economy/history` testbar — ohne Spieler-Filter kommen
serverweite Events in `sequence_no DESC`; Filter und Cursor-Pagination liefern lücken- und
überlappungsfreie Seiten; jeder Eintrag trägt `playerUuid` + `playerName`.

**Acceptance Scenarios**:

1. **Given** Events mehrerer Spieler/Währungen/Quellen, **When** die History ohne Spieler-Filter
   abgefragt wird, **Then** kommen serverweite Events sortiert nach `sequence_no DESC`, jeder mit
   `playerUuid` und `playerName`.
2. **Given** ein gesetzter `source`-/`currency`-/`type`-Filter, **When** abgefragt wird, **Then**
   enthält die Antwort ausschließlich passende Einträge.
3. **Given** mehr Treffer als das Limit, **When** mit dem `nextCursor` weitergeblättert wird,
   **Then** schließt die Folgeseite lückenlos und überlappungsfrei an; gibt es keine weiteren
   Einträge, ist `nextCursor` `null`.
4. **Given** ein nicht-positives `limit` oder ein ungültiger `type`-/`source`-Wert, **When**
   abgefragt wird, **Then** antwortet das System mit 400.

---

### User Story 3 - Transaktions-Detailseite per ID (Priority: P2)

Eine Web-Nutzerin klickt in einer Liste auf eine Transaktion und öffnet deren Detailseite. Bei
einer einfachen Buchung (CREDIT/DEBIT/SET) sieht sie ein Leg; bei einem Transfer sieht sie **beide**
Seiten (Sender + Empfänger) mit beiden Spielernamen, eindeutig als Transfer markiert.

**Why this priority**: Setzt sinnvoll auf den Listen-Slices auf (Drill-down), liefert aber für sich
genommen den vollständigen Detail-Blick inkl. Transfer-Auflösung.

**Independent Test**: Über `GET /api/web/economy/transactions/{transactionId}` testbar — Einzel-Event →
ein Leg; Transfer → zwei Legs mit beiden Namen und `kind=TRANSFER`; unbekannte ID → 404.

**Acceptance Scenarios**:

1. **Given** eine Einzel-Transaktion (CREDIT/DEBIT/SET), **When** sie per `transactionId` abgefragt
   wird, **Then** trägt die Antwort genau ein Leg und `kind=SINGLE`.
2. **Given** ein Transfer, **When** er per `transactionId` abgefragt wird, **Then** trägt die
   Antwort beide Legs (mit beiden Spielernamen), `kind=TRANSFER` und eine gesetzte `correlationId`.
3. **Given** eine unbekannte `transactionId`, **When** sie abgefragt wird, **Then** antwortet das
   System mit 404 (`economy_transaction_not_found`).

---

### User Story 4 - Live-Balance-Updates ans Webinterface (SSE) (Priority: P2)

Während eine Web-Nutzerin die Server-Economy-Page oder einen Spieler-Tab offen hat, aktualisieren
sich Balance-Änderungen live, ohne manuelles Neuladen — serverweit oder auf einen einzelnen Spieler
gefiltert.

**Why this priority**: Hebt die statischen Listen auf „live", ist aber von den Read-Calls
unabhängig (die Seiten funktionieren auch ohne SSE) — daher P2.

**Independent Test**: Über `GET /api/web/economy/stream` (alle) bzw. `?player={uuid}` (gefiltert)
testbar — ein über den bestehenden `mc:economy:balance`-Channel publiziertes Balance-Event landet
als SSE-`data:` beim Client; die gefilterte Variante verwirft Fremd-Spieler-Events serverseitig.

**Acceptance Scenarios**:

1. **Given** ein offener `/api/web/economy/stream`, **When** irgendein Balance-Change publiziert wird,
   **Then** erhält der Client das Event live als SSE-`data:` (JSON).
2. **Given** ein offener `/api/web/economy/stream?player={uuid}`, **When** ein Balance-Change eines
   **anderen** Spielers publiziert wird, **Then** wird dieses Event serverseitig verworfen und
   **nicht** gepusht.

### Edge Cases

- Spieler ohne jede Balance-Zeile → leere Liste, kein 404 (konsistent zur bestehenden
  History-Query).
- `before`-Cursor zeigt auf den letzten Eintrag → leere Folgeseite, `nextCursor=null`.
- Transfer, dessen Gegen-Leg fehlt/nicht auflösbar → Detail bleibt konsistent (Erwartung: das
  vorhandene Leg wird geliefert; die genaue Behandlung ist im Plan zu fixieren).
- Sehr großes angefragtes `limit` → serverseitig auf Max (200) geclampt, kein Fehler.
- SSE-Verbindung bricht ab → Client reconnectet; verpasste Events werden über die Read-Calls
  nachgeladen (kein Event-Replay über SSE in diesem Slice).
- Session ohne `permission.economy.read` ruft einen der vier Endpunkte (inkl. `/stream`) auf → 403,
  backend-autoritativ über den `PermissionResolver`-Port (kein Datenleck über ein fehlendes
  UI-Gate).

## Requirements *(mandatory)*

### Functional Requirements

**Sammel-Balances (US1)**

- **FR-001**: Das System MUSS für einen Spieler alle Währungsstände in einem einzigen Aufruf
  liefern (`GET /api/web/economy/players/{uuid}/balances` → `PlayerBalancesResponse { player, balances[] }`).
- **FR-002**: Jeder Balance-Eintrag MUSS die Währungs-Display-Daten tragen (`displayName`,
  `symbol`, `decimalPlaces`) zusätzlich zum Betrag.
- **FR-003**: Bei unbekanntem Spieler oder fehlenden Balance-Zeilen MUSS das System eine leere
  `balances`-Liste zurückgeben und DARF **kein** 404 liefern.

**Serverweite History (US2)**

- **FR-004**: Das System MUSS eine serverweite Transaktionsliste ohne `player_uuid`-Filter liefern
  (`GET /api/web/economy/history` → `EconomyHistoryResponse { entries[], nextCursor }`).
- **FR-005**: Die Liste MUSS nach `sequence_no DESC` (neueste zuerst) sortiert sein.
- **FR-006**: Das System MUSS optionale Filter für `currency`, `type` und `source` unterstützen
  (z. B. nur `WEBSHOP`, nur `SYSTEM:initial`); kombinierte Filter wirken als UND.
- **FR-007**: Das System MUSS Keyset-Pagination identisch zur bestehenden Spieler-History bieten
  (`before`-Cursor auf `sequence_no`, `+1`-Trick: gibt es mehr Treffer, ist `nextCursor` die
  `sequenceNo` des letzten gelieferten Eintrags, sonst `null`) — lücken- und überlappungsfrei.
- **FR-008**: Das System MUSS das `limit` serverseitig clampen (Default 50, Max 200) und ein
  nicht-positives `limit` mit 400 ablehnen — exakt wie die bestehende Query.
- **FR-009**: Jeder History-Eintrag MUSS `playerUuid` + `playerName` für die „wer"-Spalte tragen.
- **FR-010**: Ein ungültiger `type`- oder `source`-Wert MUSS mit 400 abgelehnt werden.

**Transaktions-Detail (US3)**

- **FR-011**: Das System MUSS eine Transaktion per fachlichem `transaction_id` auflösen
  (`GET /api/web/economy/transactions/{transactionId}` → `TransactionDetailResponse`).
- **FR-012**: Bei einem Einzel-Event (CREDIT/DEBIT/SET) MUSS die Antwort genau ein `leg` und
  `kind=SINGLE` tragen.
- **FR-013**: Bei einem Transfer (`TRANSFER_OUT`/`TRANSFER_IN`) MUSS das System über die
  Korrelation (`metadata->>'correlation_id'`) das Gegen-Leg laden und **beide** Seiten als `legs`
  liefern (beide Spielernamen gejoint), `kind=TRANSFER`, mit gesetzter `correlationId`.
- **FR-014**: Eine unbekannte `transactionId` MUSS mit 404 (`economy_transaction_not_found`)
  beantwortet werden.

**Live-Push / SSE (US4)**

- **FR-015**: Das System MUSS einen SSE-Endpunkt bereitstellen, der alle Balance-Changes pusht
  (`GET /api/web/economy/stream`).
- **FR-016**: Das System MUSS eine spieler-gefilterte Variante bereitstellen
  (`GET /api/web/economy/stream?player={uuid}`), die Events anderer Spieler **serverseitig** vor dem
  Push verwirft.
- **FR-017**: Der SSE-Pfad MUSS den **bereits existierenden** `mc:economy:balance`-Channel
  konsumieren und den dort publizierten `BalanceChangedEvent` als SSE-`data:` (JSON) durchreichen —
  **kein** neues Event, **kein** neuer Codec.

**Querschnitt / Constitution**

- **FR-018**: Das System DARF keine Economy-Writes, -Events, -Locking oder -Idempotenz in diesem
  Slice einführen — reiner Read-/Projektions-Pfad auf bestehenden Tabellen.
- **FR-019**: `plugin-protocol` MUSS dependency-frei (nur JDK) bleiben — neue DTOs + Endpoint-
  Konstanten erlaubt, POM weiterhin **ohne** `<dependencies>`, `PlatformProtocol.create()`
  unverändert.
- **FR-020**: Namen werden im SSE-Pfad **nicht** mitgesendet; das Webinterface löst Spielernamen
  client-seitig auf (UUID ist im Event).

**Autorisierung**

- **FR-021**: Alle vier Endpunkte (US1–US4) MÜSSEN backend-seitig über den `PermissionResolver`-Port
  gegen eine dedizierte Read-Permission (`permission.economy.read`) autorisiert werden; fehlt sie,
  MUSS das System mit 403 antworten. Inhaber der Permission DÜRFEN **jeden** Spieler lesen — es gibt
  **keine** Self-only-Einschränkung auf `GET /api/web/economy/players/{uuid}/balances` oder
  `GET /api/web/economy/stream?player={uuid}`. UI-Gating ist nur Komfort; die Prüfung ist
  backend-autoritativ (Constitution §12).

### Key Entities *(include if feature involves data)*

- **PlayerBalancesResponse**: Sammel-Antwort für US1 — `player` (Identität) + Liste von
  `BalanceResponse`.
- **BalanceResponse**: Ein Währungsstand eines Spielers — Betrag plus Display-Daten der Währung
  (`displayName`, `symbol`, `decimalPlaces`).
- **EconomyHistoryResponse**: Seite der serverweiten History — Liste von `EconomyEventEntry` +
  `nextCursor`.
- **EconomyEventEntry**: Eine Geld-Bewegung in der Liste — inkl. `playerUuid` + `playerName`,
  Betrag, Typ, Quelle, Zeitstempel, `sequenceNo`/`transactionId`.
- **TransactionDetailResponse**: Detail einer Transaktion — `transactionId`, `correlationId`,
  `kind` (SINGLE|TRANSFER), Währungs-Display-Daten, `amount`, `source`, `metadata`,
  `timestampEpochMilli`, `legs[]`.
- **Leg**: Eine Seite einer Transaktion — `playerUuid`, `playerName`, `eventType`, `balanceAfter`.
- **BalanceChangedEvent** *(bestehend)*: das über `mc:economy:balance` publizierte Live-Event, das
  der SSE-Pfad unverändert durchreicht.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Ein Spieler-Economy-Tab lädt alle Währungsstände eines Spielers in **einem** Aufruf
  (kein pro-Währung-Fan-out).
- **SC-002**: Eine Web-Nutzerin kann die komplette serverweite Transaktionshistorie seitenweise
  durchblättern, ohne dass ein Eintrag doppelt erscheint oder fehlt (lücken-/überlappungsfrei über
  alle Seiten).
- **SC-003**: Filter nach Währung, Typ und Quelle liefern ausschließlich passende Einträge
  (0 Falsch-Treffer in der Akzeptanzprüfung).
- **SC-004**: Eine Transfer-Transaktion zeigt auf der Detailseite **beide** beteiligten Spieler mit
  Namen; eine Einzelbuchung genau einen.
- **SC-005**: Ungültige Eingaben (nicht-positives Limit, unbekannter Typ/Quelle, unbekannte
  Transaktions-ID) führen zu einem klaren Fehler (400 bzw. 404), nicht zu einer leeren oder
  irreführenden 200-Antwort.
- **SC-006**: Eine Balance-Änderung erscheint im offenen Web-Stream live, ohne dass die Seite neu
  geladen wird; im spieler-gefilterten Stream erscheinen ausschließlich Events des gewählten
  Spielers.
- **SC-007**: Eine Anfrage ohne `permission.economy.read` an einen der vier Endpunkte wird in
  100 % der Fälle mit 403 abgewiesen — unabhängig davon, ob das UI die Aktion ausgeblendet hat.

## Assumptions

- Die bestehenden Tabellen `player_balance`, `economy_event`, `currency`, `player` und der bereits
  gebaute Circulation-Aggregat-Pfad bleiben die Datenquelle; dieser Slice fügt keine
  schreibenden/event-sourcten Pfade hinzu.
- Der `mc:economy:balance`-Channel und `RedisBalanceEventPublisher` existieren und publizieren
  weiterhin; der SSE-Pfad ist reiner Konsument.
- Authentifizierung folgt dem bestehenden Web-Auth-/Session-Mechanismus (JWT/Session-Slice); dieser
  Slice definiert keine neue Authentifizierung. **Autorisierung** erfolgt über den bestehenden
  `PermissionResolver`-Port gegen `permission.economy.read` (siehe FR-021) — es wird kein neuer
  Permission-Mechanismus gebaut, nur eine neue Permission-Konstante geprüft.
- Die Detail-Suche erfolgt ausschließlich über `transactionId` (fachlicher, in-game angezeigter
  Schlüssel) — Lookup über `event_id`/`sequenceNo` ist bewusst nicht Teil dieses Slice.
- Pagination-Semantik (`sequence_no DESC`, `before`-Cursor, Limit-Clamp Default 50/Max 200) wird
  1:1 von der bestehenden Spieler-History übernommen.
- Strukturelle Architektur-Entscheidungen (neuer Read-Store-Port, Move von
  `findHistory`/`circulation`, geteilte Keyset-Helper, neuer Index, Modul-Zuschnitt
  `api-rest`/`api-realtime`) werden im `/speckit-plan`-Schritt fixiert und sind hier nur als
  Constitution-Leitplanken (FR-018..FR-020) verankert.
