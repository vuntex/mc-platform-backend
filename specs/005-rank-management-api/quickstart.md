# Quickstart & Definition of Done: Rank-Management-Backend

**Feature**: 005-rank-management-api | **Date**: 2026-06-25

## Build / Publish-Reihenfolge

1. **plugin-protocol** (neue Web-Request-DTOs, additiv):
   ```
   ./gradlew :plugin-protocol:publishToMavenLocal
   ```
   Verifizieren: publizierter POM weiterhin **ohne** `<dependencies>`-Block. (Plugin-Repo braucht **kein** `--refresh-dependencies` — es konsumiert die Web-DTOs nicht.)
2. **Backend** komplett:
   ```
   ./gradlew build
   ```
   (jOOQ-Codegen zieht das via Flyway migrierte Schema inkl. `V13__role_audit.sql` aus dem Wegwerf-Container.)

## Manueller Smoke-Test (lokal)

```
docker-compose up -d            # Postgres + Redis
# 1) Login (Feature 004) → access-jwt holen
curl -sX POST localhost:8080/api/web-auth/login -H 'Content-Type: application/json' \
     -d '{"username":"<mc-name>","password":"<pw>"}'
# 2) Rolle anlegen (Admin-JWT)
curl -sX POST localhost:8080/api/web/permission/roles -H "Authorization: Bearer <jwt>" \
     -H 'Content-Type: application/json' \
     -d '{"name":"BUILDER","displayName":"Builder","weight":20,"teamRank":false,"active":true}'
# 3) Ohne/mit unzureichendem Recht → 403 ; ohne JWT → 401
```

## Definition of Done

- [ ] **Domain**: keine Änderung nötig (reuse) — bestätigt.
- [ ] **plugin-protocol**: 5 Web-Request-DTOs (ohne `actor`) ergänzt; `publishToMavenLocal` grün; POM ohne `<dependencies>`.
- [ ] **application**: `RoleAuditPort` (neu); `PermissionAdminService` um `role_audit`-Hooks erweitert (createRole/updateRole/deleteRole/add/removeRolePermission). `PermissionQueryService` unverändert.
- [ ] **infra-persistence**: `JooqRoleAuditRepository`; `V13__role_audit.sql` (nur `role_audit`, keine bestehende Migration verändert).
- [ ] **api-rest**: `WebPermissionController` (`/api/web/permission/**`): UUID aus `@AuthenticationPrincipal PlayerId`; Schreiben delegiert an `PermissionAdminService` (actor = Token); Lesen mit `permission.read`-Gate vor `PermissionQueryService`. `WebPermissionMapper` (Web-DTO → Domäne; Responses via `PermissionMapper`).
- [ ] **app**: `RoleAuditPort`-Bean + `WebPermissionController`-Deps in `PermissionConfig` verdrahtet. **Kein** `SecurityConfig`-Eingriff.
- [ ] **Kein** Eingriff in `PlatformProtocol.create()`, `SecurityConfig`, `PermissionResolver`-Port, bestehende Repositories, generische Klassen.

### Tests pro Schicht (grün)

- [ ] **Application (Fakes)**: `PermissionAdminService`-Tests um `role_audit`-Erwartungen erweitert (je Operation genau ein Audit-Eintrag mit korrektem Actor); Default-Schutz/Kaskade weiterhin grün.
- [ ] **infra-persistence (Testcontainers)**: `JooqRoleAuditRepository` schreibt korrekte Zeilen; Append-only.
- [ ] **api-rest / app E2E (Testcontainers Postgres+Redis)**:
  - 401 ohne JWT; 403 mit JWT ohne Recht.
  - Rolle anlegen/bearbeiten/löschen (Kaskade); Default-Rang → 409; Name-Kollision → 409; unbekannte Rolle → 404.
  - Permission zu Rolle add/remove → betroffene aktive Halter erhalten player-scoped `ROLE_CONFIG_CHANGED` (Pub/Sub-Assertion).
  - Grant Rolle/Permission (mit/ohne expiry); `expires_at` in Vergangenheit → 422; ungültige Permission-Syntax → 422.
  - `issued_by` == Token-UUID (nicht aus Body beeinflussbar) — Negativtest mit „gefälschtem" Body-Feld ist strukturell unmöglich (Web-DTO hat kein actor-Feld).
  - Grant an nie-gejointe UUID → akzeptiert; `effective` zeigt ihn.
  - `role_audit`/`grant_audit` enthalten je Operation genau einen Eintrag mit korrektem Actor.
- [ ] **Regression (SC-007)**: Economy-, Punishment-, Report-, Permission-002- und Web-Auth-Suiten unverändert grün.
- [ ] `./gradlew build` (Backend) grün.

### Nachzug nach Abschluss

- [ ] **PROGRESS.md** Status-Abschnitt nachgezogen (neuer Web-Pfad + role_audit; Bestand reused).
- [ ] **FEATURE_INVENTORY.md** Eintrag abgehakt.
- [ ] Bestätigt: keine generische Klasse geändert (Pattern-Leak-Ledger im Plan dokumentiert die eine feature-lokale Service-Erweiterung).
