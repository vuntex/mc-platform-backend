# Implementation Plan: Rank-Management-Backend (schreibende CRUD-Endpoints)

**Branch**: `005-rank-management-api` | **Date**: 2026-06-25 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/005-rank-management-api/spec.md`

## Summary

Die vom Webinterface genutzte, **JWT-abgesicherte Schreib-/Lesefläche** fürs Rollen-/Permission-/Grant-Management. Der gesamte Schreibkern (Domäne, Use-Cases, Repositories, Audit der Grants, Live-Push-Pfad) existiert bereits aus Feature **002** und wird **wiederverwendet**. Dieser Slice steckt eine neue Eingangsfläche `/api/web/permission/**` davor, die hinter dem JWT-Filter aus Feature **004** liegt, die Akteur-UUID **aus dem Token** zieht (nicht aus dem Body) und Lese-Zugriffe gegen `permission.read` gatet. Genuin neu ist außerdem die in der Klärung beschlossene **Audit-Erweiterung** auf Rollen-Stammdaten- und Rollen-Permission-Änderungen (FR-025a).

**Zentrale Reconciliation gegen den Auftrag (Prompt 6):** Mehrere Annahmen im Auftrag sind durch den Code-Bestand bzw. die Spec-Klärungen überholt — explizit aufgelöst:

| Annahme im Auftrag | Realität (Bestand/Spec) | Konsequenz im Plan |
| --- | --- | --- |
| Gating über `rank.view` / `rank.manage`, Seed nötig | **Q1-Clarify = B**: bestehende granulare `permission.*`-Rechte; ADMIN hat `*` | **Kein** `rank.*`-Vokabular, **kein** Gate-Seed |
| Tabellen `team_role_member` / `team_role_permission` | In V9 **gedroppt**; aktiv: `role`/`role_permission`/`player_role_grant`/`player_permission_grant` | Schreibmethoden existieren bereits auf diesen Repos |
| core-domain/application: Schreib-Use-Cases neu bauen | `PermissionAdminService` + Domäne existieren komplett | **Reuse**; einzige Änderung = Audit-Hooks (FR-025a) |
| Flyway **V11** | V11/V12 belegt (`web_auth`, `refresh_token`) | Nächste freie Migration = **V13** |
| Pub/Sub-Pfad ggf. mitbauen | Channel + Event + Codec + Publisher **existieren** (in `PlatformProtocol.create()` registriert) | **Kein** PlatformProtocol-Eingriff |
| Neue DTOs `RoleRequest/...` + `RankEndpoints` | DTOs existieren; Plugin ist **kein** Consumer der Web-Fläche (Frontend = Next.js/TS) | Reuse Response-DTOs; **neue Web-Request-DTOs ohne `actor`**; EndpointDescriptors zurückgestellt |

## Technical Context

**Language/Version**: Java 21 (records, `System.Logger`), Gradle multi-module.
**Primary Dependencies**: Spring Boot + Spring Security (nur `app`/`api-rest`), jOOQ + Flyway (`infra-persistence`), `plugin-protocol` (JDK-only). Wiederverwendet: Feature-002-Permission-Stack, Feature-004-JWT-Filter/`TokenVerifier`.
**Storage**: PostgreSQL (state-stored + append-only Audit, Migration V13). Redis nur als Pub/Sub-Transport (bestehender `mc:permission:changed`-Pfad).
**Testing**: JUnit; Testcontainers-Postgres für Persistenz/Audit; Vertical-Slice-E2E im `app`-Modul (REST → DB → Audit → Pub/Sub, inkl. 401/403/404/409/422-Pfade).
**Target Platform**: Linux-Server (Single-Node, Constitution §14).
**Project Type**: Hexagonal/DDD-Backend (mehrere Gradle-Module) + geteilter `plugin-protocol`-Contract.
**Performance Goals**: Live-Wirkung beim betroffenen Online-Spieler ≤ 2 s (SC-004); Schreibpfade sind kleine indexierte In-TX-Writes.
**Constraints**: core-domain framework-frei; `plugin-protocol` JDK-only & additiv; **kein** Eingriff in eine generische Klasse; **kein** PlatformProtocol-/SecurityConfig-Eingriff; `issued_by` nicht aus dem Body.
**Scale/Scope**: wenige Rollen; Grants ~ Spielerzahl × wenige Ränge; Web-Akteure = wenige Admins.

## Constitution Check

*GATE: vor Phase-0-Research geprüft; nach Phase-1-Design erneut.*

| Prinzip | Status | Begründung |
| --- | --- | --- |
| §I Backend = Wahrheit, Plugin = Client | ✅ | Backend-only Slice; Plugin liest weiter Live-Events, schreibt nichts. |
| §5 Schichten core→app→infra→api | ✅ | Domäne/Use-Cases reused; neue dünne Web-Controller in api-rest; jOOQ-Audit-Adapter in infra. |
| §6 Persistenz pro Feature begründet | ✅ | **state-stored CRUD + append-only Audit** (wie `config_audit`) — bewusst, kein event-sourced Aggregat. |
| §4/§II `plugin-protocol` JDK-only | ✅ | Nur additive JDK-only Request-DTOs; **kein** Codec/Wire-Eingriff. |
| §3 Abhängigkeitsrichtung | ✅ | Nur `app` verdrahtet; Web-Controller in api-rest hängt an application + Spring Security. |
| §12 Berechtigung backend-autoritativ über Port | ✅ | Schreiben gatet bereits im `PermissionAdminService` via `PermissionResolver`; Lesen gatet der Web-Controller via `PermissionResolver` (`permission.read`). Authorities leer → kein zweiter Mechanismus. |
| §9 Ein Feature = ein Anstecken; kein generischer Eingriff | ⚠️ | Siehe **Pattern-Leak-Ledger** — feature-lokale Service-Erweiterung (Audit-Hooks), **kein** Eingriff in generische Klasse / PlatformProtocol / SecurityConfig. |
| §10 Wiederverwenden statt neu bauen | ✅ | Reuse-Inventar in research.md (R1) — der Großteil des Slices ist Reuse. |
| §8 Main-Thread nie blockieren | ✅ (n/a Plugin) | Backend. |
| §22 Tests pro Schicht, Build grün | ✅ | DoD in quickstart.md. |

**Gate-Ergebnis**: PASS mit einem bewusst dokumentierten Punkt (Audit-Hooks im feature-eigenen `PermissionAdminService`). Kein verbotener Eingriff in eine generische Klasse.

### Pattern-Leak-Ledger (bewusst, begründet)

1. **`PermissionAdminService` wird erweitert** (Audit-Hooks für Rollen-Stammdaten + Rollen-Permissions, FR-025a). Das ist der **feature-eigene** Use-Case aus 002, **keine** generische Klasse. In-Feature-Evolution, **kein** Leck. Vorteil: beide Pfade (intern + web) auditieren atomar zur Schreiboperation.
2. **Neuer `RoleAuditPort` + `role_audit`-Tabelle (V13)** — eigenständiger Audit-Strang für Rollen-Konfiguration, analog `config_audit`/`grant_audit`. Additiv, kein Bestandseingriff.
3. **`PermissionQueryService` bleibt ungegatet**; der **Web-Controller** legt das `permission.read`-Gate davor (interner/Plugin-Lesepfad bleibt unverändert). Kein Eingriff in die Query-Klasse.

**Ausdrücklich NICHT angefasst:** `PlatformProtocol.create()`, `SecurityConfig` (Wildcard `/api/web/**` greift bereits), `MessageEnvelope`/Codecs, `PermissionResolver`-Port, die bestehenden Repositories (nur Reuse), bestehende Migrationen.

## Project Structure

### Documentation (this feature)

```text
specs/005-rank-management-api/
├── plan.md              # Dieses Dokument
├── research.md          # Phase 0 — Reuse-Inventar + 8 Pflicht-Nachweise + Entscheidungen
├── data-model.md        # Phase 1 — role_audit + wiederverwendete Entitäten
├── quickstart.md        # Phase 1 — Build/Publish/Test/DoD
├── contracts/
│   └── web-permission-api.md   # Phase 1 — /api/web/permission/** Endpunkt-Contract
└── checklists/requirements.md  # aus /speckit-specify
```

### Source Code (repository root)

```text
plugin-protocol/ (JDK-only, additiv)
└── src/main/java/com/mcplatform/protocol/permission/web/
    ├── RoleWriteRequest.java            # wie RoleRequest, OHNE actor
    ├── RolePermissionWriteRequest.java  # { permission }
    ├── GrantRoleWriteRequest.java       # { roleId, expiresInSeconds?, reason? } — kein actor
    ├── GrantPermissionWriteRequest.java # { permission, expiresInSeconds?, reason? }
    └── RevokePermissionWriteRequest.java# { permission, reason? }
    # Response-DTOs (RoleResponse, PlayerPermissionsResponse) werden WIEDERVERWENDET.

application/ (Use-Cases — REUSE; eine Erweiterung)
├── permission/PermissionAdminService.java   # + Audit-Hooks (FR-025a)
├── permission/PermissionQueryService.java   # unverändert
└── permission/port/RoleAuditPort.java       # NEU (append-only Rollen-Audit)

infra-persistence/ (jOOQ + Flyway)
├── JooqRoleAuditRepository.java             # NEU (implements RoleAuditPort)
└── resources/db/migration/V13__role_audit.sql  # NEU (nur role_audit)

api-rest/ (dünne Web-Controller + Principal)
├── WebPermissionController.java             # NEU — /api/web/permission/**
├── support/WebPermissionMapper.java         # NEU — Web-DTO → Domäne (reuse PermissionMapper für Responses)
└── security/                                # REUSE: JwtAuthenticationFilter, SecurityConfig (unverändert)

app/ (Composition Root)
└── bootstrap/config/PermissionConfig.java   # + RoleAuditPort-Bean, WebPermissionController-Deps
```

## Phasen-Überblick

- **Phase 0 (research.md):** Reuse-Inventar, die 8 Pflicht-Nachweise des Auftrags, die Design-Entscheidungen (Web-DTOs ohne actor, role_audit-Strang, player-scoped Push, EndpointDescriptors zurückgestellt).
- **Phase 1 (data-model.md, contracts/, quickstart.md):** `role_audit`-Schema + wiederverwendete Entitäten; der `/api/web/permission/**`-Contract mit Fehlercodes; Build/Test/DoD.
- **Phase 2 (`/speckit-tasks`):** phasierte Tasks — NICHT Teil dieses Kommandos.

## Offene [NEEDS CLARIFICATION] — Status

Die Spec ist clarification-frei (alle in `/speckit-clarify` aufgelöst). Im Plan zusätzlich verankert:

- **Gate-Vokabular (Q1)** → granulare `permission.*` (research R3) — **aufgelöst**.
- **Rollen-Löschung mit Mitgliedern (Q2)** → Kaskade, bereits im Bestand (research R6) — **aufgelöst**.
- **Bestandspfad-Disposition (Q3)** → paralleler Web-Pfad, interner Pfad unberührt (research R2) — **aufgelöst**.
- **Audit-Umfang (Clarify)** → role_audit-Strang (research R5, data-model) — **aufgelöst**.
- **Letzter-Admin-Schutz** → bewusst keine Sperre (research R7) — **bewusst verschoben/akzeptiert**.
- **History-Lesen** → späterer Slice (research R8) — **bewusst verschoben**.
- **Live-Push-Granularität (Spec-Punkt 5)** → player-scoped, bestehendes Event (research R4) — **aufgelöst**.
