# Phase 1 — Data Model: Web-Economy Read-Backend + SSE

Alle Typen hier sind **Read-Projektionen** (Application-Ebene) bzw. **Wire-DTOs** (`plugin-protocol`,
JDK-only Records). **Keine `core-domain`-Typen, keine Invarianten, keine Mutation.** Quelle sind die
bestehenden Tabellen `economy_event`, `player_balance`, `currency`, `player` (read-only).

## 1. Application-Read-Modelle (`application/.../economy[/port]`)

### ProjectedBalance *(neu)* — Use-Case A
| Feld | Typ | Quelle |
|------|-----|--------|
| `currency` | `CurrencyCode` | `player_balance.currency_code` |
| `displayName` | `String` | `currency.display_name` |
| `symbol` | `String` (nullable) | `currency.symbol` |
| `decimalPlaces` | `int` | `currency.decimal_places` |
| `balance` | `Money` | `player_balance.balance` |

> Join `player_balance × currency` auf `currency_code`. Pro Spieler 0..n Zeilen.

### EconomyHistoryEntry *(geändert)* — Use-Case C (und bestehende Player-History)
| Feld | Typ | Status |
|------|-----|--------|
| `sequenceNo` | `long` | bestehend |
| `currency` | `CurrencyCode` | bestehend |
| `type` | `EconomyEventType` | bestehend |
| `amount` | `Money` | bestehend |
| `balanceAfter` | `Money` | bestehend |
| `transactionId` | `TransactionId` | bestehend |
| `source` | `String` | bestehend |
| `correlationId` | `UUID` (nullable) | bestehend |
| `counterpartyUuid` | `UUID` (nullable) | bestehend |
| `occurredAt` | `Instant` | bestehend |
| **`playerUuid`** | **`UUID`** | **NEU** — `economy_event.player_uuid` |
| **`playerName`** | **`String`** | **NEU** — `player.name` (Join) |

### EconomyHistoryPage *(unverändert)*
`record EconomyHistoryPage(List<EconomyHistoryEntry> entries, Long nextCursor)`

### CirculationStats *(bestehend, zieht in den Read-Port um)*
`record CirculationStats(CurrencyCode currency, long total, int accounts)` — keine Feldänderung.

### TransactionDetail *(neu)* — Use-Case D
| Feld | Typ | Quelle |
|------|-----|--------|
| `transactionId` | `TransactionId` | `economy_event.transaction_id` |
| `correlationId` | `UUID` (nullable) | `metadata->>'correlation_id'` |
| `kind` | `TransactionKind` (`SINGLE`/`TRANSFER`) | abgeleitet aus `event_type` |
| `currency` | `CurrencyCode` | `economy_event.currency_code` |
| `displayName` | `String` | `currency.display_name` |
| `symbol` | `String` (nullable) | `currency.symbol` |
| `decimalPlaces` | `int` | `currency.decimal_places` |
| `amount` | `Money` | `economy_event.amount` |
| `source` | `String` | `economy_event.source` |
| `metadata` | `String` (JSONB roh, unparsed) | `economy_event.metadata` |
| `occurredAt` | `Instant` | `economy_event.created_at` |
| `legs` | `List<TransactionLeg>` | 1 (SINGLE) oder 2 (TRANSFER) |

### TransactionLeg *(neu)*
| Feld | Typ | Quelle |
|------|-----|--------|
| `playerUuid` | `UUID` | `economy_event.player_uuid` |
| `playerName` | `String` | `player.name` (Join) |
| `eventType` | `EconomyEventType` | `economy_event.event_type` |
| `balanceAfter` | `Money` | `economy_event.balance_after` |

## 2. Outbound-Port `EconomyReadStore` *(neu)*

```java
public interface EconomyReadStore {
  // 1:1 aus EconomyEventStore umgezogen (Impl byte-gleich):
  EconomyHistoryPage findHistory(PlayerId player, Optional<CurrencyCode> currency,
          Optional<EconomyEventType> eventType, Long cursorBeforeSeqNo, int limit);
  List<CirculationStats> circulation();

  // neu in diesem Slice:
  List<ProjectedBalance> playerBalances(PlayerId player);                                   // A
  EconomyHistoryPage findServerHistory(Optional<CurrencyCode> currency,
          Optional<EconomyEventType> eventType, Optional<String> source,
          Long cursorBeforeSeqNo, int limit);                                               // C
  Optional<TransactionDetail> findTransaction(TransactionId transactionId);                 // D
}
```

**`EconomyEventStore` behält danach nur:** `currentBalance`, `ensureZeroBalance`, `append`,
`transfer`, `findByTransactionId`, `findTransfer`.

## 3. `plugin-protocol` Wire-DTOs (JDK-only Records)

### PlayerBalancesResponse *(neu)* — A
`record PlayerBalancesResponse(UUID player, List<PlayerBalanceEntry> balances)`

### PlayerBalanceEntry *(neu)* — A
`record PlayerBalanceEntry(String currencyCode, String displayName, String symbol, int decimalPlaces, long balance)`

### EconomyEventEntry *(geändert)* — C
Bestehende 10 Felder **plus** `UUID playerUuid`, `String playerName`. Reihenfolge: neue Felder ans
Ende (Jackson nutzt Record-Komponenten namentlich, Reihenfolge nur für direkten Konstruktor relevant).

### EconomyHistoryResponse *(wiederverwendet)* — C
`record EconomyHistoryResponse(UUID player, List<EconomyEventEntry> entries, Long nextCursor)`
— serverweit: `player = null`, „wer" pro Eintrag.

### TransactionDetailResponse *(neu)* — D
`record TransactionDetailResponse(UUID transactionId, UUID correlationId, String kind,
   String currencyCode, String displayName, String symbol, int decimalPlaces,
   long amount, String source, String metadata, long timestampEpochMilli, List<TransactionLegDto> legs)`
— `metadata` ist der **rohe JSONB-Text** (unparsed String), nicht ein typisiertes Objekt (hält das
DTO JDK-only; das Web parst bei Bedarf client-seitig).

### TransactionLegDto *(neu)* — D
`record TransactionLegDto(UUID playerUuid, String playerName, String eventType, long balanceAfter)`

### SSE-Frame *(kein neues protocol-DTO)* — siehe contracts/; serialisiert `BalanceChangedEvent`-Felder.

## 4. Mapping-Kette (DB → Read-Modell → Wire-DTO)

| Endpunkt | DB | Application | protocol-DTO |
|----------|----|-----|------|
| A Balances | `player_balance × currency` | `List<ProjectedBalance>` | `PlayerBalancesResponse` / `PlayerBalanceEntry` |
| C Server-History | `economy_event × player` (+Filter, Keyset) | `EconomyHistoryPage`/`EconomyHistoryEntry` | `EconomyHistoryResponse`/`EconomyEventEntry` |
| D Detail | `economy_event (+Gegen-Leg) × player × currency` | `TransactionDetail`/`TransactionLeg` | `TransactionDetailResponse`/`TransactionLegDto` |
| SSE | `mc:economy:balance` (Pub/Sub) | `BalanceChangedEvent` (decodiert) | JSON-Frame (api-realtime) |

## 5. Schema-Änderung

`infra-persistence/src/main/resources/db/migration/V16__economy_event_seq_index.sql`:
```sql
CREATE INDEX idx_event_seq_desc ON economy_event (sequence_no DESC);
```
Keine Tabellen-/Spalten-Änderung; keine Daten-Migration. Read-only Slice.
