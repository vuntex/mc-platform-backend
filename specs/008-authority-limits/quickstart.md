# Quickstart — Autoritäts-Grenzen (008)

## Build / Publish

**Keine `plugin-protocol`-Änderung** (kein neues DTO/Endpoint) → **kein** `publishToMavenLocal` nötig.
Keine Flyway-Migration.

```bash
./gradlew build   # Gesamt-Build inkl. aller Testschichten
```

## Teststrategie pro Schicht

**Domain (pure, `core-domain`):** `RoleAuthorityTest`
- `canManageWeight`: non-top strikt `<` (eigene Stufe NICHT), Top-Tier `≤`.
- `weightWithinCeiling`: `≤` Autorität; über Autorität → false.
- `isWildcard`: `*`, `x.*` → true; `x.y` → false.

**Application (Fakes):**
- `PermissionAuthorityServiceTest`: `authorityWeight` = max über reachable Rollen inkl. Vererbung;
  Fallback 0; `topWeight`/`isTopTier`; Lockout-Count (letzter Top-Tier-Holder erkannt);
  `visibleRoles`-Filter; `canViewTarget`.
- `PermissionAdminServiceTest` **erweitert** (bestehende Fakes wiederverwenden): je Methode
  Autoritäts-Verstoß → `InsufficientAuthorityException`; `*`/Wildcard ohne `*` → abgelehnt; Rolle/Ziel
  über eigener Stufe → abgelehnt; letzter Top-Tier revoke/delete/self-demote → `LastTopTierException`;
  legitime Fälle (unterhalb) weiterhin erlaubt; bestehende 005/006-Tests grün (Regression — ggf.
  Test-Actor mit ausreichender Autorität ausstatten).

**app-E2E (`WebPermissionVerticalSliceTest` erweitert, Testcontainers):**
- Mod (niedriges Weight) kann Admin-Rolle nicht vergeben/bearbeiten → 403 `authority_ceiling`.
- Actor ohne `*` kann `*` weder einer Rolle noch einem Spieler geben → 403.
- Ziel-Ceiling: höher-/gleichrangigen Spieler umranken → 403.
- Read: Rollen-Liste ohne höhere Ränge; `…/players/{höher}/effective` → 403; `players/search` findet
  den Spieler weiterhin; `/me` zeigt eigenen Rang.
- Top-Tier verwaltet eigene Stufe → erlaubt; letzter Top-Tier entmachten → 409 `last_top_tier`.
- Bestehende Slice-005/006-E2E-Fälle bleiben grün (Admin=Top-Tier mit `*` darf weiterhin alles).

## Definition of Done (CLAUDE.md)

- [ ] Tests pro Schicht grün; `./gradlew build` grün.
- [ ] **Kein** `plugin-protocol`-/Schema-Change (verifiziert); POM unverändert.
- [ ] Bestehende Permission-Suiten (002/005/006) grün — ggf. Test-Actoren mit passender Autorität/`*`
      versehen (bewusst dokumentiert, kein Verhaltens-Regress der Alt-Features).
- [ ] PROGRESS.md-Status-Abschnitt ergänzt (neue Autoritäts-Schicht; Guards in `PermissionAdminService`
      + Read-Gate als bewusste, additive Eingriffe dokumentiert).
- [ ] Bestätigt: kein generischer Baustein geändert; `PermissionQueryService`/`PermissionResolver`
      unverändert.

## Regressions-Hinweis

Die bestehenden E2E-Tests nutzen `staff("ADMIN")` als Actor. Ist ADMIN das aktuell höchste Weight
(Top-Tier) und trägt `*`, bleiben alle bisherigen Aktionen erlaubt. Tests, die mit einem **nicht**-Top
Actor schreiben, müssen ggf. angepasst werden (Actor = Top-Tier oder Zielobjekt unterhalb). Vor der
Implementierung die Ausgangs-Weights (ADMIN/MODERATOR/DEFAULT) prüfen, um Regressionen gezielt zu
adressieren.
