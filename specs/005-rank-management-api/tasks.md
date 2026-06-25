---
description: "Task list — Rank-Management-Backend (005-rank-management-api)"
---

# Tasks: Rank-Management-Backend (schreibende CRUD-Endpoints)

**Input**: Design documents from `/specs/005-rank-management-api/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/web-permission-api.md, quickstart.md

**Tests**: Tests sind in der Spec/Quickstart ausdrücklich gefordert (DoD pro Schicht) → Test-Tasks enthalten.

**Leitplanken (aus plan.md/research.md — NICHT aus überholten Auftragsannahmen):**
- Gating = granulare `permission.*` (kein `rank.*`); **kein** Gate-Seed (ADMIN hat `*`, MODERATOR keins → 403).
- Pub/Sub-Pfad **existiert** → nur nutzen; **kein** Event/Codec/`PlatformProtocol`-Neubau.
- **core-domain unverändert** (Reuse); Schreib-Use-Cases + Repos existieren — einzige App-Änderung = Audit-Hooks.
- Migration = **V13** (V11/V12 belegt). Web-Request-DTOs **ohne `actor`**. **Kein** `SecurityConfig`-Eingriff.

**Globale Definition of Done (gilt für jede Task):** betroffene Tests grün · `./gradlew build` (Backend) grün · bei `plugin-protocol`-Änderung `:plugin-protocol:publishToMavenLocal` · am Feature-Ende PROGRESS.md + FEATURE_INVENTORY.md nachgezogen · keine generische Klasse geändert.

## Format: `[ID] [P?] [Story] Description`
- **[P]**: parallelisierbar (andere Datei, keine offene Abhängigkeit)
- **[Story]**: US1/US2/US3 (nur in Story-Phasen)

---

## Phase 1: Setup (Baseline)

- [x] T001 Build-Baseline sichern: `./gradlew build` grün vor jeder Änderung als Regressions-Referenz festhalten (insb. Economy/Punishment/Report/002-Permission/004-WebAuth-Suiten). **DoD**: grüner Lauf dokumentiert.

---

## Phase 2: Foundational (blockiert ALLE User Stories)

**Purpose**: Der quer zu allen Stories liegende, neue Unterbau — Audit-Strang, Web-DTOs, Controller-Gerüst, Verdrahtung. Erst danach starten die Story-Phasen.

- [x] T002 [P] Migration `infra-persistence/src/main/resources/db/migration/V13__role_audit.sql` anlegen: `role_audit` (id, action, role_id, role_name, permission, actor, at) + Index `idx_role_audit_role` exakt nach data-model.md. **Keine** bestehende Migration ändern, **kein** Gate-Seed. **DoD**: Flyway migriert sauber im jOOQ-Codegen-Container; `./gradlew build` grün.
- [x] T003 [P] Port `application/src/main/java/com/mcplatform/application/permission/port/RoleAuditPort.java` (Enum `Action{ROLE_CREATE,ROLE_UPDATE,ROLE_DELETE,ROLE_PERMISSION_ADD,ROLE_PERMISSION_REMOVE}`, `record(...)`) nach data-model.md. **DoD**: kompiliert, framework-frei.
- [x] T004 [P] [proto] Web-Request-DTOs in `plugin-protocol/src/main/java/com/mcplatform/protocol/permission/web/`: `RoleWriteRequest`, `RolePermissionWriteRequest`, `GrantRoleWriteRequest`, `GrantPermissionWriteRequest`, `RevokePermissionWriteRequest` — JDK-only, **ohne `actor`** (Felder exakt nach contracts/). **DoD**: kompiliert; keine Fremd-Imports.
- [x] T005 [proto] `./gradlew :plugin-protocol:publishToMavenLocal` und verifizieren, dass der publizierte POM **keinen** `<dependencies>`-Block hat. **DoD**: Artefakt in `~/.m2`, POM-Check bestanden.
- [x] T006 `JooqRoleAuditRepository` in `infra-persistence/src/main/java/com/mcplatform/persistence/JooqRoleAuditRepository.java` (implements `RoleAuditPort`, In-TX-Insert, append-only) + Testcontainers-Test `infra-persistence/src/test/java/com/mcplatform/persistence/JooqRoleAuditRepositoryTest.java` (jede Aktion schreibt korrekte Zeile; `permission` nur bei ADD/REMOVE). **DoD**: jOOQ-Test grün. *(abh. T002, T003)*
- [x] T007 Audit-Hooks in `application/src/main/java/com/mcplatform/application/permission/PermissionAdminService.java`: nach erfolgreichem Write je `createRole`/`updateRole`/`deleteRole`/`addRolePermission`/`removeRolePermission` ein `roleAudit.record(...)` (Actor = übergebene UUID). Konstruktor um `RoleAuditPort` erweitern. **Pattern-Leak-Ledger #1** (feature-lokal). **DoD**: bestehende `PermissionAdminServiceTest` grün + erweitert (je Operation genau ein role_audit-Eintrag mit korrektem Actor; Default-Schutz/Kaskade unverändert grün). *(abh. T003)*
- [x] T008 `WebPermissionController`-Gerüst in `api-rest/src/main/java/com/mcplatform/api/rest/WebPermissionController.java`: Klasse unter `/api/web/permission`, Akteur über `@AuthenticationPrincipal PlayerId`, privater Lese-Gate-Helper `requireRead(actor)` → `PermissionResolver.hasPermission(actor, PermissionAdminService.READ)` sonst `PermissionDeniedException`. Konstruktor: `PermissionAdminService`, `PermissionQueryService`, `PermissionResolver`, `Clock`. Noch **ohne** Endpunkt-Methoden. **DoD**: kompiliert; Bean lädt. *(abh. T004)*
- [x] T009 `WebPermissionMapper` in `api-rest/src/main/java/com/mcplatform/api/rest/support/WebPermissionMapper.java`: `RoleWriteRequest` → Domänen-`Role`-Draft (isDefault nie aus API); Grant-Write-DTOs → Service-Parameter; Responses über den **bestehenden** `PermissionMapper`. **DoD**: kompiliert; Unit-Test für `expires_at`-Berechnung (expiresInSeconds → Instant via Clock).
- [x] T010 Composition-Wiring in `app/src/main/java/com/mcplatform/bootstrap/config/PermissionConfig.java`: `RoleAuditPort`-Bean (JooqRoleAuditRepository), `PermissionAdminService` um Audit-Port erweitern, `WebPermissionController`-Deps. Bestätigen: `SecurityConfig` **unverändert** (Wildcard `/api/web/**` deckt die neue Fläche). **DoD**: Context lädt; `./gradlew build` grün. *(abh. T006, T007, T008, T009)*

**Checkpoint:** Unterbau steht — Audit schreibt, Controller-Gerüst + Gate + Mapper + Wiring da. Stories können starten.

---

## Phase 3: User Story 1 — Rollen verwalten (P1) 🎯 MVP

**Goal**: Berechtigter Admin legt Rollen an, bearbeitet/löscht sie, liest Liste/Detail — JWT-gegatet, auditiert.
**Independent Test**: Mit Admin-JWT Rolle anlegen → in Liste sehen → bearbeiten → löschen; ohne Recht 403; ohne JWT 401.

- [x] T011 [US1] Rollen-Endpunkte in `WebPermissionController` ergänzen: `GET /roles` (+read-gate), `GET /roles/{id}` (+read-gate), `POST /roles`, `PUT /roles/{id}`, `DELETE /roles/{id}` → delegieren an `PermissionAdminService` (actor = Token) bzw. `PermissionQueryService`. Body via `WebPermissionMapper`. **DoD**: kompiliert; manueller Smoke (quickstart) ok. *(abh. Phase 2)*
- [x] T012 [P] [US1] Domänen-Reuse-Beleg: bestätigen, dass Namens-Eindeutigkeit, Default-Schutz und Lösch-Kaskade **bereits** in Domäne/`PermissionAdminService` durchgesetzt sind (keine neue core-domain-Klasse). Bestehende Domänen-Unit-Tests grün lassen. **DoD**: Beleg im PR-Text; kein neuer Domain-Code.
- [x] T013 [US1] E2E `app/src/test/java/com/mcplatform/web/WebRoleManagementE2ETest.java` (Testcontainers Postgres+Redis): 401 ohne JWT; 403 ohne `permission.role.create/edit/delete` (MODERATOR-Token); 200 create/update (ADMIN via `*`); Default-Rang deaktivieren/löschen → **409** `default_role_protected`; Name-Kollision → **409** `role_name_conflict`; unbekannte Rolle → **404**; **Rolle-löschen-mit-Mitgliedern → Kaskade** (REVOKE + `grant_audit` + player-scoped Publish je Halter); `role_audit` enthält je Operation genau einen Eintrag mit Actor = Token-UUID; Lesen mit `permission.read`. **DoD**: Test grün.

**Checkpoint:** Rollen-CRUD über die Web-Fläche vollständig & getestet — eigenständig lieferbares MVP.

---

## Phase 4: User Story 2 — Permissions einer Rolle pflegen (P1)

**Goal**: Permission zu Rolle hinzufügen/entfernen + lesen; wirkt live für alle aktiven Träger.
**Independent Test**: Test-Rolle Permission hinzufügen → in Permission-Liste sehen → entfernen; aktive Träger erhalten Live-Event.

- [x] T014 [US2] Rollen-Permission-Endpunkte in `WebPermissionController`: `GET /roles/{id}/permissions` (+read-gate), `POST /roles/{id}/permissions`, `DELETE /roles/{id}/permissions` (Body `RolePermissionWriteRequest`) → `PermissionAdminService.add/removeRolePermission`. **DoD**: kompiliert; Smoke ok. *(abh. Phase 2)*
- [x] T015 [US2] E2E `app/src/test/java/com/mcplatform/web/WebRolePermissionE2ETest.java`: 403 ohne `permission.role.edit`; gültige Permission hinzufügen → 200 + `role_audit` `ROLE_PERMISSION_ADD` + **player-scoped `ROLE_CONFIG_CHANGED`** an aktive Träger (Pub/Sub-Assertion auf `mc:permission:changed`); ungültige Permission-Syntax → **422** `permission_invalid`; **doppeltes Hinzufügen** derselben Permission → genau ein Eintrag, kein Fehler (FR-015); Entfernen idempotent (kein Fehler); Lesen mit `permission.read`. **DoD**: Test grün.

**Checkpoint:** Rollen-Permission-Pflege live & auditiert.

---

## Phase 5: User Story 3 — Grants an Spieler verwalten (P1)

**Goal**: Rolle/Permission an Spieler granten (optional befristet, mit reason), widerrufen, effektiven Stand lesen; issued_by = Token.
**Independent Test**: Per UUID Rolle granten → in `effective` sehen → widerrufen; funktioniert auch für nie-gejointe UUID.

- [x] T016 [US3] Grant-Endpunkte in `WebPermissionController`: `POST /players/{uuid}/roles`, `DELETE /players/{uuid}/roles/{roleId}`, `POST /players/{uuid}/permissions`, `DELETE /players/{uuid}/permissions`, `GET /players/{uuid}/effective` (+read-gate) → `PermissionAdminService` grant/revoke (actor = Token) bzw. `PermissionQueryService.effectiveFor`. **DoD**: kompiliert; Smoke ok. *(abh. Phase 2)*
- [x] T017 [US3] Grant-Write-DTOs im `WebPermissionMapper` abbilden (expiresInSeconds → expires_at via Clock; reason; **kein actor aus Body**). **DoD**: Mapper-Unit-Test (inkl. permanent = null). *(abh. T009)*
- [x] T018 [US3] E2E `app/src/test/java/com/mcplatform/web/WebGrantManagementE2ETest.java`: 403 ohne `permission.grant.role`/`permission.grant.permission`; Grant mit/ohne Ablauf; `expires_at` in Vergangenheit → **422**; **issued_by == Token-UUID** (strukturell unfälschbar — Web-DTO hat kein actor-Feld); **Grant an nie-gejointe UUID** akzeptiert + in `effective` sichtbar; Re-Grant = Upsert (kein Duplikat); Widerruf idempotent (kein Audit/Publish bei Nicht-Existenz); erfolgreicher Grant/Revoke → `GRANT_ADDED`/`GRANT_REVOKED` published + `grant_audit`-Eintrag mit Actor; **Push-Ausfall** (Publisher-Fake wirft) → Write bleibt committed, Antwort 200, Fehler nur geloggt (FR-027/SC-008); Lesen `effective` mit `permission.read`. **DoD**: Test grün.

**Checkpoint:** Grant-Verwaltung vollständig; alle drei Stories live.

---

## Phase 6: Polish & Cross-Cutting

- [x] T019 [P] Contract-/DTO-Test `app/src/test/java/com/mcplatform/web/WebPermissionDtoJsonTest.java` (`@JsonTest`): JSON-Roundtrip der 5 Web-Request-DTOs (exakte Feldnamen, kein `actor`-Feld vorhanden). **DoD**: Test grün. *(Hinweis: kein EndpointDescriptor-Test — research R9: bewusst zurückgestellt, kein Java-Consumer.)*
- [x] T020 [P] Regression (SC-007): Economy-, Punishment-, Report-, 002-Permission- und 004-WebAuth-Suiten unverändert grün. **DoD**: alle grün.
- [x] T021 [P] PROGRESS.md Status-Abschnitt nachziehen (neue `/api/web/permission/**`-Fläche, `role_audit`/V13, Reuse des 002-Stacks, kein PlatformProtocol/SecurityConfig-Eingriff). **DoD**: Abschnitt ergänzt.
- [x] T022 [P] FEATURE_INVENTORY.md-Eintrag für Rank-Management-Web abhaken. **DoD**: Eintrag aktualisiert.
- [x] T023 Abschluss-Check: `./gradlew build` (Backend) grün; bestätigen, dass `PlatformProtocol.create()`, `SecurityConfig`, `PermissionResolver`-Port, bestehende Repositories und alle generischen Klassen **unverändert** sind (nur die im Ledger genannte feature-lokale Service-Erweiterung). **DoD**: Build grün + Bestätigung im PR-Text.

---

## Dependencies & Reihenfolge

- **Phase 1 → Phase 2 → (Phase 3, 4, 5) → Phase 6.**
- **Phase 2 blockiert alle Stories.** Intern: T002→T006; T003→T006/T007; T004→T005/T008; {T006,T007,T008,T009}→T010.
- **Stories US1/US2/US3** sind logisch unabhängige Inkremente, teilen sich aber die Datei `WebPermissionController.java` (Endpunkt-Tasks T011/T014/T016 daher sequenziell auf dieser Datei, nicht [P] untereinander). Die E2E-Tests (T013/T015/T018, je eigene Datei) sind untereinander [P].
- **Phase 6** nach Abschluss der Stories.

## Parallel-Möglichkeiten

- **Phase 2 Start parallel:** T002, T003, T004 gleichzeitig (verschiedene Module/Dateien).
- **E2E-Tests parallel:** T013, T015, T018 (verschiedene Dateien), sobald die jeweiligen Endpunkte stehen.
- **Polish parallel:** T019, T020, T021, T022.

## MVP-Scope

**User Story 1 (Rollen-CRUD)** = MVP: eigenständig lieferbar und testbar (Phase 1 + 2 + 3). US2/US3 sind additive Inkremente auf demselben Controller.

## Story-Übersicht

| Story | Tasks | Eigenständiger Test |
| --- | --- | --- |
| US1 Rollen | T011–T013 | Rolle anlegen/ändern/löschen + 401/403/404/409 + Audit |
| US2 Rollen-Permissions | T014–T015 | Permission add/remove + Live-Push + 422 |
| US3 Grants | T016–T018 | Grant/Revoke + issued_by=Token + nie-gejointe UUID + 422 |
| Foundational | T002–T010 | Audit-Strang, Web-DTOs, Controller-Gerüst, Wiring |
| Polish | T019–T023 | DTO-JSON, Regression, Doku, Build/Ledger-Check |
