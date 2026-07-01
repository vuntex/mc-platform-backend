# Implementation Plan: Autoritäts-Grenzen für die Rollen-/Permission-Verwaltung

**Branch**: `008-authority-limits` | **Date**: 2026-07-01 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/008-authority-limits/spec.md`

## Summary

Eine backend-autoritative **Autoritäts-Schicht** über der bestehenden Rollen-/Permission-Verwaltung,
die Privilege-Escalation verhindert. Achse ist das bestehende Rollen-`weight`:
`authorityWeight(actor)` = höchstes Gewicht seiner **reachable** Rollen (aktive Grants + transitive
Vererbung). Vier Regeln (Rang-Ceiling beim Rollen-Management/-Grant, Delegations-Subset für
Permissions, Ziel-Autoritäts-Ceiling, begrenzte Reads) + Lockout-Schutz für den letzten Top-Tier.
Durchsetzung als Guards in `PermissionAdminService` (nach dem bestehenden `requirePermission`) und als
Filter/Gate im Web-Read-Pfad (`WebPermissionController`). **Keine Schema-, keine Protocol-Änderung** —
reines Verhalten (403 bei Autoritäts-Überschreitung, 409 bei Lockout, gefilterte Listen).

## Technical Context

**Language/Version**: Java 21, Spring Boot 3.5.x

**Primary Dependencies**: bestehende Permission-Bausteine — `PermissionAdminService`,
`PermissionQueryService`, `PermissionResolver` (wildcard-aware), `RoleHierarchy` (core-domain,
`reachable`), `PlayerGrantRepository` (`activeRoleGrants`, `activeHoldersOf`), `RoleRepository`.

**Storage**: PostgreSQL — **keine** Änderung (weight/grants existieren). Keine Flyway-Migration.

**Testing**: JUnit 5 + AssertJ; Domain-Unit (pure), Application mit Fakes, app-E2E (Testcontainers).

**Target Platform**: Spring-Boot-Backend (Single-Node).

**Project Type**: Multi-Module-Backend (hexagonal/DDD).

**Performance Goals**: Autoritäts-Berechnung pro Write/Read über kleine Datenmengen (Rollen eines
Actors, alle Rollen fürs Max, Top-Tier-Holder-Count) — vernachlässigbar; kein Hot-Path-Thema.

**Constraints**: `core-domain` framework-frei (die reine Vergleichslogik lebt dort);
`plugin-protocol` unverändert (kein neues DTO/Endpoint); backend-autoritativ (§12). Autorität ist
**weight-only** — `*` verändert die Rang-Autorität nicht (FR-002a).

**Scale/Scope**: 1 neuer pure-Domain-Helper, 1 neuer Application-Service, Guards in 11
`PermissionAdminService`-Methoden, Read-Filter/Gate in `WebPermissionController`, 2 neue Exceptions +
Handler-Mapping. Keine neuen Endpunkte.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Prinzip | Bewertung |
|---------|-----------|
| **V.12 Permissions backend-autoritativ** | ✅ Kernzweck — verschärft die serverseitige Autorisierung über `PermissionResolver`/`PermissionAdminService`; UI bleibt nur Komfort. |
| **II.5 Schichten** | ✅ Reine Vergleichslogik in `core-domain` (`RoleAuthority`), Orchestrierung/Datenzugriff in `application` (`PermissionAuthorityService`), Gate/Filter in `api-rest`. Kein jOOQ/Spring in der Domäne. |
| **II.6 Persistenz-Wahl** | ✅ Keine neue Persistenz; nutzt bestehende Grant-/Rollen-Reads. |
| **IV.10 Wiederverwenden** | ✅ `PermissionResolver` (Subset/Wildcard), `RoleHierarchy.reachable` (Vererbung), `PlayerGrantRepository.activeHoldersOf` (Lockout-Count), bestehender Exception-Handler-Stil. |
| **VI/Sicherheit** | ✅ Schließt eine reale Eskalations-/Lockout-Lücke (bekannt aus Slice 005). |

**Bewusste Eingriffe in bestehenden Code (dokumentationspflichtig, kein Muster-Leck):**

1. **`PermissionAdminService` erhält eine neue Abhängigkeit (`PermissionAuthorityService`) und je einen
   Guard-Aufruf nach `requirePermission`** in den 11 Methoden. Das ist der *vorgesehene* Ort für
   Autorisierung (die Klasse gated bereits) — ein Feature-Service, **kein** generischer Baustein
   (FeatureCache/EventBus/MenuBuilder/MessageEnvelope). Additiv, verhaltensverschärfend.
2. **`WebPermissionController` erhält Read-Filter/Gate** (Rollen-Liste gefiltert, Permissions-Detail
   eines höher-autorisierten Spielers → 403). Additiv; die bestehenden Endpunkte behalten Pfad/Form.

→ **Gate PASS.** `PermissionQueryService` und `PermissionResolver` bleiben **unverändert** (nur
konsumiert). Keine generische Infrastruktur angefasst.

## Project Structure

### Documentation (this feature)

```text
specs/008-authority-limits/
├── plan.md              # This file
├── research.md          # Entscheidungen (authorityWeight, Lockout-Semantik, Subset/Wildcard, Read-Gate)
├── data-model.md        # Autoritäts-Konzepte + Guard-Matrix je Operation
├── contracts/
│   └── authority-behavior.md   # Verhaltens-Contract je Endpunkt (403/409/Filter), keine neuen DTOs
├── quickstart.md        # Build/Test-Schritte, DoD
└── checklists/requirements.md  # aus /speckit-specify (+ /clarify)
```

### Source Code (repository root)

```text
core-domain/src/main/java/com/mcplatform/domain/permission/
└── RoleAuthority.java                    # NEU — pure Vergleichslogik (weight-Ceiling, top-tier)

application/src/main/java/com/mcplatform/application/permission/
├── PermissionAuthorityService.java       # NEU — authorityWeight/topWeight/isTopTier + require*-Guards + Read-Helper
├── InsufficientAuthorityException.java   # NEU → 403 (authority_ceiling)
├── LastTopTierException.java             # NEU → 409 (last_top_tier)
└── PermissionAdminService.java           # GEÄNDERT — +Dependency, +Guard nach jedem requirePermission

api-rest/src/main/java/com/mcplatform/api/rest/
├── WebPermissionController.java          # GEÄNDERT — Rollen-Liste gefiltert, effective()-Gate (403)
└── PermissionExceptionHandler.java       # GEÄNDERT — +403 authority_ceiling, +409 last_top_tier

app/src/main/java/com/mcplatform/bootstrap/config/
└── PermissionConfig.java                 # GEÄNDERT — PermissionAuthorityService-Bean + in AdminService injizieren
```

**Structure Decision**: Bestehende hexagonale Struktur. Pure Regel-Logik → `core-domain`; Daten-
Orchestrierung → `application`; Gate/Filter → `api-rest`. Kein neues Modul, kein neuer Endpunkt, keine
Protocol-/Schema-Änderung.

## Kern-Entscheidungen (Details in research.md)

- **`authorityWeight(actor)`** = max `weight` über die **reachable** Rollen des Actors (aktive
  Grants + transitive Vererbung via `RoleHierarchy.reachable`), Fallback = Default-Rolle (Weight 0).
  Bewusst **nicht** `primaryRoleOf` (das priorisiert `teamRank` vor `weight`).
- **Top-Tier**: `authorityWeight(actor) == topWeight`, wobei `topWeight` = max `weight` über **alle**
  (aktiven) Rollen. Schwelle: non-top `<`, Top-Tier `≤`; niemand darf eine Rolle über `authorityWeight`
  anlegen/anheben.
- **Delegations-Subset (FR-007/008):** Beim Hinzufügen/Gewähren einer Permission `P`: ist `P` eine
  Wildcard (`*` oder endet `.*`) → `resolver.hasPermission(actor, "*")` erforderlich; sonst
  `resolver.hasPermission(actor, P)`. (Revoke ist nur ziel-autoritäts-gegated, nicht subset.)
- **Lockout (FR-015, ergebnisbasiert):** Vor Revoke/Delete/Weight-Absenkung/Self-Demote prüfen, ob nach
  der Operation noch ≥1 Account eine Rolle mit dem aktuellen `topWeight` hielte; sonst 409. Zählung
  über `activeHoldersOf` der Max-Weight-Rolle(n).
- **Reads:** Rollen-Liste/-Picker über `visibleRoles(actor)` gefiltert (weight-Ceiling);
  `effective()` eines Spielers mit Ziel-Autorität ≥ actor → 403. Spieler-Suche/-Stammdaten & `/me`
  bleiben ungefiltert (FR-010a/FR-011).

## Guard-Matrix (welche Regel je Operation)

| Operation | Rang-Ceiling (Rolle) | Ziel-Autorität | Subset (Perm) | Lockout |
|-----------|----------------------|----------------|---------------|---------|
| createRole | neue weight ≤ ceiling | — | — | — |
| updateRole | alte **und** neue weight ≤ ceiling | — | — | (Weight-Absenkung Top-Rolle) |
| deleteRole | rolle.weight | — | — | ✓ (leert Top-Tier?) |
| add/removeRolePermission | rolle.weight | — | ✓ (nur add) | — |
| add/removeInheritance | child.weight (+ parent nicht über ceiling) | — | — | — |
| grantRole | rolle.weight | ✓ | — | — |
| revokeRole | rolle.weight | ✓ | — | ✓ |
| grantPermission | — | ✓ | ✓ | — |
| revokePermission | — | ✓ | — | — |

(„ceiling" = `< authorityWeight` non-top bzw. `≤ authorityWeight` Top-Tier.)

## Phase 0 / 1 Outputs

- **research.md** — begründet: authorityWeight-Berechnung (reachable, nicht primaryRoleOf), topWeight/
  top-tier, Subset-vs-Wildcard-Semantik über `hasPermission`, ergebnisbasierte Lockout-Definition,
  Read-Gate-Verortung im Controller, Revoke-ohne-Subset, 403-vs-409-Mapping.
- **data-model.md** — `RoleAuthority` (pure) + `PermissionAuthorityService`-API + Guard-Matrix je
  `PermissionAdminService`-Methode + Read-Helper.
- **contracts/authority-behavior.md** — Verhaltens-Contract je betroffenem Endpunkt (welche
  Bedingung → 403/409/gefiltert), Fehlercodes `authority_ceiling`/`last_top_tier`. Keine neuen DTOs.
- **quickstart.md** — Testschichten + DoD (kein Publish nötig, da protocol unverändert).

## Complexity Tracking

*Keine ungerechtfertigten Verstöße.* Die zwei Eingriffe (Guards in `PermissionAdminService`,
Filter/Gate im Controller) sind der vorgesehene Autorisierungs-Ort und additiv; die reine Regel-Logik
ist in `core-domain` isoliert und unit-testbar.
