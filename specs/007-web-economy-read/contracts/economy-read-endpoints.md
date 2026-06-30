# Contract — Web-Economy Read Endpoints + SSE

Alle Endpunkte sind **read-only**, **web-interface-only** und liegen daher unter `/api/web/economy/**`
(hinter der bestehenden `/api/web/**`-JWT-Chain — ohne gültiges Token → **401** an der Chain). Auth-
Identität über `@AuthenticationPrincipal PlayerId actor`; zusätzlich backend-autoritativ über
`permission.economy.read` gegated (fehlt sie → `403 {"error":"permission_denied"}`). Die bestehenden
plugin-facing `/api/economy/**`- und `/api/players/**`-Endpunkte bleiben unverändert und werden NICHT
hierher verschoben.

## A) Sammel-Balances eines Spielers
```
GET /api/web/economy/players/{uuid}/balances
```
**EndpointDescriptor:** `EconomyEndpoints.LIST_BALANCES` (GET, Void → `PlayerBalancesResponse`)

**200 Response** `PlayerBalancesResponse`:
```json
{
  "player": "f7c1...uuid",
  "balances": [
    { "currencyCode": "COINS", "displayName": "Coins", "symbol": "$", "decimalPlaces": 0, "balance": 100 }
  ]
}
```
- Unbekannter Spieler / keine Zeilen → `{"player": "<uuid>", "balances": []}` (**kein 404**).

## C) Serverweite Transaktionsliste
```
GET /api/web/economy/history?currency={code}&type={EVENT_TYPE}&source={src}&before={seqNo}&limit={n}
```
**EndpointDescriptor:** `EconomyEndpoints.SERVER_HISTORY` (GET, Void → `EconomyHistoryResponse`)

**Query-Parameter** (alle optional):
| Param | Wirkung | Fehler |
|-------|---------|--------|
| `currency` | Filter auf `currency_code` | — |
| `type` | Filter auf `event_type` (Enum-Name) | ungültig → **400** `bad_request` |
| `source` | Filter auf `source` (z. B. `WEBSHOP`, `SYSTEM:initial`) | — |
| `before` | Keyset-Cursor (`sequence_no <`), exklusiv | — |
| `limit` | Seitengröße, Default 50, Max 200 | `<= 0` → **400** `bad_request` |

**200 Response** `EconomyHistoryResponse` (serverweit → `player: null`):
```json
{
  "player": null,
  "entries": [
    { "sequenceNo": 412, "playerUuid": "...", "playerName": "Steve", "currencyCode": "COINS",
      "eventType": "TRANSFER_OUT", "amount": 50, "balanceAfter": 50, "transactionId": "...",
      "source": "PLUGIN:pay", "correlationId": "...", "counterpartyUuid": "...",
      "timestampEpochMilli": 1750000000000 }
  ],
  "nextCursor": 405
}
```
- Sortierung `sequence_no DESC`. `nextCursor` = `sequenceNo` des letzten Eintrags wenn mehr Treffer,
  sonst `null`. Keyset lücken-/überlappungsfrei.

## D) Transaktion per ID (Detailseite)
```
GET /api/web/economy/transactions/{transactionId}
```
**EndpointDescriptor:** `EconomyEndpoints.GET_TRANSACTION` (GET, Void → `TransactionDetailResponse`)

**200 Response** `TransactionDetailResponse`:
```json
{
  "transactionId": "...", "correlationId": "...", "kind": "TRANSFER",
  "currencyCode": "COINS", "displayName": "Coins", "symbol": "$", "decimalPlaces": 0,
  "amount": 50, "source": "PLUGIN:pay", "metadata": "{\"correlation_id\":\"...\"}",
  "timestampEpochMilli": 1750000000000,
  "legs": [
    { "playerUuid": "...", "playerName": "Steve", "eventType": "TRANSFER_OUT", "balanceAfter": 50 },
    { "playerUuid": "...", "playerName": "Alex",  "eventType": "TRANSFER_IN",  "balanceAfter": 150 }
  ]
}
```
- `metadata` ist der **rohe JSONB-Text** (unparsed String, z. B. `"{\"correlation_id\":\"...\"}"`), kein typisiertes Objekt.
- CREDIT/DEBIT/SET → `kind: "SINGLE"`, genau **ein** Leg.
- Transfer → `kind: "TRANSFER"`, **zwei** Legs (beide Namen), `correlationId` gesetzt.
- Gegen-Leg fehlt (Inkonsistenz) → `kind: "TRANSFER"`, **ein** Leg, `correlationId` gesetzt, kein Fehler.
- Unbekannte `transactionId` → **404** `{"error":"economy_transaction_not_found"}`.

## SSE) Live-Push
```
GET /api/web/economy/stream              (Accept: text/event-stream)
GET /api/web/economy/stream?player={uuid}
```
- Kein `EndpointDescriptor` (Streaming, kein Request/Response-Typ-Paar).
- Jeder Balance-Change auf `mc:economy:balance` wird als SSE-Event geschrieben:
```
data: {"playerUuid":"...","currencyCode":"COINS","eventType":"CREDITED","amount":10,
       "balance":110,"version":413,"transactionId":"...","source":"WEB",
       "correlationId":null,"timestampEpochMilli":1750000000000}

```
- `?player=` verwirft Events anderer Spieler **serverseitig** vor dem Schreiben.
- Genau **eine** Redis-Subscription backend-seitig; Fan-out an alle registrierten Emitter.
- Frame-JSON = Felder von `BalanceChangedEvent` (kein neuer Codec/DTO). Per `@JsonTest` gepinnt.

## Fehlercode-Übersicht
| Situation | Status | Body `error` | Quelle |
|-----------|--------|--------------|--------|
| kein/ungültiges Token | 401 | — | `/api/web/**`-Chain (SecurityConfig) |
| fehlende Permission | 403 | `permission_denied` | `PermissionDeniedException` (`PunishmentExceptionHandler`) |
| ungültiges `type`/`source`/`limit` | 400 | `bad_request` | `IllegalArgumentException` (`EconomyExceptionHandler`) |
| unbekannte `transactionId` | 404 | `economy_transaction_not_found` | `EconomyTransactionNotFoundException` (`EconomyExceptionHandler`, neu) |
| unbekannter Spieler (Balances/History) | 200 | — (leere Liste) | — |

## protocol-Constraints
- Neue DTOs sind JDK-only Records; `plugin-protocol`-POM bleibt **ohne** `<dependencies>`.
- `PlatformProtocol.create()` **unverändert** (kein neuer Codec).
- 3 neue `EndpointDescriptor`-Konstanten in `EconomyEndpoints` (`LIST_BALANCES`, `SERVER_HISTORY`,
  `GET_TRANSACTION`).
