# Phase 0 — Research & Pflicht-Nachweise: Rollen-Vererbung

Format je Punkt: **Decision / Rationale / Alternatives**. Die sieben vom Auftrag geforderten
Nachweise sind 1:1 abgebildet. Grundlage: die real gelesenen Klassen `JooqPermissionResolver`,
`EffectivePermissions`, `PermissionQueryService`, `PermissionAdminService`, `RoleRepository`,
`PlayerGrantRepository`, `WebPermissionController`, `V9__permission_schema.sql`.

---

## 1. RESOLVER-UMBAU — flach → transitiv, ohne Regression

### Decision

Es gibt heute **zwei** Auflösungsstellen; beide werden erweitert, aber unterschiedlich:

- **Hot-Path** `JooqPermissionResolver.hasPermission` (eine SQL, liefert `boolean`): die heutige
  `active_roles`-CTE wird zur **Basis** einer neuen rekursiven CTE `reachable_roles`. Transitivität
  sitzt damit **im Resolver-Kern** (SQL).
- **View-Path** `PermissionQueryService.effectiveFor`/`roleDetail` (liefert die Anzeige-Sicht): die
  Transitivität kommt als **vorgelagerte reine Domain-Schicht** `RoleHierarchy`. `EffectivePermissions`
  bleibt **unverändert** (es unioniert weiterhin eine fertige `Map<RoleId, List<String>>`).

Heutige Resolver-SQL (gekürzt): `active_roles` (direkte aktive Grants) → `candidate` (Rollen-Perms ∪
Default-Perms wenn `NOT EXISTS active_roles` ∪ Direkt-Grants) → `EXISTS`-Match.

Neue Resolver-SQL:

```sql
WITH RECURSIVE active_roles AS (
    SELECT g.role_id
    FROM player_role_grant g
    JOIN role r ON r.id = g.role_id AND r.active
    WHERE g.uuid = ? AND g.active AND (g.expires_at IS NULL OR g.expires_at > now())
),
reachable_roles AS (
    SELECT role_id FROM active_roles                 -- Basis: direkte aktive Rollen
    UNION                                            -- UNION (nicht ALL) ⇒ Dedup ⇒ terminiert auch bei Restzyklus
    SELECT ri.inherited_role_id                      -- Schritt: Eltern entlang der Vererbungs-Kanten
    FROM role_inheritance ri
    JOIN reachable_roles rr ON rr.role_id = ri.role_id
),
candidate AS (
    SELECT rp.permission
    FROM role_permission rp
    JOIN reachable_roles ar ON ar.role_id = rp.role_id           -- (a) statt active_roles jetzt reachable_roles
    UNION
    SELECT rp.permission
    FROM role_permission rp
    JOIN role r ON r.id = rp.role_id
    WHERE r.is_default AND NOT EXISTS (SELECT 1 FROM active_roles) -- (b) Default-Zweig UNVERÄNDERT (auf BASIS gegated)
    UNION
    SELECT permission
    FROM player_permission_grant
    WHERE uuid = ? AND active AND (expires_at IS NULL OR expires_at > now())  -- (c) Direkt-Grants UNVERÄNDERT
)
SELECT EXISTS (
    SELECT 1 FROM candidate
    WHERE permission = ? OR permission = '*'
       OR (permission LIKE '%.*' AND starts_with(?, left(permission, length(permission) - 1)))
)
```

Einziger Unterschied zum Bestand: die Zeile `JOIN active_roles` → `JOIN reachable_roles` in
candidate-Zweig (a), plus die vorgeschaltete `reachable_roles`-CTE und das Schlüsselwort `RECURSIVE`.
Match-Block, Default-Zweig und Direkt-Grant-Zweig sind **textgleich**.

### Bit-Identität bei leerem Graph (Regression-Schutz)

Ist `role_inheritance` leer (oder hat eine Rolle keine Kanten), liefert der rekursive Term **keine**
Zeilen → `reachable_roles` = `active_roles`. Der candidate-Zweig (a) wird damit identisch zum
heutigen, (b)/(c) sind unverändert → **dasselbe `EXISTS`-Ergebnis** wie heute. Abgesichert durch
**Charakterisierungstests**: die bestehende Resolver-Test-Suite (002) läuft unverändert grün gegen die
neue SQL; zusätzlich ein expliziter Test „role_inheritance leer ⇒ Ergebnis identisch zur Referenz".

### Wo sitzt die Transitivität — Kern vs. vorgelagert? (begründet)

- **Hot-Path: im Kern (SQL).** Eine Vorstufe wäre hier sinnlos — die Auflösung IST die SQL, und eine
  zweite Java-seitige Faltung würde den einen Round-Trip aufgeben und die Lockstep-Garantie brechen.
- **View-Path: vorgelagert (Domain).** `EffectivePermissions` ist bereits eine reine Union-Funktion
  über eine Permissions-Map; die Vererbung erweitert nur, *welche* Rollen in die Map kommen. Diese
  Mengen-Expansion ist reine Graph-Logik (`RoleHierarchy`) und braucht für die Provenienz (FR-022a)
  ohnehin Java-Strukturen, die SQL nicht liefert. `EffectivePermissions` bleibt der unveränderte
  Regression-Anker.

Beide Pfade müssen — wie heute `PermissionMatcher` die SQL-Wildcardregel spiegelt — **semantisch im
Gleichschritt** bleiben: gleiche reachable-Semantik (Eltern entlang Kanten, ohne `active`-Filter auf
den Eltern, FR-016). Ein gemeinsamer Test-Datensatz prüft „SQL-`hasPermission` ⇔ Domain-`RoleHierarchy`".

### Rationale

Minimaler, lokal begrenzter Eingriff; ein einziger geänderter Join im Hot-Path; Regression mathematisch
trivial nachweisbar (leerer rekursiver Term).

### Alternatives considered

- **Materialisierte Closure-Tabelle** (transitive Hülle vorberechnet, bei jeder Kanten-Änderung neu):
  verworfen — mehr Schreibpfad-Komplexität + Invalidierung, kein Bedarf bei wenigen Rollen.
- **Java-seitige Faltung auch im Hot-Path** (Resolver lädt Kanten und faltet in Java): verworfen —
  gäbe den Single-Round-Trip auf und dupliziert die Closure-Logik in zwei Sprachen.

---

## 2. DEFAULT-ZUSAMMENSPIEL — konkrete Flüsse

### Decision

Der Default-Fallback bleibt ein **exklusiver** Zweig, gegated auf die **Basismenge** der direkten
aktiven Rollen (`NOT EXISTS (SELECT 1 FROM active_roles)`), NICHT auf `reachable_roles`. Default ist
ein Blatt (CL-3/FR-013).

**Fall A — Premium erbt Default** (`role_inheritance: Premium → DEFAULT`), Spieler hält nur Premium:
- `active_roles = {Premium}` → `NOT EXISTS` ist **false** → Default-Zweig (b) feuert NICHT.
- `reachable_roles = {Premium, DEFAULT, …}` → die Default-Permissions kommen über candidate-Zweig (a)
  (normaler Vererbungs-Weg) herein.
- Effektiv: `perms(Premium) ∪ perms(DEFAULT) ∪ transitive Eltern`. ✓ Default-Basis ist enthalten.

**Fall B — Premium erbt Default NICHT**, Spieler hält nur Premium:
- `active_roles = {Premium}` → `NOT EXISTS` false → Default-Zweig feuert NICHT.
- `reachable_roles = {Premium, …}` ohne DEFAULT → keine Default-Permissions.
- Effektiv: nur `perms(Premium)` (+ dessen sonstige Eltern). **Technisch korrekt durchlaufend**; dies
  ist die bewusste CL-1-Konsistenz-Falle (Spieler mit nur Premium hat ggf. weniger als ein reiner
  Default-Spieler). Kein Sonderfall, keine Hilfe (FR-012 = c).

**Fall C — Spieler hält 0 aktive Rollen:**
- `active_roles = {}` → `reachable_roles = {}` → candidate-Zweig (a) leer; `NOT EXISTS` **true** →
  Default-Zweig (b) feuert → Default-Permissions. Da Default Blatt ist, ist die „Closure von Default"
  trivial = Default selbst. ✓ (unveränderter Status quo).

### Rationale

Vererbung (über die direkten Rollen) und Default-Fallback (bei *fehlenden* direkten Rollen) sind
orthogonal, weil der eine über `reachable_roles` und der andere über `NOT EXISTS active_roles` läuft.
Sie können sich nicht widersprechen.

### Alternatives considered

- **Default implizit in jede Rolle vererben**: verworfen — zerstört die Exklusivität des Fallbacks und
  die bewusst gewählte CL-1-Semantik; widerspricht „Default = exklusiver System-Fallback".

---

## 3. ZYKLUS-SCHUTZ — zweistufig

### Decision

**Stufe 1 — Vorab-Check beim Setzen (autoritativ, 409).** `addInheritance(childId, parentId)` lehnt ab,
wenn:
- `childId == parentId` (direkte Selbstreferenz), ODER
- `childId` ist aus `parentId` über bestehende Kanten **erreichbar** (d.h. `parent` erbt bereits
  transitiv von `child`) — dann würde `child → parent` den Kreis schließen.

Realisierung über `RoleHierarchy.wouldCreateCycle(childId, parentId, directParentsOf)` (reine
Domain-Funktion, Visited-Set-DFS auf der „role → direkte Eltern"-Adjazenz). Datenquelle: ein einziger
Lesezugriff `RoleInheritanceRepository.ancestors(parentId)` (rekursive CTE) bzw. die komplette
Kantenliste. Ergebnis bei Zyklus: `RoleInheritanceCycleException` → HTTP **409**.

**Stufe 2 — defensive Auflösung (Sicherheitsnetz).** Selbst wenn (z.B. durch direkten DB-Eingriff) ein
Restzyklus existiert:
- **Domain** `RoleHierarchy.resolve(...)`: `HashSet<RoleId> visited` — jede Rolle wird höchstens einmal
  expandiert → garantierte Terminierung.
- **SQL** `reachable_roles`: `UNION` (nicht `UNION ALL`) dedupt Knoten → die Rekursion erreicht einen
  Fixpunkt und terminiert über der endlichen Rollenmenge.
- **DB**: `CHECK (role_id <> inherited_role_id)` verhindert die triviale Selbstkante als letzte Bastion.

### Rationale

Schreib-Check = Nutzer-sichtbare Garantie (FR-010); Auflösungs-Defensive = Schutz des Login-/Check-
Pfads vor Hängern (FR-010a). Beide zusammen = Gürtel + Hosenträger.

### Alternatives considered

- **Nur Auflösungs-Defensive** (keinen Schreib-Check): verworfen — Zyklen wären persistierbar und für
  Admins unsichtbar fehlerträchtig.
- **Nur Schreib-Check**: verworfen — ein DB-seitig eingeschleuster Zyklus würde die Auflösung hängen
  lassen.

---

## 4. CACHE / LIVE-PUSH — Reichweite & Mechanik

### Decision

**Kein Backend-Permission-Cache** (verifiziert: `infra-cache` enthält nur `BalanceCache` für Economy;
`JooqPermissionResolver` rechnet pro Aufruf frisch). Der PreLogin-Warmup + `FeatureCache` liegen im
**Plugin** und werden über den bestehenden Pub/Sub-Event aktualisiert. Es gibt also backend-seitig
**nichts zu invalidieren** — die Frage reduziert sich auf die **Reichweite des Live-Push**.

**Fan-out-Regel:** Eine Änderung an Rolle `R` (Kante hinzufügen/entfernen, oder Permissions von `R`
ändern) betrifft `R` **und alle Rollen, die `R` transitiv erben** (Reverse-Closure / `dependents(R)`).
Betroffene Spieler = aktive Holder von `{R} ∪ dependents(R)`. Für jeden ein
`ROLE_CONFIG_CHANGED`-Event über den bestehenden `safePublish`-Pfad.

- Bei `addInheritance(child, parent)` / `removeInheritance(child, parent)`: geänderte Rolle = **child**
  (deren effektive Menge ändert sich) → Fan-out an Holder von `{child} ∪ dependents(child)`. (Parent
  ändert sich nicht.)
- Bei `addRolePermission`/`removeRolePermission` (bestehende 005-Operationen): heute `publishToHolders(id)`
  (nur direkte Holder). Wird zu `publishToRoleAndDependents(id)` erweitert → **FR-020a**.

Neue Persistenz-Bausteine: `RoleInheritanceRepository.dependents(roleId)` (rekursive CTE in
Gegenrichtung: Start `R`, folge Kanten wo `inherited_role_id = current`, sammle `role_id`) und ein
Batch-Holder-Zugriff (Wiederverwendung von `activeHoldersOf` je Rolle, dedupliziert).

**Player-scoped, kein breites „Topologie geändert"-Signal.**

### Rationale

- Präzise (FR-020: nicht betroffene Spieler erhalten **kein** Signal) — ein Broadcast „Topologie
  geändert" würde alle Online-Spieler unnötig zum Refetch zwingen.
- **Null neue Protokoll-Fläche**: `ROLE_CONFIG_CHANGED` existiert bereits und bedeutet exakt „die
  Config, aus der sich deine Permissions ergeben, hat sich geändert — zieh deinen Stand neu". Der
  Plugin-Client behandelt es schon.
- Konsistent zum bestehenden `publishToHolders`-Muster; der einzige Unterschied ist die breitere
  (transitive) Holder-Menge.

### Alternatives considered

- **Breites Topologie-Event** (ein Signal, alle ziehen neu): verworfen — verletzt FR-020 (Lärm) und
  bräuchte einen neuen Event-Typ.
- **Tabelle der effektiven Permissions vorberechnen + diff-basiert pushen**: verworfen — Overkill bei
  wenigen Rollen/seltenen Änderungen; mehr Komplexität als der direkte Reverse-Closure-Fan-out.

---

## 5. REUSE — ergänzt vs. neu

### Decision

**Wiederverwendet (unverändert):**
- `PermissionResolver`-Port (`boolean hasPermission(UUID, String)`) — Signatur bleibt; Punishments/
  Reports/Web-Gates unberührt.
- `EffectivePermissions`, `PermissionMatcher` (core-domain) — unverändert (Regression-Anker).
- `RoleRepository`, `PlayerGrantRepository` — wiederverwendet; `activeHoldersOf` trägt den Fan-out.
- `RoleAuditPort` — neue Aktionen `ROLE_INHERITANCE_ADD`/`ROLE_INHERITANCE_REMOVE` (additive Enum-Werte).
- `PermissionChangePublisher`/`RedisPermissionEventPublisher`/`PermissionChannels`/
  `PermissionChangedEvent` + `ROLE_CONFIG_CHANGED` — wiederverwendet, **kein** neuer Channel/Event.
- `WebPermissionController` — bestehende Struktur; neue Endpoints angesteckt.

**Erweitert (bewusst, siehe plan.md Complexity Tracking):**
- `JooqPermissionResolver` — rekursive CTE.
- `PermissionAdminService` — Inheritance-Use-Cases + Fan-out auf Reverse-Closure (auch role-permission-
  Edits, FR-020a); `deleteRole` prüft Vererbungs-Abhängige (FR-015).
- `PermissionQueryService` — effektive Sichten nutzen `RoleHierarchy` (+ Provenienz).

**Neu:**
- core-domain `RoleHierarchy` (Closure + Provenienz + Zyklus-Check).
- application `RoleInheritanceRepository`-Port.
- infra `JooqRoleInheritanceRepository` + `V15`-Migration.
- protocol-DTOs/Endpoints (Punkt 6).

**Kein paralleles Resolver-Konstrukt.**

### Rationale / Alternatives

Constitution §10. Ein eigener `RoleInheritanceRepository` (statt `RoleRepository` aufzubohren) hält den
Slice self-contained; verworfen wurde, alle Kanten-Methoden in `RoleRepository` zu mischen
(verwässert die Master-Data-Verantwortung dieses Ports).

---

## 6. plugin-protocol — DTOs & Pub/Sub

### Decision (JDK-only, additiv → danach `:plugin-protocol:publishToMavenLocal`)

- **`web/InheritanceWriteRequest`** (neu): `record InheritanceWriteRequest(long parentRoleId)`.
- **`RoleResponse`** (erweitert, additiv): `+ List<Long> inheritedRoleIds` (die **direkten** Eltern).
- **`EffectivePermissionEntry`** (neu): `record EffectivePermissionEntry(String permission, boolean own,
  List<Long> inheritedFromRoleIds)` — die Provenienz je Permission (FR-022a: vollständige Quellenmenge
  + `own`-Flag).
- **`PlayerPermissionsResponse`** (erweitert, additiv): `+ List<EffectivePermissionEntry> sources`. Das
  bestehende `effectivePermissions: List<String>` (flach) bleibt für reine Allow/Deny-Konsumenten.
- **`PermissionEndpoints`** (erweitert): drei Deskriptoren — `LIST_INHERITANCE` (GET
  `/api/permission/roles/{id}/inheritance` → `long[]`), `ADD_INHERITANCE` (POST, `InheritanceWriteRequest`
  → `RoleResponse`), `REMOVE_INHERITANCE` (DELETE `/api/permission/roles/{id}/inheritance/{parentId}` →
  `RoleResponse`). Web-Pendants liegen unter `/api/web/permission/**` (Controller-Mapping).

**Pub/Sub:** bestehender Pfad (`PermissionChannels.CHANGED` = `mc:permission:changed`,
`PermissionChangedEvent`, `ROLE_CONFIG_CHANGED`). **Keine** neue PlatformProtocol-/Channel-Zeile nötig.

### Rationale

Alle Wire-Änderungen sind additiv (neue Records, neue Felder, neue Endpoint-Konstanten) → keine
Breaking-Changes für bestehende Konsumenten. `effectivePermissions` bleibt, damit der autoritative
Check (FR-022) unverändert flach ist; `sources` ist die additive Anzeige-Anreicherung.

### Alternatives considered

- **`effectivePermissions` durch eine Provenienz-Liste ersetzen** (statt zusätzlich): verworfen —
  Breaking-Change + verletzt FR-022 („flache Menge bleibt erhalten").
- **Neuer Pub/Sub-Event-Typ `INHERITANCE_CHANGED`**: verworfen — `ROLE_CONFIG_CHANGED` deckt die
  Semantik vollständig ab; ein neuer Typ wäre unnötige Protokoll-Fläche.

---

## 7. Flyway — neue Migration

### Decision

Nächste freie Version ist **V15** (V14 ist bereits `V14__remove_default_role_grants.sql` aus dem
Default-Change). Datei: `V15__role_inheritance.sql`, **nur** die Kantentabelle, keine Änderung an
bestehenden Migrationen.

```sql
CREATE TABLE role_inheritance (
    role_id            BIGINT NOT NULL REFERENCES role(id) ON DELETE CASCADE,   -- erbende (child) Rolle
    inherited_role_id  BIGINT NOT NULL REFERENCES role(id) ON DELETE RESTRICT,  -- geerbte (parent) Rolle
    created_by         UUID,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (role_id, inherited_role_id),
    CONSTRAINT chk_no_self_inherit CHECK (role_id <> inherited_role_id)
);
CREATE INDEX idx_role_inheritance_parent ON role_inheritance (inherited_role_id);  -- für dependents()/Reverse-Closure
```

- `role_id` **ON DELETE CASCADE**: wird die erbende Rolle gelöscht, verschwinden ihre ausgehenden
  Kanten automatisch (konsistent zum bestehenden Cascade-Muster von `role_permission`).
- `inherited_role_id` **ON DELETE RESTRICT**: DB-Sicherheitsnetz für FR-015 — eine Rolle, die noch
  geerbt wird, kann nicht stillschweigend weggelöscht werden. Der Use-Case fängt das **vorher** ab und
  liefert ein freundliches **409** mit den abhängigen Rollennamen.
- PK `(role_id, inherited_role_id)`: macht das erneute Setzen einer Kante idempotent (Upsert
  `ON CONFLICT DO NOTHING`, FR-014).
- `CHECK`: letzte Bastion gegen die Selbstkante (FR-010, der Use-Case lehnt schon mit 409 ab).

**Kein neuer Permission-Seed nötig:** das schreibende Gate `permission.role.edit.inherit` wird durch das
bereits geseedete ADMIN-`*` abgedeckt (V9). Das Gate ist nur ein String-Konstante im Use-Case.

### Rationale / Alternatives

Gerichtete Kante als reine M:N-Tabelle = einfachste konsistente Form (Constitution §6). Verworfen: eine
Spalte `inherited_role_ids BIGINT[]` auf `role` — würde FK-Integrität, Cascade und die Reverse-Closure-
Query verlieren.

---

## Auflösung der Spec-Klärungen im Plan

| Spec-Marker | Status im Plan |
|---|---|
| CL-1 / FR-012 (Default-Falle = keine Hilfe) | **Aufgelöst** — Punkt 2, Fall B läuft technisch korrekt durch; keine Warn-/Vorauswahl-Logik im Backend. |
| CL-2 / FR-022 + FR-022a (Herkunft anzeigen, Diamant = alle Quellen) | **Aufgelöst** — `EffectivePermissionEntry{permission, own, inheritedFromRoleIds}` (Punkt 6), berechnet von `RoleHierarchy` (Punkt 1). |
| CL-3 / FR-013 (Default ist Blatt) | **Aufgelöst** — `addInheritance` lehnt ab, wenn child = Default; Punkt 2/Punkt 3. |
| FR-014 (idempotente Kante) | **Aufgelöst** — PK + `ON CONFLICT DO NOTHING` (Punkt 7). |
| FR-015 (Löschen geerbter Rolle = 409) | **Aufgelöst** — Use-Case-Vorabprüfung + DB-RESTRICT (Punkt 7, Punkt 5). |
| FR-016 (active wird nicht vererbt) | **Aufgelöst** — `reachable_roles` filtert `active` NICHT auf den Eltern (Punkt 1). |
| FR-020 / FR-020a (Live-Push-Reichweite) | **Aufgelöst** — Reverse-Closure-Fan-out (Punkt 4). |
| Performance-Bound (in /clarify nach Plan verschoben) | **Aufgelöst** — kein harter Max-Depth-Guard nötig; `UNION`-Dedup + Visited-Set garantieren Terminierung über endlicher Rollenmenge; realistische Tiefe ≤ ~10 (SC-004). Optionaler Tiefen-Cap als reine Defensive möglich, aber nicht erforderlich. |
