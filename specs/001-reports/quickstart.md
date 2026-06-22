# Quickstart — Reports (Backend-Slice)

## Build & Test
```bash
./gradlew build         # alle Module + Codegen + Tests (Definition of Done: grün)
```
Bei reinen Domain-/Use-Case-Iterationen genügt der Modul-Test; vor „fertig" immer der volle Build.

## Bei protocol-Änderung (Pflicht-Workflow)
```bash
./gradlew :plugin-protocol:publishToMavenLocal     # nach Hinzufügen der report-DTOs/-Codec
# danach im Plugin-Repo: ./gradlew build --refresh-dependencies
```
Prüfen: publizierter `plugin-protocol`-POM hat weiterhin **keinen** `<dependencies>`-Block.

## Manuelle End-to-End-Probe (lokal, docker-compose up)
```bash
# 1) Report anlegen (Reporter+Ziel müssen bekannte Spieler sein → vorher Session-Join)
curl -XPOST localhost:8080/api/reports -H 'Content-Type: application/json' -d '{
  "reporter":"<uuidA>","target":"<uuidB>","category":"CHEATING",
  "detail":"fliegt in der mine",
  "chatContext":[{"sender":"<uuidB>","text":"lol ez","timestampEpochMilli":1750000000000}]
}'                                                  # → 200 ReportResponse (status OPEN)

# 2) Dedupe: gleicher Reporter+Ziel erneut → gleicher Report, kein zweiter
# 3) Offene Liste (Team)
curl 'localhost:8080/api/reports/open?staff=<moderatorUuid>'   # 200; ohne report.view → 403

# 4) Status ändern
curl -XPOST localhost:8080/api/reports/<id>/status -H 'Content-Type: application/json' \
  -d '{"newStatus":"IN_PROGRESS","handledBy":"<moderatorUuid>"}'   # 200
# ungültiger Übergang (z.B. RESOLVED→OPEN) → 409; Cooldown beim Erstellen → 429; Self-Report → 422
```

## Verifikations-Checkliste (Definition of Done)
- [ ] Domain-, Use-Case-, jOOQ-Integration-, Protocol-Codec- und app-E2E-Tests grün
- [ ] `./gradlew build` grün (Backend)
- [ ] `:plugin-protocol:publishToMavenLocal` ausgeführt; POM ohne `<dependencies>`
- [ ] Keine generische Klasse geändert außer der `PlatformProtocol.create()`-Zeile (+ additive
      `PersistenceConfig`-Bean) — Muster-Lecks-Abschnitt im Plan bestätigt
- [ ] PROGRESS.md-Statusabschnitt + FEATURE_INVENTORY.md #47 nachgezogen
