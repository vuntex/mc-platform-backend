# Research & Reuse-Nachweis: Rank-Management-Backend

**Feature**: 005-rank-management-api | **Date**: 2026-06-25

Dieses Dokument liefert die im Auftrag (Prompt 6) geforderten Pflicht-Nachweise und hält die Design-Entscheidungen fest. Grundbefund: **Der Schreibkern existiert (Feature 002).** Dieser Slice ist überwiegend Reuse + eine token-getriebene Eingangsfläche + eine Audit-Erweiterung.

---

## R0 — Pub/Sub-Pfad: existiert (Fall 1b)

**Decision:** Der Live-Push-Pfad wird **nur genutzt**, nicht gebaut. **Kein** Eingriff in `PlatformProtocol.create()`.

**Befund (verifiziert am Code):**
- `protocol/permission/PermissionChannels.CHANGED` = `mc:permission:changed`.
- `protocol/permission/PermissionChangedEvent` (Record: `playerUuid`, `changeType`, `timestampEpochMilli`) + `PermissionChangedEventCodec` (messageType `permission.changed`, Golden-Wire gepinnt).
- In `PlatformProtocol.create()` **bereits registriert** (neben Economy/Punishment/Report).
- Outbound-Port `application/permission/port/PermissionChangePublisher.publish(UUID player, PermissionChangeType type)`; Adapter `app/.../RedisPermissionEventPublisher` bridged auf Redis.

**Konsequenz:** Schreiboperationen rufen `publisher.publish(player, type)` (bereits im `PermissionAdminService` verdrahtet). Dieser Slice fügt **keinen** neuen publish-Pfad hinzu — er erbt ihn über die wiederverwendeten Use-Cases.

---

## R1 — Reuse-Inventar (Pflicht-Nachweis 1)

**Decision:** Kein paralleles Repository, kein paralleler Use-Case. Die Schreibmethoden existieren bereits — sie müssen **nicht** ergänzt werden.

| Baustein | Typ | Status für diesen Slice |
| --- | --- | --- |
| `domain/permission/*` (Role, RoleGrant, PermissionGrant, RoleId, PermissionMatcher, EffectivePermissions, RankDisplay, PermissionChangeType, RoleValidationException, InvalidGrantException) | core-domain | **Reuse unverändert** |
| `RoleRepository` (create/update/delete/find/findByNameIgnoreCase/findAll/findDefault/addPermission/removePermission/permissionsOf) | application port | **Schreiben existiert vollständig** — Reuse |
| `PlayerGrantRepository` (upsertRoleGrant/revokeRoleGrant/upsertPermissionGrant/revokePermissionGrant/activeRoleGrants/activePermissionGrants/activeHoldersOf) | application port | **Schreiben existiert vollständig** — Reuse |
| `GrantAuditPort` (record GRANT/REVOKE/EXPIRE für Spieler-Grants) | application port | **Reuse** für Grant-Audit |
| `PermissionAdminService` (createRole/updateRole/deleteRole/addRolePermission/removeRolePermission/grantRole/grantPermission/revokeRole/revokePermission) | application use-case | **Reuse**; nur Audit-Hooks ergänzen (R5) |
| `PermissionQueryService` (roleDetail/allRoles/effectiveFor) | application use-case | **Reuse unverändert** (Gate kommt im Web-Controller, R3) |
| `PermissionResolver` (`hasPermission(uuid, perm)`) | application port | **Reuse** als Gate |
| `JooqRoleRepository` / `JooqPlayerGrantRepository` / `JooqGrantAuditRepository` | infra-persistence | **Reuse** |
| `PermissionMapper` (Domäne ↔ Response-DTO) | api-rest support | **Reuse** für Responses |
| `PermissionExceptionHandler` (404/409/422) + globale 403/400 | api-rest | **Reuse** (R8) |
| `PermissionChannels`/`PermissionChangedEvent`/Codec/Publisher | protocol + app | **Reuse** (R0) |

**Net-neu:** Web-Request-DTOs ohne `actor` (R2), `WebPermissionController` (R2/R3), `RoleAuditPort` + `JooqRoleAuditRepository` + `V13__role_audit.sql` (R5), Audit-Hooks im Service (R5), Composition-Wiring.

**Rationale:** Der Bestand erfüllt „Wiederverwenden vor Neubau" (Constitution §10). Ein zweites Repository neben dem bestehenden wäre ein Verstoß.

---

## R2 — Bestandspfad-Disposition + Web-DTOs ohne actor (Pflicht-Nachweis 3; Spec Q3)

**Decision:** **Neue parallele Web-Fläche** `/api/web/permission/**`; der interne `/api/permission/**`-Pfad bleibt **unverändert**. Die Web-Request-DTOs enthalten **kein `actor`-Feld**.

**Begründung der DTO-Wahl (statt RoleRequest mit ignoriertem actor):**
- Die Bestands-DTOs (`RoleRequest`, `GrantRoleRequest`, …) tragen ein `actor`-Feld (ihr Javadoc sagt sogar: „the later auth feature will derive it from the session" — das ist dieser Slice).
- FR-002/FR-020 verlangen, dass `issued_by` **nicht überschreibbar** ist. Ein Feld, das der Client schickt und das der Server still ignoriert, ist ein Footgun. **Strukturelle Unmöglichkeit > Dokumentation:** Die Web-DTOs lassen `actor` weg — fälschbar ist, was nicht existiert.
- Additiv im `plugin-protocol` (`permission/web/`), JDK-only, kein Codec/Wire-Eingriff → §4-konform.

**Sicherheitshinweis (dokumentiert, nicht behoben in diesem Slice):** Der interne Pfad bleibt `permitAll` und vertraut `req.actor()`. Akzeptiert unter der Annahme, dass `/api/permission/**` netzseitig nur vom vertrauenswürdigen Plugin/System erreichbar ist (Spec-Annahme zu Q3). Eine spätere Absicherung (intern-only/Deaktivierung) ist ein eigener Slice.

---

## R3 — Gating: granulare `permission.*` + UUID aus JWT (Pflicht-Nachweise 2; Spec Q1=B)

**Decision:** **Kein `rank.*`-Vokabular.** Schreiben nutzt die bestehenden granularen Gates im Service; Lesen gatet der Web-Controller mit `permission.read`.

**Schreib-Fluss (Gate liegt bereits im Service):**
```
WebPermissionController.createRole(@AuthenticationPrincipal PlayerId actor, RoleWriteRequest body)
  → admin.createRole(mapper.draft(body), actor.value())
        → requirePermission(actor, "permission.role.create")  // PermissionResolver.hasPermission
        → roles.create(...) ; audit ; (publish bei Mitgliedern)
```
Pro Operation greift das vorhandene Recht: `permission.role.create` / `permission.role.edit` / `permission.role.delete` / `permission.grant.role` / `permission.grant.permission`. **ADMIN hat `*`** (V9-Seed) → erfüllt alle. **MODERATOR** hat keins davon (V9-Seed: nur punishment/report-Perms) → 403. **Kein neuer Seed nötig.**

**Lese-Fluss (neues Gate im Web-Controller, da Query-Service ungegatet ist):**
```
WebPermissionController.listRoles(@AuthenticationPrincipal PlayerId actor)
  → if (!resolver.hasPermission(actor.value(), PermissionAdminService.READ /* "permission.read" */)) → 403
  → query.allRoles()
```

**UUID aus dem JWT (Muster aus Slice 4/5):** `JwtAuthenticationFilter` setzt `Authentication.principal = PlayerId` (Authorities leer — „identity only"). Der Web-Controller liest `@AuthenticationPrincipal PlayerId`. Kein Spring-Security-Rollenmodell, kein `hasRole`/`@PreAuthorize` — Autorisierung bleibt beim `PermissionResolver` (Constitution §12). Gleiches Muster wie Punishments (Gate im Service) + Slice-5-Filter (Identity).

---

## R4 — Live-Push-Granularität (Pflicht-Nachweis 4; Spec-Punkt 5)

**Decision:** **Player-scoped Events** (eine Benachrichtigung je betroffener Spieler-UUID) — **bestehendes Schema**, kein neues Event.

**Befund:** `PermissionChangePublisher.publish(UUID player, PermissionChangeType)` ist player-scoped. `PermissionAdminService.publishToHolders(roleId)` iteriert `grants.activeHoldersOf(roleId, now)` und publisht je Halter ein `ROLE_CONFIG_CHANGED`. Einzel-Grants publishen `GRANT_ADDED`/`GRANT_REVOKED` für den einen Spieler.

**Warum player-scoped statt role-scoped:**
- Das Plugin cacht **pro Spieler-UUID**; es hält keine autoritative Rolle→Mitglieder-Abbildung. Ein player-scoped Event sagt direkt „lade die effektiven Rechte **dieses** Spielers neu" (Plugin ruft `GET /api/permission/players/{uuid}/effective`). Ein role-scoped Event würde das Plugin zwingen, Mitgliedschaften selbst aufzulösen → verschiebt Autorität ins Plugin (Verstoß §I).
- **Last:** Es werden nur **aktive** (i. d. R. online relevante) Halter benachrichtigt (`activeHoldersOf`). Offline-Spieler laden beim nächsten Join ohnehin frisch. Single-Server-Skala (§14) → unkritisch.

**Event-Schema (unverändert, gepinnt):** `PermissionChangedEvent { UUID playerUuid; String changeType ∈ {GRANT_ADDED, GRANT_REVOKED, GRANT_EXPIRED, ROLE_CONFIG_CHANGED}; long timestampEpochMilli }` über `mc:permission:changed`.

---

## R5 — Audit-Erweiterung auf Rollen-Änderungen (Pflicht-Nachweis 5 erweitert; Clarify-Entscheidung)

**Decision:** Neuer **`role_audit`-Strang** (Tabelle V13 + `RoleAuditPort` + jOOQ-Adapter), eingehängt in die Rollen-/Permission-Methoden des `PermissionAdminService`.

**Warum nicht `GrantAuditPort` erweitern:** Dessen `Entry` ist spieler-zentrisch (`PlayerId player` ist Pflicht). Rollen-Stammdaten-/Rollen-Permission-Änderungen haben **keinen** betroffenen Einzelspieler — sie sind Konfigurations-Ereignisse. Ein eigener Strang (analog `config_audit`) hält beide Audit-Modelle sauber.

**`role_audit` Aktionen:** `ROLE_CREATE`, `ROLE_UPDATE`, `ROLE_DELETE`, `ROLE_PERMISSION_ADD`, `ROLE_PERMISSION_REMOVE`. Felder: `role_id` (bzw. Name-Snapshot bei DELETE), optional `permission`, `actor` (Token-UUID), `at`. Append-only.

**Einhängepunkte (im feature-eigenen `PermissionAdminService`, Pattern-Leak-Ledger #1):** `createRole`/`updateRole`/`deleteRole`/`addRolePermission`/`removeRolePermission` rufen nach erfolgreichem Write `roleAudit.record(...)`. Damit auditieren **beide** Eingangspfade (intern + web) identisch und atomar zur Operation.

---

## R6 — Default-Rang-Schutz + Löschen-mit-Mitgliedern (Pflicht-Nachweis 5; Spec Q2)

**Decision:** **Bereits im Bestand erfüllt — reuse, keine Änderung.**

**Befund (`PermissionAdminService`):**
- `updateRole`: Default-Rolle darf nicht deaktiviert werden → `DefaultRoleProtectedException` → **409**. `isDefault` ist unveränderlich (Server preserviert den gespeicherten Wert).
- `deleteRole`: Default-Rolle → `DefaultRoleProtectedException` → **409**. Sonst **kaskadiert** (Q2=A): für jeden aktiven Halter REVOKE + Audit + player-scoped Publish, dann `roles.delete` (DB cascadet `role_permission`/`player_role_grant`).
- Name-Kollision (create/update) → `RoleNameConflictException` → **409**.
- Durchgesetzt in der **Domäne/Application** (nicht im Controller).

---

## R7 — Letzter-Admin-Schutz (Spec-Edge/Annahme)

**Decision:** **Keine Laufzeitsperre** (bewusst verschoben/akzeptiert). Begründung in der Spec: korrekte Erkennung des letzten Verwaltungsrecht-Inhabers im Wildcard-/Rollen-/Direkt-Grant-Modell ist nicht zuverlässig; Recovery via Seed/DB. Kein zusätzlicher Code.

---

## R8 — Lesen = nur Ist-Zustand; Fehlercodes (Pflicht-Nachweis 7; Spec Q3-Clarify)

**Decision:** Web-Lese-Endpoints liefern nur den **Ist-Zustand** (Rollen, Rollen-Permissions, aktive Grants/effective). **Keine** Audit-/History-Lese-Endpoints (späterer Slice). Audit wird geschrieben (R5), nicht gelesen.

**Fehlercodes — konsistent zu den bestehenden Handlern (Reuse, kein neuer Handler):**

| Situation | Code | Quelle |
| --- | --- | --- |
| Fehlt das `permission.*`-Recht (schreibend) / `permission.read` (lesend) | **403** | `PermissionDeniedException` (global gemappt) |
| Kein/ungültiges JWT | **401** | `SecurityConfig`-EntryPoint (Slice 5) |
| Default-Rang gelöscht/deaktiviert · Name-Kollision | **409** | `DefaultRoleProtectedException` / `RoleNameConflictException` |
| Unbekannte Rolle | **404** | `RoleNotFoundException` |
| Ungültige Permission-Syntax / `expires_at` in Vergangenheit | **422** | `RoleValidationException` / `InvalidGrantException` |
| Sonstige ungültige Eingabe | **400** | `IllegalArgumentException` (global) |
| Unbekannter Spieler ohne Grants | **200** leere Liste | by design (kein 404) |

---

## R9 — plugin-protocol & EndpointDescriptors (Pflicht-Nachweis 6)

**Decision:**
- **Request-DTOs (neu, additiv, JDK-only):** `RoleWriteRequest`, `RolePermissionWriteRequest`, `GrantRoleWriteRequest`, `GrantPermissionWriteRequest`, `RevokePermissionWriteRequest` unter `protocol/permission/web/` — alle **ohne `actor`**.
- **Response-DTOs:** `RoleResponse`, `PlayerPermissionsResponse` **wiederverwendet**.
- **`publishToMavenLocal`** nach der DTO-Ergänzung; im Plugin kein Refresh nötig (Plugin konsumiert die Web-DTOs nicht).
- **EndpointDescriptors (`RankEndpoints`) — zurückgestellt.** Begründung: `EndpointDescriptor` ist für den **Java**-`BackendClient` des Plugins. Consumer der Web-Fläche ist das **Next.js/TypeScript**-Frontend, kein Java-Client. Java-EndpointDescriptors für `/api/web/**` hätten **keinen** Consumer → toter Code. Der Endpunkt-Contract wird stattdessen in `contracts/web-permission-api.md` festgehalten. (Wird ein Java-Client der Web-Fläche nötig, werden sie additiv nachgezogen.)

**Kein** Codec-/Channel-/Wire-Eingriff (R0). `plugin-protocol`-POM bleibt ohne `<dependencies>`.

---

## R10 — Migration V13 (Pflicht-Nachweis 8)

**Decision:** **V13** (nicht V11/V12 — belegt). Inhalt: **nur** `CREATE TABLE role_audit (...)` + Index. **Kein** Gate-Seed (R3: ADMIN `*` genügt). **Keine** bestehende Migration verändert. **Keine** Spaltenänderung an `role`/Grant-Tabellen nötig (FR-023 schema-seitig erfüllt — Grant-Tabellen haben keinen FK auf `player`).

---

## Zusammenfassung der Entscheidungen

| Ref | Entscheidung |
| --- | --- |
| R0 | Pub/Sub existiert → nur nutzen, kein PlatformProtocol-Eingriff |
| R1 | Voller Reuse von Domäne/Use-Cases/Repos/Resolver/Publisher/Handler |
| R2 | Paralleler `/api/web/permission/**`-Pfad; Web-DTOs ohne `actor` |
| R3 | Granulare `permission.*` (kein `rank.*`); UUID aus JWT-Principal; Lese-Gate `permission.read` im Controller |
| R4 | Player-scoped Live-Events (bestehendes Schema) |
| R5 | Neuer `role_audit`-Strang (Port + jOOQ + V13), Hooks im Service |
| R6 | Default-Schutz + Kaskade bereits vorhanden — reuse |
| R7 | Kein Letzter-Admin-Schutz (verschoben/akzeptiert) |
| R8 | Nur Ist-Zustand lesen; Fehlercodes über bestehende Handler |
| R9 | Additive Web-Request-DTOs; EndpointDescriptors zurückgestellt |
| R10 | Migration V13, nur `role_audit` |
