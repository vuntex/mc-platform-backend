---
description: "Task list — Rollen-Vererbung (Permission-Inheritance)"
---

# Tasks: Rollen-Vererbung (Permission-Inheritance)

**Input**: Design documents from `/specs/006-role-inheritance/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/inheritance-endpoints.md

**Tests**: Eingefordert — pro Schicht (Domain-Unit, Use-Case-Fakes, Testcontainers-Integration, E2E,
Contract). 🛡 markiert **REGRESSIONS-Tests** (Schutz beim Resolver-Umbau: Verhalten ohne Vererbung
bleibt bit-identisch, FR-008/SC-002).

**Schichtung (wie beauftragt)**: core-domain → application → infra-persistence → plugin-protocol →
api-rest → publish. **Reihenfolge-Hinweis**: die `plugin-protocol`-DTOs stehen *vor* api-rest, weil
api-rest gegen sie kompiliert (Returntypen `RoleResponse`/`PlayerPermissionsResponse`); das im Prompt
genannte „api-rest → protocol" ist genau dafür getauscht. `:plugin-protocol:publishToMavenLocal`
bleibt der letzte Schritt (für das separate Plugin-Repo).

## User Stories (aus spec.md)

- **US1 (P1)**: Eine Rolle erbt transitiv die Permissions einer anderen.
- **US2 (P1)**: Zyklen werden beim Setzen abgelehnt (409) + defensiv aufgelöst.
- **US3 (P2)**: Vererbungs-Änderung schlägt live auf betroffene (transitiv abhängige) Spieler durch.
- **US4 (P3)**: Vererbte Rollen einer Rolle einsehen.

## Globale Definition of Done

- Alle Schicht-Tests grün; bestehende 002/005-Tests unverändert grün (🛡 Regression).
- `./gradlew build` grün (Backend); `:plugin-protocol:build` grün.
- Protocol-Änderung → `:plugin-protocol:publishToMavenLocal`, danach im Plugin
  `build --refresh-dependencies`.
- PROGRESS.md + FEATURE_INVENTORY.md nachgezogen.
- Bestätigt: kein generischer Baustein geändert; die zwei bewussten Feature-Kern-Eingriffe
  (Resolver-SQL, `PermissionAdminService`-Fan-out) sind benannt + regressionsgesichert.

---

## Phase 1: Setup

**Purpose**: Ausgangszustand sichern.

- [X] T001 Branch `006-role-inheritance` + Modul-Layout verifizieren; Baseline `./gradlew build` grün VOR den Änderungen (Referenz für die Regression).

---

## Phase 2: Foundational (Blocking — inerte Substrate)

**Purpose**: DDL + Exceptions, gegen die spätere Schichten bauen. Bricht nichts Bestehendes.

**⚠️ CRITICAL**: muss vor den Schicht-Phasen stehen.

- [X] T002 V15-Migration `infra-persistence/src/main/resources/db/migration/V15__role_inheritance.sql` anlegen (Tabelle `role_inheritance`: `role_id` FK ON DELETE CASCADE, `inherited_role_id` FK ON DELETE RESTRICT, `created_by`, `created_at`; PK `(role_id, inherited_role_id)`; `CHECK (role_id <> inherited_role_id)`; Index `idx_role_inheritance_parent`). Keine bestehende Migration ändern.
- [X] T003 [P] Exceptions `RoleInheritanceCycleException` und `RoleInheritedException` in `application/src/main/java/com/mcplatform/application/permission/port/` (analog zu `RoleNotFoundException`).

**Checkpoint**: Migration zieht beim Testcontainers-Boot; Exceptions kompilieren.

---

## Phase 3: core-domain — transitive Auflösung & Zyklus-Schutz (rein) 🎯 MVP-Kern

**Goal (US1/US2)**: framework-freie transitive Permission-Union + Zyklus-Erkennung; testbar ohne DB.

**Independent Test**: reine Unit-Tests auf `RoleHierarchy` — keine Spring-/jOOQ-Imports.

- [X] T004 [P] [US1] `RoleHierarchy.reachable(startRoles, directParentsOf)` + `resolveWithProvenance(startRoles, directParentsOf, permissionsOf, directPlayerPermissions)` in `core-domain/src/main/java/com/mcplatform/domain/permission/RoleHierarchy.java` (Visited-Set; Provenienz `Map<String, Provenance{own, Set<RoleId> sources}>`; nur Permissions, keine Meta-Felder).
- [X] T005 [US2] `RoleHierarchy.wouldCreateCycle(child, parent, directParentsOf)` in derselben Datei (true bei `child==parent` oder `child` aus `parent` erreichbar).

### Tests — Domain (Unit)

- [X] T006 [P] [US1] `RoleHierarchyTest` in `core-domain/src/test/java/.../permission/`: transitive Union (A erbt B erbt C → A∪B∪C); Mehrfach-Vererbung (A erbt B und C); Diamond (A erbt B und C, beide erben D → D **einmal**, `sources` vollständig); KEINE Meta-Vererbung (nur Permissions).
- [X] T007 [P] [US2] `RoleHierarchyCycleTest`: direkter Zyklus (A↔B) erkannt; Selbstreferenz; transitiver Zyklus (A→B→C, dann C→A); 🛡 Visited-Set terminiert bei künstlich eingefügtem Restzyklus (FR-010a).

**Checkpoint**: Domain-Unit-Tests grün; `EffectivePermissions`/`PermissionMatcher` unverändert.

---

## Phase 4: application — Use Cases, Gating, Default-Zusammenspiel, Fan-out

**Goal (US1/US3/US4)**: Vererbungs-Use-Cases mit Vorab-Zyklus-Check, Gates, Audit, Live-Push;
effektive Sichten transitiv; Default-Fallback unverändert. application bleibt `plugin-protocol`-frei.

- [X] T008 [US1] Port `RoleInheritanceRepository` in `application/src/main/java/com/mcplatform/application/permission/port/RoleInheritanceRepository.java` (`add`, `remove`, `directParents`, `ancestors`, `dependents`, `isInheritedByAny`).
- [X] T009 [P] `RoleAuditPort` um Aktionen `ROLE_INHERITANCE_ADD`/`ROLE_INHERITANCE_REMOVE` erweitern (`application/.../permission/port/RoleAuditPort.java`).
- [X] T010 [US1] `PermissionAdminService.addInheritance(child, parent, actor)` (`application/.../permission/PermissionAdminService.java`): Gate-Konstante `ROLE_EDIT_INHERIT = "permission.role.edit.inherit"`; reject wenn `child` = Default (`DefaultRoleProtectedException`, FR-013); Vorab-Zyklus-Check — **eine** Quelle/eine Prüfung: `repo.ancestors(parent)` liefert die Erreichbarkeit, `RoleHierarchy.wouldCreateCycle(child, parent, …)` ist die reine Entscheidung darüber → `RoleInheritanceCycleException`; `repo.add`; `roleAudit.record(ROLE_INHERITANCE_ADD,…)`; `publishToRoleAndDependents(child)`.
- [X] T011 [US1] `PermissionAdminService.removeInheritance(child, parent, actor)`: Gate; `repo.remove`; Audit `ROLE_INHERITANCE_REMOVE`; `publishToRoleAndDependents(child)`.
- [X] T012 [US3] `publishToHolders(id)` → `publishToRoleAndDependents(id)` umbauen (Fan-out an Holder von `{id} ∪ repo.dependents(id)`); wirkt damit auch auf bestehendes `addRolePermission`/`removeRolePermission` (FR-020a). **Bewusster Eingriff in 005-Code** — siehe plan.md Complexity Tracking.
- [X] T013 [US1] `PermissionAdminService.deleteRole(...)` erweitern: vor `roles.delete` `repo.isInheritedByAny(id)` prüfen → `RoleInheritedException` (409, benennt abhängige Rollen, FR-015).
- [X] T014 [US4] `PermissionQueryService` (`application/.../permission/PermissionQueryService.java`): `directParents(id)` für die Liste; effektive Sichten (`effectiveFor`, `roleDetail`) nutzen `RoleHierarchy` (transitiv expandierte Permissions-Map an unverändertes `EffectivePermissions` + Provenienz-Map `sources`). **Arbeitsteilung festgelegt (F1)**: `EffectivePermissions` bleibt die *einzige* Quelle der flachen effektiven Menge (inkl. Direkt-Permissions des Spielers); `RoleHierarchy.resolveWithProvenance` liefert NUR die `sources`-Anzeige — keine zweite flache Union.

### Tests — Application (Fakes)

- [X] T015 [P] [US1] `AddInheritanceUseCaseTest`: Zyklus-Vorab-Check → Ablehnung; Default-als-child → Ablehnung; idempotentes Re-Add; Gating (ohne `permission.role.edit.inherit` → `PermissionDeniedException`); **Publish-once** nach Write; **Audit-Eintrag** `ROLE_INHERITANCE_ADD` wird geschrieben (FR-017/SC-006) — analog `removeInheritance` → `ROLE_INHERITANCE_REMOVE`.
- [X] T016 [P] [US4] `ListRemoveInheritanceUseCaseTest`: `directParents` korrekt; `remove` idempotent; Gating lesend (`permission.read`) / schreibend.
- [X] T017 [P] [US1] `DefaultInterplayUseCaseTest` (Fake-Repos): Premium erbt Default → Default-Permissions in effektiver Menge; Premium erbt Default NICHT → nur Premium-Permissions (CL-1, läuft korrekt durch); 0 Rollen → Default-Fallback.
- [X] T018 [P] [US3] `FanOutUseCaseTest`: `addRolePermission` auf Base pusht an Holder von Base + transitiv abhängige Rollen (FR-020a); korrekte Publish-Anzahl, je UUID `ROLE_CONFIG_CHANGED`; **Negativ-Fall**: Holder einer unbeteiligten Rolle (erbt Base NICHT) erhält **kein** Signal (FR-020/SC-005).

**Checkpoint**: Use-Case-Tests grün; application ohne `plugin-protocol`-Import.

---

## Phase 5: infra-persistence — Repository + Resolver-Umbau (Testcontainers) 🛡

**Goal (US1/US2/US3)**: echte Kanten-Persistenz + transitive SQL-Auflösung; Resolver-Hot-Path
transitiv — **ohne Regression** für Rollen ohne Vererbung.

- [X] T019 [US1] `JooqRoleInheritanceRepository` in `infra-persistence/src/main/java/com/mcplatform/persistence/JooqRoleInheritanceRepository.java`: `add` (`ON CONFLICT DO NOTHING`), `remove`, `directParents`, `ancestors` (rekursive CTE), `dependents` (Reverse-CTE über `inherited_role_id`), `isInheritedByAny`.
- [X] T020 [US1] `JooqPermissionResolver` (`infra-persistence/.../persistence/JooqPermissionResolver.java`): SQL auf `WITH RECURSIVE reachable_roles` umstellen (Basis = `active_roles`; Rekursion über `role_inheritance`; `UNION`-Dedup); `candidate` joint `reachable_roles` statt `active_roles`; Default-Zweig (`NOT EXISTS active_roles`) und Direkt-Grant-Zweig **textgleich** lassen. **Bewusster Kern-Eingriff** — siehe plan.md.
- [X] T021 Composition-Root verdrahten (`app/src/main/java/com/mcplatform/bootstrap/config/`): `JooqRoleInheritanceRepository`-Bean; in `PermissionAdminService`/`PermissionQueryService` injizieren.

### Tests — Integration (Testcontainers)

- [X] T022 [P] [US1] `JooqRoleInheritanceRepositoryIT`: CRUD; `ON CONFLICT` idempotent; `directParents`/`ancestors`/`dependents` über echte Zeilen; RESTRICT-Netz.
- [X] T023 [P] [US1] `ResolverInheritanceIT`: transitiv über echte Zeilen (A→B→C); Mehrfach-Vererbung; Diamond (Permission einmal); **geerbte Wildcard** (`feature.*`/`*` an einer Parent-Rolle matcht beim erbenden Spieler, FR-005); **geerbt von deaktivierter Parent-Rolle** (`active=false`) liefert deren Permissions trotzdem (FR-016).
- [X] T024 [P] [US2] `CyclePrecheckIT`: `wouldCreateCycle` gegen DB-`ancestors` (direkt + transitiv) korrekt.
- [X] T025 [US1] `DeleteInheritedRoleIT`: Löschen einer geerbten Rolle → 409 (Use-Case) bzw. RESTRICT (DB) (FR-015).
- [X] T026 [P] 🛡 **REGRESSION** `ResolverRegressionIT`: bei leerer `role_inheritance` lösen die bestehenden 002-Resolver-Szenarien **bit-identisch** auf; expliziter Assert „empty graph == Referenz" (FR-008/SC-002).
- [X] T027 🛡 **REGRESSION** bestehende 002/005-Permission-Test-Suiten unverändert laufen lassen → grün.

**Checkpoint**: Integration grün; Regression bewiesen.

---

## Phase 6: plugin-protocol — DTOs/Endpoints (vor api-rest, Compile-Abhängigkeit)

**Goal (US1/US4)**: additive Wire-Erweiterung (JDK-only). Modul baut eigenständig grün.

- [X] T028 [P] [US1] `record InheritanceWriteRequest(long parentRoleId)` in `plugin-protocol/src/main/java/com/mcplatform/protocol/permission/web/InheritanceWriteRequest.java`.
- [X] T029 [P] [US1] `record EffectivePermissionEntry(String permission, boolean own, List<Long> inheritedFromRoleIds)` in `plugin-protocol/.../permission/EffectivePermissionEntry.java` (FR-022a).
- [X] T030 [US4] `RoleResponse` um `List<Long> inheritedRoleIds` erweitern (`plugin-protocol/.../permission/RoleResponse.java`).
- [X] T031 [US1] `PlayerPermissionsResponse` um `List<EffectivePermissionEntry> sources` erweitern (`plugin-protocol/.../permission/PlayerPermissionsResponse.java`); `effectivePermissions` bleibt flach.
- [X] T032 [US1] `PermissionEndpoints` um `LIST_INHERITANCE` (GET `…/roles/{id}/inheritance` → `long[]`), `ADD_INHERITANCE` (POST → `RoleResponse`), `REMOVE_INHERITANCE` (DELETE `…/roles/{id}/inheritance/{parentId}` → `RoleResponse`) erweitern.

### Tests — Contract

- [X] T033 [P] [US4] `PermissionEndpointsTest` (JDK-rein): Pfade/Methoden/Typen der 3 neuen Deskriptoren.
- [X] T034 [P] [US1] `@JsonTest`-Roundtrip für `InheritanceWriteRequest`, `EffectivePermissionEntry`, erweiterte `RoleResponse` und `PlayerPermissionsResponse`.

**Checkpoint**: `:plugin-protocol:build` grün (Modul kompiliert standalone, Contract-Tests grün).

---

## Phase 7: api-rest — Inheritance-Endpoints (E2E)

**Goal (US1/US2/US3/US4)**: dünne Endpoints hinter JWT-Gate + Resolver; actor aus dem Token.

- [X] T035 [US4] Mapper aktualisieren (`api-rest/.../support/PermissionMapper.java` + `WebPermissionMapper.java`): `directParents` → `RoleResponse.inheritedRoleIds`; Provenienz → `PlayerPermissionsResponse.sources`; bestehende `RoleResponse`-Konstruktion an neue Record-Form anpassen (löst die Form-Änderung aus T030/T031 build-grün auf).
- [X] T036 [US4] `GET /api/web/permission/roles/{id}/inheritance` in `api-rest/.../WebPermissionController.java` (Gate `permission.read`, liefert direkte Eltern).
- [X] T037 [US1] `POST /api/web/permission/roles/{id}/inheritance` (`InheritanceWriteRequest`, actor aus `@AuthenticationPrincipal`).
- [X] T038 [US1] `DELETE /api/web/permission/roles/{id}/inheritance/{parentRoleId}`.
- [X] T039 [US2] Exception→HTTP-Mapping (`@ControllerAdvice`): `RoleInheritanceCycleException`/`RoleInheritedException` → 409; `DefaultRoleProtectedException` (Default-als-child) → 409.

### Tests — E2E

- [X] T040 [P] [US1] `WebPermissionInheritanceE2ETest`: add/remove/list mit Recht → 200; effektive Sicht zeigt geerbte Permissions + `sources`.
- [X] T041 [P] [US1] E2E: ohne Recht → 403; actor wird aus JWT genommen, NICHT aus dem Body.
- [X] T042 [P] [US2] E2E: Zyklus → 409; Default-als-child → 409; unbekannte Rolle → 404.
- [X] T043 [P] [US3] E2E: Vererbungs-Änderung published `ROLE_CONFIG_CHANGED` an betroffene (inkl. transitiv abhängige) Spieler; nicht betroffene Online-Spieler erhalten **kein** Signal (FR-020/SC-005).
- [X] T044 🛡 **REGRESSION**: voller `./gradlew build` grün (Backend); bestehende E2E unverändert grün.

**Checkpoint**: alle Endpoints funktional; voller Build grün.

---

## Phase 8: Polish & Publish

- [X] T045 `./gradlew :plugin-protocol:publishToMavenLocal` (damit das Plugin-Repo per `--refresh-dependencies` ziehen kann).
- [X] T046 [P] PROGRESS.md nachziehen (Vererbung als angestecktes Feature + die zwei bewussten Kern-Eingriffe, Tests grün).
- [X] T047 [P] FEATURE_INVENTORY.md-Eintrag „Rollen-Vererbung (Greenfield)" abhaken.
- [X] T048 quickstart.md-Verifikation durchspielen (Happy Path + Fehlerpfade).

---

## Dependencies & Execution Order

### Phasen-Abhängigkeiten

- **Phase 1 → 2**: Setup vor Foundational.
- **Phase 2 (Substrate)** blockiert alles (Migration für Infra-Tests, Exceptions für application/api).
- **Phase 3 (Domain)**: nur abhängig von Phase 2 (Exceptions optional); rein, kann direkt starten.
- **Phase 4 (application)**: braucht Phase 3 (`RoleHierarchy`) + T008-Port.
- **Phase 5 (infra)**: braucht Phase 4-Ports + Phase 2-Migration; T021 verdrahtet.
- **Phase 6 (protocol)**: unabhängig vom Backend-Code; MUSS vor Phase 7 stehen (api-rest kompiliert dagegen).
- **Phase 7 (api-rest)**: braucht Phase 4 (Use Cases), Phase 6 (DTOs), Phase 5 (verdrahtete Beans).
- **Phase 8**: nach Phase 7 (Publish + Doku).

### Innerhalb der Phasen

- Tests (markiert) zuerst schreiben (FAIL), dann Implementierung grün ziehen (TDD pro Schicht).
- 🛡 Regressionstests (T026, T027, T044) sind der Schutz beim Resolver-Umbau (T020) — T020 gilt erst als fertig, wenn T026/T027 grün sind.

### Parallel-Möglichkeiten

- T004/T005 vs. T006/T007 (impl vs. Testdatei) parallel; T008/T009 parallel.
- T015–T018 (Use-Case-Tests) parallel; T022–T024/T026 (IT) parallel; T028/T029 + T033/T034 parallel.
- T046/T047 (Doku) parallel.

---

## Parallel Example: Domain (Phase 3)

```bash
Task: "RoleHierarchy.reachable + resolveWithProvenance (T004)"
Task: "RoleHierarchyTest: union/mehrfach/diamond/keine-meta (T006)"
Task: "RoleHierarchyCycleTest: zyklus + visited-set (T007)"
```

---

## Implementation Strategy

### MVP (US1 — eine Rolle erbt transitiv)

T001 → T002/T003 → T004/T006 → T008/T010/T014 → T019/T020/T021 + 🛡T023/T026 → T028–T032 →
T035–T038/T040. **STOP & VALIDATE**: ein Spieler mit nur `Premium` (erbt `Base`) besitzt effektiv
`Base`-Permissions; leerer Graph bleibt bit-identisch.

### Inkrementell

1. MVP (US1) → demo.
2. + US2 (Zyklus 409, T005/T007/T024/T039/T042) — eng mit US1, faktisch zusammen.
3. + US3 (Live-Push Reverse-Closure, T012/T018/T043).
4. + US4 (Liste, T036/T033) + Polish/Publish (Phase 8).

---

## Notes

- [P] = andere Datei, keine offene Abhängigkeit.
- Zwei bewusste Eingriffe in Feature-Kernlogik (T020 Resolver-SQL, T012 Fan-out) — kein Muster-Leck,
  in plan.md Complexity Tracking begründet, durch 🛡 T026/T027/T044 + T018 abgesichert.
- application bleibt `plugin-protocol`-frei; Wire-Mapping nur in api-rest/protocol.
- Pub/Sub: bestehender `ROLE_CONFIG_CHANGED`-Pfad, KEIN neuer Channel/Event.
