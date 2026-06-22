# Phase 0 — Research & Decisions: Permission-/Rank-System

Alle „NEEDS CLARIFICATION" der Technical Context sind hier aufgelöst. Format je Punkt:
Decision / Rationale / Alternatives considered.

---

## R1 — Persistenz: state-stored (nicht event-sourced)

**Decision**: State-stored CRUD + getrennte Audit-Tabelle, exakt nach dem **Reports**-Muster
(`report` + `report_status_history`), NICHT nach Economy/Punishment (Event-Store).

**Rationale**: Permissions sind config-artige, selten ändernde Stammdaten ohne geld-/urteilskritisches
Aggregat (Constitution §6, §13). Der einzige Audit-Bedarf — „wer hat wann was gegrantet/entzogen" —
wird durch eine dedizierte `grant_audit`-Tabelle gedeckt (analog `config_audit` /
`report_status_history`). Kein Bedarf für Replay, Idempotenz-über-Event-Store oder
Concurrency-kritische Aggregat-Rekonstruktion.

**Alternatives considered**: Event-sourced wie Punishment — verworfen: Overhead ohne Nutzen, kein
Geld/Urteil, keine Replay-Anforderung. Die Spec fixiert state-stored bereits (FR-024).

---

## R2 — `team_role_member` / `team_role_permission`: ablösen (replace), nicht erweitern

**Decision**: Die zwei minimalen Tabellen werden **abgelöst**. Die neue V9-Migration legt das volle
Modell an (`role`, `role_permission`, `player_role_grant`, `player_permission_grant`, `grant_audit`),
**migriert die geseedeten `team_role_permission`-Zeilen** (ADMIN `*`, MODERATOR-Subset, Report-Perms)
in `role` + `role_permission`, legt die **DEFAULT**-Rolle an und **droppt** danach
`team_role_member` + `team_role_permission`.

**Rationale**:
- `team_role_member` enthält **keine** geseedeten Mitglieder (nur Rollen-Permission-Seeds) → kein
  Spieler-Backfill, kein Datenverlust.
- `team_role_member.uuid` ist **PRIMARY KEY** → erzwingt genau einen Rang pro Spieler. Die
  Kern-Entscheidung „mehrere aktive Ränge" ist damit ohne Schema-Ablösung **nicht** umsetzbar; ein
  additiver Anbau wäre nicht möglich.
- Zwei parallele Permission-Welten widersprächen dem Ziel „eine einheitliche Permission-Welt".
- Dieses Feature **besitzt** diese Tabellen und ist laut Auftrag chartered, den Resolver „von minimal
  zu vollständig" auszubauen — Ablösen ist in-scope, **kein** Muster-Leck (der generische
  `PermissionResolver`-Port bleibt unverändert, siehe R3).

**Konsequenz / bewusst (SC-001-Klärung)**: Drei **Test**-Dateien granten heute per direktem Insert in
`TEAM_ROLE_MEMBER` (`JooqPermissionResolverTest`, `PunishmentVerticalSliceTest`,
`ReportVerticalSliceTest`). Nach Ablösung müssen deren Grant-Helfer auf das neue Modell zeigen
(Rolle + `role_permission` + `player_role_grant`). Das ist **Test-Scaffolding der Permission-Welt**,
nicht Produktions-Konsumentencode: `ReportService`/`PunishmentService` und der
`PermissionResolver`-Port bleiben unangetastet. SC-001 (Konsumenten „merken nichts") gilt für
Produktionscode + Port — dort wahr. Empfehlung: einen gemeinsamen Test-Helfer
`grantPermission(dsl, uuid, permission)` einführen, damit künftige Permission-Schema-Änderungen genau
eine Stelle berühren.

**Alternatives considered**:
- *Erweitern + beide Welten parallel lesen* — verworfen: zwei Permission-Quellen, widerspricht der
  Vereinheitlichung, dauerhafte Altlast.
- *Kompatibilitäts-Views `team_role_*` über das neue Modell* — verworfen: hält eine deprecated
  Oberfläche am Leben, nur um drei Test-Insert-Zeilen nicht anzufassen; Over-Engineering.

---

## R3 — Port-Signatur unverändert (kein Muster-Leck)

**Decision**: `PermissionResolver.hasPermission(UUID, String)` bleibt **byte-identisch**. Nur die
**Implementierung** `JooqPermissionResolver` wird reicher (Union über aktive Rang-Grants +
Default-Fallback + direkte Permission-Grants + Wildcard + `now()`-Filter). Neue Fähigkeiten
(Rollen-CRUD, Grants, effektive-Permissions-Abfrage) entstehen als **eigene** Ports/Services im neuen
Package `application/permission`, nicht als Erweiterung des bestehenden Ports.

**Rationale**: Constitution §12 — die Backing-Quelle ist hinter dem Port austauschbar; Punishments und
Reports hängen an genau dieser Signatur und dürfen nichts merken. Ein Aufbohren des Ports wäre ein
Muster-Leck.

**Pattern-Leak-Check**: KEIN Leck. Die einzige Änderung an geteiltem/generischem Code ist **eine
Zeile** in `PlatformProtocol.create()` (Codec-Registrierung) — die laut Klassen-Kommentar
ausdrücklich vorgesehene Stelle (`// future feature codecs plug in here (… permissions …)`).

---

## R4 — Auflösung korrekt-per-Query; Scheduler nur für Live-Push (Live-Ablauf-Verortung)

**Decision**: Zwei klar getrennte Verantwortungen:

1. **Korrektheit** von `hasPermission`: Der jOOQ-Resolver filtert **in SQL** gegen die DB-Zeit:
   `active = true AND (expires_at IS NULL OR expires_at > now())`. Damit ist die Antwort **immer**
   korrekt — auch in der Lücke zwischen tatsächlichem Ablauf und dem nächsten Scheduler-Tick. Der
   Resolver bleibt dadurch **clock-frei** (kein `Clock`-Inject; Postgres `now()` ist die Zeitquelle;
   Testcontainers-Tests setzen `expires_at` in Vergangenheit/Zukunft).
2. **Live-Push** (FR-020/FR-021): Ein **periodischer Scheduler** (im `app`-Modul, Zielintervall
   ≤ 60 s) findet Grants mit `expires_at <= now() AND active`, setzt sie `active = false`, schreibt je
   einen `grant_audit(EXPIRE)`-Eintrag und **publiziert** pro betroffener UUID ein
   `PermissionChangedEvent` auf `mc:permission:changed`. So wirkt der Entzug live, ohne dass die
   Korrektheit vom Scheduler abhängt.

**Verortung des Triggers**: `application/permission/GrantExpiryService` (framework-frei, testbar) hält
die Sweep-Logik; im `app`-Modul ruft ein `@Scheduled`-Bean (`SchedulingConfig`, mit
`@EnableScheduling`) `GrantExpiryService.sweep()` periodisch auf. `@EnableScheduling` ist der **erste**
Scheduler im Backend → wird als kleiner, bewusster Composition-Root-Baustein eingeführt (kein
generischer Klassen-Eingriff).

**Rationale**: Trennung von Korrektheit (Query) und Benachrichtigung (Push) macht das System robust
über Restarts (zustandslos), vermeidet Timer-Verwaltung und entspricht der Spec-Entscheidung
„periodischer Scheduler". Der `active`-Flag dient außerdem dem Upsert-Constraint (R5).

**Alternatives considered**: Zeitgenaues Scheduling pro Grant — verworfen (Spec-Entscheidung). Reine
`now()`-Filterung ohne `active`-Flag — verworfen, weil (a) der Live-Push einen „seit-letztem-Tick neu
abgelaufen"-Bestand braucht und (b) das Upsert-Unique (R5) einen materialisierten Aktiv-Zustand
braucht.

---

## R5 — Grant-Lebenszyklus: ein Zeile je `(Spieler, Rolle)`, soft-`active`

**Decision** (löst den an den Plan delegierten Punkt): Genau **eine** Zeile pro `(uuid, role_id)` bzw.
`(uuid, permission)`, mit Spalte `active BOOLEAN`. **Voll**-Unique-Constraint auf `(uuid, role_id)`
(nicht partiell — `now()` ist in partiellen Indizes nicht erlaubt).
- **Grant/Re-Grant (Upsert, FR-014a)**: `INSERT … ON CONFLICT (uuid, role_id) DO UPDATE SET
  active=true, expires_at=…, reason=…, issued_by=…, issued_at=now()`. Permanent (`null`) schlägt
  befristet. Jeder Vorgang → `grant_audit(GRANT)`.
- **Revoke (FR-017)**: `UPDATE … SET active=false` + `grant_audit(REVOKE)` + Publish.
- **Expire (R4)**: Scheduler `UPDATE … SET active=false WHERE expires_at<=now() AND active` +
  `grant_audit(EXPIRE)` + Publish.
- **Resolution**: zählt nur `active=true AND (expires_at IS NULL OR expires_at > now())`; bei
  Rang-Grants zusätzlich `role.active = true` (deaktivierte Rolle trägt nichts bei, FR-007a).

**Rationale**: Honoriert FR-020 wörtlich („als inaktiv markieren", kein Hard-Delete), hält genau eine
Zeile je Paar (sauberes Upsert + Unique), und die lückenlose Historie liegt in `grant_audit`. Direkte
Permission-Grants analog mit `(uuid, permission)`.

**Alternatives considered**: Hard-Delete bei Revoke/Expire (Historie nur in `grant_audit`) — sauber,
aber widerspricht der Spec-Formulierung „inaktiv markieren" und verliert die bequeme
„zeig auch abgelaufene Grants dieses Spielers"-Direktabfrage. Partielles Unique mit `now()` — technisch
unmöglich.

---

## R6 — Wildcard-Matching in SQL (Hot-Path) + reine Domänenfunktion (Kern)

**Decision**: Die **Wahrheit** der Wildcard-Regel lebt als reine Domänenfunktion
`PermissionMatcher.matches(Collection<String> granted, String query)` in core-domain (testbar, ohne
Framework). Der Hot-Path `hasPermission` implementiert dieselbe Regel **in SQL**:
```
granted.permission = :query
  OR granted.permission = '*'
  OR (granted.permission LIKE '%.*' AND :query LIKE substr(granted.permission, 1, len-1) || '%')
```
konkret: ein gespeichertes `feature.*` matcht `:query`, wenn `:query` mit `feature.` beginnt; `*`
matcht alles; sonst exakter Vergleich. Queries der Konsumenten sind immer **konkret**
(z. B. `report.view`), nie selbst Wildcards.

**Rationale**: Eine Domänenfunktion als testbare Wahrheit (Union/Wildcard/`isActive`) erfüllt den
Auftrag „testbarer Kern ohne jOOQ/Spring". Die SQL-Spiegelung hält `hasPermission` als eine
indexierbare Query schnell. Beide werden gegeneinander getestet (gleiche Fälle, gleiches Ergebnis).

**Alternatives considered**: hasPermission lädt alle Grants und matcht in Java — verworfen für den
Hot-Path (mehr Roundtrips), aber genau das nutzt die **read**-Use-Case „effektive Permissions
auflisten" (selten, fürs Web/Plugin-Cache), wo die Domänenfunktion direkt läuft.

---

## R7 — Pub/Sub-Event-Form (`PermissionChangedEvent`) — per betroffener Spieler

**Decision**: `PermissionChangedEvent` ist **pro betroffenem Spieler**. Wire-Payload (JDK-only,
pipe-delimited, 3 Teile, nach Vorbild `ReportChangedEventCodec`):
```
playerUuid | changeType | timestampEpochMilli
```
`changeType` ∈ `GRANT_ADDED | GRANT_REVOKED | GRANT_EXPIRED | ROLE_CONFIG_CHANGED`. Bei Änderung der
**Rollen-Permission-Konfiguration** (betrifft alle Halter) ermittelt das Backend die aktuellen
aktiven Halter der Rolle und publiziert **ein Event je Halter-UUID**.

**Rationale**: Hält den Contract minimal und den Plugin-Konsum einfach (Plugin lädt effektive Perms
für die UUID neu / invalidiert deren Cache). Rollen-Permission-Edits sind seltene Admin-Aktionen, der
Fan-out ist vertretbar. Das Backend kennt online/offline nicht — es publiziert für alle Halter; das
Plugin ignoriert UUIDs, die es nicht cached.

**Alternatives considered**: Rollen-skopiertes Event (`roleId`) mit Plugin-seitiger Halter-Auflösung —
verworfen: verschiebt Auflösungslogik ins Plugin, der Contract müsste Rollen-Mitgliedschaft kennen.

---

## R8 — Akteur-Identität (`issued_by`, Audit-Akteur) = UUID + Sentinel

**Decision**: Alle Akteur-Spalten sind `UUID` (gleiche Identität wie `PermissionResolver.staffUuid`).
Automatische Einträge (EXPIRE durch Scheduler) tragen eine **konfigurierte Sentinel-/Konsolen-UUID**
(`mcplatform.permission.system-actor-uuid`, Default eine feste Konstante) statt `null`.

**Rationale**: Klärungsentscheidung dieses Specs (FR-016/FR-016a). Das Webinterface ist später je
Account an eine Minecraft-UUID gebunden → ein Akteur-Typ für Game, Web und System.

**Alternatives considered**: Freiform-String / nullable Akteur — verworfen in der Spec-Klärung.

---

## R9 — Reuse-Inventar (Constitution §10)

| Baustein | Wiederverwendung in diesem Feature |
| --- | --- |
| `PermissionResolver`-Port | Bleibt; Impl wird angereichert. Wird zugleich vom eigenen Service als Gate genutzt (Schreibpfade), wie `PunishmentService`/`ReportService`. |
| Pub/Sub-Publish-Pfad | `RedisPermissionEventPublisher` spiegelt `RedisReportEventPublisher` 1:1 (RedisCacheAdapter + `PlatformProtocol.create()` + Codec). |
| Audit-Stil | `grant_audit` nach `config_audit` / `report_status_history`. |
| Protocol-Generik | `Channels.of`, `EndpointDescriptor`, `MessageEnvelope`/`MessageCodec`, Registrierung in `PlatformProtocol.create()`. |
| jOOQ-Codegen | Auto-Generierung aus Flyway (jooq-docker); neue V9-Tabellen erzeugen jOOQ-Klassen ohne Build-Änderung. |
| Persistenz-/Composition-Muster | `JooqXRepository` + `@Bean` in `PersistenceConfig`; Feature-`@Configuration` (`PermissionConfig`) wie `ReportConfig`. |
| Fehler-Mapping | Feature-eigener `PermissionExceptionHandler`, nur eigene Exceptions (403 `PermissionDeniedException` ist bereits global gemappt — NICHT erneut mappen). |

---

## R10 — Offene Plan-Detailpunkte (in diesem Plan entschieden)

- **`reason` optional**: `reason` ist `nullable` bei Grants (eine Rang-Vergabe ohne Begründung ist
  zulässig); Audit-Zeilen tragen den Grund wo vorhanden.
- **Resolver-Caching**: kein eigener Cache in Phase 1 — die zwei kleinen JOIN-Queries sind indexiert
  und günstig; ein späterer Cache säße hinter demselben Port. Live-Push existiert bereits für das
  Plugin-seitige Cache.
- **Default-Rolle-Auflösung**: rein im Resolver/Use-Case (Fallback ohne Zeile), kein Bootstrap-Write.
