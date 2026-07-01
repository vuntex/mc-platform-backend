# Phase 0 — Research & Decisions: Autoritäts-Grenzen

Alle Entscheidungen gegen den real erkundeten Code-Stand (nicht Annahmen).

## D1 — `authorityWeight(actor)`: max weight über reachable Rollen, NICHT `primaryRoleOf`

**Decision.** `authorityWeight(actor)` = höchstes `weight` über die **reachable** Rollen des Actors:
aktive Rollen-Grants (`PlayerGrantRepository.activeRoleGrants(player, now)`) transitiv über
`RoleHierarchy.reachable(...)` + `RoleInheritanceRepository` expandiert; Fallback = Default-Rolle
(Weight 0), wenn keine aktiven Rollen.

**Rationale.** `PermissionQueryService.primaryRoleOf` priorisiert **`teamRank` vor `weight`**
(RankDisplay.choose) — es liefert also nicht zwingend die höchste **Autoritäts**-Rolle. Für die
Autoritäts-Achse zählt ausschließlich `weight` (FR-001/FR-002a). Daher **eigene** Berechnung, nicht
`primaryRoleOf` wiederverwenden. Vererbung wird gemäß Spec („inkl. Vererbung") einbezogen; da Eltern
üblicherweise ≤ Weight sind, ändert das das Maximum selten — der reachable-Weg ist aber die korrekte,
spec-treue Definition und nutzt den bestehenden `RoleHierarchy`-Baustein.

**Alternatives rejected.** `primaryRoleOf().weight()` — falsch bei teamRank-Rollen mit niedrigerem
Weight. Nur direkte Grants ohne Vererbung — widerspricht Spec FR-001.

## D2 — Top-Tier & Schwellen

**Decision.** `topWeight` = max `weight` über **alle aktiven** Rollen (`RoleRepository.findAll`/`all`).
`isTopTier(actor)` ⟺ `authorityWeight(actor) == topWeight`. Verwaltbar ist ein Ziel-`weight` w:
`w < authorityWeight` (non-top) bzw. `w ≤ authorityWeight` (Top-Tier). Zusätzlich: **kein** Anlegen/
Anheben einer Rolle auf `w > authorityWeight` (auch Top-Tier nicht → kein Rang über dem Max).

**Rationale.** Genau die entschiedenen Regeln (strikt `<`, Top-Tier `≤`, kein Rang über Max). Die reine
Vergleichslogik lebt in `core-domain` (`RoleAuthority`), damit sie framework-frei unit-testbar ist.

## D3 — Delegations-Subset & Wildcard (`resolver.hasPermission`)

**Decision.** Beim **Hinzufügen/Gewähren** einer Permission `P` (an Rolle: `addRolePermission`; an
Spieler: `grantPermission`):
- ist `P` eine Wildcard (`P.equals("*")` oder `P.endsWith(".*")`) → erforderlich
  `resolver.hasPermission(actor, "*")`;
- sonst → erforderlich `resolver.hasPermission(actor, P)`.
Sonst `InsufficientAuthorityException` (403). **Revoke/removeRolePermission** unterliegt **nicht** der
Subset-Regel (nur dem Ziel-/Rang-Ceiling) — Rechte-Entzug ist keine Eskalation.

**Rationale.** `JooqPermissionResolver`/`PermissionMatcher` ist wildcard-aware: `hasPermission(a, X)`
ist wahr, wenn der Actor `X` exakt, `*`, oder eine passende `feature.*`-Wildcard hält. Für konkrete
Permissions ist das exakt „hält der Actor P". Für das **Vergeben einer Wildcard** verlangt die
Entscheidung (FR-008) explizit `*` — daher der Sonderzweig (nicht `hasPermission(actor, "economy.*")`,
das schon bei gehaltenem `economy.*` true wäre). Wiederverwendung des bestehenden Resolvers, keine neue
Matching-Logik.

## D4 — Ziel-Autoritäts-Ceiling

**Decision.** Bei `grantRole`/`revokeRole`/`grantPermission`/`revokePermission` MUSS die aktuelle
`authorityWeight(target)` verwaltbar sein (`< actor` non-top, `≤` Top-Tier); sonst 403. So kann niemand
einen gleich-/höherrangigen Account umranken oder dessen Permissions ändern.

**Rationale.** Symmetrisch zum Rollen-Ceiling; verhindert „Admin ändert Owner". Nutzt dieselbe
`authorityWeight`-Berechnung (D1) für das Ziel.

## D5 — Lockout-Schutz (ergebnisbasiert)

**Decision.** Vor `revokeRole`, `deleteRole`, `updateRole` (Weight-Absenkung einer Top-Rolle) und
Self-Demote wird geprüft: **Bliebe nach der Operation ≥1 Account, der eine Rolle mit dem aktuellen
`topWeight` hält?** Wenn nein → `LastTopTierException` (409). Zählung: Vereinigung der
`activeHoldersOf(role, now)` über alle Rollen mit `weight == topWeight`; die betroffene Operation wird
simuliert (Holder entfernen / Rolle entfernen / Weight senken) und der Rest-Count geprüft.

**Rationale.** Deckt **alle** Lockout-Pfade ab (Entscheidung Q2). Nutzt das vorhandene
`activeHoldersOf`. Kein „letzter Admin" hart verdrahtet — die Invariante ist „immer ≥1 Account auf dem
höchsten existierenden Weight".

**Alternatives rejected.** Nur Grant-Pfad schützen (lässt Delete/Weight-Lücke). Rollen- statt Holder-
basiert (verhindert legitimes Löschen leerer Rollen).

## D6 — Read-Gate/Filter-Verortung

**Decision.** Im `WebPermissionController` (hat den Actor):
- `listRoles`/Rollen-Picker-Reads → `PermissionAuthorityService.visibleRoles(actor)` (weight-gefiltert).
- `effective(uuid)` (+ ggf. player-bezogene Rollen-/Inheritance-Reads) → 403, wenn
  `authorityWeight(target) ≥ authorityWeight(actor)` (non-top; Top-Tier `>` erlaubt Gleichstand nicht,
  aber Top-Tier ist ohnehin max — Detailregel in data-model).
- Spieler-Suche/-Stammdaten (`/api/web/players/**`, `WebPlayerController`) und `/api/web/me`
  **unverändert/ungefiltert** (FR-010a/FR-011).

**Rationale.** `PermissionQueryService` bleibt unverändert (reiner Read-Service). Autorität ist eine
Cross-Cutting-Gate-Sache und gehört an die Web-Kante, wo der authentifizierte Actor bekannt ist —
konsistent mit dem bestehenden `requireRead`-Muster. Der neue `PermissionAuthorityService` liefert die
Prädikate/Filter.

## D7 — Fehlercodes & Mapping

**Decision.** `InsufficientAuthorityException` → **403** `{"error":"authority_ceiling"}`;
`LastTopTierException` → **409** `{"error":"last_top_tier"}`. Beide in `PermissionExceptionHandler`
gemappt (bestehender snake_case-Stil). Das bestehende `permission_denied` (403, fehlendes Gate) bleibt
für den *fehlenden* Grant; `authority_ceiling` ist semantisch „Grant vorhanden, aber Autorität reicht
nicht".

**Rationale.** Klare Unterscheidung „fehlt die Permission" vs. „überschreitet die Autorität"; der
Lockout ist ein Zustands-Konflikt (409, wie andere Konflikte im selben Handler).

## Offene, bewusst auf Implement verschobene Mikro-Punkte

- Ob `add/removeInheritance` zusätzlich prüft, dass die **Eltern**-Rolle nicht über dem Ceiling liegt
  (verhindert „Autorität über Vererbung hochziehen"). Vorschlag: ja, child- und parent-weight ≤ ceiling.
- Genaue Behandlung, wenn mehrere Rollen denselben `topWeight` teilen (Lockout betrachtet deren Holder-
  Union — in data-model fixiert).
- Ob der Permission-Katalog (008) autoritäts-gefiltert wird (Default: **nein**, rein informativ).
