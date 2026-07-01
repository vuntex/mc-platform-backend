# ECO_API — Web-Economy Read API (Slice 1)

Frontend-Referenz für den **read-only** Economy-Teil des Webinterfaces (spec `007-web-economy-read`).
Alle Endpunkte sind lesend; Admin-Writes (Credit/Debit/Set übers Web) kommen in einem späteren Slice.

---

## 1. Grundlagen

- **Base-URL (lokal):** `http://localhost:8080`
- **Alle Endpunkte liegen unter `/api/web/economy/**`** (web-interface-only, hinter der JWT-Chain).
- **Authentifizierung:** JWT als `Authorization: Bearer <accessToken>` (Access-Token aus dem
  Web-Login, `POST /api/web/auth/...`). Ohne/ungültiges Token → **401**.
- **Autorisierung:** Der eingeloggte Account braucht die Permission **`permission.economy.read`**.
  Fehlt sie → **403** `{"error":"permission_denied"}`. (UI-Gating ist nur Komfort; die Prüfung ist
  backend-autoritativ.)
- **Geld:** immer `BIGINT` in der **kleinsten Einheit** (wie Cents). `decimalPlaces` der Währung sagt,
  wo das Komma steht — z. B. `balance=12345`, `decimalPlaces=2` → Anzeige `123,45`. Niemals als Float
  rechnen.
- **Zeitstempel:** `timestampEpochMilli` = Unix-Millis (UTC).
- **Spielernamen:** Wo `playerName` geliefert wird, ist es der gecachte Name (kann theoretisch
  veralten). Im SSE-Stream wird **kein** Name mitgeschickt — dort über `playerUuid` client-seitig
  auflösen.
- **CORS:** Der erlaubte Origin ist konfigurierbar (`mcplatform.webauth.cors.allowed-origin`,
  Default `http://localhost:3000`); Credentials erlaubt.

---

## 2. Endpunkte

### A) Sammel-Balances eines Spielers

```
GET /api/web/economy/players/{uuid}/balances
```

- `{uuid}` = Spieler-UUID.
- Liefert **alle** Währungsstände des Spielers in einem Call, je Eintrag mit Display-Metadaten.
- Unbekannter Spieler / keine Balance-Zeilen → **200** mit leerer `balances`-Liste (**kein 404**).

**200** `PlayerBalancesResponse`:
```json
{
  "player": "f7c1a3e2-0000-0000-0000-000000000001",
  "balances": [
    { "currencyCode": "COINS", "displayName": "Coins", "symbol": "$", "decimalPlaces": 0, "balance": 100 },
    { "currencyCode": "GEMS",  "displayName": "Gems",  "symbol": null, "decimalPlaces": 0, "balance": 5 }
  ]
}
```

---

### C) Serverweite Transaktionsliste

```
GET /api/web/economy/history?currency={code}&type={EVENT_TYPE}&source={src}&before={seqNo}&limit={n}
```

Serverweite Geld-Bewegungen, **neueste zuerst** (`sequenceNo` absteigend), Keyset-paginiert.

| Query-Param | Pflicht | Bedeutung |
|-------------|---------|-----------|
| `currency`  | optional | nur diese Währung (z. B. `COINS`) |
| `type`      | optional | nur dieser Event-Typ (siehe Enum unten); ungültig → **400** |
| `source`    | optional | nur diese Quelle (Freitext, z. B. `WEBSHOP`, `SYSTEM:initial`, `PLUGIN:pay`) |
| `before`    | optional | Keyset-Cursor: liefert nur Einträge mit `sequenceNo < before`. Für die nächste Seite den `nextCursor` der vorigen Antwort einsetzen. |
| `limit`     | optional | Seitengröße; Default **50**, Max **200**; `≤ 0` → **400** |

**Pagination:** Erste Seite ohne `before`. `nextCursor` ist gesetzt, solange weitere (ältere)
Einträge existieren — dann mit `before=nextCursor` weiterblättern. `nextCursor: null` = letzte Seite.
Lücken-/überlappungsfrei.

**200** `EconomyHistoryResponse` (serverweit ⇒ `player` ist `null`; das „wer" steht pro Eintrag):
```json
{
  "player": null,
  "entries": [
    {
      "sequenceNo": 412,
      "playerUuid": "...", "playerName": "Steve",
      "currencyCode": "COINS", "eventType": "TRANSFER_OUT",
      "amount": 50, "balanceAfter": 50,
      "transactionId": "9b1d...", "source": "PLUGIN:pay",
      "correlationId": "5c2e...", "counterpartyUuid": "...",
      "timestampEpochMilli": 1750000000000
    }
  ],
  "nextCursor": 405
}
```

> Hinweis: Derselbe `EconomyEventEntry`-Typ wird auch von der bestehenden **Spieler-History**
> (`GET /api/players/{uuid}/economy/history`, plugin-/internseitig) verwendet; dort ist `player`
> oben gesetzt.

---

### D) Transaktion per ID (Detailseite)

```
GET /api/web/economy/transactions/{transactionId}
```

- `{transactionId}` = fachlicher Transaktions-Schlüssel (UUID), auch in-game angezeigt.
- **Einzelbuchung** (CREDITED/DEBITED/SET) → `kind: "SINGLE"`, genau **ein** Leg.
- **Transfer** → `kind: "TRANSFER"`, **zwei** Legs (Sender = `TRANSFER_OUT`, Empfänger =
  `TRANSFER_IN`), beide mit Namen, `correlationId` gesetzt.
- Unbekannte ID → **404** `{"error":"economy_transaction_not_found"}`.

**200** `TransactionDetailResponse`:
```json
{
  "transactionId": "9b1d...",
  "correlationId": "5c2e...",
  "kind": "TRANSFER",
  "currencyCode": "COINS", "displayName": "Coins", "symbol": "$", "decimalPlaces": 0,
  "amount": 50, "source": "PLUGIN:pay",
  "metadata": "{\"correlation_id\":\"5c2e...\"}",
  "timestampEpochMilli": 1750000000000,
  "legs": [
    { "playerUuid": "...", "playerName": "Steve", "eventType": "TRANSFER_OUT", "balanceAfter": 50 },
    { "playerUuid": "...", "playerName": "Alex",  "eventType": "TRANSFER_IN",  "balanceAfter": 150 }
  ]
}
```

- `metadata` ist der **rohe JSONB-Text** (unparsed String) oder `null`; bei Bedarf client-seitig parsen.
- Edge-Case: ist bei einem Transfer das Gegen-Leg (Daten-)inkonsistent abwesend, kommt `kind:"TRANSFER"`
  mit nur **einem** Leg zurück — kein Fehler.

---

### SSE) Live-Balance-Updates

```
GET /api/web/economy/stream                 (Header: Accept: text/event-stream)
GET /api/web/economy/stream?player={uuid}
```

- Server-Sent-Events-Stream; hält die Verbindung offen und pusht jede Balance-Änderung live.
- `?player={uuid}` filtert **serverseitig** auf einen Spieler (Spieler-Tab); ohne Param: alle (Dashboard).
- Jedes Event ist ein SSE-`data:`-Frame mit JSON-Payload (`BalanceStreamView`, siehe unten).
- **Kein** Name im Frame → über `playerUuid` client-seitig auflösen.
- Auth wie oben (Bearer-Token; ohne → 401, ohne Permission → 403). Im Browser: `EventSource`
  unterstützt keine Header — Token z. B. via Proxy/Cookie-Bridge setzen oder eine fetch-basierte
  SSE-Lib mit `Authorization`-Header verwenden.
- **Reconnect:** Bei Verbindungsabbruch neu verbinden; verpasste Events über die Read-Calls (C/D)
  nachladen — es gibt **kein** Event-Replay über SSE.

Beispiel-Frame:
```
data: {"playerUuid":"...","currencyCode":"COINS","eventType":"CREDITED","amount":10,"balance":110,"version":413,"transactionId":"...","source":"WEB","correlationId":null,"timestampEpochMilli":1750000000000}

```

---

## 3. DTOs

### Enums

- **`eventType`** (`EconomyEventType`): `CREDITED` | `DEBITED` | `SET` | `TRANSFER_OUT` | `TRANSFER_IN`
  - Betrag (`amount`) ist immer **positiv**; die Richtung steckt im Typ.
  - `CREDITED`/`TRANSFER_IN` addieren, `DEBITED`/`TRANSFER_OUT` subtrahieren, `SET` setzt absolut.
- **`kind`** (Transaktion): `SINGLE` | `TRANSFER`

### Feld-Referenz (Typen)

**PlayerBalancesResponse**
| Feld | Typ | Null? |
|------|-----|-------|
| `player` | UUID (string) | nein |
| `balances` | `PlayerBalanceEntry[]` | nein (kann leer sein) |

**PlayerBalanceEntry**
| Feld | Typ | Null? |
|------|-----|-------|
| `currencyCode` | string | nein |
| `displayName` | string | nein |
| `symbol` | string | **ja** |
| `decimalPlaces` | int | nein |
| `balance` | long (BIGINT, kleinste Einheit) | nein |

**EconomyHistoryResponse**
| Feld | Typ | Null? |
|------|-----|-------|
| `player` | UUID (string) | **ja** (serverweit = `null`) |
| `entries` | `EconomyEventEntry[]` | nein |
| `nextCursor` | long | **ja** (letzte Seite = `null`) |

**EconomyEventEntry**
| Feld | Typ | Null? |
|------|-----|-------|
| `sequenceNo` | long | nein (globale Ordnung + Keyset-Cursor) |
| `currencyCode` | string | nein |
| `eventType` | string (Enum) | nein |
| `amount` | long | nein |
| `balanceAfter` | long | nein |
| `transactionId` | UUID (string) | nein |
| `source` | string | nein |
| `correlationId` | UUID (string) | **ja** (nur bei Transfer) |
| `counterpartyUuid` | UUID (string) | **ja** (nur bei Transfer = der andere Spieler) |
| `timestampEpochMilli` | long | nein |
| `playerUuid` | UUID (string) | nein |
| `playerName` | string | **ja** (falls Spieler-Stammdatensatz fehlt) |

**TransactionDetailResponse**
| Feld | Typ | Null? |
|------|-----|-------|
| `transactionId` | UUID (string) | nein |
| `correlationId` | UUID (string) | **ja** |
| `kind` | string (`SINGLE`/`TRANSFER`) | nein |
| `currencyCode` | string | nein |
| `displayName` | string | nein |
| `symbol` | string | **ja** |
| `decimalPlaces` | int | nein |
| `amount` | long | nein |
| `source` | string | nein |
| `metadata` | string (roher JSONB-Text) | **ja** |
| `timestampEpochMilli` | long | nein |
| `legs` | `TransactionLegDto[]` | nein (1 oder 2 Einträge) |

**TransactionLegDto**
| Feld | Typ | Null? |
|------|-----|-------|
| `playerUuid` | UUID (string) | nein |
| `playerName` | string | **ja** |
| `eventType` | string (Enum) | nein |
| `balanceAfter` | long | nein |

**SSE-Frame (`BalanceStreamView`)**
| Feld | Typ | Null? |
|------|-----|-------|
| `playerUuid` | UUID (string) | nein |
| `currencyCode` | string | nein |
| `eventType` | string (Enum) | nein |
| `amount` | long | nein |
| `balance` | long (Stand nach dem Event) | nein |
| `version` | long (= `sequenceNo`, für Ordering/Staleness) | nein |
| `transactionId` | UUID (string) | nein |
| `source` | string | nein |
| `correlationId` | UUID (string) | **ja** |
| `timestampEpochMilli` | long | nein |

---

## 4. TypeScript-Typen (zum Kopieren)

```ts
export type EconomyEventType =
  | "CREDITED" | "DEBITED" | "SET" | "TRANSFER_OUT" | "TRANSFER_IN";
export type TransactionKind = "SINGLE" | "TRANSFER";

export interface PlayerBalanceEntry {
  currencyCode: string;
  displayName: string;
  symbol: string | null;
  decimalPlaces: number;
  balance: number;          // BIGINT, kleinste Einheit
}
export interface PlayerBalancesResponse {
  player: string;           // UUID
  balances: PlayerBalanceEntry[];
}

export interface EconomyEventEntry {
  sequenceNo: number;
  currencyCode: string;
  eventType: EconomyEventType;
  amount: number;
  balanceAfter: number;
  transactionId: string;    // UUID
  source: string;
  correlationId: string | null;
  counterpartyUuid: string | null;
  timestampEpochMilli: number;
  playerUuid: string;       // UUID
  playerName: string | null;
}
export interface EconomyHistoryResponse {
  player: string | null;    // null bei serverweiter History
  entries: EconomyEventEntry[];
  nextCursor: number | null;
}

export interface TransactionLegDto {
  playerUuid: string;
  playerName: string | null;
  eventType: EconomyEventType;
  balanceAfter: number;
}
export interface TransactionDetailResponse {
  transactionId: string;
  correlationId: string | null;
  kind: TransactionKind;
  currencyCode: string;
  displayName: string;
  symbol: string | null;
  decimalPlaces: number;
  amount: number;
  source: string;
  metadata: string | null;  // roher JSONB-Text
  timestampEpochMilli: number;
  legs: TransactionLegDto[];
}

// SSE-Frame
export interface BalanceStreamEvent {
  playerUuid: string;
  currencyCode: string;
  eventType: EconomyEventType;
  amount: number;
  balance: number;
  version: number;
  transactionId: string;
  source: string;
  correlationId: string | null;
  timestampEpochMilli: number;
}

// Beträge formatieren
export function formatAmount(units: number, decimalPlaces: number): string {
  if (decimalPlaces === 0) return String(units);
  const f = Math.pow(10, decimalPlaces);
  return (units / f).toFixed(decimalPlaces);
}
```

---

## 5. Fehler

Fehler-Antworten sind JSON `{ "error": "<code>", "message": "<text>" }` (außer 401, das kommt ohne
Body von der Security-Chain).

| Situation | Status | `error` |
|-----------|--------|---------|
| Kein/ungültiges Token | **401** | — |
| Fehlende Permission `permission.economy.read` | **403** | `permission_denied` |
| Ungültiger `type` / nicht-positives `limit` | **400** | `bad_request` |
| Unbekannte `transactionId` (Detail) | **404** | `economy_transaction_not_found` |
| Unbekannter Spieler (Balances/History) | **200** | — (leere Liste) |

---

## 6. Beispiel-Flows (Frontend)

- **Spieler-Economy-Tab:** `GET …/players/{uuid}/balances` für die Stände → optional
  `GET …/players/{uuid}/economy/history` (bestehender Spieler-History-Endpunkt) für den Verlauf →
  SSE `…/stream?player={uuid}` für Live-Updates des offenen Tabs.
- **Server-Economy-Page:** `GET …/history?limit=50` → „Mehr laden" mit `before=nextCursor` →
  Klick auf eine Zeile → `GET …/transactions/{transactionId}` für die Detailseite → SSE `…/stream`
  (unfiltered) für den Live-Ticker.
- **Beträge** immer mit `decimalPlaces` der jeweiligen Währung formatieren (siehe `formatAmount`).
