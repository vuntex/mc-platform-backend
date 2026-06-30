# Implementation Plan: Rollen-Vererbung (Permission-Inheritance)

**Branch**: `006-role-inheritance` | **Date**: 2026-06-25 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/006-role-inheritance/spec.md`

## Summary

Eine Rolle kann die **Permissions** anderer Rollen erben (Many-to-Many, explizit gesetzt, transitiv,
reine Union ohne Gewichtung/Negation). Die effektiven Permissions eines Spielers werden zur Union über
seine direkten aktiven Rollen **inklusive deren transitiver Vererbungs-Hülle** plus seinen
Direkt-Grants. Es entsteht **kein** paralleler Resolver: die Transitivität wird (a) im Hot-Path direkt
in die bestehende `JooqPermissionResolver`-SQL als rekursive CTE eingezogen und (b) im View-Path als
neue **reine Domain-Schicht** (`RoleHierarchy`) vor das unveränderte `EffectivePermissions` gesetzt.
Beide Pfade bleiben — wie heute bei `PermissionMatcher` — im Gleichschritt und sind durch
Regressionstests bei leerem Graph bit-identisch zum Status quo abgesichert.

Persistenz: eine neue `role_inheritance`-Kantentabelle (V15), state-stored CRUD + Audit. Zyklus-Schutz
zweistufig: Vorab-Check beim Setzen (409) + defensiver Visited-Set/UNION-Dedup in der Auflösung.
Live-Push: bestehender player-scoped `ROLE_CONFIG_CHANGED`-Pfad, Fan-out erweitert auf die transitive
**Reverse-Closure** (alle Rollen, die die geänderte Rolle erben) — gilt auch für bestehende
role-permission-Edits (FR-020a). Kein neuer Pub/Sub-Channel, kein neuer Event-Typ.

## Technical Context

**Language/Version**: Java 21 (Records, Text Blocks, `System.Logger`), Gradle Multi-Modul.

**Primary Dependencies**: Spring Boot (nur api-rest/app), jOOQ + Flyway (infra-persistence), Lettuce
(infra-cache/realtime Pub/Sub), `plugin-protocol` (JDK-only, Maven Local). core-domain bleibt
framework-frei (Constitution §5).

**Storage**: PostgreSQL. Neue Tabelle `role_inheritance`. Keine Änderung an bestehenden Migrationen.

**Testing**: JUnit 5. Domain-Unit (ohne DB, `RoleHierarchy`), Use-Case mit Fakes
(`PermissionAdminService`-Erweiterung), Integration mit Testcontainers (jOOQ-Repo + Resolver-SQL),
E2E gegen die `/api/web/permission/**`-Fläche inkl. Fehlerpfade (409/404/403).

**Target Platform**: Linux-Server (Backend). Single-Server (Constitution §14).

**Project Type**: Hexagonal/DDD Backend (Multi-Modul) + geteiltes `plugin-protocol`. Kein Frontend in
diesem Slice.

**Performance Goals**: Permission-Einzel-Check (`hasPermission`) bleibt ein DB-Round-Trip; die
rekursive CTE faltet die Vererbungs-Hülle in derselben Query. Für realistische Graphen (Ketten ≤ ~10,
Diamanten) kein für Spieler spürbarer Mehraufwand (SC-004).

**Constraints**: core-domain framework-frei; Main-Thread des Plugins irrelevant (Backend-Slice);
plugin-protocol bleibt dependency-frei; Auflösung MUSS auch bei Restzyklus terminieren (FR-010a).

**Scale/Scope**: Wenige Dutzend Rollen, geringe Kanten-Anzahl, seltene Änderungen (Config-artig →
state-stored CRUD gerechtfertigt, Constitution §6).

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Prinzip | Bewertung | Status |
|---|---|---|
| §5 Schichtung (Domäne framework-frei) | Transitive Logik als reine `RoleHierarchy` in core-domain; jOOQ nur in infra; Spring nur in api-rest/app. | ✅ PASS |
| §6 Persistenz-Wahl begründet | `role_inheritance` = selten ändernde Config → **state-stored CRUD + Audit** (kein Event-Sourcing; das bleibt Economy/§13). Konsistent zum restlichen Permission-Modell. | ✅ PASS |
| §9 „Ein Feature = ein Anstecken" / Muster-Leck | Kein generischer Baustein (FeatureCache/EventBus/MenuBuilder/MessageEnvelope/Scheduler) wird geändert. **Zwei bewusste Eingriffe in bestehende FEATURE-Kernlogik** (Resolver-SQL, `PermissionAdminService`-Fan-out) — kein Leck (korrekte Heimat), aber explizit benannt + regressionsgesichert. Siehe Complexity Tracking. | ✅ PASS (dokumentiert) |
| §10 Wiederverwendung vor Neubau | Resolver-Port, `EffectivePermissions`, `PermissionMatcher`, Repos, Audit-Port, Pub/Sub-Pfad werden wiederverwendet. Kein paralleler Resolver. | ✅ PASS |
| §12 Berechtigungen backend-autoritativ | Gates über `PermissionResolver` (`permission.read` lesend, neues `permission.role.edit.inherit` schreibend). Kein Spring-Security-Role-Gate. | ✅ PASS |
| §14 Single-Server | Kein Distributed-Locking; Pub/Sub bleibt Backend↔Plugin-Live-Pfad. | ✅ PASS |
| §19 Vertical Slice | Domäne → Persistenz → REST → protocol in einem Slice; Frontend bewusst out-of-scope. | ✅ PASS |
| §22 Tests pro Schicht | Domain/Use-Case/Integration(Testcontainers)/E2E inkl. Fehlerpfade; `gradlew build` grün als DoD. | ✅ PASS |

**Ergebnis: PASS.** Keine ungerechtfertigten Verstöße. Die zwei Kern-Eingriffe sind unten im
Complexity Tracking begründet.

## Project Structure

### Documentation (this feature)

```text
specs/006-role-inheritance/
├── plan.md              # This file
├── research.md          # Phase 0 — die 7 Pflicht-Nachweise (Resolver-Umbau, Default, Zyklus, Live-Push, Reuse, protocol, Flyway)
├── data-model.md        # Phase 1 — role_inheritance + Domain-/DTO-Modell
├── quickstart.md        # Phase 1 — Build/Test/publish-Reihenfolge + manuelle Verifikation
├── contracts/
│   └── inheritance-endpoints.md   # Phase 1 — REST-Endpoints + protocol-DTOs
└── tasks.md             # Phase 2 (/speckit-tasks — NICHT hier erzeugt)
```

### Source Code (repository root)

```text
core-domain/src/main/java/com/mcplatform/domain/permission/
├── RoleHierarchy.java            # NEU — reine transitive Auflösung: closure + Provenienz + Zyklus-Check (Visited-Set)
├── EffectivePermissions.java     # UNVERÄNDERT (Regression-Anker) — unioniert weiterhin eine fertige Permissions-Map
├── PermissionMatcher.java        # UNVERÄNDERT — Wildcard-Semantik
└── PermissionChangeType.java     # UNVERÄNDERT — ROLE_CONFIG_CHANGED wiederverwendet

application/src/main/java/com/mcplatform/application/permission/
├── PermissionAdminService.java   # ERWEITERT — addInheritance/removeInheritance + Fan-out auf Reverse-Closure (FR-020/020a); deleteRole prüft Vererbungs-Abhängige (FR-015)
├── PermissionQueryService.java   # ERWEITERT — effektive Sichten nutzen RoleHierarchy (transitiv + Provenienz)
└── port/
    └── RoleInheritanceRepository.java   # NEU — add/remove/list-Kanten, ancestors(parent), dependents(role)

infra-persistence/src/main/java/com/mcplatform/persistence/
├── JooqRoleInheritanceRepository.java   # NEU — Kanten-CRUD + rekursive ancestors/dependents-CTEs
└── JooqPermissionResolver.java          # ERWEITERT — rekursive `reachable_roles`-CTE (Hot-Path)

infra-persistence/src/main/resources/db/migration/
└── V15__role_inheritance.sql            # NEU — nur die Kantentabelle

api-rest/src/main/java/com/mcplatform/api/rest/
├── WebPermissionController.java         # ERWEITERT — /api/web/permission/roles/{id}/inheritance (GET/POST/DELETE)
└── support/ (PermissionMapper/WebPermissionMapper)  # ERWEITERT — RoleResponse.inheritedRoleIds + Provenienz-Mapping

plugin-protocol/src/main/java/com/mcplatform/protocol/permission/
├── RoleResponse.java                    # ERWEITERT (additiv) — + inheritedRoleIds
├── EffectivePermissionEntry.java        # NEU — {permission, own, inheritedFromRoleIds} (Provenienz, FR-022a)
├── PlayerPermissionsResponse.java       # ERWEITERT (additiv) — + sources: List<EffectivePermissionEntry>
├── PermissionEndpoints.java             # ERWEITERT — + LIST/ADD/REMOVE_INHERITANCE Deskriptoren
└── web/InheritanceWriteRequest.java     # NEU — {parentRoleId}
```

**Structure Decision**: Bestehende Hexagonal-Multi-Modul-Struktur des Permission-Systems (002/005)
wird 1:1 weitergeführt. Vererbung steckt sich als neues Domain-Konstrukt + neuer Port + neue Tabelle
+ erweiterte Endpoints an; die beiden bestehenden Kern-Klassen (`JooqPermissionResolver`,
`PermissionAdminService`) werden bewusst erweitert (siehe Complexity Tracking), nicht dupliziert.

## Phasen-Nachweise (Kurzfassung — Details in research.md)

1. **Resolver-Umbau**: Hot-Path bekommt eine `WITH RECURSIVE reachable_roles`-CTE (Basis = direkte
   aktive Rollen, Rekursion über `role_inheritance`, `UNION`-Dedup). Bei leerem Graph liefert der
   rekursive Term nichts → `reachable_roles == active_roles` → **bit-identisch** zu heute. Transitivität
   sitzt im Resolver-**Kern** (SQL) für den Check und als **vorgelagerte** reine Domain-Schicht
   (`RoleHierarchy`) für die View — `EffectivePermissions` bleibt unangetastet.
2. **Default-Zusammenspiel**: Der Default-Zweig bleibt gegated auf `NOT EXISTS (active_roles)` (BASIS,
   nicht reachable). Premium erbt Default → Default-Permissions kommen über `reachable_roles` rein;
   Premium erbt Default nicht → keine Default-Basis (CL-1-Falle, technisch korrekt); 0 Rollen → Default-
   Fallback feuert (Default ist Blatt, CL-3).
3. **Zyklus-Schutz**: Vorab-Check (ist `child` aus `parent` erreichbar? oder `child==parent`?) → 409
   im Use-Case; defensiv Visited-Set (Domain) + `UNION`-Dedup/`CHECK`-Constraint (SQL/DB).
4. **Cache/Live-Push**: Kein Backend-Cache. Fan-out = Holder von `{R} ∪ dependents(R)` (Reverse-Closure)
   je ein `ROLE_CONFIG_CHANGED`; gilt auch für role-permission-Edits (FR-020a). Player-scoped,
   bestehender Pfad, kein neues Signal.
5. **Reuse**: Resolver-Port/`EffectivePermissions`/`PermissionMatcher`/Repos/Audit/Pub/Sub
   wiederverwendet; neu nur `RoleHierarchy`, `RoleInheritanceRepository`(+Jooq), Tabelle, DTOs/Endpoints.
6. **plugin-protocol**: additive DTOs (`InheritanceWriteRequest`, `EffectivePermissionEntry`,
   `RoleResponse.inheritedRoleIds`, `PlayerPermissionsResponse.sources`) + Endpoint-Deskriptoren →
   `publishToMavenLocal`. Pub/Sub: bestehender Pfad, **keine** neue PlatformProtocol-Zeile.
7. **Flyway**: `V15__role_inheritance.sql`, nur die Kantentabelle.

## Complexity Tracking

> Zwei bewusste Eingriffe in bestehende **Feature-Kernlogik** (kein generischer Baustein → kein
> Muster-Leck nach §9, aber explizit zu benennen und mit Tests abzusichern).

| Eingriff | Warum nötig | Warum keine einfachere/parallele Alternative |
|---|---|---|
| `JooqPermissionResolver`-SQL um rekursive CTE erweitern | Transitive Auflösung ist genau die Aufgabe des Resolvers; der Hot-Path (`hasPermission`) muss die Vererbungs-Hülle kennen. | Ein paralleler „Inheritance-Resolver" verstieße gegen §10 (kein Doppel-Resolver) und würde die Lockstep-Garantie zerstören. Korrekte Heimat = diese Klasse. Regression: Charakterisierungstests mit leerem Graph (bit-identisch). |
| `PermissionAdminService` erweitern (Inheritance-Use-Cases + Fan-out auf Reverse-Closure, auch für bestehende role-permission-Edits) | Vererbungs-Writes brauchen Gate+Audit+Publish im selben Muster wie alle Rollen-Mutationen; FR-020a verlangt, dass ein `Base`-Permission-Edit auch `Premium`-Träger live erreicht. | Eine separate Service-Klasse würde die etablierte „alle Rollen-Mutationen hier"-Kohäsion (002/005) brechen und den Publish-Pfad duplizieren. `publishToHolders(id)` → `publishToRoleAndDependents(id)` ist eine additive Erweiterung, regressionsgesichert. |
