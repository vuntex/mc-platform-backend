# Phase 0 — Research & Decisions: Web-Economy Read-Backend + SSE

Alle „offenen Edge-Cases" aus dem Plan-Prompt sind hier entschieden und begründet. Grundlage: der
real erkundete Code-Stand (nicht der ältere PROGRESS-Snapshot).

## D1 — SSE-Bridge: Schicht-Verortung, eine Subscription, Lifecycle, Backpressure

**Decision.** Zwei Teile, gespiegelt am bestehenden Publisher-Muster:
- **`app/.../adapter/RedisEconomyStreamBridge`** (Composition Root): subscribt **einmalig** beim
  Start `EconomyChannels.BALANCE` über `RedisCacheAdapter.subscribe(channel, Consumer<String>)`,
  decodiert jede Wire-Nachricht via `PlatformProtocol.create().decode(wire)` → `BalanceChangedEvent`
  und übergibt sie an die Registry. Hält das `AutoCloseable`-Handle, schließt es in `@PreDestroy`.
  Liegt in `app`, weil **nur** `app` gleichzeitig `infra-cache` und `plugin-protocol` sehen darf —
  exakt wie `RedisBalanceEventPublisher`.
- **`api-realtime/.../EconomyStreamRegistry`**: hält eine `CopyOnWriteArrayList` von Subscribern
  (`SseEmitter` + optionaler `UUID`-Filter), serialisiert die übergebene View zu JSON (Jackson) und
  schreibt sie an alle passenden Emitter. Implementiert einen schmalen Inbound-Port, damit
  `RedisEconomyStreamBridge` `api-realtime` nicht direkt importieren muss und die Schichtkante sauber
  bleibt.

**Rationale.** Genau **eine** Redis-Pub/Sub-Verbindung (Lettuce öffnet pro `subscribe()`-Call eine
neue Connection — pro Web-Client zu subscriben wäre eine Connection-Leak-Falle). Fan-out im Speicher
an N `SseEmitter`. Schichtregel bleibt intakt: `api-realtime` kennt weder Redis noch Wire-Format.

**Lifecycle.** Bridge subscribt beim App-Start (nicht „beim ersten Client" — simpler, Single-Server,
kein Sub/Unsub-Flattern). Pro Web-Client: `EconomyStreamController` erzeugt `new SseEmitter(0L)`
(kein Timeout), registriert ihn (mit optionalem `?player=`-Filter) und setzt
`onCompletion`/`onTimeout`/`onError` → Deregistrierung aus der Registry. Tote Emitter werden zusätzlich
beim Schreiben (`IOException`) entfernt.

**Backpressure/Threading.** Schreiben passiert auf dem Lettuce-Listener-Thread. Ein langsamer Client
darf den Fan-out nicht blockieren: `emitter.send(...)` in try/catch, bei Fehler Emitter entfernen;
für diesen Single-Server-Slice kein per-Client-Queue/Executor (bewusst einfach gehalten, als Grenze
in der Spec/Quickstart vermerkt). Falls später viele langsame Clients → eigener Fan-out-Executor
nachrüstbar, ohne Contract-Änderung.

**Alternatives rejected.** (a) Subscribe pro Client → Connection-Leak. (b) Bridge in `api-realtime`
mit direktem `infra-cache`+`plugin-protocol`-Import → Schichtbruch. (c) Spring `@EventListener`-Bus
→ unnötige Indirektion, der bestehende Redis-Pfad reicht.

## D2 — SSE-Payload: bestehendes `BalanceChangedEvent` statt neues View-DTO

**Decision.** Kein neues protocol-/view-DTO. Die Bridge übergibt eine application-neutrale View
(Felder aus `BalanceChangedEvent`: `playerUuid, currencyCode, eventType, amount, balance, version,
transactionId, source, correlationId, timestampEpochMilli`); die Registry serialisiert sie als
SSE-`data:`-JSON.

**Rationale.** `BalanceChangedEvent` trägt bereits **alle** vom Web benötigten Felder inkl.
`playerUuid` (für den `?player=`-Filter). Spec FR-017/FR-020: kein neuer Codec, Namen löst das Web
client-seitig auf. Ein zusätzliches DTO wäre reine Duplikation.

**Hinweis.** Das JSON-Format der SSE-Frames ist ein **neuer Web-Contract** (Feldnamen) — wird in
`contracts/` fixiert und per `@JsonTest` gepinnt, obwohl `plugin-protocol` JSON-frei bleibt (die
Serialisierung lebt in `api-realtime`).

## D3 — Serverweite History: `EconomyEventEntry` erweitern, Response wiederverwenden

**Decision (a) — Entry.** `EconomyEventEntry` (protocol) und `EconomyHistoryEntry` (application)
werden um `playerUuid` (UUID) + `playerName` (String) erweitert. Beide Pfade befüllen sie:
- Server-History: pro Eintrag aus `economy_event.player_uuid` + `player`-Join.
- Player-History: `playerUuid` = der bekannte Pfad-Spieler; `playerName` aus demselben Join (additiv,
  ändert das bestehende Verhalten nicht — Top-Level `player` bleibt).

**Decision (b) — Response.** `EconomyHistoryResponse { player, entries, nextCursor }` wird
**wiederverwendet**; für die serverweite Variante ist `player` = `null` (es gibt keinen einzelnen
Spieler), die „wer"-Info sitzt pro Eintrag. Kein paralleles `ServerHistoryResponse`.

**Rationale.** Genau **ein** Entry-Typ und **eine** History-Response (Constitution IV.10). Records +
Jackson → zusätzliche Felder sind JSON-additiv und wire-rückwärtskompatibel; der einzige Bruch ist der
Record-Konstruktor, der an **einer** Stelle (Mapper/Repo-Entry-Bau) sitzt.

**Alternatives rejected.** Eigenes `ServerEconomyEventEntry` → ~8 Felder dupliziert, zwei Mapper-Pfade,
zwei Tests; widerspricht „wiederverwenden statt neu bauen".

**Wire-Kompatibilität bestätigt.** Kein externer Consumer liest `EconomyEventEntry` über einen
Pipe-Codec (es ist ein REST-JSON-DTO, kein Pub/Sub-Codec). Der `RestDtoJsonContractTest` (app,
`@JsonTest`) wird um die zwei Felder ergänzt — bewusster, reviewter Edit.

## D4 — Transfer mit fehlendem/inkonsistentem Gegen-Leg

**Decision.** `findTransaction(transactionId)`:
1. Event per `transaction_id` laden. Nicht gefunden → `Optional.empty()` → 404.
2. Kein `TRANSFER_*` → `kind=SINGLE`, genau ein Leg (der Spieler des Events).
3. `TRANSFER_*`: `correlation_id` aus `metadata` lesen; Gegen-Leg = anderes Event mit gleicher
   `correlation_id` und `player_uuid <> diesem` (vorhandene Subquery-Mechanik aus `JooqEconomyRepository`).
   - **Beide Legs vorhanden** → `kind=TRANSFER`, zwei Legs, beide Namen gejoint, `correlationId` gesetzt.
   - **Gegen-Leg fehlt** (Daten-Inkonsistenz, im event-sourced Append eigentlich unmöglich, da Transfer
     atomar beide Legs schreibt) → **degradiert sauber**: `kind=TRANSFER`, **ein** Leg, `correlationId`
     gesetzt; **kein** Fehler. Es wird geliefert, was existiert (read-only darf nie raten/erfinden).

**Rationale.** Reads müssen robust gegen theoretische Inkonsistenz sein, ohne sie zu verschleiern;
`correlationId` bleibt sichtbar als Hinweis. Belegt durch einen jOOQ-Integrationstest (Transfer mit
manuell entferntem Gegen-Leg).

## D5 — Index-Wahl (Flyway V16)

**Decision.** `V16__economy_event_seq_index.sql`:
`CREATE INDEX idx_event_seq_desc ON economy_event (sequence_no DESC);`

**Rationale.** Serverweite History sortiert/keyset-paginiert auf `sequence_no DESC` **ohne**
player-Präfix; `idx_event_player_currency (player_uuid, currency_code, sequence_no)` greift dafür
nicht (Leitspalte `player_uuid`). `sequence_no` ist `BIGSERIAL`, hat aber allein keinen Index (der PK
ist `event_id`). Nächste freie Version ist **V16** (V1–V15 belegt, V15 = role_inheritance) — nicht V9
wie im ursprünglichen Draft angenommen. **`source`-Index NICHT** spekulativ (erst bei gemessenem
Bedarf); `source`-Filter ist niedrig-selektiv und läuft im Rahmen des Seq-Scans/Index-Range mit.

## D6 — 404-Mapping für unbekannte `transactionId`

**Decision.** Neue `EconomyTransactionNotFoundException` (application), gemappt in
`EconomyExceptionHandler` auf **404** mit `{"error":"economy_transaction_not_found"}`.

**Rationale.** `EconomyExceptionHandler` mappt heute nur 422/409/400; ein 404 für den Detail-Lookup
ist neu und gehört in denselben Handler (gleicher snake_case-Error-Code-Stil). Ungültiges
`limit`/`type`/`source` bleibt 400 über das bestehende `IllegalArgumentException`-Mapping (in
`EconomyMapper.eventTypeFilter` etc., **nicht** neu deklarieren). 403 (fehlende Permission) läuft über
`PermissionDeniedException` → `PunishmentExceptionHandler` (shared).

## D7 — Limit-Clamp & Keyset wiederverwenden

**Decision.** `ServerHistoryQuery` nutzt dieselbe Clamp-Logik wie `EconomyHistoryService`
(`DEFAULT_LIMIT=50`, `MAX_LIMIT=200`, `<=0` → `IllegalArgumentException`). Die Clamp-Methode wird aus
`EconomyHistoryService` extrahiert/geteilt (z. B. statische Helper-Methode), **nicht** kopiert. Die
jOOQ-Keyset-Mechanik (`sequence_no < cursor`, `LIMIT n+1`, `nextCursor` = letzte `sequenceNo` wenn
mehr da) lebt als **eine** private Helper-Methode im `JooqEconomyReadStore` mit optionalem
player-Predicate + optionalem `source`-Filter.

**Rationale.** Constitution IV.10. Single-Source verhindert Drift zwischen player- und server-History.

## D8 — `permission.economy.read` Konstante

**Decision.** Neue Konstante (Vorschlag: in einer Economy-nahen Konstanten-Klasse oder analog zu
`PermissionAdminService`-Konstanten) `permission.economy.read`. Controller-Gate per
`@AuthenticationPrincipal PlayerId actor` → `resolver.hasPermission(actor.value(), ECONOMY_READ)` →
sonst `PermissionDeniedException` (→ 403). Gilt für **alle vier** Endpunkte inkl. `/stream`.

**Rationale.** Spec FR-021/SC-007. Muster 1:1 von `WebPermissionController.requireRead` übernommen.
**Offen für Implement:** genauer Ablageort der Konstante (eigene `EconomyPermissions`-Klasse vs.
bestehende Sammelklasse) — Stil-Entscheidung, kein Architektur-Risiko.

## Offene, bewusst auf Implement verschobene Mikro-Punkte

- Exakter Ablageort der Permission-Konstante (D8).
- Ob `EconomyStreamRegistry` den Inbound-Port in `application` oder als api-realtime-Interface
  definiert (beides schichtkonform; Entscheidung beim Verdrahten).
- Genaues JSON-Feldschema der SSE-Frames wird in `contracts/` festgeschrieben.
