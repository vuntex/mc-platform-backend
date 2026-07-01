---
description: "Task list for Autoritäts-Grenzen (Privilege-Escalation-Schutz, 008)"
---

# Tasks: Autoritäts-Grenzen für die Rollen-/Permission-Verwaltung

**Input**: Design documents from `/specs/008-authority-limits/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/authority-behavior.md

**Tests**: Enthalten — Constitution §22 / CLAUDE.md machen „Tests pro Schicht grün" zur DoD.

**Organization**: Nach User Story (US1–US4). Die Autoritäts-Engine ist Foundational (blockiert alle
Stories). **Keine** `plugin-protocol`-/Schema-Änderung → **kein** `publishToMavenLocal`.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: parallelisierbar (andere Datei, keine offene Abhängigkeit)
- **[Story]**: US1–US4 (Setup/Foundational/Polish ohne Label)
- ⚠️ **Geteilte Datei**: US1/US2/US4 bearbeiten alle `PermissionAdminService.java` → untereinander
  **sequenziell**; US3 (Controller) ist davon unabhängig.

## Path Conventions

Multi-Module-Backend. Module: `core-domain/`, `application/`, `api-rest/`, `app/` — jeweils
`src/main/java/com/mcplatform/...`.

---

## Phase 1: Setup

- [X] T001 Baseline: `./gradlew build` grün auf Branch `008-authority-limits` (saubere Vergleichsbasis für Regression).
- [X] T002 Seed-Rollen-Weights ermitteln (ADMIN/MODERATOR/DEFAULT aus `V6__seed_team_roles.sql` + Tests) und notieren, welche bestehenden 002/005/006-E2E-Actoren Top-Tier/`*` sind — als Grundlage für die Regressions-Anpassung (T021).

---

## Phase 2: Foundational — Autoritäts-Engine (Blocking Prerequisites)

**Purpose**: Die weight-basierte Autoritäts-Logik + Guards-API + Fehler + Wiring bereitstellen, auf die
**alle** Stories aufsetzen. In sich grün baubar.

**⚠️ CRITICAL**: Keine User-Story-Arbeit beginnt, bevor diese Phase grün ist.

- [X] T003 Pure Domain `RoleAuthority` in `core-domain/src/main/java/com/mcplatform/domain/permission/RoleAuthority.java`: `canManageWeight(target, authority, topTier)` (non-top `<`, top `≤`), `canManageTarget(...)`, `weightWithinCeiling(newWeight, authority)` (`≤`), `isWildcard(perm)` (`*` oder endet `.*`). Framework-frei.
- [X] T004 [P] `RoleAuthorityTest` (core-domain) — non-top strikt `<` (eigene Stufe NICHT), Top-Tier `≤`, Ceiling, Wildcard-Erkennung.
- [X] T005 [P] Fehler-Typen `InsufficientAuthorityException` + `LastTopTierException` in `application/.../permission/`.
- [X] T006 `PermissionExceptionHandler` (`api-rest/.../PermissionExceptionHandler.java`) erweitern: `InsufficientAuthorityException` → **403** `{"error":"authority_ceiling"}`, `LastTopTierException` → **409** `{"error":"last_top_tier"}`.
- [X] T007 `PermissionAuthorityService` in `application/.../permission/PermissionAuthorityService.java`: `authorityWeight(UUID)` (max weight über reachable Rollen via `RoleHierarchy.reachable` + `PlayerGrantRepository.activeRoleGrants` + `RoleInheritanceRepository`; Fallback Default-Weight 0), `topWeight()`, `isTopTier(UUID)`; Guards `requireCanManageRole`, `requireWeightWithinCeiling`, `requireCanManageTarget`, `requireCanDelegate` (Subset/Wildcard via `PermissionResolver`, Wildcard→`*`), `requireNotLastTopTier(...)` (Lockout-Count über `activeHoldersOf` der Max-Weight-Rollen); Read-Helper `visibleRoles(UUID)`, `canViewTarget(UUID, PlayerId)`. Nutzt `RoleAuthority`.
- [X] T008 [P] `PermissionAuthorityServiceTest` (Fakes): authorityWeight inkl. Vererbung + Fallback 0; topWeight/isTopTier; Lockout-Count erkennt letzten Top-Tier; `visibleRoles`-Filter; `canViewTarget`; `requireCanDelegate` (Wildcard nur mit `*`).
- [X] T009 Wiring: `PermissionAuthorityService`-Bean in `app/.../config/PermissionConfig.java`; als Dependency in `PermissionAdminService` injizieren (Konstruktor-Parameter) + `PermissionAdminServiceTest`-Konstruktion anpassen (Fake/Instanz übergeben). *Fertig:* Build grün, noch **keine** Guard-Aufrufe (die kommen pro Story).

**Checkpoint**: Engine + Wiring grün → Stories können starten.

---

## Phase 3: User Story 1 — Delegations-Subset (Priority: P1)

**Goal**: Man kann nur Permissions vergeben, die man selbst hält; Wildcard/`*` nur mit `*`.

**Independent Test**: Actor ohne `*` → `*`/`X.*` an Rolle/Spieler vergeben → 403; nicht-gehaltene
konkrete Permission → 403; gehaltene → erlaubt.

- [X] T010 [US1] Guard `requireCanDelegate(actor, permission)` in `PermissionAdminService.addRolePermission` **und** `grantPermission` (nach `requirePermission`). ⚠️ geteilte Datei.
- [X] T011 [US1] `PermissionAdminServiceTest` erweitern: `*`/`X.*` ohne `*` → `InsufficientAuthorityException`; nicht-gehaltene Permission → abgelehnt; gehaltene → erlaubt (Fakes: Actor-Permissions über den Fake-Resolver steuern).
- [X] T012 [US1] E2E (`WebPermissionVerticalSliceTest`): Actor ohne `*` → `POST /roles/{id}/permissions {"*"}` und `POST /players/{uuid}/permissions {"*"}` → **403** `authority_ceiling`; gehaltene konkrete Permission → erlaubt.

**Checkpoint**: US1 eigenständig grün.

---

## Phase 4: User Story 2 — Rang-Hierarchie (Priority: P1)

**Goal**: Rollen-Management/-Grant nur unterhalb der eigenen Stufe (non-top `<`, Top-Tier `≤`, nie über
Max); kein Umranken gleich-/höherrangiger Spieler.

**Independent Test**: non-top Actor (Autorität W) → Rolle/Ziel mit Weight ≥ W verwalten/vergeben →
403; < W → erlaubt; Rolle mit Weight > W anlegen → 403.

- [X] T013 [US2] Guards in `PermissionAdminService` (⚠️ geteilte Datei): `requireCanManageRole` in `createRole`/`updateRole`/`deleteRole`/`addRolePermission`/`removeRolePermission`/`addInheritance`(child+parent)/`removeInheritance`; `requireWeightWithinCeiling` in `createRole`/`updateRole`; `requireCanManageRole(role)` + `requireCanManageTarget(actor, player)` in `grantRole`/`revokeRole`; `requireCanManageTarget` in `grantPermission`/`revokePermission`. (Siehe Guard-Matrix in data-model.md.)
- [X] T014 [US2] `PermissionAdminServiceTest` erweitern: Rolle mit Weight ≥ eigener (non-top) → abgelehnt; < → erlaubt; Rolle über Max anlegen → abgelehnt; Ziel-Spieler mit Autorität ≥ → abgelehnt; Top-Tier `≤` erlaubt.
- [X] T015 [US2] E2E: MODERATOR (niedriges Weight) kann ADMIN-Weight-Rolle nicht create/edit/delete/grant → 403; höher-/gleichrangigen Spieler nicht umranken → 403; unterhalb erlaubt.

**Checkpoint**: US1 + US2 grün.

---

## Phase 5: User Story 3 — Begrenzte Sichtbarkeit (Read) (Priority: P2)

**Goal**: Rollen-Listen weight-gefiltert; Permissions-Tab höher-autorisierter Spieler → 403; Suche/`/me`
ungefiltert.

**Independent Test**: non-top Actor → Rollen-Liste ohne Weight ≥ W; `…/players/{höher}/effective` →
403; `players/search` findet den Spieler; `/me` zeigt eigenen Rang.

- [X] T016 [US3] `WebPermissionController` (`api-rest/.../WebPermissionController.java`): `PermissionAuthorityService` injizieren; `listRoles` (+ Rollen-Picker-Reads) → Ergebnis über `visibleRoles(actor)` filtern; **Einzel-Rollen-Reads** `getRole(id)`/`listRolePermissions(id)`/`listInheritance(id)` → 403 (`InsufficientAuthorityException`) wenn `!canViewRole(actor, role)` (FR-009a); `effective(uuid)` → 403 wenn `!canViewTarget(actor, uuid)`. `catalog` bleibt unverändert. Unabhängig von `PermissionAdminService` (andere Datei).
- [X] T017 [US3] E2E: non-top Actor → `GET /permission/roles` enthält keine Rolle mit Weight ≥ seiner Stufe; **`GET /permission/roles/{höhereId}` (+ `/permissions`, `/inheritance`) → 403** (kein Umgehen per ID); `GET /permission/players/{höher}/effective` → 403; `GET /players/search?name=…` findet den höheren Spieler weiterhin; `GET /me` liefert eigenen (hohen) Rang.

**Checkpoint**: US1–US3 grün.

---

## Phase 6: User Story 4 — Top-Tier-Selbstverwaltung & Lockout (Priority: P2)

**Goal**: Top-Tier verwaltet eigene Stufe (`≤`); der letzte Top-Tier-Inhaber kann nicht entmachtet
werden (ergebnisbasiert → 409).

**Independent Test**: Top-Tier verwaltet zweiten Top-Tier → erlaubt; letzter Top-Tier
revoke/delete/weight-lower/self-demote → 409.

- [X] T018 [US4] `requireNotLastTopTier(...)` in `PermissionAdminService.revokeRole`, `deleteRole`, `updateRole` (Weight-Absenkung einer Top-Rolle) sowie Self-Demote-Pfad einbinden (Lockout-Berechnung in `PermissionAuthorityService`, ergebnisbasiert). ⚠️ geteilte Datei.
- [X] T019 [US4] `PermissionAdminServiceTest`: letzter Top-Tier revoke/delete/weight-lower/self-demote → `LastTopTierException`; zweiter Top-Tier vorhanden → erlaubt; Top-Tier verwaltet eigene Stufe (`≤`) → erlaubt.
- [X] T020 [US4] E2E: letzten Top-Tier entmachten → **409** `last_top_tier`; mit zweitem Top-Tier → erlaubt; Top-Tier bearbeitet Rolle auf eigener Stufe → erlaubt.

**Checkpoint**: Alle vier Stories grün.

---

## Phase 7: Polish & Cross-Cutting

- [X] T021 Regression: bestehende Permission-Suiten (002/005/006, `WebPermissionVerticalSliceTest`, `PermissionAdminServiceTest`, `PermissionQueryService*Test`) grün — Test-Actoren, die als non-top schrieben, auf Top-Tier/`*` anheben bzw. Zielobjekte unterhalb wählen (bewusst dokumentiert, kein Verhaltens-Regress der Alt-Features).
- [X] T022 `./gradlew build` grün (Gesamt); bestätigen: **kein** `plugin-protocol`-/Schema-Change (POM unverändert), `PermissionQueryService`/`PermissionResolver` unverändert.
- [X] T023 PROGRESS.md: Status-Abschnitt „Autoritäts-Grenzen (008)" — neue Autoritäts-Schicht, Guards in `PermissionAdminService` + Read-Gate als bewusste additive Eingriffe, `RoleAuthority`/`PermissionAuthorityService`, Fehlercodes 403/409, keine Schema-/Protocol-Änderung; DoD abhaken.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (1)** → **Foundational (2, Engine)** blockiert alles → **US1–US4 (3–6)** → **Polish (7)**.

### User Story Dependencies

- **US1/US2/US4**: nur Phase 2; bearbeiten aber alle `PermissionAdminService.java` → **sequenziell**
  (nicht parallel), da geteilte Datei. Reihenfolge P1 zuerst (US1, US2), dann US4.
- **US3**: nur Phase 2; bearbeitet `WebPermissionController.java` (separat) → **parallel** zu
  US1/US2/US4 möglich.

### Within Each Story

Guard-Einbau → Application-Test (Fakes) → E2E.

### Parallel Opportunities

- **Phase 2**: T004, T005, T008 [P] (verschiedene Dateien).
- **Nach Phase 2**: US3 (Controller) parallel zu US1/US2/US4 (AdminService). Die AdminService-Stories
  untereinander sequenziell.

---

## Implementation Strategy

### MVP First (US1 + US2)

Setup → Foundational → US1 (Self-`*`-Schutz) → US2 (Rang-Ceiling) → **STOP & VALIDATE**: die beiden
P1-Eskalationspfade sind geschlossen. Dann US3 (Read) + US4 (Lockout).

### Incremental Delivery

Jede Story schließt einen konkreten Eskalations-/Sicht-/Lockout-Pfad und ist einzeln testbar; die
Engine (Phase 2) ist die gemeinsame Grundlage.

---

## Notes

- **Kein Publish/kein Schema:** dieses Feature ändert weder `plugin-protocol` noch die DB — reine
  Verhaltens-/Autorisierungs-Verschärfung.
- Einziger Eingriff in bestehende, funktionierende Klassen: die Guard-Aufrufe in
  `PermissionAdminService` (nach `requirePermission`) + Read-Gate in `WebPermissionController` +
  Handler-Mapping. `PermissionQueryService`/`PermissionResolver`/`RoleHierarchy` bleiben unverändert.
- Bei einem darüber hinausgehenden nötigen Eingriff in einen generischen Baustein → STOPP, als
  Muster-Leck melden.
- Commit nach jeder Task/logischen Gruppe; an jedem Checkpoint Story unabhängig validierbar.
