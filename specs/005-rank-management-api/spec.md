# Feature Specification: Rank-Management-Backend (schreibende CRUD-Endpoints)

**Feature Branch**: `005-rank-management-api`

**Created**: 2026-06-24

**Status**: Draft

**Input**: User description: Schreibende CRUD-Endpoints fürs Rollen-/Permission-/Grant-Management, vom künftigen Webinterface genutzt, mit Live-Push. Greenfield-Erweiterung des bestehenden Permission-Systems (kein Altplugin-Import).

## Kontext & Abgrenzung *(verbindlich vorab)*

Das Permission-/Rank-Backend existiert bereits (Feature `002-permission-rank-system`): Resolution, Rollen, Grants, Audit, Expiry-Sweep und ein **Live-Push-Pfad** (`mc:permission:changed` mit `PermissionChangedEvent` + Codec, registriert im geteilten Protocol) sind gebaut und getestet. Auch eine **schreibende Use-Case-Schicht** (`PermissionAdminService`) samt interner REST-Fläche (`/api/permission/**`) ist vorhanden — diese gilt heute als **interner/Plugin-/System-Pfad** und vertraut darauf, dass der Aufrufer die Akteur-UUID mitschickt.

Dieses Feature liefert die **vom Webinterface nutzbare, JWT-abgesicherte Schreibseite**: Ein über das Webinterface eingeloggter Administrator (JWT aus Feature `004-jwt-login-session`) verwaltet Rollen, deren Permissions und die Zuweisungen an Spieler. Die Akteur-Identität kommt **aus dem Token**, nicht aus dem Request-Body. Jede erfolgreiche Änderung wird sofort live wirksam — auch für gerade online befindliche Spieler.

**Wiederverwendung statt Neubau:** Die Schreiblogik, die Repositories, der Audit-Trail und der Pub/Sub-Pfad existieren. Dieses Feature steckt eine **autorisierte, token-getriebene Eingangsfläche** davor — es baut keine zweite Schreiblogik und keinen zweiten Live-Push.

## Clarifications

### Session 2026-06-25

- Q: Audit-Umfang bei Rollen-Änderungen (nur Grants vs. auch Rollen-Stammdaten/Rollen-Permissions)? → A: Voller Audit — Grants **und** Rollen-Stammdaten (anlegen/bearbeiten/löschen) **und** Rollen-Permission-Änderungen werden append-only protokolliert (Aktion, Ziel, Aussteller, Zeit).
- Q: Schutz vor Aussperren des letzten Verwaltungs-Akteurs (`*`/`permission.*`)? → A: Keine harte Sperre in diesem Slice; Restrisiko + Recovery-Weg (Seed/DB) als Annahme dokumentiert.
- Q: Lese-Umfang der Web-Fläche — auch Audit-/History-Lesen? → A: Nein; nur Ist-Zustand lesen. Audit wird geschrieben (FR-025/025a), die Historie-Anzeige ist ein späterer Slice.
- Q: (aus /speckit.analyze F1) Lösen reine Anzeige-Attribut-Edits einer Rolle einen Live-Push aus? → A: Nein — kosmetische Edits (Name/Farbe/Prefix/Suffix/Tab/Gewicht/Icon) wirken beim nächsten Refresh/Relog; Live-Push nur bei rechte-/aktiv-relevanten Änderungen (FR-026/FR-028).

## User Scenarios & Testing *(mandatory)*

### User Story 1 — Rollen verwalten (Priority: P1)

Ein berechtigter Administrator legt über das Webinterface neue Rollen an, bearbeitet ihre Anzeige-/Gewichtungs-Attribute und löscht nicht mehr benötigte Rollen. Er sieht jederzeit die Liste aller Rollen und die Details einer einzelnen Rolle.

**Why this priority**: Rollen sind das Fundament des Systems — ohne sie gibt es nichts zu konfigurieren oder zuzuweisen. Diese Story allein liefert bereits einen nutzbaren Rollen-Editor.

**Independent Test**: Mit einem gültigen Admin-JWT eine Rolle anlegen, sie in der Liste wiederfinden, bearbeiten, und (sofern leer) löschen — vollständig über die API testbar, ohne die anderen Stories.

**Acceptance Scenarios**:

1. **Given** ein eingeloggter Akteur mit `permission.role.create`, **When** er eine Rolle mit Name/Gewicht/Anzeige-Attributen anlegt, **Then** wird die Rolle persistiert und in der Rollenliste sichtbar.
2. **Given** ein Akteur **ohne** das nötige Recht, **When** er eine Rolle anzulegen versucht, **Then** wird die Aktion abgelehnt (403) und nichts wird geschrieben.
3. **Given** ein Akteur **ohne** gültiges/aktuelles JWT, **When** er irgendeinen Endpoint dieses Features aufruft, **Then** wird er abgewiesen (401), bevor eine Rechteprüfung stattfindet.
4. **Given** eine bestehende Rolle, **When** ein berechtigter Akteur Gewicht/Anzeige-Attribute ändert, **Then** wird die Änderung persistiert; ist die Rolle gerade aktiven Spielern zugewiesen, wird deren effektive Darstellung/Rechtelage live aktualisiert.
5. **Given** ein Akteur mit nur `permission.read` (kein Schreibrecht), **When** er die Rollenliste oder eine Rollen-Detailansicht abruft, **Then** erhält er die Daten; **When** er eine schreibende Aktion versucht, **Then** 403.

---

### User Story 2 — Permissions einer Rolle pflegen (Priority: P1)

Ein berechtigter Administrator fügt einer Rolle Permissions hinzu oder entfernt sie und sieht die aktuelle Permission-Liste einer Rolle. Änderungen wirken sofort für alle Träger der Rolle.

**Why this priority**: Ohne Permission-Zuordnung ist eine Rolle wirkungslos. Gleiche Priorität wie Story 1, weil beide zusammen den „eine Rolle wird wirksam"-Kern bilden.

**Independent Test**: Einer Test-Rolle eine Permission hinzufügen, sie in der Permission-Liste der Rolle sehen, entfernen — über die API testbar.

**Acceptance Scenarios**:

1. **Given** eine Rolle und ein Akteur mit `permission.role.edit`, **When** er eine gültige Permission hinzufügt, **Then** ist die Permission Teil der Rollenkonfiguration und alle aktiven Träger der Rolle erhalten sie ab sofort (live für Online-Spieler).
2. **Given** eine Permission mit ungültiger Syntax, **When** sie hinzugefügt werden soll, **Then** wird sie abgelehnt (422) und nicht gespeichert.
3. **Given** eine Rolle mit einer Permission, **When** ein berechtigter Akteur sie entfernt, **Then** verlieren alle aktiven Träger diese Permission ab sofort (live für Online-Spieler).

---

### User Story 3 — Grants an Spieler verwalten (Priority: P1)

Ein berechtigter Administrator weist einem Spieler eine Rolle oder eine einzelne Permission zu (optional befristet, mit Begründung), widerruft Zuweisungen und sieht die aktuellen Grants eines Spielers. Der handelnde Admin wird automatisch als Aussteller protokolliert.

**Why this priority**: Das ist der häufigste operative Vorgang (Spieler befördern/degradieren, temporäre Rechte). Gleiche Kern-Priorität.

**Independent Test**: Einem Spieler per UUID eine Rolle granten, den Grant in der Grant-Liste des Spielers sehen, widerrufen — über die API testbar; funktioniert auch für eine UUID, die noch nie online war.

**Acceptance Scenarios**:

1. **Given** ein Akteur mit `permission.grant.role`, **When** er einem Spieler eine Rolle grantet, **Then** wird der Grant persistiert, der Aussteller = die Token-UUID des Akteurs, Zeitpunkt automatisch gesetzt, und der betroffene Spieler wird live aktualisiert (falls online).
2. **Given** ein Grant mit `expires_at` in der Vergangenheit, **When** er erteilt werden soll, **Then** Ablehnung (422).
3. **Given** ein Spieler hat bereits einen aktiven Grant derselben Rolle, **When** erneut gegrantet wird, **Then** wird der bestehende Grant aktualisiert (kein Duplikat); ein permanenter Grant verdrängt einen befristeten.
4. **Given** ein bestehender Grant, **When** ein berechtigter Akteur ihn widerruft, **Then** wird er als inaktiv markiert/entfernt, im Audit als REVOKE mit Aussteller festgehalten, und der Spieler live aktualisiert.
5. **Given** eine Spieler-UUID, die noch nie den Server betreten hat, **When** ein berechtigter Akteur ihr eine Rolle grantet, **Then** wird der Grant akzeptiert und gilt, sobald der Spieler das erste Mal beitritt.
6. **Given** ein Widerruf eines Grants, der gar nicht existiert, **When** der Aufruf erfolgt, **Then** ist das Ergebnis idempotent (kein Fehler, kein Audit-Eintrag, kein Live-Push).

---

### Edge Cases

- **Rolle löschen mit aktiven Mitgliedern** → kaskadiert: jeder aktive Träger wird widerrufen (REVOKE + Audit + Live-Push), dann wird die Rolle entfernt (Entscheidung Q2).
- **Default-Rolle** (implizite Fallback-Rolle des Permission-Designs): darf nicht gelöscht und nicht deaktiviert werden (im Bestand bereits geschützt). Umbenennen wird in diesem Feature ebenfalls als unkritisch behandelt/erlaubt, da der Default über ein Flag, nicht über den Namen identifiziert wird (siehe Annahmen).
- **JWT abgelaufen mitten in der Session** → 401, Webinterface muss neu authentifizieren (Refresh-Pfad aus 004).
- **Akteur entzieht sich selbst sein Schreibrecht** (z. B. entfernt sich selbst die Admin-Rolle): erlaubt; nachfolgende schreibende Aufrufe desselben Akteurs scheitern dann konsistent mit 403 (Rechte werden pro Request frisch aufgelöst).
- **„Letzter Admin"-Aussperren**: Es gibt **keine** harte Sperre gegen das Entfernen des letzten Inhabers eines Verwaltungsrechts (`*`/`permission.*`). Tritt der Fall ein, ist das System nur noch per Seed/DB-Eingriff reparierbar (siehe Annahmen). Bewusst akzeptiertes Restrisiko (Entscheidung Q2-Clarify).
- **Doppelte Permission zu einer Rolle hinzufügen** → idempotent (kein Duplikat, kein Fehler).
- **Permission entfernen, die die Rolle nicht hat** → idempotent (kein Fehler).
- **Unbekannte Rolle/unbekannter Spieler bei Lese-/Schreibaufruf** → klar definierter Fehler (404 für unbekannte Rolle) bzw. leeres Ergebnis (Grants eines Spielers ohne Grants = leere Liste, kein 404).
- **Konkurrierende Änderungen** an derselben Rolle/demselben Grant durch zwei Admins → letzte konsistente Schreibung gewinnt; kein Dateninkonsistenz-Zustand (Upsert-Semantik bei Grants, definierte Schreibreihenfolge bei Rollen).
- **Massen-Live-Push**: Entfernen einer Permission von einer Rolle mit sehr vielen Trägern erzeugt potenziell viele Benachrichtigungen → Granularität/Last siehe Annahmen (im Plan zu detaillieren).

## Requirements *(mandatory)*

### Functional Requirements

**Autorisierung & Identität**

- **FR-001**: Jeder Endpoint dieses Features MUSS eine gültige, nicht abgelaufene Web-Session-Identität (JWT aus Feature 004) voraussetzen; fehlt sie, MUSS der Aufruf mit 401 abgewiesen werden, **bevor** eine Rechteprüfung erfolgt.
- **FR-002**: Die handelnde Akteur-Identität (UUID) MUSS ausschließlich aus dem verifizierten Token stammen. Eine im Request-Body/Parameter mitgeschickte Akteur-Angabe DARF NICHT als Identitätsquelle verwendet werden.
- **FR-003**: Schreibende Operationen MÜSSEN backend-autoritativ über den bestehenden Permission-Resolver gegen das jeweils zuständige **granulare `permission.*`-Recht** geprüft werden (Rolle anlegen → `permission.role.create`; Rolle bearbeiten/Rollen-Permission ändern → `permission.role.edit`; Rolle löschen → `permission.role.delete`; Rollen-Grant → `permission.grant.role`; direkter Permission-Grant → `permission.grant.permission`); fehlt das Recht → 403, ohne jede Schreibwirkung. *(Entscheidung Q1: bestehendes granulares Vokabular, kein neues `rank.*`.)*
- **FR-004**: Lesende Operationen MÜSSEN gegen `permission.read` geprüft werden; fehlt es → 403.
- **FR-005**: Die Rechteprüfung MUSS pro Request frisch aufgelöst werden (keine im Token eingebackenen Rollen/Rechte); es DARF kein zweiter Autorisierungsmechanismus neben dem Permission-Resolver entstehen.

**Rollen-CRUD**

- **FR-006**: Berechtigte Akteure MÜSSEN Rollen anlegen können (mindestens: Name, Gewicht/Anzeige-Priorität, Anzeige-Attribute inkl. optionalem Anzeige-Icon).
- **FR-007**: Berechtigte Akteure MÜSSEN bestehende Rollen bearbeiten können (Gewicht, Anzeige-Attribute, Aktiv-Status).
- **FR-008**: Berechtigte Akteure (`permission.role.delete`) MÜSSEN Rollen löschen können. Bei Rollen mit aktiven Mitgliedern MUSS die Löschung **kaskadieren**: Für jeden aktiven Träger wird die Rolle widerrufen (REVOKE + Audit-Eintrag + Live-Push je Halter), danach wird die Rolle entfernt. *(Entscheidung Q2: Kaskade — entspricht dem bereits gebauten Verhalten.)*
- **FR-009**: Akteure MÜSSEN alle Rollen auflisten und eine einzelne Rolle im Detail abrufen können.
- **FR-010**: Die Default-/Fallback-Rolle MUSS vor Löschung und Deaktivierung geschützt sein; ein entsprechender Versuch MUSS mit einem definierten Fehler (409) abgelehnt werden.
- **FR-011**: Rollennamen MÜSSEN eindeutig sein (case-insensitiv); ein kollidierender Name MUSS mit 409 abgelehnt werden.

**Permissions einer Rolle**

- **FR-012**: Berechtigte Akteure MÜSSEN einer Rolle eine Permission hinzufügen und eine Permission entfernen können.
- **FR-013**: Akteure MÜSSEN die Permission-Liste einer Rolle abrufen können.
- **FR-014**: Permission-Strings MÜSSEN backend-autoritativ gegen die im Resolver gültige Syntax (Wildcard-Regeln wie `*`, `feature.*`, exakt; keine Negation) validiert werden; ungültige Strings → 422.
- **FR-015**: Hinzufügen/Entfernen von Rollen-Permissions MUSS idempotent sein (doppeltes Hinzufügen erzeugt kein Duplikat; Entfernen einer nicht vorhandenen Permission ist kein Fehler).

**Grants an Spieler**

- **FR-016**: Berechtigte Akteure MÜSSEN einem Spieler (per UUID) eine Rolle zuweisen können, optional mit Ablaufzeitpunkt und Begründung.
- **FR-017**: Berechtigte Akteure MÜSSEN einem Spieler eine einzelne Permission direkt zuweisen können, optional mit Ablaufzeitpunkt und Begründung.
- **FR-018**: Berechtigte Akteure MÜSSEN Rollen- und Permission-Grants widerrufen können.
- **FR-019**: Akteure MÜSSEN die **aktiven** Grants eines Spielers auflisten können (Ist-Zustand). Das Lesen des Audit-/Änderungsverlaufs ist **nicht** Teil dieses Slices (siehe Scope-Grenzen). *(Entscheidung Q3-Clarify.)*
- **FR-020**: Jeder Grant MUSS den Aussteller (= Token-UUID des Akteurs) und den Ausstellungszeitpunkt automatisch und unveränderlich festhalten; diese Werte DÜRFEN NICHT vom Aufrufer überschreibbar sein.
- **FR-021**: Ein Ablaufzeitpunkt MUSS in der Zukunft liegen; ein vergangener/zeitgleicher Wert → 422.
- **FR-022**: Erneutes Granten derselben Rolle/Permission an denselben Spieler MUSS als Upsert wirken (höchstens ein aktiver Grant je (Spieler, Rolle) bzw. (Spieler, Permission); permanent verdrängt befristet).
- **FR-023**: Ein Grant MUSS für eine Spieler-UUID akzeptiert werden, die noch nie den Server betreten hat (UUID-zentrisch; der Grant gilt ab dem ersten Join). *(Schema-seitig bereits erfüllt — keine Spieler-Zeile als Vorbedingung.)*
- **FR-024**: Widerruf eines nicht existierenden Grants MUSS idempotent sein (kein Fehler, kein Audit-Eintrag, kein Live-Push).

**Audit & Live-Push**

- **FR-025**: Jede schreibende Grant-Operation (GRANT/REVOKE) MUSS einen unveränderlichen Audit-Eintrag mit Aktion, betroffenem Spieler, betroffener Rolle/Permission, Aussteller, optionaler Begründung und Zeitpunkt erzeugen (append-only).
- **FR-025a**: Auch Rollen-Stammdaten-Änderungen (Rolle anlegen/bearbeiten/löschen) und Rollen-Permission-Änderungen (Permission zu Rolle hinzufügen/entfernen) MÜSSEN append-only auditiert werden — je Eintrag: Aktion, betroffene Rolle (+ betroffene Permission bei Permission-Änderungen), Aussteller (= Token-UUID) und Zeitpunkt. *(Entscheidung Q1-Clarify: voller Audit, nicht nur Grants.)*
- **FR-026**: Nach jeder erfolgreichen Operation, die die **effektiven Rechte oder den Aktiv-Status** eines oder mehrerer Spieler verändert (Grant/Revoke, Rollen-Permission-Änderung, Rollen-(De)Aktivierung, Rollen-Löschung), MUSS ein Permission-Änderungs-Event auf den bestehenden Live-Channel publiziert werden, gerichtet auf die betroffene(n) Spieler-UUID(s), sodass das Plugin den/die betroffenen Cache(s) neu lädt. Reine Anzeige-Attribut-Edits einer Rolle (Name/Farbe/Prefix/Suffix/Tab-Attribute/Gewicht/Icon) lösen **keinen** Live-Push aus — sie wirken beim nächsten Refresh/Relog. *(Entscheidung F1: kosmetisch, nicht rechte-relevant; konsistent mit der im Bestand wiederverwendeten `updateRole`-Logik, die nur bei Aktiv-Flag-Wechsel published.)*
- **FR-027**: Der Live-Push MUSS best-effort **nach** dem erfolgreichen Commit der Schreiboperation erfolgen; ein fehlgeschlagener Push DARF die bereits committete Änderung nicht zurückrollen oder den Aufruf fehlschlagen lassen (er wird protokolliert).
- **FR-028**: Operationen, die mehrere Spieler betreffen (Permission zu Rolle hinzufügen/entfernen, Rolle (de)aktivieren, Rolle löschen), MÜSSEN den/die betroffenen **aktiven** Träger live aktualisieren. Die Push-Granularität ist **player-scoped**: ein Event je betroffenem aktivem Träger (bestehendes `PermissionChangedEvent`), nicht ein Rollen-Event. *(Aufgelöst in plan research R4 — keine offene Plan-Entscheidung mehr.)*

**Bestandspfad**

- **FR-029**: Die neue, vom Webinterface genutzte Schreib-/Lesefläche MUSS als **eigene JWT-abgesicherte Endpunktgruppe** (`/api/web/permission/**`) bereitgestellt werden, die hinter dem Token-Filter liegt und die Akteur-UUID aus dem Token zieht. Der bestehende interne Pfad (`/api/permission/**`, Akteur aus dem Request) bleibt **unverändert** bestehen und wird durch dieses Feature weder umgestellt noch entfernt. *(Entscheidung Q3: paralleler, später ausschließlich über JWT erreichbarer Web-Pfad; kein Eingriff in `SecurityConfig` nötig, da der Wildcard `/api/web/**` bereits authentifiziert ist.)*

### Key Entities *(include if feature involves data)*

- **Rolle**: Benannte Sammlung von Permissions mit Anzeige-Attributen (Gewicht/Priorität, Anzeigename, Farbe, Prefix/Suffix, Tab-Attribute, optionales Anzeige-Icon), Aktiv-Status und einem Default-Flag. Bereits existierend.
- **Rollen-Permission**: Zuordnung einer Permission-Zeichenkette zu einer Rolle. Bereits existierend.
- **Rollen-Grant (Spieler→Rolle)**: Aktive Zuweisung einer Rolle an eine Spieler-UUID, mit Aussteller, Ausstellungszeit, optionalem Ablauf und Begründung. Bereits existierend; ohne FK auf eine Spieler-Stammzeile.
- **Permission-Grant (Spieler→Permission)**: Direkte Zuweisung einer einzelnen Permission an eine Spieler-UUID, mit denselben Audit-Feldern. Bereits existierend.
- **Grant-Audit-Eintrag**: Append-only Protokollzeile je GRANT/REVOKE/EXPIRE mit Aktion, Ziel, Aussteller, Begründung, Zeitpunkt. Bereits existierend.
- **Akteur-Identität**: Die aus dem Web-Session-Token abgeleitete Spieler-UUID des handelnden Administrators (Aussteller jedes Grants). Liefert Feature 004.
- **Permission-Änderungs-Event**: Live-Benachrichtigung über den bestehenden Channel, gerichtet auf betroffene Spieler-UUID(s). Bereits existierend.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Ein berechtigter Administrator kann eine Rolle anlegen, ihr eine Permission hinzufügen und sie einem Spieler zuweisen — vollständig über die abgesicherte Schnittstelle, ohne die Akteur-Identität manuell anzugeben.
- **SC-002**: 100 % der schreibenden Aufrufe ohne ausreichendes Recht werden abgewiesen, ohne dass eine Datenänderung erfolgt (kein „teilweise geschrieben").
- **SC-003**: 100 % der Aufrufe ohne gültige Session werden abgewiesen, bevor eine Rechte- oder Datenoperation stattfindet.
- **SC-004**: Eine vorgenommene Rechteänderung ist für einen betroffenen, online befindlichen Spieler innerhalb von 2 Sekunden wirksam (Live-Push), ohne dass der Spieler sich neu verbinden muss.
- **SC-005**: Jede zustandsändernde Grant-Operation erzeugt genau einen Audit-Eintrag mit korrektem Aussteller (= Token-Identität); in keinem Fall ist der Aussteller vom Aufrufer fälschbar.
- **SC-006**: Wiederholtes Granten/Permission-Hinzufügen erzeugt keine Duplikate; wiederholter Widerruf/Entfernen ist fehlerfrei (Idempotenz nachweisbar).
- **SC-007**: Die bereits ausgelieferten Features (Economy, Punishments, Reports, Permission-Resolution, Web-Auth/JWT) bleiben unverändert funktionsfähig (keine Regression).
- **SC-008**: Ein temporärer Ausfall des Live-Push-Transports führt nicht zum Fehlschlag oder Rollback einer ansonsten erfolgreichen Schreiboperation.

## Assumptions

- **Akteur aus Token (FR-002/FR-020)**: Der Aussteller jedes Grants und die Identität jeder schreibenden Aktion stammen aus der verifizierten JWT-UUID (Feature 004). Die Grant-Tabellen führen `issued_by` bereits als Pflichtfeld — keine Schemaänderung nötig. *(Bestätigt den vom Nutzer aufgeworfenen Punkt 3.)*
- **Grant an nie-gejointe Spieler (FR-023)**: Schema-seitig bereits möglich — die Grant-Tabellen haben **keinen** Fremdschlüssel auf eine Spieler-Stammzeile; eine UUID genügt. Es wird kein Spieler-Stub angelegt. *(Löst den vom Nutzer aufgeworfenen Punkt 4 ohne Schemaänderung.)*
- **Default-Rolle (FR-010)**: Über ein Default-Flag identifiziert, nicht über den Namen; bereits vor Löschung/Deaktivierung geschützt. Umbenennen bleibt erlaubt (kein Identitätsbruch). *(Punkt 2 — der Schutz existiert; ein Umbenenn-Schutz wird als nicht nötig angenommen, kann im Plan revidiert werden.)*
- **Live-Push-Granularität (FR-028)**: Standard ist „eine Benachrichtigung je betroffenem (aktivem) Spieler", konsistent zum bestehenden Verhalten der Schreiblogik. Bei Operationen mit sehr vielen Trägern wird im Plan geprüft, ob ein gröber aufgelöstes Rollen-Event die Pub/Sub-Last besser bedient. *(Punkt 5 — Default gesetzt, Detailentscheidung in den Plan verschoben.)*
- **Validierung (FR-014)**: Permission-Syntax, Eindeutigkeit der Rollennamen und Wertebereiche werden serverseitig autoritativ geprüft; die Permission-Syntax folgt exakt den Resolver-Regeln. *(Punkt 6.)*
- **Persistenzmodell**: state-stored CRUD + append-only Audit (kein event-sourced Aggregat) — konsistent zur Constitution (Prinzip 6) und zum bestehenden Permission-Feature; die Audit-Felder am Grant decken den Nachvollziehbarkeitsbedarf.
- **Scope-Grenzen**: Kein Frontend (Next.js separat), kein Plugin-Anteil (Live-Konsumption existiert plugin-seitig bereits — hier nur die Backend-Publish-Seite), kein Economy-Admin/Config/Reports-View, kein Bukkit-Permission-Mirror (laut Permission-Design ohnehin zurückgestellt), **keine Audit-/History-Lese-Endpoints** (Audit wird geschrieben, aber die Verlaufsanzeige ist ein späterer Slice — Q3-Clarify).
- **Kein „letzter-Admin"-Schutz (FR-…/Edge Case)**: Eine korrekte Erkennung des letzten Verwaltungsrecht-Inhabers ist im Wildcard-/Rollen-/Direkt-Grant-Modell nicht zuverlässig bestimmbar; daher keine Laufzeitsperre. Recovery bei versehentlichem Aussperren erfolgt über den Rollen-Seed bzw. einen DB-Eingriff (wie bei der Erstinbetriebnahme der ADMIN-Rolle mit `*`).
- **Abhängigkeit**: Setzt die Web-Session/JWT-Infrastruktur aus Feature 004 voraus (Token-Verifikation, geschützte Web-Fläche).

## Dependencies

- Feature `002-permission-rank-system` (Rollen/Grants/Audit/Resolver/Live-Push-Pfad) — Grundlage, wird wiederverwendet.
- Feature `004-jwt-login-session` (Token-Verifikation, geschützte Web-Fläche, Akteur-Identität) — Voraussetzung.
