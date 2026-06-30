# Prompt für `/speckit.implement` — Web-Economy Read-Backend + SSE (Slice 1)

> Kopiere diesen Block als Input für den `/speckit.implement`-Schritt im **Backend-Repo**,
> nachdem `tasks.md` bestätigt ist.

---

Implementiere `specs/002-web-economy-read/tasks.md`, **Task für Task, in der vorgegebenen
Reihenfolge**. Nach jeder Phase: kompilieren + die Tests dieser Phase laufen lassen, erst dann
weiter („erst lauffähig, dann in die Breite", CLAUDE.md).

**Kontext (zuerst lesen):** `.specify/memory/constitution.md`, `PROGRESS.md`, und die drei
Slice-Artefakte (`spec.md`, `plan.md`, `tasks.md`).

## Eiserne Regeln für diesen Slice
- **Read-only.** Kein neues Event, kein Write, kein Optimistic Locking, keine Idempotenz, kein
  neuer Codec, KEINE Änderung an `PlatformProtocol.create()`.
- **Einziger Eingriff in bestehenden Code:** der Phase-0-Port-Move (`findHistory`/`circulation`
  aus `EconomyEventStore` → `EconomyReadStore`) + dessen Wiring. Stößt du auf einen weiteren
  nötigen Eingriff in einen generischen Baustein → **STOPP, als Muster-Leck melden**, nicht
  durchziehen.
- `core-domain` bleibt unberührt (Read-Modelle leben in `application`).
- `plugin-protocol` bleibt JDK-only; nach jeder protocol-Änderung
  `:plugin-protocol:publishToMavenLocal`. POM-Check: weiterhin **kein** `<dependencies>`-Block.
- Main-Thread/Blocking ist hier Backend-seitig kein Thema, aber SSE-Subscriber-Threads sauber
  verwalten (Lifecycle/Cleanup wie in plan.md).

## Reihenfolge & Checkpoints
1. **Phase 0 zuerst und allein bauen + grün** (Port-Move inkl. Move-Regression-Test), bevor neue
   Reads dazukommen. Das ist ein eigener, in sich abgeschlossener Stand.
2. Danach Phase 1→5 strikt der Reihe nach. Geteilte Keyset-Helper in Phase 2 NICHT duplizieren.
3. Transfer-Auflösung (`findTransaction`): exakt die in plan.md beschriebene Logik (txId → bei
   `TRANSFER_*` Gegen-Leg über `metadata->>'correlation_id'` → zwei Legs, beide Namen). Edge-Case
   „Gegen-Leg fehlt" wie in plan.md spezifiziert behandeln, nicht improvisieren.
4. SSE: nur den bestehenden `BalanceChangedEvent` durchreichen; `?player=` serverseitig filtern.

## Nach Abschluss (Definition of Done, CLAUDE.md)
- Alle Schicht-Tests grün, `./gradlew build` grün.
- `:plugin-protocol:publishToMavenLocal` ausgeführt (DTO-/Endpoint-Änderung).
- **PROGRESS.md** um einen Status-Abschnitt „Web-Economy Read-Backend + SSE (Slice 1)" ergänzt:
  was steht, der dokumentierte Port-Move als bewusste Struktur-Änderung, bewusste Grenzen
  (keine Writes/Leaderboard/Zeitreihe/CSV/playerName-im-Wire), Testliste, offene/spätere Punkte.
- Bestätigt im Abschluss-Text: kein generischer Baustein geändert außer dem Port-Move.

Halte nach jeder Phase kurz an und berichte Stand (gebaute Dateien, grüne Tests), bevor du die
nächste beginnst — kein Durchrauschen über alle Phasen ohne Zwischen-Checkpoint.
