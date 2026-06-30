# Prompt für `/speckit.tasks` — Web-Economy Read-Backend + SSE (Slice 1)

> Kopiere diesen Block als Input für den `/speckit.tasks`-Schritt im **Backend-Repo**,
> nachdem `plan.md` bestätigt ist.

---

Erzeuge `specs/002-web-economy-read/tasks.md` aus der bestätigten `plan.md`.

**Kontext (zuerst lesen):** `.specify/memory/constitution.md`, `PROGRESS.md`,
`specs/002-web-economy-read/spec.md`, `specs/002-web-economy-read/plan.md`.

## Anforderungen an die Task-Liste

- **Phasiert und geordnet**, jede Task klein genug für einen fokussierten Commit, mit klarer
  Abhängigkeitsreihenfolge. Innenliegende Schichten zuerst (Port/Read-Modelle → Persistenz →
  Application → REST → Realtime → Tests/Doku), damit jede Phase für sich kompiliert und testbar ist.
- **Jede Task benennt:** betroffene(s) Modul/Datei(en), das Akzeptanzkriterium aus der Spec, das
  sie erfüllt, und ihren Test (oder „Test in Task X"). Keine Task ohne nachvollziehbares „fertig
  heißt".

## Erwartete Phasen (Reihenfolge, anpassen falls plan.md abweicht)

**Phase 0 — Port-Move (Vorab-Refactor, isoliert)**
- `EconomyReadStore`-Interface anlegen (application).
- `findHistory()` + `circulation()` aus `EconomyEventStore` entfernen, in `EconomyReadStore`
  übernehmen (Signaturen 1:1).
- `JooqEconomyReadStore`-Adapter: bestehende Impl von `findHistory`/`circulation` herüberziehen
  (byte-gleich), Wiring in der Composition Root (`app`) umhängen.
- Bestehenden Circulation-Use-Case + bestehende `findHistory`-Nutzer auf den neuen Port umstellen.
- **Eigene Task: bestehende `findHistory`/`circulation`-Tests grün lassen** (Move-Regression).
- Diese Phase ist ein in sich abgeschlossener, grün baubarer Commit BEVOR neue Features dazukommen.

**Phase 1 — Read-Modelle + protocol-DTOs**
- Application-Records `ProjectedBalance`, `TransactionDetail`, `TransactionLeg`.
- `plugin-protocol`: `PlayerBalancesResponse`, `TransactionDetailResponse`, `TransactionLeg`-DTO,
  `EconomyEventEntry`-Erweiterung (`playerUuid`/`playerName`), `EconomyEndpoints`-Konstanten.
  JDK-only, POM-Check. `publishToMavenLocal`-Task.
- protocol-Tests: `EndpointDescriptor.expand`, JSON-Roundtrip im `app` (`@JsonTest`) für neue DTOs.

**Phase 2 — Persistenz (jOOQ)**
- Flyway `V9` Index.
- `JooqEconomyReadStore`: `playerBalances`, `findServerHistory`, `findTransaction` (mit
  Transfer-Auflösung). **Geteilte private Keyset-Helper** für player-/serverweite History (eine
  Task, die explizit die Dedup sicherstellt).
- jOOQ-Integrationstests (Testcontainers): Reihenfolge, Filter, Pagination, Transfer-zwei-Legs,
  fehlendes Gegen-Leg (Edge-Case aus plan.md), playerBalances-Join.

**Phase 3 — Application-Use-Cases**
- `PlayerBalancesQuery`, `ServerHistoryQuery` (Limit-Clamp/Filter wiederverwenden),
  `TransactionDetailQuery` (Single- vs. Transfer-Mapping).
- Application-Tests mit Fakes.

**Phase 4 — REST**
- Controller (getrennt) + Mapper (`api/rest/support`) für die drei GET-Endpoints.
- Fehlerpfade: 404 unbekannte txId, 400 ungültiges Limit/Filter.
- E2E-REST-Tests im `app`.

**Phase 5 — SSE-Bridge (api-realtime)**
- Subscriber-Bridge auf `mc:economy:balance` (`infra-cache`, Decode via `PlatformProtocol.create()`).
- SSE-Controller `GET /api/economy/stream[?player=]`, serverseitiger player-Filter.
- Lifecycle: Subscribe/Cleanup bei Connect/Disconnect, Verhalten bei Redis-Ausfall (aus plan.md).
- E2E: echtes Balance-Change-Event → SSE-Client empfängt; `?player=`-Filter.

**Phase 6 — Abschluss**
- `./gradlew build` grün (Gesamt).
- PROGRESS.md-Status-Abschnitt für diesen Slice schreiben (inkl. dokumentiertem Port-Move als
  bewusste Struktur-Änderung).
- DoD-Checkliste aus CLAUDE.md abhaken.

## Querschnitt
- Markiere Tasks, die `plugin-protocol` ändern, mit dem Pflicht-Folgeschritt
  `publishToMavenLocal`.
- Keine Task darf einen generischen Baustein ändern außer dem Phase-0-Port-Move; falls beim
  Schreiben ein weiterer Eingriff nötig erscheint → als Muster-Leck in der Task notieren, nicht
  stillschweigend tun.
