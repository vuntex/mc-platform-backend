# Implementation Plan: Permission-/Rank-System (Foundation, Phase 1)

**Branch**: `002-permission-rank-system` | **Date**: 2026-06-23 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/002-permission-rank-system/spec.md`

## Summary

Die bestehende minimale `JooqPermissionResolver`-Implementierung (heute: `team_role_member` +
`team_role_permission`, ein Rang pro Spieler, read-only, kein Ablauf) wird hinter dem **unveränderten**
`PermissionResolver`-Port zur vollständigen, einheitlichen Permission-Welt ausgebaut: flache
Rollen-CRUD, mehrere zeitlich unabhängige Rang-Grants pro Spieler, direkte Permission-Grants (je mit
optionalem Ablauf), additive Auflösung mit Wildcards (`feature.*`, `*`), deterministische Anzeige-Wahl
und **Live-Entzug** bei Ablauf/Änderung über Redis-Pub/Sub. **State-stored** (Reports-Muster), nicht
event-sourced. Der reine Auflösungskern (Union, Wildcard, `isActive`, Anzeige-Tie-Break) lebt
framework-frei in core-domain; `hasPermission` bleibt als indexierte SQL-Query der Hot-Path.

## Technical Context

**Language/Version**: Java 21 (records, `System.Logger`), Gradle multi-module.
**Primary Dependencies**: Spring Boot (nur `app`/`api-*`), jOOQ + Flyway (`infra-persistence`), Lettuce
via `infra-cache` (`RedisCacheAdapter`), `plugin-protocol` (JDK-only). Spring Scheduling (neu aktiviert).
**Storage**: PostgreSQL (state-stored, V9-Migration). Redis nur als Pub/Sub-Transport.
**Testing**: JUnit; Testcontainers-Postgres für Persistenz/Resolver; Vertical-Slice-E2E im `app`-Modul.
**Target Platform**: Linux-Server (Single-Node, Constitution §14).
**Project Type**: Hexagonal/DDD-Backend (mehrere Gradle-Module) + geteilter `plugin-protocol`-Contract.
**Performance Goals**: `hasPermission` = zwei kleine indexierte JOIN-Queries; Live-Ablauf wirkt
≤ 60 s (SC-004). 200+ Spieler (PROGRESS.md).
**Constraints**: Port-Signatur unveränderlich; core-domain framework-frei; `plugin-protocol` JDK-only;
genau **eine** erlaubte Zeile geteilten Codes (`PlatformProtocol.create()`).
**Scale/Scope**: wenige Rollen, Grants in der Größenordnung Spielerzahl × wenige Ränge.

## Constitution Check

*GATE: vor Phase-0-Research geprüft; nach Phase-1-Design erneut.*

| Prinzip | Status | Begründung |
| --- | --- | --- |
| §I Backend = Wahrheit, Plugin = Client | ✅ | Auflösung & Schreibpfade rein Backend; Plugin liest Live-Events. |
| §5 Schichten core→app→infra→api | ✅ | Reiner Kern in core-domain; Use-Cases in application; jOOQ in infra; dünne Controller in api-rest. |
| §6 Persistenz pro Feature begründet | ✅ | **state-stored** begründet (R1, FR-024) — config-artig, kein Geld/Urteil. |
| §4/§II `plugin-protocol` JDK-only | ✅ | Neue DTOs/Event/Codec ohne Fremd-Deps; Wire über `MessageEnvelope`/`MessageCodec`. |
| §3 Abhängigkeitsrichtung | ✅ | Nur `app`-Composition-Root berührt protocol+cache zugleich (Publisher), wie bei Reports. |
| §12 Berechtigung backend-autoritativ über Port | ✅ | Port **unverändert** (R3); Impl reicher; Schreibpfade gaten via Port (wie PunishmentService). |
| §9 Ein Feature = ein Anstecken; kein generischer Eingriff | ⚠️ | Siehe **Pattern-Leak-Ledger** — eine erlaubte Codec-Zeile + erstmaliges `@EnableScheduling` (Composition-Root) + Ablösung der feature-eigenen `team_role_*`-Tabellen. Kein Eingriff in generische Klassen. |
| §10 Wiederverwenden statt neu bauen | ✅ | Reuse-Inventar in research.md R9. |
| §8 Main-Thread nie blockieren | ✅ (n/a Plugin) | Backend; Scheduler läuft async, kein Bukkit-Thread. |
| §22 Tests pro Schicht, Build grün | ✅ | DoD in quickstart.md. |

**Gate-Ergebnis**: PASS mit drei bewusst dokumentierten Punkten (siehe Ledger) — keiner ist ein
verbotener Eingriff in eine generische Klasse.

### Pattern-Leak-Ledger (bewusst, begründet)
1. **`PlatformProtocol.create()` +1 Zeile** (Codec-Registrierung). Die laut Klassenkommentar
   ausdrücklich vorgesehene Plug-in-Stelle — **kein** Leck.
2. **`@EnableScheduling` (neu) im `app`-Modul** für den periodischen Ablauf-Sweep. Erster Scheduler im
   Backend; lebt im Composition-Root (`SchedulingConfig`), nicht in einer generischen Klasse. Die
   Sweep-Logik selbst ist framework-frei (`GrantExpiryService`).
3. **Ablösung `team_role_member`/`team_role_permission`** (R2). Diese Tabellen gehören der
   Permission-Welt, die dieses Feature laut Auftrag ausbaut — in-scope, **kein** Leck. Folge: drei
   **Test**-Grant-Helfer werden auf das neue Modell umgestellt (Produktionscode + Port unberührt;
   SC-001-Intention gewahrt).

## Project Structure

### Documentation (this feature)
```text
specs/002-permission-rank-system/
├── plan.md              # Dieses Dokument
├── research.md          # Phase 0 — Entscheidungen R1..R10
├── data-model.md        # Phase 1 — Entitäten + V9-Tabellen
├── contracts/
│   └── permission-contracts.md   # REST-Endpoints + Pub/Sub-Event + Fehlercodes
├── quickstart.md        # Build/Test/DoD/Smoke
├── checklists/requirements.md
└── tasks.md             # Phase 2 (/speckit.tasks — NICHT hier)
```

### Source Code (neue/geänderte Pfade)
```text
core-domain/.../domain/permission/        # NEU, framework-frei
├── Role.java  RoleId.java
├── RoleGrant.java  PermissionGrant.java
├── PermissionMatcher.java                # reine Wildcard/Union-Funktion (testbarer Kern)
├── EffectivePermissions.java  RankDisplay.java
├── PermissionChangeType.java             # Domänen-Enum (Publisher-Port nutzt ihn, kein protocol-Import)
└── RoleValidationException.java  InvalidGrantException.java

application/.../application/permission/   # NEU
├── PermissionAdminService.java           # Rollen-CRUD, role_permission-Config, Grant/Revoke — gatet via Port
├── PermissionQueryService.java           # effektive Permissions + Anzeige (nutzt core-domain)
├── GrantExpiryService.java               # Sweep: findet abgelaufene Grants → inactive + EXPIRE-Audit + Publish
└── port/
    ├── RoleRepository.java  PlayerGrantRepository.java  GrantAuditPort.java
    ├── PermissionChangePublisher.java     # Pub/Sub-Port (wie ReportPublisher)
    └── RoleNotFoundException.java  RoleNameConflictException.java  DefaultRoleProtectedException.java
# application/.../security/PermissionResolver.java          # UNVERÄNDERT (Port)

infra-persistence/.../persistence/
├── JooqPermissionResolver.java           # UMGESCHRIEBEN: Union + Default-Fallback + Wildcard-SQL + now()-Filter
├── JooqRoleRepository.java               # NEU
└── JooqPlayerGrantRepository.java        # NEU (Grants + grant_audit)
infra-persistence/.../resources/db/migration/V9__permission_schema.sql   # NEU (siehe data-model.md)

api-rest/.../api/rest/
├── PermissionController.java             # NEU, dünn
├── PermissionExceptionHandler.java       # NEU, nur eigene Exceptions (kein erneutes 403/400-Mapping)
└── support/PermissionMapper.java         # NEU (Domain ↔ DTO)

app/.../bootstrap/
├── config/PermissionConfig.java          # NEU — Composition-Root (Services, Repos, Publisher)
├── config/SchedulingConfig.java          # NEU — @EnableScheduling + @Scheduled → GrantExpiryService.sweep()
└── adapter/RedisPermissionEventPublisher.java  # NEU — spiegelt RedisReportEventPublisher

plugin-protocol/.../protocol/permission/  # NEU, JDK-only
├── PermissionChannels.java               # Channels.of("permission","changed")
├── PermissionChangedEvent.java  PermissionChangedEventCodec.java
├── PermissionEndpoints.java
└── (DTO-records: RoleRequest/Response, Grant*Request, PlayerPermissionsResponse, …)
# plugin-protocol/.../protocol/PlatformProtocol.java   # +1 Zeile: Codec registrieren

# Tests (SC-001): Grant-Helfer umstellen
app/src/test/.../PunishmentVerticalSliceTest.java  ReportVerticalSliceTest.java
infra-persistence/src/test/.../JooqPermissionResolverTest.java
```

**Structure Decision**: Strikt das etablierte Reports-Schichtenmuster (state-stored) gespiegelt. Ein
neues `permission`-Package je Schicht + je ein Composition-Root-`@Configuration`. Keine Vermischung
mit Plugin-Arbeit (separater Slice).

## Live-Ablauf — konkrete Verortung (Spec-Anforderung)

- **Korrektheit** liegt in der SQL-Query von `JooqPermissionResolver` (`grant.active AND (expires_at IS
  NULL OR expires_at > now())`, bei Rang-Grants zusätzlich `role.active = true` — FR-007a) → richtig
  auch zwischen Scheduler-Ticks; Resolver bleibt clock-frei.
- **Live-Push**: `app`/`SchedulingConfig` (`@Scheduled fixedDelay ≤ 60 s`) ruft
  `application/permission/GrantExpiryService.sweep()`. Der Service findet abgelaufene aktive Grants,
  setzt sie inaktiv (eigene Tx über `PlayerGrantRepository`), schreibt `grant_audit(EXPIRE)` mit der
  Sentinel-/System-UUID und ruft `PermissionChangePublisher.publish(playerUuid, GRANT_EXPIRED)`. Der
  Publisher (`RedisPermissionEventPublisher`) bridged auf `mc:permission:changed`. Identisch wird bei
  manuellem Revoke und bei Rollen-Permission-Änderungen publiziert (FR-021; bei Rollen-Config-Änderung
  ein Event je aktivem Halter, R7).

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
| --- | --- | --- |
| `@EnableScheduling` neu im Backend | FR-020 verlangt aktive Ablauf-Erkennung + Push; rein implizites `isActive(now)` (wie Punishment) löst **kein** Event aus | Kein Sweeper → Ablauf wirkt erst beim Relog, verletzt SC-004/„Ablauf wirkt live". Logik bleibt framework-frei im Service; nur die Trigger-Annotation ist Spring. |
| Ablösung der `team_role_*`-Tabellen + 3 Test-Helfer-Anpassungen | „mehrere aktive Ränge" ist mit `team_role_member.uuid` als PK strukturell unmöglich; einheitliche Welt erfordert ein Modell | Erweitern/zwei-Welten-parallel: dauerhafte Altlast + zweite Authority-Quelle, widerspricht dem Zielmodell. Kompat-Views: Over-Engineering nur für Test-Inserts. |

## Phase 1 — Re-Check nach Design

Erneut gegen die Constitution geprüft: Port unverändert (R3), core-domain framework-frei (Matcher/
Display/Effective sind reine Funktionen), protocol JDK-only mit einer Plug-in-Zeile, Persistenz
state-stored + Audit wie Reports. **PASS** — keine neuen Verstöße; die zwei dokumentierten Punkte sind
im Ledger/Complexity-Tracking begründet.
