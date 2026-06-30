# Prompt für `/speckit.plan` — Web-Economy Read-Backend + SSE (Slice 1)

> Kopiere diesen Block als Input für den `/speckit.plan`-Schritt im **Backend-Repo**.

---

Erzeuge `specs/002-web-economy-read/plan.md` aus der bestätigten `spec.md`.

**Verbindlicher Kontext (zuerst lesen, Reihenfolge):**
1. `.specify/memory/constitution.md` — bei Konflikt gewinnt die Constitution.
2. `PROGRESS.md` — insbesondere die Economy-Abschnitte (Event Store, History/Audit-Query,
   Keyset-Pagination, `EconomyEventStore`-Port) und die Single-Server-Kurskorrektur.
3. `specs/002-web-economy-read/spec.md` — der Scope dieses Slices.

**Dieser Slice ist read-only.** Keine neuen Events, keine Writes, kein Optimistic Locking,
keine Idempotenz, kein neuer Codec, keine Änderung an `PlatformProtocol.create()`. Reiner
Lese-Pfad auf `player_balance`, `economy_event`, `currency`, `player` + eine SSE-Bridge auf
dem bestehenden `mc:economy:balance`-Channel.

## Was der Plan abdecken muss

### Schicht-für-Schicht (wie Economy/Punishments/Reports)
- **application:** Neuer Outbound-Port `EconomyReadStore`. **Move:** `findHistory()` und
  `circulation()` wandern 1:1 aus `EconomyEventStore` hierher (Impl byte-gleich); begründe im
  Plan, dass `EconomyEventStore` nur Methoden mit Event-/Versions-/Idempotenz-Semantik behält
  (`currentBalance`, `ensureZeroBalance`, `append`, `transfer`, `findByTransactionId`,
  `findTransfer`). Neue Read-Use-Cases: `PlayerBalancesQuery`, `ServerHistoryQuery`,
  `TransactionDetailQuery` (Circulation-Use-Case existiert schon — nur Port-Bezug umhängen).
  Limit-Clamping (Default 50, Max 200, nicht-positiv → 400) wird aus dem bestehenden
  `EconomyHistoryService`-Muster wiederverwendet, NICHT neu erfunden.
- **infra-persistence:** Neuer `JooqEconomyReadStore`-Adapter. **Geteilte Keyset-Helper:**
  `findHistory` (player-gefiltert) und `findServerHistory` (serverweit) teilen EINE private
  Query-Methode mit optionalem player-Predicate — Plan muss explizit sagen, dass die
  Pagination-Logik nicht dupliziert wird. Transfer-Auflösung in `findTransaction`: Event über
  `transaction_id` laden → bei `TRANSFER_*` das Gegen-Leg über `metadata->>'correlation_id'`
  nachladen → beide Player-Namen joinen → zwei `legs`. `playerBalances`: join
  `player_balance` × `currency`.
- **api-rest:** Dünne, getrennte Controller. `GET /api/players/{uuid}/balances`,
  `GET /api/economy/history` (serverweit, neuer `source`-Filter),
  `GET /api/economy/transactions/{transactionId}`. Mapper in `api/rest/support`. Fehlercodes
  über den bestehenden Handler-Stil: unbekannte txId → 404 (`economy_transaction_not_found`),
  ungültiges Limit/Filter → 400 (bestehendes Mapping greift, nicht re-deklarieren).
- **api-realtime:** SSE-Controller + Subscriber-Bridge. `GET /api/economy/stream[?player=]`.
  Subscribt `mc:economy:balance` über `infra-cache`, dekodiert via `PlatformProtocol.create()`,
  reicht `BalanceChangedEvent` als SSE-`data:` (JSON) durch. `?player=` filtert serverseitig vor
  dem Push. Plane Lifecycle: Subscribe beim ersten Client, sauberes Abmelden/Cleanup bei
  Client-Disconnect, Thread-/Backpressure-Verhalten benennen.
- **plugin-protocol (JDK-only):** Neue DTOs `PlayerBalancesResponse`, `TransactionDetailResponse`
  (+ `TransactionLeg`), Erweiterung `EconomyEventEntry` um `playerUuid`/`playerName` (prüfen, ob
  rückwärtskompatibel — wird die bestehende Spieler-History-Response beeinflusst?).
  `EndpointDescriptor`-Konstanten in `EconomyEndpoints` (LIST_BALANCES, SERVER_HISTORY,
  GET_TRANSACTION). POM bleibt ohne `<dependencies>`. SSE-DTO-Frage: reicht der bestehende
  `BalanceChangedEvent` als SSE-Payload oder braucht das Web ein eigenes view-DTO? Entscheide
  und begründe.

### Datenmodell / Migration
- Flyway `V9`: `CREATE INDEX idx_event_seq_desc ON economy_event (sequence_no DESC);` (serverweite
  Sortierung; `idx_event_player_currency` greift ohne player-Präfix nicht). `source`-Index NICHT
  spekulativ — als „bei Bedarf" notieren. Prüfe die nächste freie Versionsnummer gegen die
  bestehenden Migrationen (V1–V8 laut PROGRESS.md).
- Read-Modell-Records benennen: `ProjectedBalance`, `TransactionDetail`, `TransactionLeg`
  (Application-Ebene, KEINE core-domain-Typen — reine Projektionen ohne Invarianten).

### Wiederverwendung explizit prüfen (Constitution: „wiederverwenden statt neu bauen")
- Bestehende `EconomyHistoryPage`/`EconomyHistoryEntry`, das Limit-Clamping, die Keyset-Mechanik,
  der `EconomyExceptionHandler`-Stil, die Mapper-Konvention, der bestehende Circulation-Use-Case.
- Benenne, was NICHT generisch geändert werden darf, und bestätige, dass der einzige Eingriff in
  bestehenden Code der Port-Move (`EconomyEventStore` → `EconomyReadStore`) + dessen Wiring ist.

### Teststrategie pro Schicht
- jOOQ-Integration (Testcontainers): serverweite History-Reihenfolge + Filter (currency/type/
  source) + Keyset-Pagination ohne Lücken/Überlappung + korrekter `nextCursor`; Transfer-Auflösung
  (zwei Legs, beide Namen, correlation_id aus metadata); `playerBalances`; `circulation` nach Move
  unverändert grün.
- Application (Fakes): Limit-Clamp/Filter-Weitergabe, Transfer- vs. Single-Mapping.
- E2E (`app`): REST-Reads + SSE-Push (Balance-Change über echtes Pub/Sub triggern, SSE-Client
  empfängt; `?player=`-Filter verifizieren). 404/400-Pfade.
- Move-Regression: bestehende `findHistory`/`circulation`-Tests grün nach Umzug.

### Definition of Done (aus CLAUDE.md)
- Tests pro Schicht grün, `./gradlew build` grün.
- `:plugin-protocol:publishToMavenLocal` nach DTO-/Endpoint-Änderung.
- PROGRESS.md-Status nachgezogen (inkl. dokumentiertem Port-Move), `FEATURE_INVENTORY.md` n/a
  (kein Legacy-Feature — Web-Infra), aber im PROGRESS-Slice-Eintrag vermerken.
- Bestätigt: kein generischer Baustein geändert außer dem bewussten Port-Move.

**Behandle den ersten Plan-Entwurf nicht als final** — decke offene Edge-Cases auf (z. B.: was
liefert `findTransaction` bei einem Transfer, dessen Gegen-Leg fehlt/inkonsistent ist? Wie verhält
sich SSE bei Redis-Ausfall? Ist `EconomyEventEntry`-Erweiterung wire-kompatibel?), bevor es zu
`/speckit.tasks` geht.
