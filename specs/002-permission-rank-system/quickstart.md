# Quickstart — Permission-/Rank-System (Vertical Slice)

## Bauen & Testen
```bash
# Backend (Domain → Use-Case → Integration(Testcontainers) → E2E)
./gradlew build

# Bei protocol-Änderung (neues Event/Endpoints/DTOs): ins Maven-Local publizieren,
# danach im Plugin-Repo `build --refresh-dependencies`
./gradlew :plugin-protocol:publishToMavenLocal
```
jOOQ-Klassen für die neuen V9-Tabellen werden beim Build automatisch generiert (jooq-docker spinnt ein
Wegwerf-Postgres hoch und liest die Flyway-Migrationen) — kein manueller Codegen-Schritt.

## Definition of Done (dieses Feature)
- [ ] core-domain-Tests grün: `PermissionMatcher` (Wildcards `*`, `feature.*`, exakt, kein Treffer),
      `EffectivePermissions` (Union mehrerer Ränge + Default-Fallback + direkte Grants),
      `RankDisplay` (Tie-Break `team_rank`→`weight`→`id`), `isActive(now)`.
- [ ] Use-Case-Tests (Fakes): jeder Schreibpfad prüft Permission **vor** Schreiben; Upsert (FR-014a);
      kaskadierender Rollen-Löschpfad (FR-012a); Default-Rolle nicht löschbar/deaktivierbar; Sweep
      erzeugt EXPIRE-Audit + Publish.
- [ ] Integration (Testcontainers, echtes Postgres): `JooqPermissionResolver` Union/Wildcard/`now()`-
      Filter; V9-Migration inkl. Erhalt der ADMIN/MODERATOR-Permissions; Audit-Zeilen.
- [ ] E2E: REST Rollen-CRUD + Grant/Revoke + 403-Pfad; `mc:permission:changed` wird publiziert.
- [ ] **SC-001**: `PunishmentVerticalSliceTest`, `ReportVerticalSliceTest`, `JooqPermissionResolverTest`
      grün — Grant-Test-Helfer auf das neue Modell umgestellt (siehe research.md R2); Produktions-
      Konsumenten + Port unverändert.
- [ ] `./gradlew build` grün (Backend). PROGRESS.md + FEATURE_INVENTORY.md nachgezogen.
- [ ] Bestätigt: keine generische Klasse geändert außer der **einen** Codec-Zeile in
      `PlatformProtocol.create()` (vorgesehene Plug-in-Stelle).

## Smoke-Pfad (manuell)
1. `POST /api/permission/roles` → Rolle „Premium" (weight 10).
2. `PUT  /api/permission/roles/{id}/permissions` → `["home.set","home.tp"]`.
3. `POST /api/permission/players/{uuid}/roles` → Premium permanent.
4. `GET  /api/permission/players/{uuid}/effective` → enthält `home.set`, `home.tp`.
5. Zweiter Grant Premium mit `expiresInSeconds` → **eine** Zeile, verlängert (FR-014a).
6. `DELETE /api/permission/roles/{id}` → Grant kaskadiert als REVOKE; effektive Perms fallen auf
   Default-Rolle zurück; `grant_audit` zeigt REVOKE.
