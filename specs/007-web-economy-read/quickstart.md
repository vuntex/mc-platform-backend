# Quickstart — Web-Economy Read-Backend + SSE (Slice 1)

## Build- & Publish-Reihenfolge (protocol-Änderung!)

Dieser Slice erweitert `plugin-protocol` (neue DTOs + Endpoint-Konstanten + 2 Felder an
`EconomyEventEntry`). Daher:

```bash
# 1. Backend bauen (inkl. jOOQ-Codegen + Tests)
./gradlew build

# 2. Nach grünem Build: Contract publizieren (DTO/Endpoint-Änderung)
./gradlew :plugin-protocol:publishToMavenLocal

# 3. Im Plugin-Repo (separates Repo) Dependencies ziehen — NUR falls das Plugin die neuen
#    DTOs/Endpoints schon nutzen soll (für diesen rein backend-/web-seitigen Slice nicht nötig):
#    (im mc-platform-plugin-Repo)  ./gradlew build --refresh-dependencies
```

## Teststrategie pro Schicht

**jOOQ-Integration (Testcontainers Postgres):**
- Server-History: Reihenfolge `sequence_no DESC`; Filter `currency`/`type`/`source` (einzeln + kombiniert);
  Keyset-Pagination über mehrere Seiten ohne Lücken/Überlappung + korrekter `nextCursor`; Einträge
  tragen `playerUuid` + `playerName`.
- `playerBalances`: Join `player_balance × currency`, mehrere Währungen, Display-Felder korrekt;
  leerer Spieler → leere Liste.
- `findTransaction`: SINGLE (ein Leg); TRANSFER (zwei Legs, beide Namen, `correlation_id` aus metadata);
  **Gegen-Leg manuell entfernt** → ein Leg, `kind=TRANSFER`, kein Fehler; unbekannte txId → empty.
- **Move-Regression:** bestehende `findHistory`- und `circulation`-Tests laufen nach dem Umzug in
  `JooqEconomyReadStore` unverändert grün.

**Application (Fakes):**
- `ServerHistoryQuery`: Limit-Clamp (null→50, ≤0→400, >200→200), Filter-Weitergabe (inkl. `source`).
- `TransactionDetailQuery`: SINGLE- vs. TRANSFER-Mapping; empty → `EconomyTransactionNotFoundException`.
- `PlayerBalancesQuery`: Mapping `ProjectedBalance` → leer bei unbekanntem Spieler.

**E2E (`app`, Testcontainers Postgres + Redis):**
- REST-Reads: Balances (inkl. leer), Server-History (Filter + Pagination + 400-Pfade),
  Detail (SINGLE/TRANSFER/404).
- **Permission-Gate:** Session ohne `permission.economy.read` → 403 auf allen vier Endpunkten.
- **SSE:** echten Balance-Change über REST/Service triggern → publiziert auf `mc:economy:balance` →
  SSE-Client empfängt das JSON-Frame; `?player=`-Filter verifizieren (Fremd-Event kommt nicht an).

**DTO-Contract (`@JsonTest`, app):** `RestDtoJsonContractTest` um die neuen DTOs + die 2 neuen
`EconomyEventEntry`-Felder ergänzen; SSE-Frame-Feldnamen pinnen.

## Definition of Done (aus CLAUDE.md)

- [ ] Tests pro Schicht grün; `./gradlew build` grün.
- [ ] `:plugin-protocol:publishToMavenLocal` nach DTO-/Endpoint-Änderung ausgeführt; POM weiterhin
      **ohne** `<dependencies>`; `PlatformProtocol.create()` unverändert.
- [ ] **PROGRESS.md nachgezogen:** neuer Slice-Eintrag *inkl. dokumentiertem Port-Move*
      (`findHistory`/`circulation`: `EconomyEventStore` → `EconomyReadStore`, Muster-Leck behoben,
      `EconomyHistoryService` + `EconomyStatsService` umgehängt) und additiver
      `EconomyEventEntry`-Erweiterung.
- [ ] `FEATURE_INVENTORY.md` n/a (kein Legacy-Feature — Web-Infra), aber im PROGRESS-Slice vermerkt.
- [ ] Bestätigt: **kein generischer Baustein** geändert außer dem bewussten Port-Move + der additiven
      DTO-Erweiterung (beides begründet dokumentiert).

## Manuelle Verifikation (optional)

```bash
# Backend lokal hochfahren (Postgres + Redis via docker-compose)
docker-compose up -d
./gradlew :app:bootRun

# A) Balances
curl -s localhost:8080/api/players/<uuid>/balances
# C) Server-History
curl -s 'localhost:8080/api/economy/history?source=WEBSHOP&limit=20'
# D) Detail
curl -s localhost:8080/api/economy/transactions/<txId>
# SSE (live)
curl -N localhost:8080/api/economy/stream?player=<uuid>
```
(Mit gültiger Web-Session/Permission; ohne → 403.)
