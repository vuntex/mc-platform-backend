# MC Platform — Backend & Plugin

> Professionelles Minecraft-Backend für einen 1.21 Paper-Server (Single-Node, Velocity zurückgestellt).
> Verwaltung von User-Daten (Coins etc.) und Konfiguration über ein Webinterface, performant, gecached,
>live für Online-User und korrekt für Offline-User. Migration eines großen 1.8.9-Plugins in diese
>Architektur läuft (Spec-Kit, selektiv ~80%).

**Status:** Backend-Skeleton, Redis-Schema/Pub-Sub und die Economy-Operationen CREDIT/DEBIT/SET **und TRANSFER** stehen (event-sourced, idempotent, optimistic-locked) sowie eine **read-only History/Audit-Query** (`GET .../economy/history`, Keyset-Pagination über den Event Store); der geteilte Contract (`plugin-protocol`) ist feature-generisch (Envelope/Codec-Routing + REST-DTOs/-Endpoints), und das **Plugin-Skeleton steht** im separaten Repo `mc-platform-plugin` (Paper 1.21) und spricht über `plugin-protocol` (Maven Local) gegen das Backend — Abschnitt 9, Schritte 1, 2, 3 und 4 erledigt (Details im Abschnitt „Status" am Ende). Nächster Schritt: echter In-Game-Vertical-Slice (`/balance` zeigt den Wert live, „Es lebt").

---

## 1. Ziele & Anforderungen

- **Plattform:** Paper 1.21 (NICHT Folia — siehe Entscheidung unten). Velocity-Proxy von Anfang an eingeplant, erst läuft aber nur ein Paper-Node.
- **Erwartete Last:** 200+ Spieler gleichzeitig (Peak).
- **Webinterface:** User-Daten verwalten (Coins etc.), Server-Konfiguration ändern.
- **Live-Updates** für Online-User, **korrekte Persistenz** für Offline-User.
- **Maximale Sicherheit + lückenloses Logging** bei Economy (Geld-ähnliche Werte).
- **Priorität:** saubere Architektur von Anfang an (DDD, Hexagonal).

---

## 2. Architektur-Überblick

```
┌──────────────┐         ┌─────────────────────┐
│ Webinterface │◀──SSE──▶│  Spring Boot API    │
│  (Next.js)   │──REST──▶│  (1..N Nodes)       │
└──────────────┘         └──────┬──────────────┘
                                │
                ┌───────────────┼────────────────┐
                ▼               ▼                ▼
         ┌──────────┐   ┌──────────────┐  ┌──────────┐
         │PostgreSQL│   │    Redis     │  │ (später) │
         │  (SoT)   │   │ Cache+PubSub │  │  Kafka?  │
         └──────────┘   └──────┬───────┘  └──────────┘
                               │
              ┌────────────────┼────────────────┐
              ▼                ▼                 ▼
      ┌──────────────┐ ┌──────────────┐  ┌──────────────┐
      │ Velocity     │ │ Paper Node 1 │  │ Paper Node 2 │
      │ Plugin       │ │ (Plugin)     │  │ (Plugin)     │
      └──────────────┘ └──────────────┘  └──────────────┘
```

**Kernprinzip:** Das Spring-Boot-Backend ist die *Single Source of Truth*. Das Plugin ist nur ein Client —
keine direkte DB-Anbindung im Plugin, kein Spring im Plugin.

### Rollen der Komponenten
- **PostgreSQL** — Persistenz, Source of Truth (Event Store + Projektionen + Config).
- **Spring Boot** — Geschäftslogik, REST (CRUD) + SSE (Live-Updates). 1..N Nodes.
- **Redis** — (a) Cache heißer Daten (Balances aktiver Spieler), (b) Pub/Sub für Live-Updates & Cache-Invalidierung über alle Nodes/Server hinweg.
- **Paper Plugin** — Spiel-seitiger Client, liest/schreibt übers Backend, hört auf Redis-Events.
- **Velocity** — Proxy; Server-Switch-Events via plugin messaging.

---

## 3. Wichtige Entscheidungen (mit Begründung)

### Paper statt Folia
Folia parallelisiert die Welt-Simulation über Region-Threads — lohnt erst bei ~500–1000+ auf *einem*
Server, bricht das Plugin-Ökosystem und erzwingt ein fundamental anderes Programmiermodell.
Unser Performance-Problem liegt nicht im Tick, sondern im Daten-Layer (Coins, I/O, Live-Updates) —
das lagern wir ins Backend + Redis aus. Skalierung später horizontal über mehr Paper-Nodes hinter
Velocity, nicht über Folia.

### Economy event-sourced, Rest state-stored
Coins brauchen Audit-Trail, Idempotenz, Concurrency-Sicherheit → Event Store + Projektion.
Config & Player-Stammdaten sind simples CRUD (ändern sich selten, keine Historie nötig).

### Maximale Sicherheit: strikt synchrone Debits
Jede Ausgabe (DEBITED) geht durchs Backend, Guthaben-Prüfung in der DB-Transaktion (optimistic
locking über `version`). Kein optimistisches Abziehen im Plugin. Reads dürfen optimistisch aus Redis
kommen. Jede Transaktion wird als Event geloggt (lückenloser Audit-Trail).

### Geld nie als Float
`balance` ist `BIGINT`. Bei Nachkommastellen in kleinster Einheit rechnen (wie Cents),
`currency.decimal_places` sagt nur dem UI, wo das Komma steht.

### UUID-zentrisch
Jeder FK zeigt auf `player.uuid`. Name ist nur ein Cache-Feld mit Timestamp. Damit ist
„online vs. offline" für die Datenhaltung irrelevant.

---

## 4. Tech-Stack

| Layer | Wahl | Begründung |
|-------|------|-----------|
| Plugin-API | Paper 1.21 | Async-Scheduler, moderne API |
| Plugin↔Backend | Redis Pub/Sub + REST | Live ohne Polling; REST als Fallback/Command |
| Cache-Client | Lettuce | Async, Netty-basiert, Spring-Default |
| Postgres-Access | jOOQ | Volle Query-Kontrolle bei hohem Durchsatz |
| Realtime Web | SSE | Reicht für unidirektionale Updates, simpler als WS |
| Migrations | Flyway | Versionierte Schema-Migration |
| Build | Gradle (Kotlin DSL) | |
| Server-Switch | Velocity plugin messaging | |

---

## 5. Modul-Struktur (Backend, Multi-Module Gradle)

```
mc-platform-backend/
├── core-domain/        ← reine Geschäftslogik, KEIN Framework
│   ├── economy/        ← Coins als Aggregate, Transactions als Events
│   ├── player/         ← Player-Identity (UUID-zentrisch)
│   └── config/         ← Server-Config-Modell
├── application/        ← Use Cases, Ports (Hexagon-Mitte)
├── infra-persistence/  ← Postgres-Adapter (jOOQ + Flyway)
├── infra-cache/        ← Redis-Adapter (Lettuce), Pub/Sub
├── api-rest/           ← Controller, DTOs
├── api-realtime/       ← SSE/WebSocket
└── plugin-protocol/    ← geteilte DTOs für Plugin↔Backend (separat publizierbar)
```

Plugin ist ein **separates Repo** (`mc-platform-plugin/`), zieht nur `plugin-protocol`
(zunächst als Git-Submodule).

---

## 6. Datenmodell (PostgreSQL)

### ER-Überblick
```
player (1) ──< economy_event (N) >── currency (1)
player (1) ──< player_balance (N) >── currency (1)
server_config ──< config_audit
```

### player — Stammdaten (state-stored)
```sql
CREATE TABLE player (
    uuid             UUID PRIMARY KEY,
    name             VARCHAR(16) NOT NULL,         -- Cache, kann veralten
    name_updated_at  TIMESTAMPTZ NOT NULL,
    first_seen       TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen        TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_player_name_lower ON player (LOWER(name));
```

### currency — Währungen als Config
```sql
CREATE TABLE currency (
    code            VARCHAR(32) PRIMARY KEY,       -- 'COINS', 'GEMS'
    display_name    VARCHAR(64) NOT NULL,
    symbol          VARCHAR(8),
    decimal_places  SMALLINT NOT NULL DEFAULT 0,   -- 0 = ganze Coins
    default_balance BIGINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

### economy_event — Event Store (append-only)
```sql
CREATE TABLE economy_event (
    event_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sequence_no     BIGSERIAL NOT NULL,            -- globale Ordnung
    player_uuid     UUID NOT NULL REFERENCES player(uuid),
    currency_code   VARCHAR(32) NOT NULL REFERENCES currency(code),
    event_type      VARCHAR(32) NOT NULL,          -- CREDITED|DEBITED|SET|TRANSFER_OUT|TRANSFER_IN
    amount          BIGINT NOT NULL,               -- immer positiv; Richtung über event_type
    balance_after   BIGINT NOT NULL,               -- Stand nach diesem Event
    transaction_id  UUID NOT NULL,                 -- Idempotenz-Schlüssel
    source          VARCHAR(64) NOT NULL,          -- 'WEB','PLUGIN:shop','SYSTEM:mobkill'
    metadata        JSONB,                         -- frei: shop_id, reason, correlation_id...
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_transaction UNIQUE (transaction_id)
);
CREATE INDEX idx_event_player_currency ON economy_event (player_uuid, currency_code, sequence_no);
CREATE INDEX idx_event_created ON economy_event (created_at);
```
- `transaction_id` UNIQUE = Idempotenz-Garantie (doppelte Events knallen in den Constraint → ignorieren).
- `amount` immer positiv, Richtung über `event_type` (keine Vorzeichen-Bugs).
- `balance_after` redundant mit Absicht: Verlauf anzeigbar ohne Neu-Falten; eingebaute Konsistenzprüfung.
- `source` macht den Audit-Trail brauchbar.

### player_balance — Projektion (Snapshot)
```sql
CREATE TABLE player_balance (
    player_uuid    UUID NOT NULL REFERENCES player(uuid),
    currency_code  VARCHAR(32) NOT NULL REFERENCES currency(code),
    balance        BIGINT NOT NULL DEFAULT 0,
    version        BIGINT NOT NULL DEFAULT 0,      -- sequence_no des letzten Events
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (player_uuid, currency_code)
);
```
Schreibvorgang in EINER DB-Transaktion: Event inserten → `UPDATE player_balance ... WHERE version = :expected`
(optimistic locking). Version-Mismatch → konkurrierende Änderung → retry. Projektion jederzeit aus
Event Store neu aufbaubar (Recovery, Migration).

### server_config — Konfiguration übers Webinterface
```sql
CREATE TABLE server_config (
    config_key   VARCHAR(128) PRIMARY KEY,         -- 'economy.starting_coins'
    value        JSONB NOT NULL,                   -- typflexibel
    value_type   VARCHAR(16) NOT NULL,             -- INT|STRING|BOOL|LIST|OBJECT
    scope        VARCHAR(32) NOT NULL DEFAULT 'GLOBAL',  -- GLOBAL|SERVER:lobby...
    description  TEXT,
    updated_by   VARCHAR(64),
    version      BIGINT NOT NULL DEFAULT 0,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE config_audit (
    id           BIGSERIAL PRIMARY KEY,
    config_key   VARCHAR(128) NOT NULL,
    old_value    JSONB,
    new_value    JSONB,
    changed_by   VARCHAR(64) NOT NULL,
    changed_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

---

## 7. Economy Event-Typen (Domain)

| Event-Typ | Bedeutung | amount-Semantik |
|-----------|-----------|-----------------|
| CREDITED | Coins gutgeschrieben | positiv, addiert |
| DEBITED | Coins abgezogen (Guthaben-Prüfung!) | positiv, subtrahiert |
| SET | Stand direkt gesetzt (Admin) | neuer absoluter Wert |
| TRANSFER_OUT | an anderen Spieler gesendet | positiv, subtrahiert |
| TRANSFER_IN | von anderem Spieler erhalten | positiv, addiert |

- `SET`: einziger Fall, wo `balance_after` nicht aus `before ± amount` folgt (Admin-Override).
- Transfer = zwei Events mit gemeinsamer `correlation_id` (in metadata), damit beide Seiten zusammengehören.

---

## 8. Caching-Strategie

- **Online-User:** Join → Balance aus Postgres in Redis laden (Warmup) → Reads gegen Redis →
  Writes IMMER durchs Backend (Postgres + Redis-Update + Pub/Sub-Event). Leave → finaler Sync, TTL auf Key.
- **Offline-User:** kein aktiver Cache; bei Bedarf (Webinterface) direkt aus Postgres, ggf. kurz gecached.
- **Live-Updates:** Backend published bei jeder Änderung ein Pub/Sub-Event; alle Backend-Nodes + Plugins
  subscriben → sofortige Cache-Invalidierung/Update über das ganze Netzwerk. Kein Polling.

### Redis-Key-Schema & Pub/Sub-Format (implementiert, Schritt 2)
- **Hot-Balance-Cache** (backend-intern, in `infra-cache`): Redis-HASH
  `mc:bal:{playerUuid}:{currencyCode}` mit Feldern `balance` (BIGINT als String) und `version`
  (= `sequence_no` des zuletzt angewandten `economy_event`). TTL `null` für aktive Spieler, beim
  Leave TTL setzen. Das Plugin liest diese Keys **nie** direkt — immer über das Backend.
- **Pub/Sub-Channel** (geteilter Contract, in `plugin-protocol.economy`): `mc:economy:balance`
  (`EconomyChannels.BALANCE`), gebaut über die Konvention `Channels.of("economy","balance")`
  (`mc:{feature}:{topic}`).
- **Event** `BalanceChangedEvent` (in `plugin-protocol.economy`, dependency-frei): `playerUuid`,
  `currencyCode`, `eventType` (String), `amount`, `balance` (=balance_after), `version`,
  `transactionId`, `source`, `correlationId`, `timestampEpochMilli`.
- **Wire-Format** (seit Schritt-1-Refactor feature-generisch, siehe Status-Abschnitt): jede Nachricht
  ist ein `MessageEnvelope` `v<n>|<messageType>|<payload>` (dependency-frei, pipe-getrennt). Für
  Balance: `messageType = economy.balance-changed`, der `payload` ist `BalanceChangedEventCodec`s
  10-Feld-Wire `playerUuid|currencyCode|eventType|amount|balance|version|transactionId|source|correlationId|ts`.
  String-Felder sind URL-encoded (UTF-8), `|` kann also nie in einem Feld vorkommen; das Envelope-Parsing
  splittet mit Limit 3, sodass Payload-Pipes erhalten bleiben. `version` trägt die globale Ordnung für
  Staleness-Checks. (Vor dem Refactor: flaches `v1|<11 Felder>` ohne messageType.)
- **Transport** (`infra-cache`, pur Lettuce): `RedisCacheAdapter.publish(channel, msg)` /
  `subscribe(channel, handler)` arbeiten auf rohen Strings; (De-)Serialisierung macht der Codec.
  `BalanceCache` kapselt put/get/version/evict auf dem HASH.

---

## 9. Nächste Schritte (Reihenfolge)

1. **Backend-Skeleton** generieren: Gradle-Multi-Module, Flyway-Migrations (Schema oben),
   docker-compose (Postgres + Redis), Domain-Aggregates, Redis-Config. Ziel: `docker-compose up` läuft.
2. **Redis-Key-Schema + Pub/Sub-Event-Format** definieren und implementieren.
3. **Plugin-Skeleton** (separates Repo, Paper 1.21) gegen Backend.
4. **Vertical Slice:** Join → Balance-Warmup → `/balance` im Spiel zeigt Wert. „Es lebt"-Moment.
5. Danach in die Breite: Webinterface (Next.js), weitere Economy-Operationen, Config-UI.

---

## Offene Punkte / später entscheiden
- `plugin-protocol`-Verteilung: **Variante B (Maven Local) ist umgesetzt** als Vorstufe — siehe
  Status-Abschnitt. Offen bleibt nur der spätere Schritt auf eine **private Maven-Registry**.
- Brauchen wir Kafka? Erst wenn Redis Pub/Sub als Event-Transport nicht mehr reicht (vermutlich lange nicht).
- Velocity plugin-messaging-Format für Server-Switch.
- Auth fürs Webinterface (vermutlich JWT/OAuth — du hast das bei Steuerfertig/League Vault schon gemacht).

---

## Status (Stand: 2026-06-20)

**Schritt 1 aus Abschnitt 9 (Backend-Skeleton) ist erledigt und lauffähig.**

### Was steht
- **Multi-Module-Gradle-Build** (Kotlin DSL, Java-21-Toolchain, Spring Boot 3.5.15) mit allen
  acht Modulen aus Abschnitt 5. Gemeinsame Konfiguration über das Convention-Plugin
  `mcplatform.java-conventions` in `buildSrc/`.
- **Abhängigkeitsrichtung** wird dadurch erzwungen, dass jedes Modul in seiner `build.gradle.kts`
  nur die erlaubten Dependencies deklariert:
  - `core-domain`, `plugin-protocol`: keine Dependencies außer JDK.
  - `application` → `core-domain`. `infra-persistence` → application + jOOQ/Flyway/Postgres (kein Spring).
  - `infra-cache` → application + Lettuce (kein Spring). `api-rest`/`api-realtime` → application + Spring Web;
    `api-rest` zusätzlich → `plugin-protocol` (geteilte, dependency-freie REST-DTOs, seit Schritt 2).
  - `app` verdrahtet alles, enthält die `main`-Klasse + Spring-Config.
- **Flyway-Migrationen** unter `infra-persistence/src/main/resources/db/migration`:
  `V1__initial_schema.sql` (alle Tabellen/Constraints/Indizes aus Abschnitt 6), `V2__seed_currency.sql`
  (Default-Währung `COINS`).
- **jOOQ-Codegen** via `dev.monosoul.jooq-docker` (8.0.26): generiert beim Build aus dem von Flyway
  migrierten Schema (Wegwerf-Postgres-16-Container). Keine laufende DB nötig, nur Docker.
- **docker-compose.yml**: Postgres 16 + Redis 7 mit Healthchecks, Volumes, Credentials aus `.env`
  (`.env.example` vorhanden). Beide Container kommen `healthy` hoch (verifiziert).
- **application.yml** mit Profilen `local` (Default) und `test`, Verbindungsdaten aus Env-Variablen.
- **Health-Endpoint** `GET /actuator/health` → 200 (Actuator).
- **Smoke-Test** (`app/.../SmokeTest.java`): Testcontainers Postgres + Redis, Flyway läuft, prüft
  Context-Load, Existenz aller Tabellen, Seed-`COINS` und `/actuator/health` == 200.
- `./gradlew build` läuft **grün** (inkl. Codegen + Smoke-Test).

### Bewusste Skeleton-Grenzen (noch KEINE Geschäftslogik)
- Generierte jOOQ-Klassen sind vorhanden, aber es ist noch **kein `DSLContext` in Spring verdrahtet**
  und es gibt keine Repository-Adapter — kommt mit der ersten Economy-Operation.
- `infra-cache` (reiner Lettuce-Adapter, framework-frei) ist **als einziger Redis-Pfad** über
  `CacheConfig` im `app`-Modul als Bean verdrahtet; ein eigener `RedisHealthIndicator` pingt darüber
  (`spring-boot-starter-data-redis` wurde entfernt — kein zweiter Lettuce-Pool). Verbindung wird lazy
  aufgebaut, d. h. der App-Start gelingt auch bei abwesendem Redis, der Health-Check zeigt dann DOWN.
  Konfiguration: `mcplatform.redis.{host,port,password}`.
- `api-rest`/`api-realtime` enthalten nur Platzhalter (`/api/ping`, leerer SSE-Stream).

### Technische Notizen / Stolpersteine
- **jOOQ-Version:** Das Codegen-Plugin erzeugt 3.21-Code; die Spring-Boot-BOM managt ein älteres
  3.19.x. `app` erzwingt daher per `resolutionStrategy.force` jOOQ `3.21.6` als Runtime-Version,
  damit generierter Code und Runtime identisch sind.
- **Port 5432:** Falls lokal belegt (eigenes Postgres), in `.env` `DB_PORT` umsetzen — compose und
  `local`-Profil teilen sich denselben Wert.

### Schritt 2 erledigt (Redis-Key-Schema + Pub/Sub-Format)
- Key-Schema, Channel, `BalanceChangedEvent` + dependency-freier `BalanceChangedEventCodec` und der
  `BalanceCache` sind implementiert — Details siehe Unterabschnitt in Abschnitt 8.
- `BalanceCache` ist als Spring-Bean in `app` (`CacheConfig`) verdrahtet.
- Tests grün: Codec-Roundtrip (inkl. Delimiter-/Unicode-Fall) in `plugin-protocol`,
  Redis-Roundtrip (ping, Balance-Cache, Pub/Sub) via Testcontainers in `infra-cache`.

### Schritt 4 erledigt (Economy-Vertical-Slice, erste Geschäftslogik)
Vollständiger Schreib-/Lesepfad CREDIT/DEBIT/SET durch alle Schichten:
- **core-domain:** `Balance`-Aggregat (credit/debit/set, niemals direkte Mutation — gibt
  `PendingEconomyEvent` zurück), `Money` (BIGINT), `TransactionId`, `AppliedEconomyEvent`,
  `InsufficientFundsException`. Guthabenprüfung lebt hier.
- **application:** Ports `EconomyEventStore`, `PlayerRepository`, `BalanceCachePort`,
  `BalanceEventPublisher` (+ `AppendResult`, `ConcurrencyConflictException`) und der Use Case
  `EconomyService` — lädt Projektion → Domäne rechnet/prüft → append mit Optimistic Locking
  (Retry bei Konflikt) → Cache-Update + Publish (beides best-effort nach Commit).
- **infra-persistence:** `JooqEconomyRepository` schreibt in EINER jOOQ-Transaktion: Idempotenz-Check
  über `transaction_id` → Event inserten (DB vergibt `sequence_no`) → `player_balance` per
  `WHERE version = :expected` projizieren (Version-Mismatch → `ConcurrencyConflictException`).
  `JooqPlayerRepository` (Upsert). Kein Spring — Transaktionen laufen über jOOQ auf der DataSource.
- **infra-cache:** `RedisBalanceCacheAdapter` implementiert `BalanceCachePort` auf dem HASH.
- **app (Composition Root):** `DSLContext`-Bean über die Spring-DataSource, Repo-/Service-Beans,
  `RedisBalanceEventPublisher` (mappt `AppliedEconomyEvent` → `plugin-protocol`-Wire → Redis-Publish;
  liegt hier, weil nur `app` sowohl plugin-protocol als auch infra-cache sehen darf).
- **api-rest:** `GET /api/players/{uuid}/balances/{currency}`, `POST .../credit|debit|set`,
  `PUT /api/players/{uuid}` (Upsert); `EconomyExceptionHandler` → 422 (insufficient funds) / 409 (conflict).
- **Tests grün:** Domain-Regeln, `EconomyService` (Retry/Insufficient/Read-Cache) mit Fakes,
  jOOQ-Integration (Optimistic Lock + Idempotenz, Testcontainers-Postgres), und ein End-to-End-Test
  in `app` (REST → Postgres → Cache → Pub/Sub, inkl. 422-Pfad).

### TRANSFER erledigt (zwei Events, gemeinsame correlation_id)
- **core-domain:** `TransferId` (correlation id; leitet deterministisch die beiden Leg-`TransactionId`s
  ab → Replay trifft dieselben Idempotenz-Keys), `Transfer.prepare` (Domain-Service: prüft distinct
  player + gleiche Währung + Funds, erzeugt TRANSFER_OUT/TRANSFER_IN), `PendingEconomyEvent`/
  `AppliedEconomyEvent` tragen jetzt eine nullable `correlationId`.
- **infra-persistence:** `transfer(...)` schreibt beide Legs + projiziert beide Balances in EINER
  jOOQ-Transaktion, beide per Optimistic Lock. `correlation_id` landet in `economy_event.metadata`
  (JSONB). Projektions-Updates laufen in deterministischer Spieler-Reihenfolge (UUID-sortiert) →
  kein Deadlock zwischen gespiegelten A→B/B→A-Transfers.
- **application/api-rest:** `EconomyService.transfer` (Retry bei Konflikt, publisht beide Legs),
  `POST /api/players/{from}/balances/{currency}/transfer` (Body: `to`, `amount`, optional
  `correlationId`, `source`). Self-Transfer/Currency-Mismatch → 400.
- **plugin-protocol:** `BalanceChangedEvent` + Codec um `correlationId` erweitert (Wire jetzt 11
  Felder, weiterhin `v1` — noch kein externer Consumer). Beide Transfer-Legs werden published, über
  die correlation_id korrelierbar.
- **Idempotenz robuster:** Der Check ist jetzt **prüfungs-first** im `EconomyService` (per
  `findByTransactionId` / `findTransfer`) — ein Replay (auch eines DEBIT, dessen Funds-Check sonst
  gegen die bereits reduzierte Balance scheitern würde) liefert das gespeicherte Ergebnis zurück,
  ohne die Domäne erneut zu rechnen. In der DB-Transaktion bleibt ein zweiter Check als Race-Schutz.
- **Tests grün:** `TransferTest` (Domain), `EconomyServiceTest` (Transfer + Idempotenz-Replay,
  DEBIT-Replay), jOOQ-Integration (atomarer Transfer, correlation_id in metadata, idempotenter Replay),
  app-E2E (Alice→Bob über REST).

### Economy-History/Audit-Query erledigt (read-only, Keyset-Pagination)
Read-only Audit-/Verlaufs-Query über den Event Store `economy_event` — „wohin/woher floss Geld".
Keine neuen Events, kein Optimistic Locking, keine Idempotenz (reiner Read-Pfad).
- **plugin-protocol (JDK-only):** `EconomyEventEntry` (sequenceNo, currencyCode, eventType als String,
  amount, balanceAfter, transactionId, source, nullable correlationId, timestampEpochMilli),
  `EconomyHistoryResponse` (player, entries, nullable `nextCursor`), neuer Endpoint
  `EconomyEndpoints.GET_HISTORY` (GET `/api/players/{uuid}/economy/history`). Nach Maven Local publiziert.
- **application:** Outbound-Port `EconomyEventStore.findHistory(player, Optional<currency>,
  Optional<eventType>, cursorBeforeSeqNo, limit)` → `EconomyHistoryPage(entries, nextCursor)` mit
  `EconomyHistoryEntry` (Domain-Typen). Use Case `EconomyHistoryService` clampt das Limit serverseitig
  (Default 50, Max 200; nicht-positiv → `IllegalArgumentException` → 400), Cursor optional (null = neueste).
- **infra-persistence (jOOQ, kein Spring):** Keyset-Pagination auf `sequence_no DESC` (neueste zuerst),
  `WHERE player_uuid=? [AND currency_code=?] [AND event_type=?] [AND sequence_no < :cursor] LIMIT :limit+1`
  — nutzt den vorhandenen Index `idx_event_player_currency`. Das +1 entscheidet `nextCursor` (mehr da →
  `nextCursor` = `sequence_no` des letzten zurückgegebenen Eintrags, sonst null). `correlation_id` wird
  per `metadata ->> 'correlation_id'` aus dem JSONB gelesen.
- **api-rest:** `EconomyHistoryController` (dünn, getrennt neben `EconomyController`) —
  `GET /api/players/{uuid}/economy/history?currency&type&before&limit`. Unbekannter Spieler/leeres
  Ergebnis → leere `entries` + `nextCursor` null (kein 404). Ungültiger `type` / nicht-positives `limit`
  → 400 über den bestehenden `EconomyExceptionHandler`-Stil.
- **Tests grün:** jOOQ-Integration (Reihenfolge, Currency-/Type-Filter, Keyset-Pagination ohne
  Lücken/Überlappung + korrekter `nextCursor`, correlation_id aus metadata mit beiden Transfer-Legs),
  `EconomyHistoryServiceTest` (Limit-Clamping/Default/Cursor- & Filter-Weitergabe, Fakes),
  app-E2E (CREDIT/DEBIT + Transfer über REST → GET history, Type-Filter, Pagination, 400-Pfade).

### Technische Notizen
- **`-parameters`-Flag:** Controller liegen in `api-rest` (kein Spring-Boot-Plugin). Spring MVC braucht
  Parameter-Namen für `@PathVariable`; daher compiliert das `mcplatform.java-conventions`-Plugin alle
  Module mit `-parameters`.
- **Idempotenz vollständig (auch bei Gleichzeitigkeit):** Drei Ebenen — (1) prüfungs-first im
  `EconomyService`, (2) `lookup` am Transaktionsanfang im Repo, (3) als Race-Schutz fängt das Repo die
  `IntegrityConstraintViolationException` auf `uq_transaction` ab: bei zwei *zeitgleichen* Requests
  blockiert der Verlierer am Unique-Index bis der Gewinner committet, bekommt dann 23505 → das Repo
  liest das committete Ergebnis frisch nach und gibt es als idempotenten Replay zurück (kein Fehler,
  kein Doppelbuchen). Für Transfer analog über beide Legs. Belegt durch zwei Nebenläufigkeits-Tests
  (8 Threads, CyclicBarrier) für append und transfer — Ergebnis: genau eine Anwendung, kein Fehler.
  Hinweis: Der Catch muss NACH dem Rollback in einer frischen Query nachlesen, weil eine Postgres-
  Transaktion nach einem Constraint-Fehler vergiftet ist.

### Player Session Join erledigt (Spieler betritt Server → Identity + Default-Balances)
Eigener Use Case in der **Player-/Session-Domäne** (NICHT Economy), idempotent und sicher unter
parallelen Joins mehrerer Nodes (Velocity später).
- **Endpoint:** `POST /api/players/{uuid}/session/join`, Body `{ "name": "<spielername>" }`
  (wiederverwendet `PlayerRequest`). Antwort `SessionJoinResponse` = `{player, name, created, balances[]}`,
  wobei jeder Balance-Eintrag das bestehende `BalanceResponse` ist (kein paralleles DTO).
- **api-rest:** `PlayerSessionController` (dünn, nur Mapping) — bewusst getrennt vom `EconomyController`.
- **application:** `PlayerSessionService` (Use Case) orchestriert: Player-Upsert → bei Neuanlage je
  konfigurierter Währung initialisieren → Balances einsammeln. Ergebnis-Record `SessionJoin`.
  Neuer Outbound-Port `CurrencyRepository` (+ `CurrencyDefault`) liefert die konfigurierten Währungen
  mit ihrem `default_balance`.
- **Idempotenz & Atomarität (Variante A, bewusst gewählt):** Der Player-Upsert ist EIN atomares
  Statement (`INSERT … ON CONFLICT (uuid) DO UPDATE SET name, name_updated_at=now(), last_seen=now()`,
  `RETURNING (xmax = 0)`) und ist die alleinige Autorität für „neu?" — unter parallelen Joins gewinnt
  genau ein INSERT. Die Default-Balances entstehen über den **bestehenden** `EconomyService.credit`
  (event-sourced, eigene atomare TX), Quelle `source = "SYSTEM:initial"`. Der Idempotenz-Schlüssel
  wird **deterministisch** aus `(player, currency)` abgeleitet (`TransactionId.forInitialBalance`,
  `UUID.nameUUIDFromBytes`), sodass ein versehentlicher Doppel-Init am `uq_transaction`-Constraint
  abprallt statt doppelt gutzuschreiben. Kein Single-TX-Umbau des getesteten Economy-Stores
  (Reuse statt parallele Economy-Logik).
- **Startwert ist Config, kein Code:** kommt aus `currency.default_balance` (BIGINT, pro Währung),
  übers Webinterface per `UPDATE currency` änderbar. `default_balance > 0` → ein CREDITED-Event;
  `default_balance == 0` → KEIN Event (kein sinnloses CREDITED 0), aber eine konsistente
  `player_balance`-0-Zeile via neuem `EconomyEventStore.ensureZeroBalance` (Projektion bei
  Abwesenheit als 0/Version 0 anlegen — folding von null Events ist 0, also kein Direkt-Mutate).
- **infra-persistence:** `JooqPlayerRepository.upsertReturningWhetherNew` (xmax-Trick; `save`
  delegiert dorthin), `JooqCurrencyRepository`, `JooqEconomyRepository.ensureZeroBalance`.
- **app:** `CurrencyRepository`- und `PlayerSessionService`-Beans verdrahtet (`PersistenceConfig`,
  neuer `PlayerConfig`).
- **Tests grün:** `PlayerSessionJoinTest` (Testcontainers Postgres+Redis, REST + jOOQ-Assertions):
  erster Join → player + player_balance + genau ein `SYSTEM:initial`-CREDITED; zweiter Join →
  keine Doppel-Events/-Coins, aber name/name_updated_at/last_seen aktualisiert; `default_balance == 0`
  → 0-Zeile ohne Event. `./gradlew build` läuft grün.

### plugin-protocol lokal publizierbar (Variante B, Vorstufe zur privaten Registry)
- `plugin-protocol/build.gradle.kts` wendet jetzt zusätzlich `maven-publish` an (`java-library`
  kommt weiterhin aus dem Convention-Plugin) und definiert eine `MavenPublication`
  (`from(components["java"])`). group/version werden unverändert aus dem Convention-Plugin geerbt
  (`com.mcplatform` / `0.1.0-SNAPSHOT`), artifactId = Modulname `plugin-protocol`.
- `./gradlew :plugin-protocol:publishToMavenLocal` läuft grün; Artefakte (JAR + POM + Gradle-
  Module-Metadata) liegen unter `~/.m2/repository/com/mcplatform/plugin-protocol/0.1.0-SNAPSHOT/`.
- **Architektur-Check bestätigt:** Der publizierte POM enthält **keinen** `<dependencies>`-Block —
  das Modul ist framework-frei (kein Spring/jOOQ/Lettuce, nur JDK; Test-Deps werden nicht publiziert),
  konform zu Abschnitt 5.
- Alltags-Workflow dokumentiert in der README: Protokoll geändert →
  `:plugin-protocol:publishToMavenLocal` → im Plugin-Repo `build --refresh-dependencies`.
- Sonst wurde nichts geändert: nur das eine Modul angefasst, keine weiteren Build-Skripte/Module.

### COINS-Start-Bonus konfiguriert (Default-Startwert 100, via Migration)
- Neue Flyway-Migration `V3__set_coins_default.sql` (nächste freie Version nach V1/V2; keine
  bestehende Migration verändert): `UPDATE currency SET default_balance = 100 WHERE code = 'COINS'`.
- **Richtiger Ort, nicht hartcodiert:** Der Join-Service liest den Startwert über
  `CurrencyRepository` aus `currency.default_balance` — der Wert lebt also als Config-Datum und bleibt
  übers Webinterface (`UPDATE currency`) anpassbar, ohne Code-Änderung. Der Service bleibt unverändert.
- **Verifiziert (`CoinsStartBonusTest`, Testcontainers, ohne Override des Defaults):**
  (1) `currency.default_balance` für COINS == 100 nach Flyway. (2) Neuer Spieler joint mit balance=100
  und genau einem `SYSTEM:initial`-CREDITED-Event (amount=100). (3) Bestehender Spieler (Balance-Zeile
  bei 0 vor dem Bonus) wird **nicht rückwirkend** beschenkt — Join liefert `created=false`, keine
  Init-Gutschrift, balance bleibt 0, keine Events. Die Migration fasst nur `currency` an, nie
  `player_balance`. `./gradlew build` läuft grün.

### plugin-protocol feature-generisch gemacht (Schritt 1 des Plugin-Refactors)
Der Contract trägt jetzt JEDES künftige Feature über denselben Weg statt das Economy-Pipe-Format
zu duplizieren. Economy-Verhalten unverändert, nur über die neue Schicht geführt.
- **core-Schicht (`plugin-protocol.core`, generisch, kein Feature-Import):**
  `MessageEnvelope` (Framing `v<n>|messageType|payload`, `parse` splittet mit Limit 3 → Payload-Pipes
  überleben), `MessageCodec<T>` (Interface: `messageType`/`encodePayload`/`decodePayload`),
  `MessageProtocol` (löst **Routing** messageType→Codec **und Versionierung** EINMAL: `encode`,
  `decode(wire)`, typsicheres `decode(wire,codec)`, `peek`), `Channels` (Konvention `mc:{feature}:{topic}`).
- **economy-Schicht (`plugin-protocol.economy`):** `BalanceChangedEvent` (Package-Move, sonst gleich),
  `BalanceChangedEventCodec implements MessageCodec<BalanceChangedEvent>` (die früheren `v1`-Felder sind
  jetzt der versionslose 10-Feld-Payload INNERHALB des Envelopes; Version lebt im Envelope),
  `EconomyChannels.BALANCE` via `Channels.of(...)`.
- **Einziger Anstech-Punkt:** `PlatformProtocol.create()` registriert alle Feature-Codecs (heute nur
  Economy) — Backend (`RedisBalanceEventPublisher`) und Plugin bauen ihr Protocol daraus. Neues Feature
  = ein Codec hier + ein `XChannels`-Konstante + Event + Codec.
- **Wire bewusst geändert** (kein externer Consumer laut PROGRESS): Routing braucht den `messageType`
  auf dem Wire, daher `v1|economy.balance-changed|<10-Feld-Payload>` statt flachem `v1|<11 Felder>`.
  `protocolVersion` bleibt bei `1`. In einem Golden-Test gepinnt, damit jede künftige Wire-Änderung
  ein bewusster, reviewter Edit ist.
- **Tests grün:** `MessageEnvelopeTest` (Framing/Pipe-Erhalt/Reject), `MessageProtocolTest` (Routing mit
  zwei Fake-Codecs, Versions-/Typ-/Dup-Reject), `BalanceChangedEventCodecTest` (Envelope-Roundtrip ohne/
  mit correlationId, Delimiter+Unicode, **Golden-Wire**, Reject), und die app-E2E `EconomyVerticalSliceTest`
  dekodiert den neuen Wire über `PlatformProtocol.create().decode(...)` (Beweis: Backend-Consumer liest
  weiter). `./gradlew build` grün; publizierter POM weiterhin **ohne** `<dependencies>`.

### REST-Contract in plugin-protocol genormt (Schritt 2 des Plugin-Refactors)
Die REST-DTOs Plugin/Web↔Backend sind jetzt **geteilte Quelle** im Contract statt nur in `api-rest`;
plus eine generische, framework-freie Endpunkt-Beschreibung, damit der Client (Prompt 4) keine Pfade
als magische Strings streut.
- **DTOs (dependency-frei, JDK-only) im Contract:** `protocol.economy` → `BalanceResponse`,
  `AmountRequest`, `TransferRequest`, `TransferResponse`; `protocol.session` → `PlayerRequest`,
  `SessionJoinResponse` (enthält `List<BalanceResponse>`). Feldnamen = Wire-Contract; **kein** JSON im
  Modul.
- **Endpunkt-Beschreibung (generisch, `protocol.core`):** `HttpMethod`-Enum +
  `EndpointDescriptor<REQ,RES>(method, pathTemplate, requestType, responseType)` mit `expand(pathVars…)`
  (füllt `{uuid}`/`{currency}` JDK-only). Feature-Registries `EconomyEndpoints`
  (GET_BALANCE/CREDIT/DEBIT/SET/TRANSFER) und `SessionEndpoints` (UPSERT_PLAYER=PUT 204, JOIN). Neues
  Feature = eigene `XEndpoints`-Konstanten über denselben Descriptor.
- **Geteilte Quelle umgesetzt (Option A, bewusst):** Die alten `api-rest`-DTO-Records sind gelöscht;
  `api-rest` hängt jetzt an `:plugin-protocol` und benutzt dessen Records direkt. Die domänen-gekoppelte
  Abbildung (war als Factory-/Helper-Methoden auf den Records) liegt jetzt in dünnen Backend-Mappern
  `api/rest/support/EconomyMapper` + `SessionMapper` — die protocol-Records bleiben pure Daten. JSON
  unverändert (Jackson nutzt Record-Komponenten, paketunabhängig) → **kein API-Verhalten geändert**.
- **Proof-Weg begründet:** protocol bleibt JSON-frei, daher kein JSON-Test dort. Da es nach Option A nur
  noch EINEN DTO-Typ gibt, wäre „Feldabgleich Backend↔protocol" eine Tautologie → stattdessen
  JSON-Roundtrip-Test im `app` (`@JsonTest`, Spring-Jackson, kein Container): `RestDtoJsonContractTest`
  (4 grün) prüft exakte Feldnamen + De-/Serialisierung je DTO. In `plugin-protocol` ein **rein-JDK**
  `EndpointDescriptorTest` (3 grün) für `expand`/Typen/Reject. Zusätzlicher Live-Beweis: die
  bestehenden E2E-Suiten (`EconomyVerticalSliceTest`, `PlayerSessionJoinTest`, `CoinsStartBonusTest`)
  deserialisieren echtes HTTP-JSON jetzt in die protocol-DTOs → grün = kompatibel.
- `./gradlew build` grün; publizierter `plugin-protocol`-POM weiterhin **ohne** `<dependencies>`.

### Plugin-Skeleton erledigt (Schritt 3) — separates Repo `mc-platform-plugin` (Paper 1.21)
Eigenständiges Repo, Paper 1.21 / Java 21, **reiner Backend-Client**: keine DB, kein Spring, kein
direkter Zugriff auf die backend-internen `mc:bal:*`-HASHes. Es zieht ausschließlich den geteilten
Contract `com.mcplatform:plugin-protocol:0.1.0-SNAPSHOT` über **Maven Local** (`mavenLocal()` in den
Plugin-Repos). Hinweis: aus diesem Backend-Repo nicht mitgebaut — die folgenden Einträge sind
contract-seitig (real hier) bzw. spiegeln den Stand des Plugin-Repos.

**Was steht**
- **Schichtung** (DDD/hexagonal-analog, dünne Platform, generischer Transport, Feature-Module + geteilter Contract):
  - `platform` — dünnes Bootstrap (`JavaPlugin#onEnable/onDisable`): liest Config (Backend-Base-URL,
    Redis host/port/password), baut Transport + `FeatureRegistry`, registriert alle Features. Keine
    Feature-Logik.
  - `transport` — feature-agnostisch: `BackendClient` (REST) + `EventBus` (Redis-Sub) + dünner
    Redis-Subscriber. Kennt KEIN Economy-Detail.
  - `feature.*` — pro Feature ein Modul (heute `feature.economy` als Referenz): lokaler Read-Cache,
    Command-Handler (`/balance`), Channel-Handler. Implementiert ein `Feature`-Interface.
  - geteilter **`protocol`** = das `plugin-protocol`-Artefakt (core: Envelope/Codec/Channels/Endpoints;
    economy/session: Event + DTOs). Eine Quelle für Backend und Plugin.
- **Generischer `BackendClient` über `EndpointDescriptor`:** ruft `client.call(EconomyEndpoints.CREDIT,
  pathVars…, body)` — die URL kommt aus `descriptor.expand(uuid, currency)`, Methode/Request-/Response-Typ
  aus dem Descriptor. KEINE Pfad-Strings im Feature-Code; JSON-(De)Serialisierung kapselt EINE Stelle im
  Plugin (das Plugin darf eine JSON-Lib haben — der Contract bleibt JSON-frei). Reads/Commands gehen
  ausschließlich hierüber (z. B. Balance-Warmup per `GET_BALANCE`, Join per `SessionEndpoints.JOIN`).
- **Version-aware `EventBus`:** subscribt die Channels (`EconomyChannels.BALANCE` = `mc:economy:balance`),
  dekodiert eingehende Wire-Nachrichten über **dieselbe** `PlatformProtocol.create()` wie das Backend
  (Routing über `messageType` bleibt synchron) und dispatcht typisiert an den registrierten Handler. Pro
  `(player,currency)` wird die `version` (= `sequence_no`) verglichen: kleinere/zurückliegende Versionen
  werden **verworfen** (Schutz gegen out-of-order/Stale-Events), neuere aktualisieren den lokalen Cache.
- **`FeatureRegistry` + „so steckt man ein neues Feature an":** ein Feature implementiert das
  `Feature`-Interface (Channels + Handler + ggf. Commands) und wird an EINER Stelle in der Registry
  registriert; Transport (REST + EventBus) bekommt es geschenkt. Der genormte Weg für JEDES künftige
  Feature (Cosmetics, Permissions, Stats, Punishments) ist damit: **ein Channel (`Channels.of(...)`) +
  ein Event + ein `MessageCodec` (im Contract, registriert in `PlatformProtocol`) + ein `XEndpoints` +
  eine `Feature`-Registrierung** — kein Eingriff in Platform/Transport.

**Bewusste Grenzen (Skeleton)**
- Nur **Economy** ist als Referenz-Feature end-to-end verdrahtet (`/balance` liest den lokalen Cache,
  gefüllt per REST-Warmup beim Join und live aktuell gehalten über den EventBus). Session-Join wird beim
  Spieler-Join gegen `SessionEndpoints.JOIN` gerufen.
- **Kein Velocity** plugin-messaging (Server-Switch) — bleibt späterer Schritt. Single Paper-Node.
- Plugin schreibt nie direkt in Redis/DB; `mc:bal:*` ist backend-intern und für das Plugin unsichtbar.

**Technische Notizen**
- Maven-Local-Bezug: Contract ändern → `:plugin-protocol:publishToMavenLocal` (Backend-Repo) → im
  Plugin-Repo `build --refresh-dependencies` (Alltags-Workflow steht in der README).
- REST-Calls laufen über den Async-Scheduler (kein Blockieren des Main-Threads); EventBus-Dispatch
  übergibt Spielwelt-Mutationen zurück auf den Main-Thread.
- Decode-Symmetrie: Plugin und Backend bauen ihr `MessageProtocol` aus demselben
  `PlatformProtocol.create()` — ein neues registriertes Feature ist sofort auf beiden Seiten lesbar.

**Tests grün (im Plugin-Repo `mc-platform-plugin`)**
- Transport: `BackendClient` baut URLs korrekt aus `EndpointDescriptor.expand(...)` und trifft
  Methode/Request-/Response-Typ (gegen einen lokalen HTTP-Stub), inkl. der idempotenten Felder
  (`transactionId`/`correlationId` optional).
- `EventBus`: dekodiert ein echtes `mc:economy:balance`-Wire über `PlatformProtocol.create()` zu
  `BalanceChangedEvent` und wendet es an; **Staleness-Test** beweist, dass ein Event mit kleinerer
  `version` verworfen und der Cache nicht zurückgesetzt wird.
- `FeatureRegistry`: Registrieren eines Features verdrahtet dessen Channel-Subscription + Handler;
  ein Dummy-Feature zeigt den „ein Anstecken"-Pfad ohne Transport-Änderung.

### Nächster Schritt
- **Schritt 4 (echter In-Game-Slice / „Es lebt"):** Spieler joint → `SessionEndpoints.JOIN` → Warmup per
  `GET_BALANCE` in den lokalen Cache → `/balance` zeigt den Wert; eine Backend-Änderung (Web/Admin)
  pusht über `mc:economy:balance` live in den Cache und damit ins Spiel.
- Danach: SSE-Live-Push ans Webinterface (subscribt auf `mc:economy:balance`) und Config-CRUD/-Audit.

---

## Punishments — zweites Feature (Stand: 2026-06-20)

**Backend-seitig komplett gebaut, event-sourced, `./gradlew build` grün** (41 neue Tests). Reihenfolge
und Stil exakt wie Economy: `core-domain` → `application`/Ports → `infra-persistence` (jOOQ + Flyway) →
`api-rest`, plus `plugin-protocol`-Ergänzungen. Punishments sind ein **paralleles Geschwister** von
Economy (eigener Event-Store, eigene Tabellen) — **kein generischer Baustein wurde geändert**, einziger
Eingriff in geteilten Code ist die eine Registry-Zeile in `PlatformProtocol.create()`.

### Domäne (`core-domain/punishment`, framework-frei)
- **Aggregat `Punishment`**: `id, player, type, reason, issuedBy, issuedAt, expiresAt (null=permanent/n.a.),
  revokedBy, revokedAt, version`. `PunishmentType` = WARN|CHATBAN|TEMPBAN|PERMABAN, jeweils mit
  `PunishmentCategory` (NOTICE|CHAT|ACCESS) und `isTimeBound()`.
- **Event-sourced**: Events `ISSUED`/`REVOKED` (`PendingPunishmentEvent`/`AppliedPunishmentEvent`).
  **EXPIRED ist KEIN Event** — `isActive(now)` = `nicht revoked ∧ (expiresAt == null ∨ expiresAt > now)`.
- **Koexistenz-Regel** (`PunishmentPolicy`, unit-getestet): exklusive Kategorien (CHAT, ACCESS) lassen je
  **eine** aktive Strafe zu; NOTICE (WARN) kumuliert. → Chatban + Ban ja, Warns beliebig, zweiter aktiver
  Ban / zweiter Chatban → `PunishmentConflictException` (409). Idempotenz über `PunishmentTxId`
  (punishment-lokal, bewusst NICHT economy's `TransactionId` — kein Cross-Feature-Coupling).

### Persistenz (`infra-persistence`, Flyway V4–V6)
- `punishment_event` (append-only, `uq_punishment_transaction`) + Projektion `punishment` (current state
  je Aggregat, `category` mitgeführt). Schreibpfad in EINER Transaktion: Idempotenz-Lookup → bei
  exklusiver Kategorie **`SELECT … FOR UPDATE` auf die `player`-Zeile + Re-Check** → Event inserten →
  projizieren. Bewusst **kein** `version`-OCC wie Economy und **kein** partieller Unique-Index: die
  Invariante hängt an `now()` (Ablauf), den ein statischer Index nicht ausdrücken kann — der Per-Player-
  Lock ist der korrekte Concurrency-Backstop.
- **Templates** `punishment_template` (+ `punishment_template_audit`): CRUD mit Audit analog
  `server_config`/`config_audit` (`upsert` schreibt alt/neu-Snapshot). REST-seitig vorerst nur Lesen +
  Anwenden (Schreib-Endpoints kommen mit dem Webinterface); Seeds: `cheating`/`spam`/`warn_minor`.
- **`PermissionResolver`** (Port in `application.security`): erste Impl `JooqPermissionResolver` gegen
  `team_role_member` (uuid→role) + `team_role_permission` (role→permission, `*` = alles). Bewusst
  zwei Tabellen statt `server_config`-JSONB (queryable Many-to-Many). Eine spätere LuckPerms-Impl
  ersetzt NUR diese eine Klasse — alles hängt am Port. Seeds: ADMIN=`*`, MODERATOR=Teilmenge (kein
  `punishment.cheating`).

### Berechtigung (backend-autoritativ)
- Jeder Issue-/Revoke-/Template-Call prüft die required-permission **vor** dem Schreiben im
  `PunishmentService`; fehlt sie → `PermissionDeniedException` (403). Direkter Issue:
  `punishment.issue.<type>`; Template: dessen `requiredPermission`; Revoke: `punishment.revoke`.
- `GET /templates` liefert **alle** aktiven Templates **+ pro Template ein `canApply`-Flag** (backend
  berechnet für die abfragende UUID) — Entscheidung: bessere UX/Discoverability als Filtern, und nicht
  fälschbar, weil serverseitig.

### protocol (`plugin-protocol.punishment`, dependency-frei → Maven Local publiziert)
- DTOs `PunishmentResponse`, `IssueRequest`, `IssueFromTemplateRequest`, `RevokeRequest`,
  `TemplateResponse`; Event `PunishmentChangedEvent` + `PunishmentChangedEventCodec` (Wire über den
  bestehenden `MessageEnvelope`, `messageType = punishment.changed`, kein neues Format).
- `PunishmentChannels.CHANGED = mc:punishment:changed`. `PunishmentEndpoints`: `LIST_ACTIVE`, `ISSUE`,
  `ISSUE_FROM_TEMPLATE`, `REVOKE`, `LIST_TEMPLATES`. Codec in `PlatformProtocol` registriert.

### REST (`api-rest`)
- `GET /api/players/{uuid}/punishments/active`, `POST .../punishments` (Issue),
  `POST .../punishments/from-template`, `POST /api/punishments/{id}/revoke`,
  `GET /api/punishments/templates?staff={uuid}`.
- Fehler-Codes (`PunishmentExceptionHandler`, konsistent zu `EconomyExceptionHandler`): 403 fehlende
  Permission, 409 Koexistenz-/Revoke-Konflikt, 404 unbekannte Strafe/Template, 422 semantisch ungültig
  (z. B. TEMPBAN/CHATBAN ohne Dauer), 400 (Bad Request) bleibt beim Economy-Handler.
- Bei jeder Zustandsänderung: `PunishmentChangedEvent` auf `mc:punishment:changed` (best-effort nach
  Commit, gleicher Pfad wie `BalanceChangedEvent`).

### Tests grün (41)
- Domain: `PunishmentTest` (aktiv/expired/revoked), `PunishmentPolicyTest` (Koexistenz inkl.
  expired/revoked blockiert nicht). Application: `PunishmentServiceTest` (Permission, Konflikt, Revoke,
  Idempotenz+Publish-once, from-template, Validierung, canApply) mit Fakes + fixem `Clock`.
- jOOQ/Testcontainers: `JooqPunishmentRepositoryTest` (issue+active+revoke, Idempotenz, exklusiv-Konflikt,
  expired blockiert nicht, not-found/already-revoked), `JooqPermissionResolverTest`,
  `JooqPunishmentTemplateRepositoryTest` (Audit-Trail). Protocol: `PunishmentChangedEventCodecTest`.
  E2E: `PunishmentVerticalSliceTest` (issue→active→revoke + Pub/Sub-Event, 409 zweiter Ban,
  from-template 200/403, `canApply`).

### Offen / später
- Template-Schreib-Endpoints (CRUD-UI) + Velocity/Plugin-Enforcement (Join-Block bei aktivem ACCESS-Ban,
  Chat-Block bei aktivem CHATBAN) — Plugin-Repo.
- Nach diesem zweiten event-sourced Feature lohnt die Prüfung, ob `TransactionId`/`PunishmentTxId` und der
  Insert-in-Tx+Projektion-Kern zu einem gemeinsamen, economy-freien Baustein extrahiert werden (Rule of three).

---

## Kurskorrektur & Migrations-Setup (Stand: 2026-06-22)

**Architektur-Entscheidung revidiert: Single-Server statt Multi-Server/Velocity.**
Frühere Einträge (Abschnitte 1–2, „1..N Nodes", „200+ Peak", Pub/Sub „über alle Nodes hinweg")
spiegeln den ursprünglichen Multi-Server-Plan — bewusst als Historie belassen. Aktueller Stand:

- **Single Paper-Node.** Kein Distributed-Locking, kein Cross-Server-Item-Dupe-Schutz, keine
  Cross-Server-Presence nötig. Velocity ist zurückgestellt (nicht gestrichen, aber kein Designtreiber).
- **Redis-Pub/Sub bleibt** der Live-Pfad Backend↔Plugin/Webinterface (Cache-Invalidierung, Live-Push),
  nicht zwischen Game-Servern. Das alte RAM-Cache+Flush-Modell wird trotzdem durch „Plugin schreibt
  durchs Backend" ersetzt — als Crash-Sicherheit/Qualität, nicht aus Multi-Server-Zwang.
- **Externer Webshop** ist ein legitimer zweiter Schreib-Akteur für Economy: Käufe als event-sourced
  Credit mit `source='WEBSHOP'`, idempotent über Bestell-ID. Backend bleibt Balance-Wahrheit.
- **Moderation aufgeteilt:** Spieler-Strafen → Punishments; Globalmute/Chatclear/Wordfilter/Broadcast
  → Server-/Moderation-Tools (Server-Zustand); Reports + Support/Tickets → eigenes Moderation/Tickets-
  Modul (Anschuldigung ≠ Urteil).

**Migration via Spec-Kit aufgesetzt.** Constitution unter `.specify/memory/constitution.md`, CLAUDE.md
verankert. Altes Plugin inventarisiert in `FEATURE_INVENTORY.md` (91 Features, ~80% migrieren).
Vorgehen: ein Feature = ein kompletter Vertical Slice (Backend → publish → Plugin), eins nach dem
anderen, in Inventar-Reihenfolge.

**Offen / beim dritten event-sourced Feature prüfen:** Die in der Punishments-Sektion notierte
Rule-of-three-Extraktion (`TransactionId`/`PunishmentTxId` + Insert-in-Tx+Projektion-Kern → gemeinsamer
economy-freier Baustein) wird beim ersten datenzentrischen Import relevant — bewusst beobachten, ob
sich ein drittes paralleles Geschwister lohnt oder die Generalisierung.

### Nächster Schritt
- **Phase-0-Pipeline-Test** (Spec-Kit-Workflow validieren an etwas Risikoarmem): Worldborder (#89)
  oder Settings/Toggles (#9) als kompletter Slice. Ziel: beweisen, dass Spec→Plan→Tasks→Implement
  sauber durch beide Repos läuft, bevor ein großes Feature riskiert wird.

---

## Reports — drittes Feature (Moderation-Modul, Stand: 2026-06-22)

**Backend-seitig komplett gebaut, `./gradlew build` grün.** Erstes Feature, das den vollen Spec-Kit-Lauf
(specify → clarify → plan → tasks → analyze → implement) durchlaufen hat. Reports sind ein
**eigenständiges Moderation-Modul, strikt getrennt von Punishments** (Constitution-Prinzip 16:
Anschuldigung ≠ Urteil). Spec/Plan/Artefakte unter `specs/001-reports/`.

### Bewusst NICHT event-sourced (Kontrast zu Economy/Punishments)
Reports sind **state-stored**: Zustandstabelle `report` + Audit-Tabelle `report_status_history` (analog
`server_config`/`config_audit`). Begründung (Prinzip 6): ein Report ist kein geld-/urteils-kritisches
Aggregat; der einzige Audit-Bedarf („wer hat wann den Status geändert") deckt die History-Tabelle. Damit
**kein** drittes event-sourced Geschwister — die notierte Rule-of-three-Extraktion bleibt unberührt.
Concurrency über **Optimistic Locking** (`report.version`), Dedupe/Idempotenz über einen **partiellen
Unique-Index** `(reporter,target) WHERE status IN ('OPEN','IN_PROGRESS')` (statischer Invariant → Index
statt Per-Player-Lock wie bei Punishments).

### Domäne (`core-domain/report`, framework-frei)
- Aggregat `Report` (state-stored record): `id, reporter, target, category, detail, chatContext, status,
  createdAt, lastHandledBy, lastStatusChangeAt, version`. Factory `create` (Self-Report-Sperre +
  Detail-Validierung), `transitionTo` (Status-Übergangsregeln). `ReportStatus` (OPEN→IN_PROGRESS→
  RESOLVED/REJECTED, terminal-Regeln), `ReportCategory` (CHEATING|BELEIDIGUNG|SPAM_WERBUNG|
  TEAMING_BUG_ABUSE|SONSTIGES). `ChatContext`/`ChatContextEntry` (unveränderlicher öffentlicher
  Chat-Schnappschuss, mehrere Absender, Größen-/Längen-Validierung). `ReportChange` (Live-Notifikation,
  **ohne** Chat-Kontext). Exceptions `ReportValidationException`/`InvalidStatusTransitionException`.

### Persistenz (`infra-persistence`, Flyway V7–V8, jOOQ)
- `V7__report_schema.sql`: `report` (+ partieller Unique-Index `uq_report_open`, CHECK
  `reporter<>target`, Indizes), `report_chat_message` (1:N-Kind-Tabelle für den Chat-Schnappschuss —
  **bewusst statt JSONB**, weil `infra-persistence` keine JSON-Lib hat; robuster Round-Trip über
  jOOQ-Zeilen), `report_status_history` (Audit je Statuswechsel). `V8__seed_report_permissions.sql`
  (`report.view`/`report.handle` → MODERATOR; ADMIN via `*`).
- `JooqReportRepository`: create (report + chat + History in 1 TX; Unique-Verletzung → bestehenden
  offenen Report zurück = Dedupe; FK-Verletzung → ValidationException), `findOpenFor`,
  `lastCreatedAtByReporter` (Cooldown), `findOpen`, `find`, `changeStatus` (OCC + History).

### Application (`application/report`)
- `ReportService`: `create` (Dedupe → Cooldown → persist → publish), `listOpen` (`report.view`-Gate),
  `changeStatus` (`report.handle`-Gate, Domain-Übergang, OCC, publish). Ports `ReportRepository`,
  `ReportPublisher`. **PermissionResolver wiederverwendet** (kein Neubau). Cooldown als Config
  (`mcplatform.reports.cooldown-seconds`, Default 60).

### protocol (`plugin-protocol.report`, JDK-only → Maven Local publiziert)
- DTOs `CreateReportRequest`/`ChangeStatusRequest`/`ReportResponse`/`ChatMessage`; Event
  `ReportChangedEvent` + `ReportChangedEventCodec` (`messageType = report.changed`, 7-Feld-Pipe-Wire);
  `ReportChannels.CHANGED = mc:report:changed`; `ReportEndpoints` (CREATE/LIST_OPEN/CHANGE_STATUS).
  Codec in `PlatformProtocol.create()` registriert — **der einzige Eingriff in geteilten Code**. POM
  weiterhin ohne `<dependencies>`.

### REST (`api-rest`)
- `POST /api/reports` (offen, kein Permission-Gate), `GET /api/reports/open?staff=` (report.view),
  `POST /api/reports/{id}/status` (report.handle). `ReportExceptionHandler` ergänzt 422
  (`report_invalid`), 429 (`report_cooldown`), 404 (`report_not_found`), 409 (`report_conflict` —
  ungültiger Übergang + OCC). **403/400 bewusst NICHT re-deklariert** (globales Mapping aus
  Punishment-/Economy-Handler greift; Doppel-Deklaration wäre ambiger Konflikt).

### Berechtigung / Abgrenzung
- Erstellen: jeder Spieler. Sehen/Bearbeiten: backend-autoritativ über PermissionResolver. Ein Report
  **erzeugt nie eine Strafe** — kein Schreibpfad ins Punishments-Modul, keine geteilten Idempotenz-Keys.

### Bewusste Grenzen (Slice 1)
- Kein Lese-/Such-Endpoint für **abgeschlossene** Reports (nur DB-auditierbar), keine Aggregation
  mehrerer Melder, keine Spieler-Benachrichtigung über den Ausgang, **keine PNs** im Chat-Kontext (nur
  öffentlicher Chat — PN-Datenschutz-Policy verschoben), kein Auto-Purge/Retention (unbegrenzt).
  Plugin-/Menü-Seite (Adventure-Menü) ist separate Plugin-Arbeit.

### Tests grün
- Domain: `ReportTest` (Self-Report/Detail/Chat-Validierung), `ReportStatusTest` (Übergangsmatrix).
  Application: `ReportServiceTest` (Dedupe, Cooldown, Permission-Gates, Lifecycle, Publish-once, Fakes).
  jOOQ/Testcontainers: `JooqReportRepositoryTest` (persist+History+Chat-Round-Trip, Dedupe via
  Unique-Index, FK-unbekanntes-Ziel, OCC-Konflikt, findOpen). Protocol: `ReportChangedEventCodecTest`
  (Roundtrip + Golden-Wire + Routing). E2E: `ReportVerticalSliceTest` (create+Chat, 422/429/403/404/409,
  offene Liste, Status-Lebenszyklus, Pub/Sub CREATED+STATUS_CHANGED).

### Offen / später
- US-Erweiterungen: Lese-/Historien-Endpoint für abgeschlossene Reports, PN-Chat-Kontext (mit
  Datenschutz-Policy), Retention/Purge, optionale Referenz Report→Punishment. Plugin-Slice (Menü +
  EventBus-Anbindung an `mc:report:changed`).

---

## Permissions/Ranks — viertes Feature (Foundation, state-stored)

Baut die minimale `JooqPermissionResolver`-Implementierung (vorher: `team_role_member` +
`team_role_permission`, ein Rang/Spieler, read-only, kein Ablauf) hinter dem **unveränderten**
`PermissionResolver`-Port zur vollständigen, einheitlichen Permission-Welt aus. **State-stored**
(Reports-Muster), nicht event-sourced. Branch `002-permission-rank-system`.

### Domain (`core-domain`, framework-frei)
- `Role`/`RoleId`, `RoleGrant`/`PermissionGrant` (`isActive(now)`), `PermissionMatcher` (reine
  Wildcard/Union-Regel: `*`, `feature.*`-Präfix, exakt, keine Negation), `EffectivePermissions.resolve`
  (Union aktiver Rollen-Perms + Default-Fallback + direkte Grants), `RankDisplay` (Tie-Break
  `team_rank`→`weight`→`RoleId`), `PermissionChangeType` (Domänen-Enum). Vollständig unit-getestet.

### Application (`application/permission`)
- Ports `RoleRepository`, `PlayerGrantRepository`, `GrantAuditPort`, `PermissionChangePublisher`
  (Domänentyp — `application` bleibt `plugin-protocol`-frei).
- `PermissionAdminService`: Rollen-CRUD + Rollen-Permission-Config + Grant/Revoke; **Permission-Check
  VOR jedem Schreiben** (wie PunishmentService, Gate = derselbe Port); Upsert (max. 1 aktiver Grant je
  `(uuid,role)`, permanent schlägt befristet); kaskadierendes Löschen (REVOKE je Halter + Audit);
  Default-Rolle nicht lösch-/deaktivierbar.
- `PermissionQueryService` (effektive Permissions + Anzeige), `GrantExpiryService` (Sweep: inaktiv +
  EXPIRE-Audit mit SYSTEM-Sentinel + **ein Event je betroffener UUID**).

### Persistenz (`infra-persistence`, Flyway **V9**)
- Tabellen `role`, `role_permission`, `player_role_grant`, `player_permission_grant`, `grant_audit`
  (Audit-Stil analog `config_audit`). `team_role_*` **abgelöst** — leere member-Tabelle, daher kein
  Backfill; geseedete ADMIN(`*`)/MODERATOR-Permissions in `role_permission` migriert, DEFAULT-Rolle
  (leer) angelegt.
- `JooqPermissionResolver` **umgeschrieben** (Port-Signatur byte-identisch): eine CTE-Query, Union über
  aktive Grants auf **aktive** Rollen (`role.active`, FR-007a) + Default-Fallback + direkte Grants,
  Wildcard via `starts_with`, Aktivität gegen DB-`now()` → korrekt auch zwischen Scheduler-Ticks,
  Resolver bleibt clock-frei. `JooqRoleRepository`, `JooqPlayerGrantRepository` (Upsert via
  `ON CONFLICT`), `JooqGrantAuditRepository`.

### REST (`api-rest`) + Live (`app`)
- `PermissionController` (Rollen-CRUD, Rollen-Permission add/remove, Grant/Revoke Rolle+Permission,
  `/effective`). `PermissionExceptionHandler`: 404 `role_not_found`, 409 `role_name_conflict` /
  `default_role_protected`, 422 `permission_invalid` — **403/400 bewusst NICHT re-deklariert**.
- `RedisPermissionEventPublisher` → `mc:permission:changed` (mappt Domänen-Enum → Wire-String).
  `SchedulingConfig` (`@EnableScheduling`, erster Backend-Scheduler — Composition-Root) treibt den
  Ablauf-Sweep (Default 30 s).

### Protocol (`plugin-protocol`, JDK-only)
- `PermissionChangedEvent` + `PermissionChangedEventCodec` (`permission.changed`, 3-Feld-Pipe-Wire,
  Golden-Test gepinnt), `PermissionChannels.CHANGED = mc:permission:changed`, `PermissionEndpoints`,
  DTOs (`RoleRequest`/`RoleResponse`/`Grant*Request`/`PlayerPermissionsResponse`/…). Codec in
  `PlatformProtocol.create()` registriert — **der EINZIGE Eingriff in geteilten Code**. POM ohne
  `<dependencies>`. `publishToMavenLocal` ausgeführt.

### Geklärte Entscheidungen (Spec/Clarify)
- Default-Rang = impliziter Fallback ohne Zeile · Anzeige-Tie-Break `team_rank`→`weight`→ID ·
  Live-Ablauf = periodischer Scheduler + Pub/Sub · Akteur = UUID (+ SYSTEM-Sentinel für EXPIRE) ·
  Rollen-Löschung kaskadiert · doppelter Grant = Upsert · Default-Rolle startet leer.

### Tests grün
- Domain: `PermissionMatcherTest`, `EffectivePermissionsTest`, `GrantActivityTest`, `RankDisplayTest`.
  Application (Fakes): `PermissionAdminServiceTest` (403-Gate, Upsert, Kaskaden-REVOKE,
  Default-Schutz), `GrantExpiryServiceTest` (Expire→inaktiv+Audit, ein Event je UUID).
  jOOQ/Testcontainers: `JooqPermissionResolverTest` (Union, Ablauf via `now()`, Wildcard, deaktivierte
  Rolle, Default-Fallback, ADMIN/MOD-Seeds erhalten). Protocol: `PermissionChangedEventCodecTest`.
  E2E: `PermissionVerticalSliceTest` (Create+Config+Grant→`/effective`, 403, Default-Anzeige,
  `mc:permission:changed`). **SC-001 bewiesen**: Punishment-/Report-Slices unverändert grün.

### Bewusste Grenzen / Abgrenzung
- Account-Verknüpfung Web-Login ↔ Minecraft-UUID NICHT enthalten (späteres Auth-Feature) — die
  einheitliche Permission-Welt dockt dann transparent an. Plugin-/Menü-Seite separater Slice.
  Kein Resolver-Cache (zwei kleine indexierte Queries; Live-Push existiert fürs Plugin-Cache).
- **Keine generische Klasse geändert** außer der einen Codec-Zeile in `PlatformProtocol.create()`.
  Neu im Composition-Root: `@EnableScheduling` (Sweep-Logik selbst framework-frei).

### Nachtrag (additiv): Rollen-Display-Icon
- Optionales `display_icon` (Flyway **V10**, `ALTER TABLE role ADD COLUMN`, nullable `VARCHAR(512)` —
  V9 unangetastet) auf der flachen Rollen-Tabelle, neben `prefix`/`color`/`weight`. Backend speichert
  einen **opaken** präfixierten String (`<type>:<payload>`, z. B. `material:DIAMOND_SWORD`,
  `head-texture:<texture>`, `head-player:<uuid>`) und **interpretiert ihn NIE** — kein ItemStack/NBT/
  Bukkit-Wissen; interpretiert wird nur im Plugin. Behandlung wie `prefix`/`color`: gespeichert,
  durchgereicht, im `/effective`-Display mitgeliefert.
- Grobe Domänen-Validierung in `RoleDisplayIcon` (core-domain, framework-frei, unit-getestet): null =
  kein Icon; sonst nicht-leer, Form `<type>:<payload>` mit nicht-leerer Nutzlast und **bekanntem**
  Präfix (Allowlist `material`/`head-texture`/`head-player`). Nutzlast bewusst NICHT geprüft. Neue
  Icon-Typen = Allowlist erweitern, **keine** Schema-Änderung. Über denselben Rollen-Endpoint setzbar
  (kein neuer Endpoint); als String in `RoleRequest`/`RoleResponse`/`RoleDisplay` ergänzt
  (`publishToMavenLocal` erneut). `PermissionChangedEvent` trägt **keine** Darstellung (Plugin lädt
  `/effective` nach), daher kein Codec-/Golden-Wire-Eingriff nötig. Tests: `RoleDisplayIconTest` +
  DTO-Roundtrip/Display in `PermissionVerticalSliceTest`.

---

## Web-Auth-Bridge — fünftes Feature (state-stored, Stand: 2026-06-24)

**Backend-seitig komplett gebaut, `./gradlew build` grün.** Erste **Greenfield-Infrastruktur fürs
Webinterface** (kein Altplugin-Import). Ein Spieler verbindet ingame seine Minecraft-Identität (UUID)
mit einem Web-Account: `/web link` erzeugt einen kurzlebigen, einmal verwendbaren Token (als klickbarer
Link), im Web setzt der Spieler ein Passwort; `/web resetPassword` ist Recovery über denselben
Mechanismus. Die laufende JWT-Login-Session bleibt ein **separater Folge-Slice**. Spec/Plan/Artefakte:
`specs/003-web-auth-bridge/`. Branch `003-web-auth-bridge`.

### Besonderheiten gegenüber den bisherigen Features
- **State-stored** (Reports-Muster), nicht event-sourced: ein Web-Account ist Identitäts-/Config-Datum.
  Tabellen `web_account` + `web_link_token` + append-only `web_auth_audit` (Flyway **V11**;
  V9/V10 waren vom Permission-Feature belegt).
- **Erstes Feature ohne Live-Pfad:** kein Redis/Pub-Sub, **kein** `MessageCodec`/`Channel` →
  `PlatformProtocol.create()` bleibt **byte-identisch** (verifiziert). Der einzige geteilte
  Contract-Zuwachs sind rein datentragende, **neue** Klassen (`WebAuthEndpoints`, `TokenResponse`,
  `RedeemRequest`).
- **Kein Permission-Gate** in diesem Slice: Self-Service (der Server-Login beweist die Identität).
  `/web unlink` bewusst verschoben (Konto-Management-Slice).

### Domäne (`core-domain/webauth`, framework-frei)
- `WebAccount` (state-stored record), `LinkToken` (`isExpired(now)`), `TokenPurpose` (LINK|RESET),
  `PasswordPolicy` (8..64 Zeichen, 64er-Grenze unter der 72-Byte-BCrypt-Schranke → kein stilles
  Abschneiden), Ports `PasswordHasher` (hash/matches) + `TokenGenerator`, `InvalidPasswordException`.
- **Passwort-Hashing als Port:** BCrypt-Impl liegt im `app`-Modul (spring-security-crypto), **nicht** in
  `infra-persistence` — das bleibt Spring-frei. core-domain sieht nie BCrypt.

### Persistenz (`infra-persistence`, jOOQ, Flyway V11)
- `JooqLinkTokenRepository`: `issue` = `DELETE WHERE (uuid,purpose)` → `INSERT` in **einer** TX
  (höchstens ein aktiver Token je (uuid,purpose), `uq_web_link_token_uuid_purpose`); `lastCreatedAt`
  (Cooldown-Anchor); `deleteExpired` (Hygiene). **Token-at-rest = SHA-256-Hash** des Rohtokens
  (`token_hash` PK) — ein DB-Read-Leak liefert keinen einlösbaren Token; SHA-256 reicht (Token ≥128 Bit,
  kein Salt/Slow-Hash nötig), reines JDK → keine neue Dependency in infra.
- `JooqWebAccountRepository`: `exists`; **atomarer `redeem` in einer TX** (FR-018): `SELECT … FOR UPDATE`
  auf die unexpired Token-Zeile → LINK `INSERT … ON CONFLICT DO NOTHING` (0 → 409) bzw. RESET
  `UPDATE … WHERE player_uuid` (0 → 409) → Token-`DELETE` (single-use) → `web_auth_audit`-INSERT
  (`ACCOUNT_CREATED`/`PASSWORD_RESET`, nie Passwort/Hash). **Kein** `version`/OCC: pro Account schreibt
  nur der Eigentümer-Redeem; der `FOR UPDATE` auf der einzelnen Token-Zeile serialisiert konkurrierende
  Redeems desselben Tokens.

### Application (`application/webauth`)
- `WebAuthService`: `requestLinkToken` (Vorbedingung kein Account → 409, Cooldown → 429),
  `requestResetToken` (Vorbedingung Account → 409, Cooldown), `redeem` (Policy-Check → hash → atomarer
  Repo-Redeem). Ports `WebAccountRepository`/`LinkTokenRepository`, `TokenResult`, Exceptions
  (Exists/Missing/Conflict/TokenInvalid/Cooldown). Cooldown via `Clock` + `Duration` (Reports-Muster).

### REST (`api-rest`) + app
- `WebAuthController`: `POST /api/players/{uuid}/web-auth/link-token` & `.../reset-token` (Plugin),
  `POST /api/web-auth/redeem` (Web, 204). `WebAuthExceptionHandler`: 409 `web_account_exists`/
  `_missing`/`_conflict`, **410** `web_auth_token_invalid` (uniform — leakt keine Existenz), 422
  `password_invalid`, 429 `web_auth_cooldown`; **400 bewusst nicht re-deklariert**. `WebAuthMapper`
  (Domain↔DTO). `WebAuthConfig` (Beans, Cooldown/TTL aus Config, bestehende `Clock`-Bean),
  `PersistenceConfig` +2 Repo-Beans, optionaler `WebLinkTokenPurge` (`@Scheduled` über die bestehende
  `@EnableScheduling` — Hygiene, nicht sicherheitskritisch). Config: `mcplatform.webauth.token-ttl-minutes`
  (10), `token-cooldown-seconds` (60), `purge-interval-ms`.

### Protocol (`plugin-protocol.webauth`, JDK-only → Maven Local)
- `TokenResponse` (token, purpose, expiresAtEpochMilli — **nie** Hash/email), `RedeemRequest`
  (token, password), `WebAuthEndpoints` (REQUEST_LINK/REQUEST_RESET/REDEEM via `EndpointDescriptor`).
  **Kein** Codec/Channel. POM weiterhin ohne `<dependencies>`; `publishToMavenLocal` ausgeführt.

### Tests grün
- Domain: `PasswordPolicyTest`, `LinkTokenTest`. Application (Fakes): `WebAuthServiceTest`
  (Vorbedingungen, Cooldown, Redeem LINK/RESET, Single-use, Token-invalid/Conflict, **kein Klartext**).
  jOOQ/Testcontainers: `JooqLinkTokenRepositoryTest` (DELETE-vor-INSERT, expires_at, deleteExpired,
  SHA-256-Roundtrip, FK), `JooqWebAccountRepositoryTest` (atomarer Redeem LINK/RESET, Replay→410,
  expired→410, Konflikte). Protocol (rein-JDK): `WebAuthEndpointsTest`. E2E (`app`):
  `WebAuthVerticalSliceTest` (link→redeem→BCrypt-Account ohne Klartext, 409/410/422/429, Reset-Flow,
  JSON-Contract ohne Hash/email). Bestehende Slices unverändert grün.

### Bewusste Grenzen / Abgrenzung
- Kein JWT-Login (Folge-Slice), keine E-Mail/E-Mail-Recovery, kein Redis/Pub-Sub, kein `/web unlink`
  (Q1, Konto-Management-Slice), keine Rollen-/Rechte-UI. Name→UUID-Auflösung (`LOWER(name)` + jüngster
  `last_seen`, nutzt `idx_player_name_lower`) ist als Regel fixiert, aber **erst im Login-Slice**
  implementiert — dieser Slice braucht sie nicht (Command kennt die UUID).
- **Keine generische Klasse geändert** außer additiven Anstech-Punkten (PersistenceConfig-Beans,
  neue WebAuthConfig, neue protocol-Klassen). Erste Krypto-Dependency (`spring-security-crypto`) — nur
  im `app`-Modul, hinter dem `PasswordHasher`-Port.

### Offen / später (separater Plugin-Repo `mc-platform-plugin`)
- `feature.web`-Modul: `/web link`/`resetPassword` über `BackendClient` → `WebAuthEndpoints`, klickbarer
  Adventure-Link aus `TokenResponse.token`. Danach: JWT-Login-Session-Slice, `/web unlink`-Slice.

---

## JWT-Login-Session — sechstes Feature (state-stored, Stand: 2026-06-24)

**Backend-seitig komplett gebaut, `./gradlew build` grün.** Folge-Slice der Web-Auth-Bridge: die laufende
Web-Login-Session. Ein Spieler mit `web_account` loggt sich im Webinterface mit aktuellem MC-Namen + Passwort
ein und erhält ein **stateless Access-JWT** (HS256, Subject = player_uuid, **nur Identität**) plus ein
**state-stored, rotierendes Refresh-Token**. Spec/Plan/Artefakte: `specs/004-jwt-login-session/`. Branch
`004-jwt-login-session`.

### Besonderheiten / Entscheidungen
- **State-stored** (Bridge-/Reports-Muster), nicht event-sourced: Session ist ein Identitäts-/Sitzungsdatum.
  Eine Tabelle `refresh_token` (Flyway **V12**; V10/V11 vom Permission-/Bridge-Feature belegt).
- **Access-JWT zustandslos** — gar nicht persistiert. Nur das Refresh-Token ist state-stored, **nie im
  Klartext** (SHA-256-Hash at rest, reused `JooqLinkTokenRepository.sha256Hex`).
- **Autorisierung ausschließlich über `PermissionResolver`** (kein zweiter Pfad): der JWT trägt keine
  Rollen/Authorities; die Security-Chain vergibt leere Authorities, Rechte kommen pro Request aus dem Resolver.
- **Kein neues `infra-security`-Modul:** JWT-Port (`TokenIssuer`/`TokenVerifier`) in `application`, jjwt-Impl
  (`JwtTokenService`, HS256, Secret aus Env) im `app`-Composition-Root (spiegelt die BCrypt-Impl der Bridge),
  Filter + `SecurityFilterChain` in `api-rest` (sehen nur den `TokenVerifier`-Port — jjwt bleibt aus api-rest).
- **Rotation strikt:** Refresh entwertet das alte Token in EINER TX (`rotated_at`-Marker, nicht Löschen) und
  stellt ein neues aus; Replay eines bereits rotierten Tokens = Diebstahls-Signal → **alle** Refresh-Tokens der
  player_uuid werden invalidiert (kein Familien-Feld). Logout löscht das vorgelegte Token (idempotent).
- **Passwort-Reset (Bridge, D4):** `JooqWebAccountRepository.resetPassword` löscht zusätzlich **alle**
  Refresh-Tokens des Spielers in derselben TX (atomar; einziger Eingriff in bestehenden Bridge-Code).
- **Token-Transport:** Access im JSON-Body (+ `Authorization: Bearer`), Refresh als httpOnly/Secure/
  SameSite=Strict-Cookie (`Path=/api/web-auth/session`), **nie** im Body. CORS: explizite Allowed-Origin +
  credentials; CSRF global aus (stateless Bearer-API), Refresh/Logout zusätzlich per `X-Refresh`-Header geschützt.

### Domäne (`core-domain/webauth`, framework-frei, JWT-frei)
- `RefreshToken` (Wertobjekt: tokenHash, playerUuid, createdAt, expiresAt, rotatedAt, rotatedFrom) mit
  `isExpired`/`isActive`/`isConsumed`. Reused: `PasswordHasher`/`TokenGenerator`/`WebAccount` aus der Bridge.

### Application (`application/webauth`)
- Ports `TokenIssuer`/`TokenVerifier` (JWT als Port), `RefreshTokenRepository` (+ sealed `RotateResult`
  Rotated/Invalid/Replay). `WebSessionService` (login/refresh/logout). Additive Port-Methoden:
  `PlayerRepository.findUuidByName` (Name→UUID, jüngster last_seen) + `WebAccountRepository.find`. Einheitliche
  `InvalidCredentialsException` (kein Name-vs-Passwort-/Account-Leak, D3).

### Persistenz (`infra-persistence`, jOOQ, Flyway V12)
- `JooqRefreshTokenRepository`: `store` (+ Audit `LOGIN`), atomares `rotate` (`FOR UPDATE` → rotated/replay/
  invalid; Replay löscht alle Player-Tokens + Audit `TOKEN_REUSE_DETECTED`; Erfolg Audit `TOKEN_ROTATED`),
  `deleteByRawToken` (Logout, Audit `LOGOUT`), `deleteAllForPlayer`, `purgeExpired`. Audit reused den
  `web_auth_audit`-Trail (free-text `event_type`, nie Token/Hash). `JooqPlayerRepository.findUuidByName`,
  `JooqWebAccountRepository.find` + D4-Purge ergänzt.

### REST/Security (`api-rest`) + app
- `JwtAuthenticationFilter` (Bearer → `TokenVerifier`-Port → SecurityContext-Principal = PlayerId, leere
  Authorities), `SecurityConfig` (stateless, csrf off, `/api/web/**` authenticated, **anyRequest permitAll** →
  Alt-Endpoints unberührt, CORS-Bean). `WebSessionController` (login/refresh/logout, Cookie-Handling,
  `X-Refresh`-Guard). `WebAuthExceptionHandler` um 401 erweitert (`web_auth_invalid_credentials`/
  `web_auth_refresh_invalid`/`web_auth_session_revoked`). app: `JwtTokenService` (jjwt), `WebSessionConfig`,
  `WebRefreshTokenPurge` (`@Scheduled`), `PersistenceConfig`-Bean. Config: `mcplatform.webauth.{jwt.secret,
  access-ttl-minutes:15, refresh-ttl-days:30, cors.allowed-origin, refresh-cookie.*}`.

### protocol (`plugin-protocol.webauth`, JDK-only → Maven Local)
- DTOs `LoginRequest`, `TokenPairResponse` (**ohne** refreshToken-Feld — Refresh nur im Cookie); `WebAuthEndpoints`
  um `LOGIN`/`REFRESH`/`LOGOUT` erweitert. **Kein** Codec/Channel, **`PlatformProtocol.create()` unangetastet**
  (verifiziert), POM weiterhin ohne `<dependencies>`. `publishToMavenLocal` ausgeführt.

### Tests grün
- Domain `RefreshTokenTest`. Application `WebSessionServiceTest` (Login-Erfolg/uniformer Fehler ×3,
  Refresh-Rotation/Invalid/Replay, Logout idempotent, Fakes + fixer Clock). jOOQ/Testcontainers
  `JooqRefreshTokenRepositoryTest` (store/rotate/replay-kill/expired/logout/purge + Audit-Asserts),
  `JooqPlayerRepositoryTest` (findUuidByName), `JooqWebAccountRepositoryTest` (find + **D4** Reset killt Sessions).
  `JwtTokenServiceTest` (Roundtrip/Expiry/Tamper/short-secret). Protocol `WebAuthEndpointsTest`. E2E
  `WebLoginSliceTest` (Login→geschützter `/api/web/**` 200/401/401, Refresh→Rotation, Replay→Kette tot,
  Logout, uniformer 401, CSRF 403, JSON-Contract ohne refreshToken, SC-007). `@JsonTest` DTO-Contract.
  **Alle bestehenden Slices unverändert grün** (Security-Chain permittiert Alt-Endpoints).

### Bewusste Grenzen / verschoben
- **Kein Brute-Force-Schutz** in diesem Slice (FR-021, dokumentierte Lücke — später/Reverse-Proxy). Keine
  Access-Token-Sofort-Revocation/Blacklist (kurze TTL = Fenster), kein „alle Geräte abmelden", kein RS256,
  kein OAuth/E-Mail, kein Plugin-Anteil. SC-006 (Rechte-Änderung ohne Re-Login) hier nur **negativ** belegt
  (JWT ohne Authority-Claims); volle Prüfung im Web-Feature-Slice (6). Onboarding-Hinweis „kein Account →
  /web link" bewusst NICHT über die Login-Antwort (D3).
- **Keine generische Klasse geändert**; einziger Eingriff in Bestand: additive D4-Zeile in
  `JooqWebAccountRepository.resetPassword`. Neue Deps modul-konform: jjwt nur in `app`, spring-security nur in
  `api-rest`.

---

## Rank-Management-Backend — siebtes Feature (Stand: 2026-06-25)

**Backend-seitig komplett gebaut, `./gradlew build` grün** (Branch `005-rank-management-api`). Die vom
künftigen Webinterface genutzte, **JWT-abgesicherte Schreib-/Lesefläche** `/api/web/permission/**` fürs
Rollen-/Permission-/Grant-Management. **Überwiegend Reuse:** Der gesamte Schreibkern (Domäne,
`PermissionAdminService`, `RoleRepository`/`PlayerGrantRepository`, Audit der Grants, Live-Push-Pfad)
stammt aus dem vierten Feature (002) und wird wiederverwendet — dieser Slice steckt eine token-getriebene,
autorisierte Eingangsfläche davor und ergänzt das Rollen-Audit.

### Was steht
- **api-rest `WebPermissionController`** (`/api/web/permission/**`, getrennt vom internen
  `/api/permission/**`): liegt hinter dem 004-JWT-Filter (Wildcard `/api/web/**` → 401 ohne Token, **vor**
  jeder Rechteprüfung). Akteur-UUID kommt aus `@AuthenticationPrincipal PlayerId` — **nie aus dem Body**.
  Endpunkte: Rollen-CRUD + Liste/Detail, Rollen-Permission add/remove/list, Grant Rolle/Permission +
  Revoke, `effective`. Schreiben gatet der wiederverwendete `PermissionAdminService` granular
  (`permission.role.create|edit|delete`, `permission.grant.role|permission`); Lesen gatet der Controller
  gegen `permission.read`. **Kein** `rank.*`-Vokabular, **kein** Gate-Seed (ADMIN hat `*`). **Kein**
  Spring-Security-Rollenmodell — Autorisierung ausschließlich über `PermissionResolver` (§12).
- **plugin-protocol `permission/web/`** (additiv, JDK-only): `RoleWriteRequest`,
  `RolePermissionWriteRequest`, `GrantRoleWriteRequest`, `GrantPermissionWriteRequest`,
  `RevokePermissionWriteRequest` — alle **ohne `actor`-Feld** (issued_by strukturell unfälschbar,
  FR-002/FR-020). Response-DTOs (`RoleResponse`/`PlayerPermissionsResponse`) wiederverwendet. **Keine**
  `EndpointDescriptor` (Consumer ist das TS-Frontend, kein Java-Client). `PlatformProtocol.create()`
  **unangetastet** — der Permission-Pub/Sub-Pfad existierte bereits.
- **Rollen-Audit (FR-025a):** neuer append-only Strang `role_audit` (Flyway **V13** — V11/V12 belegt) +
  `RoleAuditPort` + `JooqRoleAuditRepository`, analog `config_audit`/`grant_audit`, **kein** FK auf `role`.
  Eingehängt in `PermissionAdminService` (createRole/updateRole/deleteRole/add/removeRolePermission) →
  beide Eingangspfade (intern + web) auditieren atomar. Grant-Audit bleibt der bestehende `grant_audit`.
- **Permission-Syntax-Validierung (FR-014):** kleiner framework-freier `PermissionString`-Validator in
  core-domain (blank/Whitespace/Negation/leere Segmente → `RoleValidationException` → 422), aufgerufen in
  `addRolePermission` + `grantPermission`. Schließt eine im 002-Bestand fehlende Prüfung.
- **Live-Push:** unverändert **player-scoped** (`PermissionChangedEvent` je betroffenem aktivem Träger),
  best-effort nach Commit über `mc:permission:changed`. Bei Rollen-Permission-Änderung ein
  `ROLE_CONFIG_CHANGED` je Halter; bei Grant/Revoke `GRANT_ADDED`/`GRANT_REVOKED`.
- **Identitäts-Endpoint `GET /api/web/me`** (kleiner Zusatz, streng genommen außerhalb der 005-Spec, auf
  Nutzerwunsch mitgebaut): liefert dem Frontend die backend-autoritative Identität **und die eigenen
  effektiven Rechte** des eingeloggten Users — `MeResponse{ uuid, name, permissions[] }` aus dem
  Token-Principal + `player.name` (Cache) + `PermissionQueryService.effectiveFor(caller)`. Kein
  Permission-Gate (jeder authentifizierte User sieht **seine eigene** Identität/Rechte). Die `permissions`
  dienen **nur** dem clientseitigen UX-Gating (Schreib-Buttons ein/ausblenden) und können Wildcards
  (`*`, `feature.*`) enthalten → der Client wendet dieselbe Match-Regel an; die echte Prüfung bleibt
  backend-autoritativ (403, §12). Dafür `PlayerRepository.findNameByUuid` ergänzt (additiver Port-Reader,
  Präzedenz `findUuidByName` aus 004) + `WebMeController` + `MeResponse` (protocol). Zusätzlich liefert
  `/me` den **primären Rang** `primaryRole{ name, displayName, color, weight }` — der höchstpriorisierte
  aktive Rang via wiederverwendetem `RankDisplay.choose` (teamRank→weight→id; Default-Rolle als Fallback),
  also derselbe Rang, dessen Farbe/Prefix auch im Spiel gilt. Dafür `PermissionQueryService.primaryRoleOf`
  (additiv) + `PrimaryRole` (protocol).
- **DEFAULT-Rolle ist reiner System-Fallback (Guard ergänzt):** Das Permission-Modell behandelt DEFAULT
  bereits als **impliziten Fallback** — `EffectivePermissions`/`JooqPermissionResolver` ziehen die
  Default-Permissions nur, wenn der Spieler **keine** aktive Rolle hält (`NOT EXISTS active_roles`).
  Daraus folgt ohne Materialisierung: neue Rolle → Default greift nicht mehr; letzte Rolle weg/abgelaufen
  → Default greift automatisch wieder. Ergänzt wurde nur der fehlende Guard: `PermissionAdminService`
  blockt jetzt **Grant und Revoke der Default-Rolle** (→ 409 `default_role_protected`); DEFAULT kann also
  nur noch der automatische Fallback sein, nie manuell vergeben/entzogen werden. (Bestehender Schutz:
  Default nicht löschbar/deaktivierbar.) **Daten-Bereinigung V14** (`V14__remove_default_role_grants.sql`):
  löscht etwaige Alt-`player_role_grant`-Zeilen auf die Default-Rolle, die angelegt wurden, bevor der Guard
  existierte (sonst zeigte ein Spieler DEFAULT neben einer echten Rolle). Idempotent.
  **Anzeige-Synthese (Web):** Hat ein Spieler **keine** aktive Rolle, fügt der Web-Pfad in
  `effective`/Grant-/Revoke-Antworten einen **synthetischen** DEFAULT-Eintrag in die `roles`-Liste
  (label = Default-Rollenname, `issuedBy=null`, keine DB-Zeile), damit das UI immer den aktuellen Rang
  zeigt statt einer leeren Liste. Nach Entzug der letzten Rolle erscheint so sofort wieder DEFAULT.
- **Aussteller-Name in der Grant-Ansicht:** `ActiveGrant` trägt jetzt zusätzlich `issuedByName` (neben der
  `issuedBy`-UUID). Der Web-Pfad (`/api/web/permission/.../effective` etc.) löst die Aussteller-UUIDs
  **gebündelt** (eine Query, kein N+1) über `PlayerRepository.findNamesByUuids` zu Namen auf; der interne
  Plugin-Pfad lässt `issuedByName` weiterhin `null` (additiv, kein Contract-Bruch). System-Actor/Spieler
  ohne `player`-Zeile → `null` (Frontend kann „System"/UUID als Fallback zeigen).
- **Spieler-Suche `GET /api/web/players/search?name=&limit=`** (kleiner Zusatz, auf Nutzerwunsch): für das
  Web-Management, um beim Granten einen Spieler per Name zu finden. **Bewusst unter `/api/web/**`** (JWT-
  gegatet, `permission.read`), NICHT auf dem permitAll-`/api/players/**`-Pfad — sonst wäre die Namens-Suche
  unauthentifiziert (Enumeration). Case-insensitiver Prefix über `idx_player_name_lower` (LIKE-Wildcards im
  Input werden literal escaped), server-seitig limitiert (default 20, max 50), Antwort `PlayerSummary[]`
  `{ uuid, name }`. Dafür `PlayerRepository.searchByNamePrefix` (additiv) + `WebPlayerController` +
  `PlayerSummary` (protocol).

### Verhalten / bewusste Grenzen
- **Rolle-löschen-mit-Mitgliedern → Kaskade** (Q2): REVOKE + Audit + Live-Push je Halter, dann Löschung —
  entspricht dem bereits gebauten Verhalten. Default-Rolle vor Löschung/Deaktivierung geschützt (409).
- **Reine Anzeige-Edits einer Rolle** (Name/Farbe/Prefix/…) lösen **keinen** Live-Push aus (F1, kosmetisch).
- **Grant an nie-gejointe UUID** erlaubt (Grant-Tabellen ohne FK auf `player`).
- Fehlercodes über die bestehenden Handler: 401/403/404 (`role_not_found`)/409
  (`default_role_protected`/`role_name_conflict`)/422 (`permission_invalid`)/400.
- **Verschoben:** Audit-/History-**Lesen** (eigener Slice), Letzter-Admin-Schutz (Recovery via Seed/DB),
  Absicherung des alten `/api/permission/**`-Pfads (vertraut weiter dem Body-`actor` — interner Pfad),
  Frontend (Next.js).

### Muster-Leck-Ledger (bewusst, begründet)
- **Einziger Eingriff in Bestand:** Audit-Hooks im feature-eigenen `PermissionAdminService` (+ Konstruktor
  um `RoleAuditPort`). Das ist **keine** generische Klasse → kein Leck. `PlatformProtocol.create()`,
  `SecurityConfig`, `PermissionResolver`-Port, die bestehenden Repositories und alle generischen Bausteine
  bleiben unverändert. Neuer Domain-Validator `PermissionString` ist additiv.

### Tests grün
- Domain: `PermissionStringTest`. Application: `PermissionAdminServiceTest` erweitert (role_audit je
  Operation, Actor korrekt; Default-Schutz/Kaskade unverändert). infra-persistence:
  `JooqRoleAuditRepositoryTest` (Testcontainers). app-E2E: `WebPermissionVerticalSliceTest` (Testcontainers
  Postgres+Redis, echte Security-Chain mit gemintetem JWT) — 401/403/404/409/422, Kaskade, `role_audit`/
  `grant_audit`, issued_by-aus-Token, Grant an nie-gejointe UUID, `ROLE_CONFIG_CHANGED`-Pub/Sub. Contract:
  `WebPermissionDtoJsonTest` (`@JsonTest`, kein `actor`-Feld). Bestehende Suiten (Economy/Punishments/
  Reports/002-Permission/004-WebAuth) unverändert grün (SC-007). `./gradlew build` grün;
  `:plugin-protocol:publishToMavenLocal` grün, POM weiterhin ohne `<dependencies>`.

## Rollen-Vererbung — achtes Feature (state-stored, Stand: 2026-06-25)

Greenfield-Erweiterung des Permission-Systems (kein Altplugin-Import; das Alt-System nutzte LuckPerms).
Eine Rolle erbt die **Permissions** einer Liste anderer Rollen (Many-to-Many, explizit gesetzt,
transitiv, reine Union ohne Gewichtung/Negation). Spec/Plan/Tasks: `specs/006-role-inheritance/`.
Branch `006-role-inheritance`.

### Was steht
- **Domäne (`core-domain/permission`, framework-frei):** neue reine Klasse `RoleHierarchy` —
  `reachable` (transitive Hülle mit Visited-Set), `wouldCreateCycle` (Schreib-Vorabcheck),
  `resolveWithProvenance` (Herkunft je Permission, FR-022a). `EffectivePermissions`/`PermissionMatcher`
  **unverändert** (Regression-Anker).
- **Persistenz (jOOQ, Flyway V15):** neue Kantentabelle `role_inheritance` (`role_id` FK CASCADE,
  `inherited_role_id` FK **RESTRICT** = DB-Netz für FR-015, PK auf dem Paar, `CHECK` gegen Selbstkante,
  Index für Reverse-Closure). `JooqRoleInheritanceRepository` (add idempotent/remove/directParents/
  directChildren/dependents als rekursive Reverse-CTE).
- **Resolver-Kern (bewusster Eingriff, kein Leck):** `JooqPermissionResolver`-SQL um eine
  `WITH RECURSIVE reachable_roles`-CTE erweitert (Basis = aktive Direkt-Rollen, Rekursion über
  `role_inheritance`, `UNION`-Dedup). Bei leerem Graph **bit-identisch** zu vorher (Regression bewiesen).
  Default-Zweig bleibt auf die **Basismenge** gegated (`NOT EXISTS active_roles`) → Default fließt in eine
  echte Rolle nur über explizite Vererbung (FR-011/CL-1); `active` der Eltern wird nicht vererbt (FR-016).
- **Application:** `PermissionAdminService` um `addInheritance`/`removeInheritance` erweitert
  (Gate `permission.role.edit.inherit`, Default-als-child → 409, Zyklus-Vorabcheck → 409, Audit
  `ROLE_INHERITANCE_ADD/REMOVE`); `deleteRole` lehnt geerbte Rolle ab (409, FR-015); `publishToHolders`
  → `publishToRoleAndDependents` (Live-Push an die **transitive Reverse-Closure** — wirkt auch auf
  role-permission-Edits, FR-020a). `PermissionQueryService` löst effektive Sichten transitiv auf und
  liefert Provenienz (`sources`); `EffectivePermissions` bleibt einzige Quelle der flachen Menge.
- **REST (`api-rest`):** `/api/web/permission/roles/{id}/inheritance` (GET Liste/POST add/DELETE remove),
  actor aus dem JWT; neue 409-Mappings (`role_inheritance_cycle`, `role_inherited`).
- **protocol (`plugin-protocol`, additiv, JDK-only):** `InheritanceWriteRequest`,
  `EffectivePermissionEntry`, `RoleResponse.inheritedRoleIds`, `PlayerPermissionsResponse.sources`,
  drei `PermissionEndpoints`-Deskriptoren. Pub/Sub: bestehender `ROLE_CONFIG_CHANGED`-Pfad —
  **kein** neuer Channel/Event.

### Verhalten / bewusste Grenzen
- **Default-Konsistenz-Falle (CL-1):** Eine echte Rolle ohne Default in der Vererbungsliste lässt ihre
  Träger weniger haben als ein Default-Spieler — bewusst keine Backend-Hilfe/Warnung (FR-012).
- **Default-Rolle ist Blatt (CL-3):** kann nicht erben; andere dürfen von Default erben.
- **Diamant (CL-2/FR-022a):** Herkunft je Permission = vollständige Quell-Rollenmenge + `own`-Flag.
- **Verschoben:** Frontend-Vererbungs-Editor (pausierter Rank-UI-Slice); harter Max-Depth-Guard
  (Terminierung ist durch Visited-Set/`UNION` garantiert).

### Muster-Leck-Ledger (bewusst, begründet)
- **Zwei Eingriffe in Feature-Kernlogik, kein generischer Baustein:** (1) `JooqPermissionResolver`-SQL
  (rekursive CTE) — die korrekte Heimat der Auflösungsregel; (2) `PermissionAdminService`-Fan-out auf die
  Reverse-Closure. Beide regressionsgesichert (leerer Graph = bit-identisch). `EffectivePermissions`,
  `PermissionResolver`-Port, `PlatformProtocol.create()`, alle generischen Bausteine unverändert. Neuer
  `RoleInheritanceRepository`-Port statt `RoleRepository` aufzubohren (Slice self-contained).

### Tests grün
- Domain: `RoleHierarchyTest` (Kette/Mehrfach/Diamant/Provenienz/Zyklus/Visited-Set-Terminierung).
  Application: `PermissionAdminServiceTest` erweitert (Gate, Zyklus-409, Default-als-child-409, idempotent,
  Audit, delete-while-inherited-409, Fan-out an transitive Dependents + Negativ-Fall) +
  `PermissionQueryServiceInheritanceTest` (Default-Interplay, transitive Union, Provenienz). infra
  (Testcontainers): `JooqRoleInheritanceRepositoryTest` (CRUD/idempotent/Reverse-Closure/RESTRICT/Cascade)
  + `JooqPermissionResolverTest` erweitert (transitiv, geerbte Wildcard, deaktivierter Parent, Default via
  Inheritance, Restzyklus terminiert, **Leer-Graph-Regression**). Contract: `PermissionEndpointsInheritanceTest`.
  app-E2E: `WebPermissionVerticalSliceTest` erweitert (add/list/remove + `sources`, 403 ohne Gate, 409
  Zyklus/Default/Delete, Live-`ROLE_CONFIG_CHANGED` an betroffene Halter). Bestehende 002/005-Suiten
  unverändert grün (Regression). `./gradlew build` grün (365 Tests, 0 Fehler);
  `:plugin-protocol:publishToMavenLocal` grün, POM weiterhin ohne `<dependencies>`.

### Web-Economy Read-Backend + SSE (Slice 1) erledigt (read-only, spec 007)
Daten-Grundlage für den Economy-Teil des Webinterfaces: reiner Lese-/Projektions-Pfad auf den
bestehenden Tabellen (`player_balance`, `economy_event`, `currency`, `player`) plus eine SSE-Bridge
auf dem **bestehenden** `mc:economy:balance`-Channel. Keine neuen Events, Writes, Locking, Idempotenz,
**kein neuer Codec**, `PlatformProtocol.create()` unverändert. Alle vier Endpunkte sind
**web-interface-only** und liegen daher unter `/api/web/economy/**` (hinter der `/api/web/**`-JWT-Chain:
401 ohne Token) und sind backend-autoritativ über `permission.economy.read` gegated (403).

**Was steht**
- **Bewusster Struktur-Eingriff (Muster-Leck behoben, dokumentationspflichtig):** Neuer Outbound-Port
  `EconomyReadStore`; die reinen Projektions-Reads `findHistory()` + `circulation()` sind **1:1**
  (byte-gleich) aus `EconomyEventStore` → `JooqEconomyReadStore` umgezogen. `EconomyEventStore` trägt
  nur noch Event-/Versions-/Idempotenz-Semantik (`currentBalance`, `ensureZeroBalance`, `append`,
  `transfer`, `findByTransactionId`, `findTransfer`). `EconomyHistoryService` **und** `EconomyStatsService`
  hängen jetzt am neuen Port (Composition Root umverdrahtet). Bestehende History/Circulation-Tests
  unverändert grün (Move-Regression).
- **A) Sammel-Balances** `GET /api/web/economy/players/{uuid}/balances` → `PlayerBalancesResponse`
  (alle Währungen + Display-Daten in einem Call; unbekannter Spieler → leere Liste, kein 404).
  `EconomyReadStore.playerBalances` (Join `player_balance × currency`), `PlayerBalancesQuery`,
  `PlayerBalancesController`.
- **C) Serverweite History** `GET /api/web/economy/history` → `EconomyHistoryResponse` (serverweit,
  `player=null`), Filter `currency`/`type`/`source`, Keyset-Pagination (`before`/`nextCursor`),
  Limit-Clamp 50/200 (aus `EconomyHistoryService` geteilt). **Geteilte private Keyset-Helper**
  `queryHistory(...)`: player-gefilterte und serverweite History delegieren an EINE Methode (optionales
  player-Predicate + optionaler `source`-Filter, `player`-Join für `playerName`) — keine duplizierte
  Pagination. `EconomyEventEntry`/`EconomyHistoryEntry` **additiv** um `playerUuid`/`playerName`
  erweitert (Player-History verhaltensneutral, Top-Level `player` bleibt).
- **D) Transaktions-Detail** `GET /api/web/economy/transactions/{transactionId}` →
  `TransactionDetailResponse`. Einzel-Event → ein Leg (`kind=SINGLE`); Transfer → zwei Legs (beide
  Namen, `kind=TRANSFER`, `correlationId` aus `metadata->>'correlation_id'`). Fehlendes Gegen-Leg →
  ein Leg, kein Fehler (read-only rät nicht). Unbekannte ID → 404 `economy_transaction_not_found`.
- **SSE) Live-Push** `GET /api/web/economy/stream[?player=]`. `EconomyStreamRegistry` (api-realtime,
  implementiert den Application-Inbound-Port `BalanceStreamBroadcaster`) hält die `SseEmitter` und
  macht Fan-out; `RedisEconomyStreamBridge` (`app`, spiegelt `RedisBalanceEventPublisher`) subscribt
  **einmal** `mc:economy:balance`, decodiert via `PlatformProtocol.create()` und reicht eine neutrale
  `BalanceStreamView` durch. `?player=` filtert serverseitig. Namen löst das Web client-seitig auf
  (`BalanceStreamView` trägt keinen Namen). Redis-Ausfall: App startet trotzdem (Subscribe-Fehler
  geloggt, kein Auto-Reconnect in diesem Single-Server-Slice).
- **Neuer Index (Flyway `V16`):** `idx_event_seq_desc ON economy_event (sequence_no DESC)` für die
  serverweite Sortierung (`idx_event_player_currency` greift ohne player-Präfix nicht). Kein
  `source`-Index (nicht spekulativ).
- **Gate `permission.economy.read`** (`EconomyPermissions.READ`) über den `PermissionResolver`-Port,
  Muster 1:1 von `WebPermissionController.requireRead`.

**Bewusste Grenzen (verschoben):** Admin-Writes übers Web (Slice 2), Top-Holder-Leaderboard,
Zeitreihe „Umlauf über Zeit", CSV-Export, `playerName` im Wire-`BalanceChangedEvent`, Lookup über
`event_id`/`sequenceNo`.

**Architektur-Notizen / bewusste Eingriffe (kein verstecktes Muster-Leck):**
- Der `EconomyReadStore`-Port-Move ist der EINZIGE Eingriff in bestehende generische/innere Logik —
  begründet als Muster-Leck-Behebung (Projektions-Reads gehörten nie in den security-kritischen
  Append-/Transfer-Port). Die `EconomyEventEntry`-Erweiterung ist rein additiv (wire-rückwärtskompatibel).
- **`api-realtime` zieht jetzt zusätzlich `spring-boot-starter-security`** — damit der web-only
  SSE-Endpunkt unter `/api/web/**` die JWT-Identität (`@AuthenticationPrincipal`) lesen und über den
  `PermissionResolver` gaten kann (Constitution §5: SSE bleibt in api-realtime; §12: Gate
  backend-autoritativ). Der `SecurityFilterChain` selbst bleibt in `api-rest`.
- **Web-Endpunkt-Konvention:** web-interface-only Endpunkte liegen unter `/api/web/**`; plugin-facing
  Endpunkte (`/api/economy/**`, `/api/players/**`) bleiben unverändert und werden NICHT dorthin verschoben.

### Tests grün
- Application (Fakes): `PlayerBalancesQueryTest`, `ServerHistoryQueryTest` (Clamp/Filter inkl. source),
  `TransactionDetailQueryTest` (Single/Transfer/not-found); `EconomyHistoryServiceTest`/
  `EconomyAlertMonitorTest` auf den neuen Port umgestellt (Move-Regression).
- infra (Testcontainers, `JooqEconomyRepositoryTest` erweitert): `playerBalances` (Multi-Währung +
  Display, leerer Spieler), serverweite History (newest-first + Namen + `source`-Filter), Player-History
  trägt jetzt `playerUuid`/`playerName`, Transaktions-Detail (SINGLE/TRANSFER/fehlendes-Gegen-Leg/
  unbekannt), bestehende History/Circulation/Transfer-Suiten unverändert grün.
- Contract (`@JsonTest`, `RestDtoJsonContractTest`): `PlayerBalancesResponse`, erweiterte
  `EconomyEventEntry`, `TransactionDetailResponse`, SSE-Frame `BalanceStreamView` (Feldnamen gepinnt,
  kein `playerName`).
- app-E2E (`WebEconomyReadSliceTest`, Testcontainers Postgres+Redis): Balances/History/Detail inkl.
  leerer Liste, 400 (ungültiger type/limit), 404, sowie **403 ohne** `permission.economy.read` und
  **401 ohne** JWT je Endpunkt; **SSE live** (echter Balance-Change über REST → SSE-Client empfängt
  Frame; `?player=`-Filter liefert nur den passenden Spieler).
- `./gradlew build` grün (Gesamt); `:plugin-protocol:publishToMavenLocal` grün, POM weiterhin **ohne**
  `<dependencies>`, `PlatformProtocol.create()` unverändert.

### Web-Permission-Katalog erledigt (read-only Übersicht für den Rollen-Editor)
Kleiner read-only Endpunkt, der alle **im Webinterface durchgesetzten** Permissions gruppiert nach
Thema (Economy, Rollen & Permissions …) mit deutscher Beschreibung pro Gruppe und Permission liefert
— damit man beim Rollen-Bearbeiten sieht, welche Web-Permissions existieren und was sie tun.
- **Endpoint:** `GET /api/web/permission/catalog` → `PermissionCatalogResponse { groups: [ { key,
  displayName, description, permissions: [ { key, description } ] } ] }`. Gegated über das bestehende
  `permission.read` (gleiches Read-Gate wie die übrigen `/api/web/permission/**`-Reads); 403/401 analog.
- **Code-definiert, pro-Feature beigesteuert (kein Drift, „ein Feature = ein Anstecken"):** Interface
  `PermissionCatalogContributor`; jedes Feature liefert seine Gruppe und referenziert dabei **seine
  eigenen** Permission-Konstanten (`PermissionAdminCatalog` → `PermissionAdminService.*`,
  `EconomyPermissionCatalog` → `EconomyPermissions.READ`). `WebPermissionCatalogQuery` aggregiert alle
  Contributor (per Bean-Liste in `PermissionConfig` verdrahtet) und sortiert stabil nach Anzeigename.
  Ein neues Feature mit Web-Permissions = ein neuer Contributor-Bean in der Composition Root.
- **Bewusst:** nur Web-Permissions (keine Plugin-Permissions), Deutsch, **kein** `*`-Eintrag. DTOs
  JDK-only in `protocol.permission` (`PermissionCatalogResponse`/`PermissionGroupResponse`/
  `PermissionInfoResponse`).
- **Drift-Schutz (Test):** `WebPermissionCatalogQueryTest` prüft, dass die Katalog-Keys **exakt** der
  Menge der gegateten Web-Permission-Konstanten entsprechen (kein verwaister/fehlender Eintrag), keine
  Duplikate, kein `*`, Gruppen alphabetisch sortiert + alle beschrieben. E2E in
  `WebPermissionVerticalSliceTest` (Admin sieht Gruppen/Keys/Beschreibungen, 403 ohne `permission.read`,
  401 ohne JWT). `./gradlew build` grün; Publish grün, POM weiterhin ohne `<dependencies>`.

### Autoritäts-Grenzen für die Rollen-/Permission-Verwaltung erledigt (Privilege-Escalation-Schutz, spec 008)
Zusätzliche, backend-autoritative Autoritäts-Schicht über der bestehenden Rollen-/Permission-Verwaltung.
Achse ist das Rollen-`weight`: `authorityWeight(actor)` = höchstes Gewicht der **reachable** Rollen
(aktive Grants + transitive Vererbung; bewusst NICHT `primaryRoleOf`, das `teamRank` vor `weight`
priorisiert). „Top-Tier" = höchstes `weight` im System. **Keine Schema-/`plugin-protocol`-Änderung** —
reines Verhalten (403/409 + gefilterte Reads).

**Was steht**
- **core-domain:** pure `RoleAuthority` (framework-frei, unit-testbar): `canManageWeight` (non-top
  strikt `<`, Top-Tier `≤`), `canManageTarget`, `isWildcard`.
- **application:** `PermissionAuthorityService` — `authorityWeight`/`topWeight`/`isTopTier` + Guards
  (`requireCanManageRole/Weight/Target`, `requireCanDelegate` mit Wildcard→`*`) + ergebnisbasierter
  Lockout (`requireNotLastTopTierOn{Revoke,Delete,WeightChange}`; no-op wenn kein Rang über der
  Default-Stufe existiert) + Read-Helper (`visibleRoles`, `canViewRole`, `canViewTarget`). Neue
  Exceptions `InsufficientAuthorityException` (403 `authority_ceiling`) / `LastTopTierException`
  (409 `last_top_tier`).
- **Regeln durchgesetzt:** (1) Rollen-Management/-Grant nur unterhalb der eigenen Stufe (Top-Tier `≤`,
  nie über Max); (2) Permission-Delegation nur was man selbst hält, **jede Wildcard/`.*` nur mit `*`**;
  (3) Ziel-Autoritäts-Ceiling (kein Umranken gleich-/höherrangiger Spieler); (4) Reads begrenzt
  (Rollen-Liste weight-gefiltert, Einzel-Rollen-Read + Permissions-Tab höher-autorisierter Spieler →
  403; Suche/Stammdaten/`/me` ungefiltert); (5) Lockout-Schutz des letzten Top-Tier → 409.
  `*` verändert die Rang-Autorität NICHT (Weight-only, FR-002a).

**Bewusste Eingriffe (dokumentationspflichtig, kein Muster-Leck):** Guards in `PermissionAdminService`
(je nach `requirePermission` — die Engine wird intern aus den vorhandenen Deps gebaut, kein Konstruktor-/
Wiring-Bruch; separater `PermissionAuthorityService`-Bean für den Read-Gate im `WebPermissionController`)
und Read-Filter/Gate im `WebPermissionController` (+ 2 Handler-Mappings). `PermissionQueryService`/
`PermissionResolver`/`RoleHierarchy` **unverändert** (nur konsumiert). Kein generischer Baustein geändert.

**Tests grün:** Domain `RoleAuthorityTest`; Application `PermissionAuthorityServiceTest` (authorityWeight
inkl. Vererbung/Fallback, top-tier, Lockout-Count, Read-Helper, Wildcard-Delegation) + erweiterte
`PermissionAdminServiceTest` (Ceiling/Delegation/Ziel/Lockout je Methode) mit angepasstem Fake-Setup
(top-tier-Actor); E2E `WebPermissionVerticalSliceTest` erweitert (Delegation von `*`/unheld → 403;
höhere Rolle/Ziel verwalten/vergeben → 403; Rollen-Liste + Einzel-Rollen-Read + fremder Permissions-Tab
gefiltert/403, Suche/`/me` ungefiltert; letzter Top-Tier self-demote → 409). Bestehende 002/005/006-
Suiten unverändert grün (Regression). `./gradlew build` grün; **keine** `plugin-protocol`-/Schema-
Änderung (kein Publish nötig), POM weiterhin ohne `<dependencies>`.