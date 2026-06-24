# Feature Specification: Permission-/Rank-System (Foundation, Phase 1)

**Feature Branch**: `002-permission-rank-system`

**Created**: 2026-06-23

**Status**: Draft

**Input**: User description: "Permission-/Rank-System als Foundation-Feature (Phase 1). Backend-autoritatives Permission-/Rank-System, das die bestehende JooqPermissionResolver-Implementierung hinter dem existierenden PermissionResolver-Port von 'minimal' zu 'vollständig' ausbaut: Rollen-CRUD, Spieler-Rang-Grants, direkte Permission-Grants (je mit optionalem Ablauf), additive Permission-Auflösung über Wildcards, Live-Entzug bei Ablauf/Änderung."

---

## Migrations-Entscheidung *(Pflicht-Einstieg laut Constitution §17)*

**Migrieren wir das, und in welchem Umfang?** — **Ja, vollständig, aber als Neubau hinter dem
bestehenden Port.** Das alte System (JPA/Hibernate, `rank`/`rank_data`/`rank_server_data`, getrennte
Web-Authority-Enum, `ddl-auto`) wird NICHT codeseitig übernommen. Übernommen werden ausschließlich
die *Verhaltensmuster* (siehe `PERMISSION_EXTRACT.md`, Abschnitt „Zielmodell für den Neubau"). Die
heutige minimale Resolver-Implementierung (`team_role_member` + `team_role_permission`, ein Rang pro
Spieler, read-only, kein Ablauf, kein Audit) wird zur vollständigen Permission-Welt ausgebaut.

**Was vom Alten WEGFÄLLT** (Constitution §18):

- Serverbezug der Ränge (`rank_server_data`, `Permission.server`) — Single-Node, eine flache Rolle.
- Die alte Dreiteilung `rank` / `rank_data` / `rank_server_data` — zu **einer** flachen Rolle vereint.
- Die zweite Berechtigungswelt (`RegisteredAuthority`-Enum für Web) — **eine** einheitliche
  Permission-Welt am selben Port.
- Legacy-§/&-Color-Felder (`legacy_*`) — Darstellung nur noch modern (Adventure-tauglich).
- Hibernate `ddl-auto` + Start-SQL (`SchemaSetupListener`) — Schema ist **Flyway**-versioniert.
- Negationen/Deny-Regeln — bewusst nicht eingeführt (es gab im Alten ohnehin keine).
- Nicht durchgesetzte Controller-Permissions — Prüfung gehört in Service/Resolver, nicht nur in
  Annotationen.

**Explizit NICHT Teil dieses Features (Scope-Grenzen):**

- Die Account-Verknüpfung Web-Login ↔ Minecraft-UUID (späteres, separates Auth-Feature). Dieses
  System macht nur die Permission-Welt einheitlich, damit die Verknüpfung später transparent andockt.
- Die Plugin-/Menü-Seite (eigener Slice im Plugin-Repo).
- LuckPerms-Anbindung (der Port bleibt austauschbar, aber bleibt hier DB-backed).

---

## Geklärte Entscheidungen *(aus der Klärung dieses Specs)*

Diese vier zuvor offenen Punkte sind nun verbindlich entschieden:

1. **Default-Rang = impliziter Fallback ohne Zeile.** Es wird keine Mitgliedschaftszeile pro Spieler
   angelegt. Ein Spieler ohne aktiven Rang-Grant erbt bei der Auflösung automatisch die Default-Rolle.
   Kein Bootstrap-Schreibpfad, kein Backfill, ein Wechsel der Default-Rolle wirkt sofort für alle.
2. **Anzeige-Tie-Break: `team_rank` desc → `weight` desc → Rollen-ID asc.** Vollständig deterministisch
   und stabil, unabhängig von der Grant-Reihenfolge.
3. **Live-Ablauf via periodischem Scheduler.** Ein Hintergrund-Job erkennt abgelaufene Grants,
   deaktiviert sie und löst ein Änderungs-Event (Pub/Sub) aus. Robust, zustandslos über Restarts,
   Genauigkeit = Intervall.
4. **Audit-Tiefe: Grant-Felder + getrennte Audit-Historie.** Jeder Grant trägt
   `issued_by`/`issued_at`/`expires_at`/`reason`; zusätzlich hält eine Audit-Historie
   GRANT/REVOKE/EXPIRE fest (analog `config_audit` / `punishment_template_audit`).

---

## Clarifications

### Session 2026-06-23

- Q: Löschen einer Rolle, die noch aktive Rang-Grants hat — blockieren, kaskadierend entziehen oder
  soft-delete? → A: Kaskadierend entziehen (Option B). Beim Löschen wird bei jedem betroffenen
  Spieler der Grant automatisch als REVOKE entzogen; ohne verbleibenden Grant greift der implizite
  Default-Fallback (keine Hierarchie/Herabstufung). Die Default-Rolle bleibt nicht löschbar.
- Q: Zweiter Grant derselben Rolle an denselben Spieler — ablehnen, verlängern oder zweite Zeile?
  → A: Upsert/Verlängern (Option B). Max. ein aktiver Grant pro `(Spieler, Rolle)`; erneuter Grant
  aktualisiert `expires_at`/`reason`/`issued_by` (permanent schlägt befristet), als neuer GRANT
  auditiert.
- Q: Welcher Datentyp bildet den Akteur (`issued_by`, Audit-Akteur) ab? → A: Staff-**UUID**
  (Option B), dieselbe Identität wie `PermissionResolver.staffUuid`. Automatische Einträge (EXPIRE)
  nutzen eine fest konfigurierte Sentinel-/Konsolen-UUID (`SYSTEM`) statt `null`. Begründung: Web
  ist später je Account an eine Minecraft-UUID gebunden — ein Akteur-Typ für alle Quellen.
- Q: Womit startet die automatisch angelegte Default-Rolle? → A: Leeres Permission-Set (Option A).
  Default-Rolle ist zunächst reine Anzeige-/Fallback-Baseline; Spieler-Permissions kommen später je
  migriertem Feature über die normale Rollen-Permission-Konfiguration hinzu.

---

## User Scenarios & Testing *(mandatory)*

> „User" sind hier (a) **Team-Mitglieder/Web-Admins**, die Rollen und Grants verwalten, (b) der
> **Spieler**, dessen effektive Rechte und Anzeige sich daraus ergeben, und (c) die **konsumierenden
> Features** (Punishments, Reports und künftige), die ausschließlich `hasPermission(uuid, permission)`
> aufrufen.

### User Story 1 - Effektive Permission-Auflösung über mehrere Ränge + direkte Grants (Priority: P1)

Ein konsumierendes Feature fragt „Darf dieser Spieler diese Aktion?". Das System bildet die
**Vereinigungsmenge** aller Permissions aus allen *aktiven* Rang-Grants des Spielers (inkl.
impliziter Default-Rolle, falls kein Grant) plus allen *aktiven* direkten Permission-Grants und
beantwortet die Frage — mit Wildcard-Unterstützung (`feature.*`, `*`), ohne Negationen.

**Why this priority**: Das ist der einzige Vertrag, an dem Punishments und Reports heute schon
hängen. Ohne korrekte Auflösung funktioniert nichts; mit ihr ist das Foundation-Feature für die
bestehenden Konsumenten sofort nutzbar. Diese Story allein ist ein lauffähiger MVP.

**Independent Test**: Über die Port-Schnittstelle `hasPermission(uuid, permission)` testbar:
Spieler mit Rolle X (enthält `report.*`) → `hasPermission(uuid, "report.view")` = true,
`hasPermission(uuid, "punishment.warn")` = false; Spieler ohne Grant erbt Default-Rolle.

**Acceptance Scenarios**:

1. **Given** ein Spieler mit zwei aktiven Rang-Grants (Premium: `home.set`, `home.tp`; Epic: `fly`),
   **When** `hasPermission(uuid, "fly")`, **Then** true (Union über beide Ränge).
2. **Given** ein Spieler, dessen Rolle die Permission `report.*` hält, **When**
   `hasPermission(uuid, "report.handle")`, **Then** true (Wildcard-Präfix matcht).
3. **Given** ein Spieler mit direkter Permission `*` (Admin-Grant), **When** `hasPermission(uuid,
   "irgendwas.beliebig")`, **Then** true (globaler Wildcard).
4. **Given** ein Spieler ohne jeden Grant, **When** `hasPermission(uuid, <perm der Default-Rolle>)`,
   **Then** true (impliziter Default-Fallback).
5. **Given** ein Spieler mit Rolle, die NUR `home.set` hält und keinen passenden Grant, **When**
   `hasPermission(uuid, "home.delete")`, **Then** false (rein additiv, kein impliziter Zusatz).

---

### User Story 2 - Rollen verwalten (CRUD inkl. Rollen-Permission-Konfiguration) (Priority: P1)

Ein Team-Mitglied mit der passenden Berechtigung legt Rollen an, benennt sie um, pflegt deren
Darstellungsdaten (Anzeigename, Farbe, Prefix/Suffix, Tablist, `weight`, `team_rank`-Flag) und
konfiguriert, **welche Permissions zu einer Rolle gehören** (Stammdaten-Pflege, kein zeitlich
begrenzter Grant). Eine Rolle kann deaktiviert oder gelöscht werden.

**Why this priority**: Ohne Rollen gibt es nichts zu granten. Rollen-Permission-Konfiguration ist
die Quelle, aus der die Auflösung (Story 1) speist. Zusammen mit Story 1 ein vollständiger
Verwaltungs-MVP.

**Independent Test**: Rolle „Supporter" anlegen → `report.view` hinzufügen → über den Resolver
prüfen, dass ein Spieler mit Supporter-Grant `report.view` erhält; `report.view` von der Rolle
entfernen → Spieler verliert sie.

**Acceptance Scenarios**:

1. **Given** ein berechtigtes Team-Mitglied, **When** es eine Rolle mit Namen „Supporter" anlegt,
   **Then** existiert die Rolle und ihre Erstellung ist auditierbar (wer/wann).
2. **Given** eine bestehende Rolle, **When** versucht wird, eine zweite Rolle mit demselben Namen
   (case-insensitive) anzulegen, **Then** wird dies abgelehnt.
3. **Given** eine Rolle, **When** eine Permission hinzugefügt/entfernt wird, **Then** wird die
   Rollen-Permission-Konfiguration angepasst und mit `added_by`/`added_at` auditiert (ohne Ablauf).
4. **Given** ein nicht-berechtigtes Team-Mitglied, **When** es eine Rolle anlegen will, **Then**
   wird die Aktion backend-autoritativ abgelehnt (nicht nur UI-Gate).
5. **Given** eine Rolle, die aktiven Spielern zugewiesen ist, **When** sie gelöscht wird, **Then**
   verhält sich das System gemäß definierter Lösch-Regel (siehe Edge Cases) und die betroffenen
   Spieler verlieren die zugehörigen Rang-Permissions.

---

### User Story 3 - Spieler-Rang-Grants mit optionalem Ablauf (Priority: P2)

Ein Team-Mitglied weist einem Spieler eine Rolle zu (Rang-Grant) — permanent (`expires_at = null`)
oder zeitlich begrenzt. Ein Spieler kann **mehrere aktive Rang-Grants gleichzeitig** halten, jeder
mit eigenem `expires_at`. Läuft einer ab, verschwindet **nur diese eine Mitgliedschaft**; andere
bleiben unberührt (keine Downgrade-/Reset-Logik). Jeder Grant trägt `issued_by`/`issued_at`/
`expires_at`/`reason`.

**Why this priority**: Dies führt die in der Bestandsaufnahme fehlende Spieler-Rang-Zuweisung
erstmals ein und ist die Voraussetzung für Premium/Epic/Supporter-Modelle. Baut auf Story 1+2 auf.

**Independent Test**: Spieler erhält Premium (permanent) + Epic (3 Tage). Nach Ablauf von Epic über
den Resolver prüfen: Premium-Permissions bleiben, Epic-Permissions sind weg.

**Acceptance Scenarios**:

1. **Given** ein Spieler ohne Ränge, **When** ihm Premium permanent gewährt wird, **Then** ist
   Premium aktiv und der Grant trägt vollständige Audit-Felder.
2. **Given** ein Premium-Spieler, **When** ihm zusätzlich Epic für 3 Tage gewährt wird, **Then** hält
   er beide Ränge parallel mit unabhängigen Ablaufzeiten.
3. **Given** ein Spieler mit ablaufendem Epic-Grant, **When** die Ablaufzeit überschritten ist,
   **Then** ist der Epic-Grant inaktiv, Premium bleibt aktiv, und der Vorgang ist als EXPIRE
   auditiert.
4. **Given** ein aktiver Rang-Grant, **When** ein Team-Mitglied ihn manuell entzieht (Revoke),
   **Then** ist er sofort inaktiv und als REVOKE auditiert (wer/wann/warum).

---

### User Story 4 - Direkte Permission-Grants mit optionalem Ablauf (Priority: P2)

Ein Team-Mitglied gewährt einem Spieler eine **einzelne Permission** direkt (unabhängig von Rollen),
permanent oder mit Ablauf, mit denselben Audit-/Lebenszyklus-Feldern wie ein Rang-Grant. Direkte
Grants fließen additiv in die Auflösung ein.

**Why this priority**: Deckt Ausnahmefälle ab, die keine eigene Rolle rechtfertigen. Gleiches
Grant-Muster wie Story 3, daher gemeinsam umsetzbar.

**Independent Test**: Spieler ohne passende Rolle erhält direkten Grant `home.set` für 1 Tag →
Resolver liefert true; nach Ablauf false.

**Acceptance Scenarios**:

1. **Given** ein Spieler, **When** ihm `kit.vip` permanent direkt gewährt wird, **Then** liefert der
   Resolver `kit.vip` = true, unabhängig von seinen Rollen.
2. **Given** ein direkter Grant mit Ablauf, **When** die Zeit überschritten ist, **Then** ist die
   Permission nicht mehr wirksam und der Ablauf ist auditiert.
3. **Given** ein direkter Grant `feature.*`, **When** `hasPermission(uuid, "feature.x")`, **Then**
   true (Wildcard auch bei direkten Grants).

---

### User Story 5 - Live-Entzug bei Ablauf/Änderung (Priority: P2)

Läuft eine Rang-Mitgliedschaft oder ein Permission-Grant ab, während der Spieler online ist, oder
ändert ein Team-Mitglied Rollen-Permissions/Grants, muss der Entzug **sofort** wirksam werden — nicht
erst beim nächsten Relog. Das System erkennt ablaufende Einträge und löst ein Änderungs-Event aus,
das live (Pub/Sub) propagiert wird.

**Why this priority**: „Ablauf wirkt live" ist eine feste Anforderung des Zielmodells. Ein bloßes
implizites `isActive(now)` wie bei Punishments reicht nicht, weil aktiv ein Event ausgelöst werden
muss. Setzt Story 3/4 voraus.

**Independent Test**: Online-Spieler mit ablaufendem Grant → Scheduler erkennt Ablauf innerhalb des
Intervalls → ein Änderungs-Event für die UUID wird veröffentlicht; Konsumenten/Caches können darauf
reagieren.

**Acceptance Scenarios**:

1. **Given** ein Online-Spieler mit Grant, der in Kürze abläuft, **When** die Ablaufzeit erreicht
   ist, **Then** wird der Grant innerhalb des Scheduler-Intervalls deaktiviert und ein
   Änderungs-Event für diese UUID veröffentlicht.
2. **Given** ein Team-Mitglied entzieht einem Online-Spieler einen Rang, **When** der Entzug
   gespeichert ist, **Then** wird unmittelbar ein Änderungs-Event veröffentlicht.
3. **Given** ein Team-Mitglied entfernt eine Permission aus einer Rolle, **When** gespeichert,
   **Then** wird ein Änderungs-Event ausgelöst, das alle Halter dieser Rolle betrifft.

---

### Edge Cases

- **Mehrere aktive Team-Ränge / gleiches `weight`**: Anzeige-Tie-Break ist `team_rank` desc →
  `weight` desc → Rollen-ID asc (deterministisch, stabil).
- **Spieler ganz ohne Grant**: erbt implizit die Default-Rolle (keine Zeile nötig).
- **Doppelter Grant derselben Rolle an denselben Spieler**: Es existiert maximal ein aktiver
  Rang-Grant pro `(Spieler, Rolle)`. Ein erneuter Grant aktualisiert den bestehenden (Upsert):
  `expires_at`/`reason`/`issued_by` werden überschrieben, wobei ein permanenter Grant (`null`) einen
  befristeten schlägt; der Vorgang wird als neuer GRANT auditiert. Keine zweite Zeile.
- **Ablauf in der Vergangenheit beim Anlegen**: ein Grant mit `expires_at` in der Vergangenheit gilt
  als sofort inaktiv und darf keine Rechte gewähren.
- **Löschen einer Rolle mit aktiven Grants**: kaskadierend entziehen — beim Löschen einer Rolle wird
  bei jedem betroffenen Spieler der zugehörige Rang-Grant automatisch entzogen (jeder als REVOKE
  auditiert). Hat ein Spieler danach keinen aktiven Rang-Grant mehr, greift der implizite
  Default-Fallback (kein „nächst niedrigerer Rang" — Rollen sind flach, es gibt keine Hierarchie).
  Die Default-Rolle selbst ist nicht löschbar.
- **Default-Rolle deaktivieren/umbenennen**: Default-Rolle muss immer existieren und aktiv sein;
  Löschen/Deaktivieren ist unzulässig.
- **Deaktivierte (Nicht-Default-)Rolle mit aktiven Grants**: Die Grants bleiben bestehen, tragen aber
  bis zur Reaktivierung weder zur Auflösung noch zur Anzeige bei (FR-007a) — anders als beim Löschen
  (FR-012a, kaskadierender REVOKE) ist Deaktivieren reversibel und entzieht keine Grants.
- **Unbekannte UUID**: ein nie gesehener Spieler erhält die Permissions der Default-Rolle.
- **Wildcard-Semantik**: `*` matcht alles; `feature.*` matcht `feature.x` und `feature.y.z`. Eine
  exakte Permission ohne passenden Grant matcht nicht. Keine Negation kann ein Match aufheben.
- **Konsistenz für bestehende Konsumenten**: Punishments/Reports rufen weiterhin nur
  `hasPermission(uuid, permission)` — sie dürfen von der Schema-/Implementierungsänderung nichts
  merken (ADMIN behält `*`, MODERATOR behält seine bestehenden Permissions).

## Requirements *(mandatory)*

### Functional Requirements

**Auflösung (Port-Vertrag)**

- **FR-001**: Das System MUSS `hasPermission(uuid, permission)` mit unveränderter Signatur über den
  bestehenden `PermissionResolver`-Port beantworten; bestehende Konsumenten (Punishments, Reports)
  dürfen keinerlei Änderung bemerken.
- **FR-002**: Die effektive Permission-Menge eines Spielers MUSS die **Vereinigung** aller
  Permissions aller *aktiven* Rang-Grants plus aller *aktiven* direkten Permission-Grants sein
  (rein additiv).
- **FR-003**: Das System MUSS Wildcards auflösen: `*` gewährt jede Permission; ein Präfix-Wildcard
  `feature.*` gewährt jede Permission unterhalb von `feature.`.
- **FR-004**: Das System DARF KEINE Negationen/Deny-Regeln unterstützen; keine Permission kann eine
  andere aufheben.
- **FR-005**: Ein Spieler ohne aktiven Rang-Grant MUSS implizit die Permissions der Default-Rolle
  erhalten (Fallback ohne Mitgliedschaftszeile).
- **FR-006**: Ein Grant (Rang oder Permission) mit `expires_at <= jetzt` MUSS als inaktiv behandelt
  werden und keine Rechte gewähren.

**Rollen-Verwaltung (Stammdaten)**

- **FR-007**: Berechtigte Team-Mitglieder MÜSSEN Rollen anlegen, umbenennen, deaktivieren und löschen
  können.
- **FR-007a**: Eine deaktivierte Rolle (`active = false`) MUSS bei der Permission-Auflösung **und** der
  Anzeige-Auswahl ignoriert werden — bestehende Rang-Grants auf diese Rolle bleiben erhalten, liefern
  aber keine Permissions und keine Darstellung, bis die Rolle wieder aktiviert wird. Die Default-Rolle
  kann nicht deaktiviert werden (FR-012).
- **FR-008**: Rollennamen MÜSSEN eindeutig sein (case-insensitive Duplikatsprüfung).
- **FR-009**: Jede Rolle MUSS Darstellungsdaten tragen: Anzeigename, Farbe, Prefix, Suffix,
  Tablist-Daten, `weight` und ein `team_rank`-Flag.
- **FR-010**: `weight` und `team_rank` MÜSSEN ausschließlich die Anzeige beeinflussen, NIE die
  Permission-Auflösung.
- **FR-011**: Berechtigte Team-Mitglieder MÜSSEN die Permissions einer Rolle konfigurieren
  (hinzufügen/entfernen) können; diese Konfiguration ist Stammdaten ohne Ablauf und MUSS mit
  `added_by`/`added_at` auditiert werden.
- **FR-012**: Das System MUSS genau eine Default-Rolle automatisch anlegen und sicherstellen, dass
  sie nicht gelöscht oder deaktiviert werden kann. Sie wird mit **leerem** Permission-Set angelegt;
  konkrete Spieler-Permissions werden später regulär über die Rollen-Permission-Konfiguration
  ergänzt.
- **FR-012a**: Das Löschen einer Rolle mit aktiven Rang-Grants MUSS kaskadierend wirken: jeder
  betroffene Grant wird automatisch als REVOKE entzogen (auditiert), danach wird die Rolle entfernt.
  Spieler ohne verbleibenden Grant fallen auf die Default-Rolle zurück. Es gibt keine
  Herabstufung auf eine andere Rolle.

**Spieler-Grants (Lebenszyklus)**

- **FR-013**: Berechtigte Team-Mitglieder MÜSSEN einem Spieler eine Rolle granten können (Rang-Grant),
  permanent oder mit `expires_at`.
- **FR-014**: Ein Spieler MUSS mehrere aktive Rang-Grants gleichzeitig halten können, jeder mit
  eigenem, unabhängigem `expires_at`; der Ablauf eines Grants DARF andere nicht beeinflussen.
- **FR-014a**: Es DARF höchstens einen aktiven Rang-Grant pro `(Spieler, Rolle)` geben. Ein erneuter
  Grant derselben Rolle MUSS den bestehenden aktualisieren (Upsert: `expires_at`/`reason`/`issued_by`
  überschreiben, permanent schlägt befristet) und als neuen GRANT auditieren — keine zweite Zeile.
- **FR-015**: Berechtigte Team-Mitglieder MÜSSEN einem Spieler eine einzelne Permission direkt
  granten können (Permission-Grant), permanent oder mit `expires_at`.
- **FR-016**: Jeder Grant (Rang und Permission) MUSS die Felder `issued_by`, `issued_at`,
  `expires_at` (nullable = permanent) und `reason` tragen. `issued_by` ist eine **UUID** (dieselbe
  Identität wie `PermissionResolver.staffUuid`; das Webinterface ist später je Account an genau eine
  Minecraft-UUID gebunden).
- **FR-016a**: Automatisch erzeugte Einträge ohne menschlichen Akteur (z. B. EXPIRE durch den
  Scheduler) MÜSSEN eine fest konfigurierte Sentinel-UUID (Konsolen-/`SYSTEM`-Akteur) als Akteur
  tragen, statt `null`. Der konkrete UUID-Wert ist Konfiguration.
- **FR-017**: Berechtigte Team-Mitglieder MÜSSEN aktive Grants manuell entziehen können (Revoke), mit
  sofortiger Wirkung.

**Audit**

- **FR-018**: Das System MUSS jede Grant-Lebenszyklus-Aktion in einer getrennten Audit-Historie
  festhalten: GRANT, REVOKE und EXPIRE, jeweils mit handelndem Akteur (UUID; Sentinel-UUID für
  `SYSTEM`/EXPIRE), Zeitpunkt, betroffener Spieler-UUID, Rolle bzw. Permission und (wo zutreffend)
  Grund — analog `config_audit` / `punishment_template_audit`.

**Anzeige-Auswahl**

- **FR-019**: Bei mehreren aktiven Rängen MUSS die Darstellung deterministisch gewählt werden nach
  der Ordnung `team_rank` desc → `weight` desc → Rollen-ID asc.

**Live-Ablauf / Propagation**

- **FR-020**: Das System MUSS abgelaufene Grants aktiv erkennen (periodischer Mechanismus), sie als
  inaktiv markieren und je betroffener UUID ein Änderungs-Event auslösen.
- **FR-021**: Jede Änderung, die die effektiven Rechte eines Spielers beeinflusst (Grant, Revoke,
  Ablauf, Änderung der Rollen-Permission-Konfiguration), MUSS ein Live-Änderungs-Event auslösen, das
  online befindliche Spieler sofort (ohne Relog) erreicht.

**Sicherheit / Autorität**

- **FR-022**: Alle verändernden Aktionen (Rollen-CRUD, Rollen-Permission-Konfiguration, Grant/Revoke)
  MÜSSEN backend-autoritativ berechtigt werden — die Prüfung liegt im Service/Resolver, nicht nur in
  UI-Gates oder Controller-Annotationen.
- **FR-023**: Web-Admin-Aktionen MÜSSEN über dieselbe einheitliche Permission-Welt gegated werden
  (normale Permission-Strings am selben Port, z. B. `permission.role.edit`) — keine zweite
  Authority-Enum.

**Persistenz-Wahl (begründet, Constitution §6)**

- **FR-024**: Das Datenmodell MUSS **state-stored (CRUD + Audit)** sein, nicht event-sourced:
  Permissions sind config-artige, selten ändernde Daten ohne geld-/urteilskritisches Aggregat. Das
  Schema MUSS Flyway-versioniert sein.

### Key Entities

- **Rolle (Role)**: Flacher Rollen-Stammdatensatz. Attribute: eindeutiger Name, Darstellungsdaten
  (Anzeigename, Farbe, Prefix, Suffix, Tablist), `weight`, `team_rank`-Flag, Aktiv-Status,
  Default-Markierung, Audit (wer/wann erstellt). Kein Serverbezug, keine Vererbung.
- **Rollen-Permission-Konfiguration**: Zuordnung Rolle → Permission-String (Stammdaten, kein Ablauf),
  mit `added_by`/`added_at`. Wildcards erlaubt.
- **Rang-Grant (Player→Role)**: Zuweisung eines Spielers (UUID) zu einer Rolle. Felder: `issued_by`,
  `issued_at`, `expires_at` (nullable), `reason`, Aktiv-Status. Mehrere pro Spieler möglich.
- **Permission-Grant (Player→Permission)**: Direkte Zuweisung eines Spielers (UUID) zu einem
  Permission-String, gleiche Lebenszyklus-/Audit-Felder wie der Rang-Grant.
- **Grant-Audit-Historie**: Append-only-Einträge GRANT/REVOKE/EXPIRE über Rang- und
  Permission-Grants, mit Akteur, Zeitpunkt, Ziel (UUID + Rolle/Permission) und Grund.
- **Default-Rolle**: Genau eine, vom System angelegt, nicht löschbar/deaktivierbar; gilt implizit für
  jeden Spieler ohne aktiven Rang-Grant.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Bestehende Konsumenten laufen unverändert weiter — die komplette bestehende
  Test-Suite von Punishments und Reports bleibt grün, ohne Änderung an deren Code.
- **SC-002**: Ein Spieler mit mehreren aktiven Rängen erhält in 100% der Fälle die exakte
  Vereinigungsmenge der Permissions; keine Permission fehlt und keine zusätzliche erscheint.
- **SC-003**: Wildcard-Auflösung ist in 100% der definierten Fälle korrekt (`*`, `feature.*`, exakte
  Treffer, Nicht-Treffer).
- **SC-004**: Läuft ein Grant eines Online-Spielers ab, ist der Entzug innerhalb des
  Scheduler-Intervalls (Zielwert ≤ 60 Sekunden) wirksam und propagiert, ohne dass sich der Spieler
  neu verbinden muss.
- **SC-005**: Die Anzeige-Auswahl bei mehreren Rängen ist deterministisch — gleiche Eingabe liefert
  immer dieselbe gewählte Rolle, unabhängig von der Reihenfolge der Grant-Vergabe.
- **SC-006**: Jede Grant-Lebenszyklus-Aktion (GRANT/REVOKE/EXPIRE) erzeugt genau einen
  nachvollziehbaren Audit-Eintrag (wer/wann/warum). Der Grund ist optional — automatische
  EXPIRE-Einträge tragen die Sentinel-/`SYSTEM`-UUID als Akteur und können ohne Grund sein.
- **SC-007**: Ein Spieler ohne jeden Grant erhält in 100% der Fälle exakt die Permissions der
  Default-Rolle.

## Assumptions

- Single-Node (Constitution §14): kein Distributed-Locking, kein Cross-Server-Sync; Pub/Sub ist der
  Live-Pfad Backend↔Plugin/Webinterface.
- Spieleridentität ist die Minecraft-UUID; die Verknüpfung zu Web-Login-Identitäten ist späteres,
  separates Feature und hier ausdrücklich nicht enthalten.
- Die heutigen `team_role_member`/`team_role_permission`-Tabellen enthalten keine geseedeten
  Mitglieder (nur Rollen-Permission-Seeds für ADMIN/MODERATOR); die Schema-Evolution kann daher ohne
  Spieler-Backfill erfolgen, muss aber die geseedeten ADMIN/MODERATOR-Permissions erhalten.
- Permission-Strings sind frei definierte Punkt-Notation (`feature.aktion`); es gibt keine zentrale
  Enum, die sie einschränkt (einheitliche Permission-Welt).
- Der periodische Ablauf-Scheduler mit Zielintervall ≤ 60 s ist für die Anforderung „Ablauf wirkt
  live" ausreichend; sekundengenaues Scheduling ist nicht gefordert.
- Darstellungsdaten sind modern/Adventure-tauglich; Legacy-§/&-Felder werden nicht übernommen.

## Dependencies

- Bestehender `PermissionResolver`-Port (`application/.../security/PermissionResolver.java`) und seine
  heutige Implementierung (`JooqPermissionResolver`) — werden erweitert, Port-Signatur bleibt.
- Bestehendes Flyway-Schema (zuletzt `V8`); neue Migrationen setzen darauf auf und erhalten die
  geseedeten ADMIN/MODERATOR-Rollen-Permissions.
- Bestehender Redis-Pub/Sub-Live-Pfad als Transport für Änderungs-Events.
- Konsumenten Punishments und Reports (dürfen transparent weiterlaufen).
