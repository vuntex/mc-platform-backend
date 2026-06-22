# Feature Specification: Reports (Moderation — Spieler-Meldungen)

**Feature Branch**: `001-reports`

**Created**: 2026-06-22

**Status**: Draft

**Input**: Migration von Feature #47 (Reports) aus dem alten 1.8.9-Plugin als neues, eigenständiges
Moderation-Modul. Erster, bewusst eng geschnittener Slice. Constitution-Prinzip 16: Reports sind
**Anschuldigung, nicht Urteil** — strikt getrennt vom Punishments-Modul.

---

## Übersicht & Abgrenzung

Ein Spieler kann einen anderen Spieler mit einer Kategorie und einem Freitext-Grund **melden**. Das
Team sieht die offenen Meldungen und arbeitet sie über einen Status-Lebenszyklus ab. Eine Meldung ist
eine **Anschuldigung**: Sie dokumentiert einen Vorwurf, fällt aber kein Urteil und erzeugt **niemals
selbst eine Strafe**. Eine spätere (optionale) Verweis-Beziehung „dieser Report führte zu jener
Strafe" ist ausdrücklich **außerhalb dieses Slices**.

**Was vom alten Plugin WEGFÄLLT** (Constitution-Prinzip 18): Die heutige flüchtige RAM-Haltung (#47:
„RAM — bei Restart verloren") wird durch persistente Backend-Speicherung ersetzt; manuelles
Inventory-Click-Handling und §-Farbcodes entfallen (Menü-Framework + Adventure-Components im Plugin,
außerhalb dieses Backend-Slices).

### In Scope

- Report **erstellen** (Reporter, gemeldetes Ziel, Kategorie, Freitext-Detail).
- **Optionaler Chat-Kontext**: ein unveränderlicher Schnappschuss der letzten ~20 **öffentlichen**
  Chat-Nachrichten im **Umfeld des gemeldeten Spielers** — d. h. ein Gesprächs-Fenster, das Nachrichten
  des Gemeldeten **und** umgebenden öffentlichen Chat anderer Spieler enthalten kann (je: Absender-UUID,
  Text, Zeitstempel), vom Plugin beim Erstellen mitgeliefert, damit das Team das Chat-Verhalten im
  Gesprächskontext nachvollziehen kann. Erfasst wird ausschließlich **öffentlicher** Chat. Bei Reports
  ohne Chat-Bezug bleibt der Kontext leer.
- **Team-Liste** offener Reports (für berechtigte Teamler).
- **Status-Lebenszyklus** `OFFEN → IN_BEARBEITUNG → ERLEDIGT` bzw. `→ ABGELEHNT`, mit Festhalten des
  bearbeitenden Teamlers + Zeitstempel je Statuswechsel (Status-Historie).
- **Live-Benachrichtigung** des Online-Teams bei neuem Report und bei Statuswechsel.
- **Persistenz** über Server-Neustarts hinweg.

### Out of Scope (dieser Slice)

- Support/Tickets (#48) — späteres Geschwister-Modul mit eigenem Lebenszyklus.
- Automatische Verknüpfung/Erzeugung von Punishments aus einem Report.
- Benachrichtigung des Reporters über den Ausgang seiner Meldung.
- Wiedereröffnen abgeschlossener Reports, Kommentar-Threads, Beweis-Anhänge (Screenshots/Logs).
- Aggregation mehrerer Melder gegen dasselbe Ziel zu einer Sammel-Meldung.
- **Lese-/Such-Endpoint für abgeschlossene Reports** (Einzel-`GET`/Historien-/Filter-Sicht) — abgeschlossene
  Reports bleiben gespeichert und DB-auditierbar, sind aber in diesem Slice nicht über die API abrufbar.
  Ein Lese-/Historien-Endpoint kommt bei Bedarf als spätere Erweiterung.
- **Aufbewahrungs-/Lösch-Mechanismus (Retention/Purge)** für (abgeschlossene) Reports inkl. Chat-
  Kontext — kein Auto-Purge in diesem Slice (unbegrenzte Aufbewahrung). Siehe **Offene Punkte /
  Verschoben**.
- **Private Nachrichten (`/msg`) im Chat-Kontext** — nur **öffentlicher** Chat wird erfasst. PNs
  erfordern eine Datenschutz-Policy (Mitlesen fremder PNs durch Reporter/Team) und kommen als spätere
  Erweiterung. Siehe **Offene Punkte / Verschoben**.

---

## Clarifications

### Session 2026-06-22

- Q: Umfasst der Chat-Kontext nur Nachrichten des Gemeldeten oder das umgebende öffentliche
  Gesprächs-Fenster (mehrere Absender)? → A: Kontext-Fenster — die letzten ~20 öffentlichen
  Nachrichten im Umfeld, inkl. umgebenden öffentlichen Chats anderer Spieler; Absender-UUID pro
  Eintrag ist bedeutungstragend (nur öffentlicher Chat).
- Q: Braucht dieser Slice einen Aufbewahrungs-/Lösch-Mechanismus für (abgeschlossene) Reports inkl.
  eingebettetem Dritt-Chat? → A: Nein — unbegrenzt aufbewahren, kein Auto-Purge in Slice 1; eine
  Retention-/Lösch-Policy wird als spätere, eigene Spec behandelt (vertretbar, da nur öffentlicher
  Chat gespeichert wird).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Spieler meldet einen Mitspieler (Priority: P1)

Ein Spieler beobachtet Fehlverhalten und meldet den verantwortlichen Spieler mit einer passenden
Kategorie und einer kurzen Beschreibung. Er bekommt eine Bestätigung, dass die Meldung beim Team
eingegangen ist.

**Why this priority**: Kern-Eingabeseite des Features. Ohne Erstellen gibt es nichts zu bearbeiten —
das ist die kleinste Einheit, die für sich genommen schon Wert liefert (Meldungen werden erfasst und
gehen nicht mehr bei Restart verloren).

**Independent Test**: Ein Spieler erstellt eine Meldung gegen einen anderen Spieler; die Meldung ist
danach dauerhaft gespeichert und mit Status `OFFEN`, Kategorie, Freitext, Reporter, Ziel und
Erstell-Zeitstempel auffindbar — auch nach einem simulierten Neustart.

**Acceptance Scenarios**:

1. **Given** Spieler A ist berechtigt zu melden und Ziel B existiert, **When** A meldet B mit Kategorie
   `CHEATING` und Freitext „fliegt in der Mine", **Then** wird ein Report mit Status `OFFEN`,
   Reporter=A, Ziel=B, Kategorie und Freitext persistent angelegt und A erhält eine Eingangsbestätigung.
2. **Given** A hat gegen B bereits einen Report mit Status `OFFEN` (oder `IN_BEARBEITUNG`), **When** A
   B erneut meldet, **Then** wird **kein** zweiter Report angelegt; stattdessen wird der bestehende
   offene Report zurückgegeben (idempotenter Treffer, kein Fehler im Sinne von „kaputt").
3. **Given** A hat gerade eben einen Report erstellt und der Reporter-Cooldown läuft noch, **When** A
   sofort einen weiteren (anderen) Report erstellen will, **Then** wird die Erstellung abgelehnt mit
   einem Hinweis auf die Wartezeit.
4. **Given** A versucht sich selbst zu melden, **When** Reporter = Ziel, **Then** wird die Erstellung
   abgelehnt (Self-Report unzulässig).
5. **Given** A meldet B und liefert einen Chat-Kontext-Schnappschuss (z. B. die letzten 20
   öffentlichen Nachrichten von B) mit, **When** der Report erstellt wird, **Then** wird der
   Schnappschuss **unverändert** am Report gespeichert (Reihenfolge, Absender-UUID, Text, Zeitstempel
   je Eintrag) und bleibt unveränderlich.
6. **Given** A meldet B **ohne** Chat-Kontext, **When** der Report erstellt wird, **Then** wird der
   Report mit leerem Chat-Kontext angelegt (kein Fehler, Kontext ist optional).

---

### User Story 2 - Team sieht offene Reports (Priority: P1)

Ein berechtigter Teamler öffnet die Liste der offenen Meldungen, um zu sehen, was bearbeitet werden
muss. Nicht-Team sieht die Liste nicht.

**Why this priority**: Ohne Sichtbarkeit für das Team ist eine erfasste Meldung wertlos. Zusammen mit
US1 bildet das den minimal nutzbaren Slice.

**Independent Test**: Mit Testdaten (mehrere Reports in verschiedenen Status) liefert die Abfrage für
einen berechtigten Teamler genau die nicht-abgeschlossenen Reports; ein nicht berechtigter Aufrufer
wird abgewiesen.

**Acceptance Scenarios**:

1. **Given** es existieren Reports in `OFFEN`, `IN_BEARBEITUNG`, `ERLEDIGT` und `ABGELEHNT`, **When**
   ein Teamler mit `report.view` die offene Liste abruft, **Then** erhält er die `OFFEN`- und
   `IN_BEARBEITUNG`-Reports (die zu bearbeitenden), nicht die abgeschlossenen.
2. **Given** ein Aufrufer ohne `report.view`, **When** er die Liste abruft, **Then** wird der Zugriff
   verweigert (backend-autoritativ), unabhängig von etwaigen Client-seitigen UI-Gates.
3. **Given** ein Report mit gespeichertem Chat-Kontext, **When** ein berechtigter Teamler den Report
   im Detail betrachtet, **Then** sieht er den unveränderten Chat-Schnappschuss (Absender-UUID, Text,
   Zeitstempel je Eintrag, in ursprünglicher Reihenfolge).

---

### User Story 3 - Team bearbeitet eine Meldung (Status-Lebenszyklus) (Priority: P2)

Ein Teamler nimmt eine offene Meldung in Bearbeitung, prüft sie und schließt sie als erledigt oder
abgelehnt ab. Wer wann welchen Statuswechsel vorgenommen hat, ist nachvollziehbar.

**Why this priority**: Macht das Feature vollständig „abarbeitbar". Setzt US1 + US2 voraus, liefert aber
den eigentlichen Moderations-Workflow.

**Independent Test**: Ein Teamler führt die Übergänge `OFFEN → IN_BEARBEITUNG → ERLEDIGT` durch; jeder
Schritt hält bearbeitenden Teamler + Zeitstempel fest, und ein unzulässiger Übergang wird abgewiesen.

**Acceptance Scenarios**:

1. **Given** ein Report `OFFEN`, **When** ein Teamler mit `report.handle` ihn auf `IN_BEARBEITUNG`
   setzt, **Then** trägt der Report den bearbeitenden Teamler und den Zeitpunkt des Wechsels.
2. **Given** ein Report `IN_BEARBEITUNG`, **When** der Teamler ihn auf `ERLEDIGT` (oder `ABGELEHNT`)
   setzt, **Then** ist der Report abgeschlossen und erscheint nicht mehr in der offenen Liste; der
   abschließende Teamler + Zeitstempel sind festgehalten.
3. **Given** ein bereits `ERLEDIGT`-er Report, **When** ein Teamler versucht ihn zurück auf `OFFEN` zu
   setzen, **Then** wird der Übergang abgewiesen (kein definierter Übergang in diesem Slice).
4. **Given** ein Aufrufer ohne `report.handle`, **When** er einen Statuswechsel versucht, **Then** wird
   die Aktion verweigert (backend-autoritativ).

---

### User Story 4 - Online-Team wird live informiert (Priority: P3)

Sobald eine neue Meldung eingeht (oder sich ihr Status ändert), wird das aktuell online befindliche
Team zeitnah informiert, ohne aktiv pollen zu müssen.

**Why this priority**: Wertsteigernd (schnelle Reaktion), aber das Feature ist auch ohne Live-Push
nutzbar (Team kann die Liste öffnen). Daher nachrangig, aber Teil des 1:1-Verhaltens („Online-Staff
wird benachrichtigt") und über das etablierte Live-Update-Muster billig zu haben.

**Independent Test**: Ein neuer Report bzw. ein Statuswechsel löst genau ein Live-Ereignis auf dem
Report-Kanal aus, das ein Abonnent (z. B. ein Test-Listener) empfängt und korrekt dekodiert.

**Acceptance Scenarios**:

1. **Given** ein abonnierter Live-Kanal, **When** ein neuer Report erstellt wird, **Then** wird genau
   ein Report-Ereignis veröffentlicht, das Report-Identität, Status, Kategorie und Ziel transportiert.
2. **Given** ein abonnierter Live-Kanal, **When** ein Statuswechsel erfolgt, **Then** wird ein
   entsprechendes Ereignis mit dem neuen Status veröffentlicht.

---

### Edge Cases

- **Self-Report**: Reporter == Ziel → Erstellung abgelehnt.
- **Dedupe-Treffer**: zweiter Report desselben Reporters gegen dasselbe Ziel, solange ein
  `OFFEN`/`IN_BEARBEITUNG`-Report existiert → kein Neuanlegen, bestehender Report wird zurückgegeben.
  Verschiedene Reporter gegen dasselbe Ziel → jeweils eigener Report (kein Dedupe über Reporter hinweg).
- **Cooldown-Verletzung**: Reporter erstellt schneller als der erlaubte Mindestabstand → Ablehnung mit
  Wartezeit-Hinweis.
- **Unbekanntes Ziel**: Meldung gegen eine UUID, zu der kein Spieler bekannt ist → definierte Ablehnung
  (kein stiller Erfolg). *(Annahme zur Behandlung siehe Assumptions.)*
- **Offline-Ziel**: Ziel ist offline → Meldung **ist zulässig** (UUID-zentrisch; Online-Sein ist keine
  Voraussetzung).
- **Ungültiger Statusübergang**: z. B. `ERLEDIGT → OFFEN` oder Überspringen außerhalb des definierten
  Pfades → Ablehnung.
- **Fehlende Berechtigung**: Liste/Statuswechsel ohne `report.view`/`report.handle` → Verweigerung
  (backend-autoritativ; ein Client-UI-Gate ist nur Komfort).
- **Leeres/überlanges Freitext-Detail**: Validierung gegen definierte Längen-Grenzen (siehe
  Assumptions).
- **Konkurrierender Statuswechsel**: zwei Teamler ändern denselben Report nahezu gleichzeitig → es darf
  kein inkonsistenter Zustand entstehen; ein Wechsel gewinnt, der andere sieht den aktuellen Stand bzw.
  eine definierte Konflikt-Antwort.
- **Leerer/fehlender Chat-Kontext**: Plugin liefert keinen oder einen leeren Schnappschuss → Report wird
  mit leerem Kontext angelegt (zulässig).
- **Überlanger Chat-Kontext**: Plugin liefert mehr als die erwartete Obergrenze an Einträgen → das
  Backend validiert/begrenzt gegen eine definierte Maximal-Größe (siehe Assumptions), statt unbegrenzt
  zu speichern.
- **Chat-Kontext-Inhalt**: Der Schnappschuss wird als vom Plugin gelieferte Momentaufnahme
  **unverändert** gespeichert; das Backend kennt den laufenden Chat nicht und rekonstruiert nichts.

---

## Requirements *(mandatory)*

### Functional Requirements

**Erstellen**

- **FR-001**: Ein berechtigter Spieler MUSS einen anderen Spieler melden können, indem er **Ziel**,
  **Kategorie** und ein **Freitext-Detail** angibt; Reporter ist der meldende Spieler.
- **FR-002**: Die **Kategorie** MUSS gegen einen festen, backend-validierten Satz geprüft werden:
  `CHEATING`, `BELEIDIGUNG`, `SPAM_WERBUNG`, `TEAMING_BUG_ABUSE`, `SONSTIGES`. Unbekannte Kategorie →
  Ablehnung.
- **FR-003**: Das System MUSS Self-Reports verhindern (Reporter == Ziel → Ablehnung).
- **FR-004**: Das System MUSS pro **(Reporter, Ziel)** höchstens **einen** nicht-abgeschlossenen Report
  (`OFFEN` oder `IN_BEARBEITUNG`) zulassen. Ein weiterer Erstellversuch in diesem Fenster legt **nichts
  Neues** an, sondern liefert den bestehenden Report zurück (Idempotenz, abgesichert durch eine
  eindeutige Bedingung — Constitution-Prinzip 7).
- **FR-005**: Das System MUSS einen **Reporter-Cooldown** durchsetzen (Mindestabstand zwischen zwei
  Erstellungen desselben Reporters); Verstöße werden backend-autoritativ abgelehnt.
- **FR-006**: Das System MUSS einen neu erstellten Report dauerhaft mit mindestens folgenden Angaben
  speichern: eindeutige Report-Identität, Reporter, Ziel, Kategorie, Freitext-Detail, Status `OFFEN`,
  Erstell-Zeitstempel.
- **FR-007**: Das System MUSS das Freitext-Detail gegen definierte Längen-Grenzen validieren (nicht
  leer; Obergrenze gemäß Assumptions).
- **FR-018**: Das System MUSS beim Erstellen einen **optionalen Chat-Kontext** entgegennehmen: eine
  geordnete Liste von Einträgen, je mit **Absender-UUID**, **Text** und **Zeitstempel**. Der Kontext
  ist ein Gesprächs-Fenster und KANN Einträge **verschiedener Absender** enthalten (Nachrichten des
  Gemeldeten plus umgebender öffentlicher Chat); die Absender-UUID pro Eintrag ist bedeutungstragend.
  Der Kontext ist optional; fehlt er oder ist er leer, wird der Report dennoch angelegt.
- **FR-019**: Das System MUSS den gelieferten Chat-Kontext **unveränderlich** als Teil des
  Report-Datensatzes speichern (state-stored, strukturierte Liste) und ihn weder nachträglich ändern
  noch aus eigenem Wissen ergänzen — das Backend kennt den laufenden Chat nicht (der Ringpuffer ist
  Plugin-seitige Mechanik). Die Größe des Kontexts MUSS gegen eine definierte Obergrenze begrenzt
  werden (siehe Assumptions).

**Sehen / Liste**

- **FR-008**: Ein berechtigter Teamler MUSS die Liste der **offenen** Reports (`OFFEN` +
  `IN_BEARBEITUNG`) abrufen können.
- **FR-009**: Der Zugriff auf die Liste MUSS backend-autoritativ über die Berechtigung `report.view`
  geprüft werden; fehlende Berechtigung → Verweigerung.

**Bearbeiten / Lebenszyklus**

- **FR-010**: Das System MUSS den Status-Lebenszyklus mit genau diesen definierten Übergängen
  durchsetzen: `OFFEN → IN_BEARBEITUNG`, `OFFEN → ABGELEHNT`, `IN_BEARBEITUNG → ERLEDIGT`,
  `IN_BEARBEITUNG → ABGELEHNT`. Alle anderen Übergänge (inkl. Rückwärts-/Sprung-Übergänge) → Ablehnung.
- **FR-011**: Bei jedem Statuswechsel MUSS das System den **bearbeitenden Teamler** (Identität) und den
  **Zeitstempel** des Wechsels festhalten (Status-Historie/Audit).
- **FR-012**: Statuswechsel MÜSSEN backend-autoritativ über die Berechtigung `report.handle` geprüft
  werden; fehlende Berechtigung → Verweigerung.
- **FR-013**: Abgeschlossene Reports (`ERLEDIGT`/`ABGELEHNT`) MÜSSEN aus der offenen Liste verschwinden,
  bleiben aber gespeichert. In diesem Slice sind sie **DB-seitig auditierbar**, aber es gibt **keinen**
  Lese-/Such-Endpoint für abgeschlossene Reports (die offene Liste schließt sie aus, kein
  `GET /api/reports/{id}`). Ein Lese-/Historien-Endpoint für abgeschlossene Reports ist Out-of-Scope
  (siehe unten).
- **FR-014**: Konkurrierende Statuswechsel am selben Report MÜSSEN zu einem konsistenten Endzustand
  führen (kein verlorenes/überschriebenes Update ohne definierte Konflikt-Behandlung).

**Live-Benachrichtigung**

- **FR-015**: Bei Erstellung und bei jedem Statuswechsel MUSS das System genau ein Live-Ereignis auf
  einem dedizierten Report-Kanal veröffentlichen (best-effort nach erfolgreicher Persistenz), das
  mindestens Report-Identität, neuen Status, Kategorie und Ziel transportiert.

**Abgrenzung / Autorität**

- **FR-016**: Ein Report MUSS rein dokumentierend sein: Er DARF **keine** Strafe erzeugen oder auslösen.
  Das Reports-Modul bleibt vom Punishments-Modul getrennt (keine geteilten Idempotenz-/Korrelations-
  Schlüssel, kein Cross-Feature-Coupling).
- **FR-017**: Alle zustandsändernden Operationen (Erstellen, Statuswechsel) MÜSSEN backend-autoritativ
  autorisiert werden; ein Plugin-/Client-seitiges UI-Gate ist nur Komfort und nicht die
  Sicherheitsgrenze.

### Key Entities *(include if feature involves data)*

- **Report**: Eine Meldung als Anschuldigung. Attribute: eindeutige Identität, Reporter (Spieler-UUID),
  Ziel (Spieler-UUID), Kategorie (fester Satz), Freitext-Detail, **optionaler Chat-Kontext**, aktueller
  Status, Erstell-Zeitstempel, zuletzt bearbeitender Teamler + Zeitstempel des letzten Statuswechsels.
  Beziehungen: verweist auf zwei Spieler (Reporter, Ziel); **keine** Beziehung zu Punishments in diesem
  Slice.
- **Chat-Kontext (Schnappschuss)**: Eine geordnete, unveränderliche Liste von Chat-Einträgen
  (Gesprächs-Fenster), die das Plugin beim Erstellen mitliefert. Jeder Eintrag: **Absender-UUID**,
  **Text**, **Zeitstempel**. Kann Einträge **mehrerer Absender** enthalten (Gemeldeter + umgebender
  öffentlicher Chat). Optional (kann leer sein), gehört untrennbar zum Report und repräsentiert nur
  **öffentlichen** Chat.
- **Report-Status**: Lebenszyklus-Zustand aus `{OFFEN, IN_BEARBEITUNG, ERLEDIGT, ABGELEHNT}` mit den in
  FR-010 definierten erlaubten Übergängen.
- **Report-Kategorie**: Fester, backend-validierter Satz von Vorwurf-Typen (FR-002).
- **Status-Historie (Audit)**: Chronologische Spur der Statuswechsel eines Reports mit jeweils
  ausführendem Teamler + Zeitstempel (erfüllt die Nachvollziehbarkeit aus FR-011).

---

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 100 % der erstellten Reports überleben einen Server-Neustart (kein Verlust — direkter
  Kontrast zum heutigen RAM-Verhalten von #47).
- **SC-002**: Zu keinem Zeitpunkt existiert mehr als **ein** nicht-abgeschlossener Report je
  (Reporter, Ziel) — über beliebige parallele Erstellversuche hinweg.
- **SC-003**: 100 % der Self-Report-Versuche werden abgelehnt.
- **SC-004**: 100 % der Zugriffe auf Liste bzw. Statuswechsel durch nicht berechtigte Aufrufer werden
  abgelehnt (auch wenn ein Client-UI das Gegenteil suggeriert).
- **SC-005**: Jeder abgeschlossene Report (`ERLEDIGT`/`ABGELEHNT`) trägt nachweislich den
  abschließenden Teamler und den Abschluss-Zeitpunkt.
- **SC-006**: 100 % der unzulässigen Statusübergänge werden abgelehnt.
- **SC-007**: Ein neuer Report ist für ein online befindliches, berechtigtes Team-Mitglied ohne aktives
  Nachladen innerhalb weniger Sekunden sichtbar (Live-Push), gemessen vom Erstellzeitpunkt bis zum
  Empfang des Ereignisses.
- **SC-008**: Kein Report erzeugt jemals eine Strafe (das Reports-Modul hat keinen Schreibpfad ins
  Punishments-Modul).
- **SC-009**: Ein mitgelieferter Chat-Kontext wird zu 100 % unverändert gespeichert und wiedergegeben
  (gleiche Einträge, gleiche Reihenfolge, je Absender-UUID/Text/Zeitstempel) — nach Erstellung gibt es
  keinen Pfad, der ihn verändert.

---

## Assumptions

- **Persistenz-Wahl (Constitution-Prinzip 6, hier begründet)**: Reports werden **state-stored** mit
  Status-Lebenszyklus + Status-Historie/Audit gehalten (konzeptuell analog `server_config`/
  `config_audit`), **nicht** event-sourced. Begründung: Ein Report ist kein geld-/concurrency-
  kritisches Aggregat mit Audit-Pflicht über jede Mutation wie Economy/Punishments; der relevante
  Audit-Bedarf (wer hat wann den Status geändert) wird durch die Status-Historie gedeckt. Reports sind
  damit bewusst **kein** drittes event-sourced Geschwister — die in PROGRESS.md notierte
  Rule-of-three-Extraktion bleibt davon unberührt.
- **Berechtigungsmodell**: „Melden" ist standardmäßig allen Spielern erlaubt (optionale Permission
  `report.create`, default erlaubt). „Sehen" erfordert `report.view`, „Bearbeiten" `report.handle` —
  geprüft über den bestehenden, backend-autoritativen Berechtigungs-Port (wiederverwendet aus
  Punishments; keine neue Berechtigungs-Infrastruktur).
- **Cooldown-Default**: Mindestabstand zwischen zwei Erstellungen desselben Reporters = **60 Sekunden**
  (konfigurierbar, nicht hartcodiert — sinnvoller Default; finaler Wert im Plan/Config festzulegen).
- **Freitext-Grenzen**: Detail ist Pflicht (nicht leer/whitespace) und auf eine sinnvolle Obergrenze
  begrenzt (Annahme: **256 Zeichen**; finaler Wert im Plan).
- **Unbekanntes Ziel**: Default-Annahme ist, dass nur gegen bekannte Spieler (per UUID/Name auflösbar)
  gemeldet werden kann; ein nicht auflösbares Ziel führt zu einer definierten Ablehnung. (Falls das
  Plugin nur Online-Spieler als Ziel anbietet, ist dies ohnehin gegeben.)
- **Liste-Umfang**: „Offene Liste" meint die zu bearbeitenden Reports (`OFFEN` + `IN_BEARBEITUNG`).
  Filter/Pagination/historische Sicht (abgeschlossene Reports durchsuchen) sind nicht Teil dieses
  Slices, das Datenmodell schließt sie aber nicht aus.
- **Single-Server (Constitution-Prinzip 14)**: Kein Cross-Server-Bedarf; der konkurrierende
  Statuswechsel (FR-014) ist ein lokaler Nebenläufigkeitsfall, kein verteiltes Locking.
- **Menü/UI**: Das Spieler- und Team-Menü (Adventure-Components, Menü-Framework gemäß `MENU_DESIGN.md`)
  ist Plugin-Arbeit und liegt außerhalb dieses Backend-Slices; diese Spec definiert das **Verhalten**,
  nicht die Menü-Darstellung.
- **Chat-Ringpuffer ist Plugin-Mechanik (Architektur-Vorgabe)**: Der Puffer der letzten N öffentlichen
  Nachrichten pro Spieler ist ein **lokaler RAM-Ring im Plugin**. Das Backend hat **keine** Kenntnis
  des laufenden Chats und kein Chat-Empfangs-/Mitschnitt-Feature; es empfängt den Schnappschuss
  ausschließlich als Teil des Report-Requests und speichert ihn passiv. Aufbau/Pflege des Rings,
  Filterung auf „öffentlich" und die Auswahl der ~20 Nachrichten passieren Plugin-seitig (außerhalb
  dieses Backend-Slices).
- **Chat-Kontext-Grenzen (Default)**: erwartete Größe ~**20 Einträge**; das Backend begrenzt gegen eine
  Obergrenze (Annahme: **max. 30 Einträge**, **Text je Eintrag ≤ 256 Zeichen**) gegen missbräuchlich
  große Requests. Finale Werte im Plan/Config.

## Offene Punkte / Verschoben

- **Private Nachrichten (`/msg`) im Report-Kontext** — bewusst **nicht** in diesem Slice. Voraussetzung
  ist eine **Datenschutz-Policy** für das Mitlesen fremder PNs durch Reporter und Team. Erwartete
  Leitregel für die spätere Erweiterung: PNs werden nur dann Teil des Kontexts, **wenn der Reporter
  selbst Gesprächsteilnehmer war**. Bis dahin erfasst der Chat-Kontext ausschließlich öffentlichen Chat.
  → Wird beim Aufgreifen als eigene Spec/Spec-Erweiterung behandelt, nicht hier implementiert.
- **Aufbewahrungs-/Lösch-Policy (Retention/Purge)** — in diesem Slice werden Reports **unbegrenzt**
  aufbewahrt (auch nach `ERLEDIGT`/`ABGELEHNT`), inkl. eingebettetem öffentlichem Chat-Kontext; es gibt
  **keinen** Auto-Purge-/TTL-Mechanismus. Eine spätere, eigene Spec definiert die Retention-Dauer und
  einen Lösch-/Anonymisierungs-Pfad (gleiche Datenschutz-Familie wie die verschobene PN-Frage). Bis
  dahin gilt: vertretbar, weil ausschließlich **öffentlicher** Chat gespeichert wird.
