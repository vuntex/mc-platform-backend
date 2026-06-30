# Spec: Web-Economy Read-Backend + SSE (Slice 1)

**Branch:** `002-web-economy-read`
**Status:** Draft (specify)
**Vorgänger:** Economy CREDIT/DEBIT/SET/TRANSFER + History/Audit-Query (siehe PROGRESS.md),
Circulation-Aggregat (`GET /api/economy/circulation`, bereits gebaut).

---

## 1. Migrieren wir das — und in welchem Umfang?

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

---

## 2. Verhalten (WAS)

### A) Sammel-Balances eines Spielers
`GET /api/players/{uuid}/balances`
→ `PlayerBalancesResponse { player, balances: [BalanceResponse] }`

- Alle Währungen des Spielers in einem Call (statt pro-Währung wie bisher).
- Unbekannter Spieler / keine Balance-Zeilen → leere `balances`-Liste, **kein 404**
  (konsistent zur bestehenden History-Query).
- Jeder Eintrag trägt currency-Display-Daten (displayName, symbol, decimalPlaces) fürs UI.

### C) Serverweite Transaktionsliste
`GET /api/economy/history?currency&type&source&before&limit`
→ `EconomyHistoryResponse { entries: [EconomyEventEntry], nextCursor }`

- Wie die bestehende Spieler-History, aber **ohne** `player_uuid`-Filter (serverweit).
- Neuer optionaler `source`-Filter (z. B. nur `WEBSHOP`, nur `SYSTEM:initial`).
- Keyset-Pagination identisch zur bestehenden Query: `sequence_no DESC`, `before`-Cursor,
  `+1`-Trick für `nextCursor` (mehr da → `nextCursor` = `sequenceNo` des letzten Eintrags,
  sonst null).
- Limit serverseitig geclampt (Default 50, Max 200; nicht-positiv → 400), exakt wie bestehend.
- Jeder `EconomyEventEntry` trägt zusätzlich `playerUuid` + `playerName` für die „wer"-Spalte.
- Ungültiger `type`/`source`-Wert oder nicht-positives `limit` → 400.

### D) Transaktion per ID (Detailseite)
`GET /api/economy/transactions/{transactionId}`
→ `TransactionDetailResponse`

- Lookup über `transaction_id` (fachlicher Schlüssel, auch in-game angezeigt).
- **Transfer-Auflösung:** ist das Event ein `TRANSFER_OUT`/`TRANSFER_IN`, wird über
  `metadata->>'correlation_id'` das Gegen-Leg geladen; Response trägt **beide** Seiten als
  `legs`, beide Player-Namen gejoint.
- Einzel-Event (CREDIT/DEBIT/SET) → genau ein `leg`.
- Unbekannte `transactionId` → **404** (`economy_transaction_not_found`).

Response-Form:
```
TransactionDetailResponse {
  transactionId, correlationId, kind (SINGLE|TRANSFER),
  currencyCode, displayName, symbol, decimalPlaces,
  amount, source, metadata, timestampEpochMilli,
  legs: [ { playerUuid, playerName, eventType, balanceAfter } ]
}
```

### SSE — Live-Push ans Webinterface
`GET /api/economy/stream`            → alle Balance-Changes (Server-Dashboard)
`GET /api/economy/stream?player={uuid}` → gefiltert auf einen Spieler (Spieler-Tab)

- `api-realtime` subscribt den **bestehenden** `mc:economy:balance`-Channel (über `infra-cache`),
  dekodiert via `PlatformProtocol.create()` und reicht den `BalanceChangedEvent` als SSE-`data:`
  (JSON) durch.
- **Kein neuer Codec, kein neues Event** — reiner Konsum dessen, was `RedisBalanceEventPublisher`
  schon published. Keine Änderung an `PlatformProtocol.create()`.
- Filter-Variante `?player=` verwirft serverseitig Events anderer Spieler vor dem Push.
- Namen löst das Web client-seitig auf (UUID ist im Event; Player-Lookup-Cache im Frontend).

---

## 3. Abgrenzung & Constitution-Konformität

- `core-domain` bleibt unberührt — dies sind reine Projektionen ohne Invarianten, daher
  **Application-Read-Modelle**, keine Domänen-Typen.
- **Neuer Outbound-Port `EconomyReadStore`** (getrennt vom event-sourced `EconomyEventStore`/
  Write-Pfad). Behebt ein bestehendes Muster-Leck: `findHistory()` und `circulation()` sind heute
  reine Projektions-Reads (kein Versions-/Idempotenz-Bezug), sitzen aber im `EconomyEventStore`,
  dessen Verantwortung der security-kritische Append-/Transfer-Pfad ist. Beide ziehen **1:1**
  (Impl byte-gleich) in den neuen Read-Store um; `EconomyEventStore` behält nur Methoden mit
  Event-/Versions-/Idempotenz-Semantik (`currentBalance`, `ensureZeroBalance`, `append`,
  `transfer`, `findByTransactionId`, `findTransfer` — letztere zwei dienen dem Write-Pfad/Replay
  und bleiben dort).

  Der `EconomyReadStore` trägt damit:
  ```
  // umgezogen (1:1):
  EconomyHistoryPage findHistory(player, currency?, eventType?, cursorBeforeSeqNo, limit);
  List<CirculationStats> circulation();
  // neu in diesem Slice:
  List<ProjectedBalance> playerBalances(player);                         // A
  EconomyHistoryPage findServerHistory(currency?, eventType?, source?, cursorBeforeSeqNo, limit); // C
  Optional<TransactionDetail> findTransaction(transactionId);            // D
  ```
- **Geteilte Keyset-Logik:** `findHistory` (player-gefiltert) und `findServerHistory` (serverweit)
  teilen dieselbe `sequence_no DESC`-Keyset-Pagination. Die jOOQ-Impl schreibt sie **einmal**
  (private Helper mit optionalem player-Predicate), beide Public-Methoden delegieren — keine
  duplizierte Pagination-Logik.
- **Move ist dokumentationspflichtig:** Entfernen von `findHistory`/`circulation` aus
  `EconomyEventStore` ist eine bewusste Struktur-Änderung an einem bestehenden Port → in
  PROGRESS.md nachziehen (Muster-Leck behoben, keine generische Logik geändert).
- `plugin-protocol`: neue **JDK-only** DTOs + `EndpointDescriptor`-Konstanten in
  `EconomyEndpoints`. POM bleibt ohne `<dependencies>`. **Kein** neuer Codec.
- `api-rest`: dünne, getrennte Controller (`ServerEconomyController`, Erweiterung
  `PlayerBalances`), Mapper in `api/rest/support`.
- `api-realtime`: SSE-Controller + Subscriber-Bridge auf `infra-cache`.
- Neuer Index (Flyway `V9`) für serverweite Sortierung, da `idx_event_player_currency`
  ohne player-Präfix nicht greift:
  `CREATE INDEX idx_event_seq_desc ON economy_event (sequence_no DESC);`
  (`source`-Index erst bei Bedarf, nicht spekulativ.)

---

## 4. Akzeptanzkriterien

- [ ] `GET .../balances` liefert alle Währungen eines Spielers; leerer Spieler → `[]`, kein 404.
- [ ] `GET /api/economy/history` ohne player-Filter liefert serverweite Events, `sequence_no DESC`.
- [ ] `source`/`currency`/`type`-Filter und Keyset-Pagination (`before`/`nextCursor`) ohne
      Lücken/Überlappung; ungültiges Limit/Filter → 400.
- [ ] Entries tragen `playerUuid` + `playerName`.
- [ ] `GET .../transactions/{txId}` für CREDIT/DEBIT/SET → ein Leg.
- [ ] `GET .../transactions/{txId}` für Transfer → zwei Legs (beide Namen), `kind=TRANSFER`,
      `correlationId` gesetzt.
- [ ] Unbekannte txId → 404.
- [ ] SSE `/stream` pusht Balance-Changes live; `?player=` filtert serverseitig.
- [ ] `plugin-protocol`-POM weiterhin ohne `<dependencies>`; `PlatformProtocol.create()` unverändert.
- [ ] `./gradlew build` grün (Backend); Testschichten: jOOQ-Integration (Read-Queries +
      Transfer-Auflösung + Pagination), Application (Limit-Clamp/Filter-Weitergabe, Fakes),
      E2E (REST-Reads + SSE-Push über Pub/Sub).

---

## 5. Offene Punkte (vor /speckit.plan)

Keine offen — Read-Store-Schnitt und Detail-Key (`transactionId`) sind entschieden.
`findHistory` + `circulation` ziehen in den neuen `EconomyReadStore` um (siehe Abschnitt 3).
