# Feature Specification: Web-Auth-Bridge

**Feature Branch**: `003-web-auth-bridge`

**Created**: 2026-06-24

**Status**: Draft

**Input**: User description: "Web-Auth-Bridge — Minecraft-Spieler legt ingame einen Web-Account fürs Webinterface an. Ingame initiiert (Besitz der UUID = Besitz des Accounts), ein kurzlebiger Token überträgt das Vertrauen vom Spiel ins Web, dort setzt der Spieler ein Passwort."

## Überblick & Migrations-Einordnung

**Greenfield-Infrastruktur fürs kommende Webinterface — kein Altplugin-Import.** Im
`FEATURE_INVENTORY.md` gibt es keinen direkten Vorgänger; das alte System hatte nur
`onlinegems`/`onlinemoney`-Hinweise auf eine Webshop-Anbindung, aber keine Web-Account-Bindung.
Scope wird daher frisch definiert; es gibt kein 1:1-Verhalten zu erhalten.

Dieser Slice baut **nur die Brücke**: Account anlegen, Passwort setzen/zurücksetzen, optional die
Verknüpfung lösen. Die laufende Login-Session (Token-Ausstellung/-Prüfung beim Web-Login) ist ein
**separater Folge-Slice** und nicht Teil dieser Spec.

**Vertrauensmodell (das „Warum"):** Ein Minecraft-Spieler, der ingame einen `/web`-Befehl tippt,
hat seine Identität bereits durch den Server-Login bewiesen (Besitz der UUID). Diese Spec überträgt
dieses bereits bestehende Vertrauen über einen kurzlebigen, einmal verwendbaren Token ins Web, wo der
Spieler ein Passwort setzt — ohne E-Mail, ohne Registrierungsformular.

## Clarifications

### Session 2026-06-24

- Q: Token-Sicherheit — welche Unrätbarkeit muss der Token garantieren? → A: Hochentroper, opaker
  Zufalls-Token (≥128 Bit), nicht erratbar/aufzählbar; der Token selbst ist das Geheimnis im Web-Link.
  Zusatz: Er wird als **anklickbare** Komponente (`open_url`) ausgeliefert und nie vom Spieler
  abgetippt — das 256-Zeichen-Chat-Eingabelimit von Minecraft ist damit irrelevant (ein 128–256-Bit-
  Token bleibt ohnehin weit unter jeder Link-/Paketgrenze).
- Q: Audit-Spur — verbindlich oder optional? → A: Verbindlich (MUST). Account-Anlage, Passwort-Reset
  und Token-Einlösung werden append-only protokolliert (Zeitpunkt, UUID, Ereignistyp), analog
  `config_audit` — niemals mit Klartext-Passwort oder Hash.
- Q: Passwort-Obergrenze — welcher konkrete Wert? → A: Harte Obergrenze 64 Zeichen, backend-autoritativ
  abgelehnt bei Überschreitung; liegt sicher unter der 72-Byte-Grenze von BCrypt (auch bei Mehrbyte-
  UTF-8) → kein stilles Abschneiden.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Erstmals Web-Account anlegen (Priority: P1)

Ein Spieler ohne Web-Account tippt ingame `/web link`. Das System bestätigt, dass für seine Identität
noch kein Web-Account existiert, und stellt ihm einen anklickbaren Web-Link mit einem kurzlebigen Token
bereit. Der Spieler öffnet den Link, gibt ein Passwort ein und sendet es ab. Danach existiert für seine
Minecraft-Identität ein Web-Account mit gesetztem Passwort, mit dem er sich später (Folge-Slice) im
Webinterface einloggen kann.

**Why this priority**: Das ist der Kern und die Daseinsberechtigung des Features — ohne ihn gibt es
keinen Web-Account. Alle anderen Stories setzen voraus, dass ein Account entstehen kann. P1 = das
allein ausgelieferte MVP.

**Independent Test**: Vollständig testbar end-to-end: Spieler ohne Account → `/web link` → Token →
Web-Endpunkt mit Token + Passwort → Account existiert und trägt das gesetzte Passwort; ein zweiter
`/web link` desselben Spielers wird mit Verweis auf `/web resetPassword` abgelehnt.

**Acceptance Scenarios**:

1. **Given** für die Spieler-Identität existiert kein Web-Account, **When** der Spieler `/web link`
   ausführt, **Then** wird ein kurzlebiger LINK-Token erzeugt und dem Spieler als anklickbarer Web-Link
   angezeigt.
2. **Given** ein gültiger, nicht abgelaufener LINK-Token, **When** der Spieler im Web ein
   regelkonformes Passwort absendet, **Then** wird der Web-Account angelegt, das Passwort gesetzt und der
   Token verbraucht (nicht erneut einlösbar).
3. **Given** für die Spieler-Identität existiert bereits ein Web-Account, **When** der Spieler
   `/web link` ausführt, **Then** wird die Aktion abgelehnt mit einem klaren Hinweis, stattdessen
   `/web resetPassword` zu nutzen — es entsteht kein zweiter Account und kein neuer LINK-Token.
4. **Given** ein bereits eingelöster oder abgelaufener LINK-Token, **When** er erneut/verspätet
   eingelöst wird, **Then** wird die Einlösung abgelehnt, ohne einen Account zu verändern.
5. **Given** ein gültiger LINK-Token, aber zwischenzeitlich wurde für dieselbe Identität bereits ein
   Account angelegt, **When** der Token eingelöst wird, **Then** schlägt die Einlösung kontrolliert fehl
   (kein zweiter Account, keine Überschreibung).

---

### User Story 2 - Passwort zurücksetzen / Account wiederherstellen (Priority: P2)

Ein Spieler mit bestehendem Web-Account, der sein Passwort vergessen hat, tippt ingame
`/web resetPassword`. Das System bestätigt, dass ein Account existiert, und stellt einen kurzlebigen
RESET-Token als Web-Link bereit. Über den Link setzt der Spieler ein neues Passwort, das das alte
ersetzt.

**Why this priority**: Recovery ist der einzige Wiederherstellungsweg (kein E-Mail-Reset). Wichtig,
aber wertlos ohne US1 — daher P2.

**Independent Test**: Spieler mit Account → `/web resetPassword` → Token → Web-Endpunkt mit Token +
neuem Passwort → Passwort ist ersetzt (alter Hash gilt nicht mehr); `/web resetPassword` ohne
bestehenden Account wird mit Verweis auf `/web link` abgelehnt.

**Acceptance Scenarios**:

1. **Given** für die Spieler-Identität existiert ein Web-Account, **When** der Spieler
   `/web resetPassword` ausführt, **Then** wird ein kurzlebiger RESET-Token erzeugt und als Web-Link
   angezeigt.
2. **Given** ein gültiger RESET-Token, **When** der Spieler im Web ein neues regelkonformes Passwort
   absendet, **Then** wird das Passwort des Accounts ersetzt und der Token verbraucht.
3. **Given** für die Spieler-Identität existiert kein Web-Account, **When** der Spieler
   `/web resetPassword` ausführt, **Then** wird die Aktion abgelehnt mit Verweis auf `/web link`.
4. **Given** ein gültiger RESET-Token, aber der Account wurde zwischenzeitlich entfernt, **When** der
   Token eingelöst wird, **Then** schlägt die Einlösung kontrolliert fehl (kein Account wird angelegt).

---

> **`/web unlink` ist bewusst NICHT Teil dieses Slice** (Entscheidung Q1) — kein Kernpfad, und ein
> Hard-Delete wäre ein de-facto Recovery-Umweg an `/web resetPassword` vorbei. Verschoben in einen
> eigenen Folge-Slice (Konto-Management), siehe „Out of Scope".

### Edge Cases

- **Doppelter Befehl in kurzer Folge**: Zwei `/web link`- (bzw. `resetPassword`-)Aufrufe derselben
  Identität erzeugen **nicht** zwei gültige Tokens — pro Identität und Zweck ist höchstens **ein** Token
  gleichzeitig gültig; ein neuer Aufruf entwertet den vorherigen.
- **Token-Ablauf während der Eingabe**: Läuft der Token ab, während der Spieler im Web tippt, wird die
  Einlösung abgelehnt; der Spieler muss den Befehl ingame erneut auslösen.
- **Identität wechselt Zustand zwischen Token-Erzeugung und Einlösung**: LINK-Token, aber inzwischen
  existiert ein Account → Einlösung scheitert kontrolliert. RESET-Token, aber Account wurde gelöst →
  Einlösung scheitert kontrolliert.
- **Spieler ändert seinen Minecraft-Namen**: Der Web-Account bleibt erhalten (er hängt an der stabilen
  Identität, nicht am Anzeigenamen); kein Account-Verlust.
- **Recycelter/mehrdeutiger Name beim namensbasierten Login-Lookup** (greift erst im Login-Slice, hier
  aber als Regel festgenagelt): Es wird die Identität mit dem **jüngsten letzten Server-Besuch**
  aufgelöst (siehe Assumptions).
- **Gesperrter Spieler**: Wer den Server nicht betreten kann (aktive Zugangs-Sperre), kann keinen
  `/web`-Befehl auslösen und damit keinen Web-Zugang erlangen/wiederherstellen — gewollte Konsequenz
  (siehe Assumptions).
- **Ungültiger/manipulierter Token**: Ein nicht existierender oder syntaktisch falscher Token wird wie
  „ungültig/abgelaufen" behandelt, ohne preiszugeben, ob er je existierte.
- **Schwaches/überlanges Passwort**: Ein Passwort unter 8 oder über 64 Zeichen wird **serverseitig**
  abgelehnt; Account/Passwort bleiben unverändert (die 64er-Grenze verhindert stilles Hash-Abschneiden).
- **Recovery-Spam**: Wiederholtes `/web resetPassword` innerhalb des Cooldowns wird ingame abgelehnt;
  ein bereits erzeugter, noch gültiger Token bleibt davon unberührt.

## Requirements *(mandatory)*

### Functional Requirements

**Identität & Account-Bindung**

- **FR-001**: Das System MUSS jeden Web-Account an genau **eine** stabile Minecraft-Identität (UUID)
  binden. Es DARF keinen Web-Account ohne zugeordnete Identität geben.
- **FR-002**: Eine Identität DARF zu jedem Zeitpunkt höchstens **einen** Web-Account besitzen.
- **FR-003**: Das System MUSS gegen die stabile Identität authentifizieren, **nicht** gegen den
  Anzeigenamen. Eine Namensänderung in Minecraft DARF NICHT zum Verlust oder zur Fehlzuordnung des
  Web-Accounts führen.
- **FR-004** *(verschoben in den Login-Slice — keine Umsetzung in diesem Slice; hier nur als Regel
  fixiert)*: Wo ein Minecraft-Name als Eingabe dient (Web-Login), MUSS er groß-/kleinschreibungs-
  unabhängig zur Identität aufgelöst werden; bei mehreren historischen Treffern MUSS die Identität mit
  dem **jüngsten letzten Server-Besuch** gewählt werden. (Die `/web`-Befehle dieses Slice kennen die UUID
  bereits aus der Session — ein Namens-Lookup wird hier nicht gebraucht.)

**Account anlegen (`/web link`)**

- **FR-005**: Das System MUSS einem Spieler ohne bestehenden Web-Account erlauben, per Ingame-Befehl die
  Account-Anlage zu initiieren.
- **FR-006**: Bei Initiierung der Anlage MUSS das System einen kurzlebigen, einmal verwendbaren
  **LINK-Token** erzeugen, der die initiierende Identität und den Zweck „Anlage" trägt, und ihn dem
  Spieler als anklickbaren Web-Link bereitstellen.
- **FR-007**: Das System MUSS die Anlage-Initiierung ablehnen, wenn für die Identität bereits ein
  Web-Account existiert, und dabei klar auf den Zurücksetzen-Weg verweisen.

**Passwort zurücksetzen (`/web resetPassword`)**

- **FR-008**: Das System MUSS einem Spieler mit bestehendem Web-Account erlauben, per Ingame-Befehl ein
  Passwort-Zurücksetzen zu initiieren.
- **FR-009**: Bei Initiierung des Zurücksetzens MUSS das System einen kurzlebigen, einmal verwendbaren
  **RESET-Token** erzeugen (Identität + Zweck „Zurücksetzen") und als Web-Link bereitstellen.
- **FR-010**: Das System MUSS das Zurücksetzen-Initiieren ablehnen, wenn für die Identität **kein**
  Web-Account existiert, und dabei klar auf den Anlage-Weg verweisen.

**Token-Lebenszyklus**

- **FR-011**: Jeder Token MUSS eine begrenzte Gültigkeitsdauer haben (Zielwert ~10 Minuten) und nach
  Ablauf nicht mehr einlösbar sein.
- **FR-012**: Jeder Token MUSS **einmal verwendbar** sein: Mit erfolgreicher Einlösung MUSS er
  unwiderruflich entwertet werden, sodass eine zweite Einlösung scheitert.
- **FR-013**: Pro Identität und Zweck DARF höchstens **ein** Token gleichzeitig gültig sein; das
  Erzeugen eines neuen Tokens MUSS einen zuvor noch gültigen Token desselben Zwecks entwerten.
- **FR-014**: Das System MUSS sich beim Einlösen **nicht** auf eine Aufräumroutine verlassen, um Ablauf
  zu erzwingen — die Gültigkeitsprüfung beim Einlösen ist die maßgebliche Sicherheitsgrenze.

**Einlösen im Web (Bridge-Endpunkt, ohne Login-Session)**

- **FR-015**: Das System MUSS einen Web-Endpunkt bereitstellen, der einen Token und ein neues Passwort
  entgegennimmt, den Token auf Existenz und Gültigkeit (nicht abgelaufen) prüft und Identität + Zweck
  ermittelt.
- **FR-016**: Bei Zweck „Anlage" MUSS der Endpunkt den Web-Account anlegen und das Passwort setzen; ist
  inzwischen doch ein Account vorhanden, MUSS er kontrolliert fehlschlagen (kein zweiter Account).
- **FR-017**: Bei Zweck „Zurücksetzen" MUSS der Endpunkt das bestehende Passwort ersetzen; fehlt
  inzwischen der Account, MUSS er kontrolliert fehlschlagen (kein Account wird angelegt).
- **FR-018**: Account-Veränderung und Token-Entwertung MÜSSEN **gemeinsam und atomar** geschehen —
  entweder beides (Passwort gesetzt + Token verbraucht) oder nichts.
- **FR-019**: Ein ungültiger, unbekannter oder abgelaufener Token MUSS einheitlich abgelehnt werden,
  ohne offenzulegen, ob er je existierte oder welcher Identität er gehörte.

**Passwort-Behandlung**

- **FR-020**: Das System MUSS Passwörter ausschließlich als nicht umkehrbaren Hash (Industrie-Standard,
  salted) speichern; das Klartext-Passwort DARF NICHT persistiert oder geloggt werden.
- **FR-021**: Das System MUSS die Passwort-Regeln **backend-autoritativ** durchsetzen; eine etwaige
  UI-Prüfung ist nur Komfort und DARF NICHT die einzige Schranke sein. Die Regel ist: **mindestens 8
  und höchstens 64 Zeichen**, keine erzwungenen Zeichenklassen (Länge statt Komplexitätszwang). Die
  Obergrenze von 64 liegt sicher unter der 72-Byte-Grenze des Hash-Verfahrens (auch bei Mehrbyte-UTF-8)
  und verhindert stilles Abschneiden. Ein nicht regelkonformes Passwort (zu kurz oder zu lang) wird
  abgelehnt, ohne Account/Passwort zu verändern.

**Missbrauchs-/Spam-Schutz**

- **FR-022**: Das System MUSS das Token-Erzeugen je Identität und Zweck mit einem **konfigurierbaren
  Cooldown** begrenzen (gleiches Muster wie der Report-Cooldown). Ein erneuter `/web link`-/
  `resetPassword`-Aufruf innerhalb des Cooldowns wird ingame abgelehnt; ein bereits erzeugter, noch
  gültiger Token bleibt unberührt. Dies ergänzt die „ein-Token-pro-Identität/Zweck"-Regel (FR-013) um
  einen Frequenz-Schutz.

**Audit**

- **FR-026**: Das System MUSS sicherheitsrelevante Account-Lebenszyklus-Ereignisse — Account-Anlage,
  Passwort-Zurücksetzung und Token-Einlösung — **append-only** protokollieren (mindestens Zeitpunkt,
  betroffene Identität, Ereignistyp), analog zum bestehenden `config_audit`-Muster. Der Audit-Eintrag
  DARF **niemals** das Klartext-Passwort oder den Passwort-Hash enthalten.

**Token-Sicherheit**

- **FR-024**: Jeder Token MUSS aus einem kryptografisch sicheren Zufallsraum von **≥ 128 Bit** stammen,
  sodass er innerhalb seiner Gültigkeit weder erraten noch aufgezählt werden kann; der Token selbst ist
  das einzige Geheimnis (kein zusätzlicher Verifier).
- **FR-025**: Der Token MUSS dem Spieler als **anklickbare** Web-Link-Komponente bereitgestellt werden
  und DARF NICHT manuelles Abtippen erfordern (das Minecraft-Chat-Eingabelimit ist damit irrelevant).

**Hygiene**

- **FR-023**: Das System SOLLTE abgelaufene Token-Daten regelmäßig aufräumen (reine Hygiene; nicht
  sicherheitskritisch, da FR-014 die Grenze bereits beim Einlösen zieht).

### Key Entities *(include if feature involves data)*

- **Web-Account**: Repräsentiert das Web-Login-Konto eines Spielers. Hängt an genau einer
  Minecraft-Identität (UUID als harter Anker). Trägt ein nicht umkehrbares Passwort-Geheimnis sowie
  Zeitstempel für Anlage und letzte Passwortänderung. **Kein** Anzeigename, **keine** E-Mail.
- **Verknüpfungs-/Reset-Token**: Kurzlebiger, einmal verwendbarer Vertrauensträger vom Spiel ins Web.
  Trägt die zugeordnete Identität, den Zweck (Anlage oder Zurücksetzen) und einen Ablaufzeitpunkt.
  Höchstens einer pro Identität und Zweck gleichzeitig.
- **Account-Audit-Eintrag**: Append-only-Protokollzeile eines sicherheitsrelevanten Lebenszyklus-
  Ereignisses (Anlage / Passwort-Reset / Token-Einlösung). Trägt Zeitpunkt, betroffene Identität und
  Ereignistyp — **nie** das Passwort oder dessen Hash. Folgt dem bestehenden `config_audit`-Stil.
- **Spieler-Identität** *(bestehend, wiederverwendet)*: Stabile UUID + zwischengespeicherter,
  groß-/kleinschreibungs-indizierter Anzeigename mit Zeitstempeln des letzten Besuchs. Quelle für die
  Namens→Identitäts-Auflösung; wird durch dieses Feature **nicht** verändert.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Ein Spieler ohne Web-Account kann von `/web link` bis zum gesetzten Passwort in unter
  **2 Minuten** und ohne externe Hilfsmittel (keine E-Mail, kein Support) gelangen.
- **SC-002**: Für eine Minecraft-Identität existiert zu jedem Zeitpunkt **höchstens ein** Web-Account —
  in **100 %** der Fälle, auch bei doppelt/rasch abgesetzten Befehlen oder doppelter Token-Einlösung.
- **SC-003**: Ein Token ist nach erstmaliger erfolgreicher Einlösung in **0 %** der Fälle ein zweites
  Mal verwendbar, und nach Ablauf der Gültigkeitsdauer in **0 %** der Fälle noch einlösbar.
- **SC-004**: Eine Minecraft-Namensänderung führt in **0 %** der Fälle zu Account-Verlust oder
  -Fehlzuordnung (der Account bleibt an der Identität).
- **SC-005**: Ein abgelehnter Token-/Account-Pfad gibt in **100 %** der Fälle eine einheitliche
  Ablehnung zurück, ohne offenzulegen, ob/für wen ein Token oder Account existiert.
- **SC-006**: Ein abgesetztes Passwort ist in **0 %** der Fälle im Klartext aus persistierten Daten
  oder Logs rekonstruierbar.
- **SC-007**: Ein Angreifer kann innerhalb der Token-Gültigkeit keinen gültigen Token erraten oder
  aufzählen — der nutzbare Token-Raum beträgt **≥ 128 Bit**, und der Token wird nur als anklickbarer
  Link (nie als abzutippender Code) zugestellt.

## Assumptions

- **Persistenz state-stored (begründet, Constitution §6):** Web-Account und Token sind config-/
  identitätsartige Zustandsdaten, kein geld-/urteils-kritisches Aggregat — daher **state-stored** (wie
  Reports), nicht event-sourced. Sicherheitsrelevante Lebenszyklus-Ereignisse (Anlage, Passwortänderung)
  dürfen über eine Audit-Spur protokolliert werden, ohne dass das Aggregat event-sourced wird; das
  Passwort-Geheimnis selbst wird in-place ersetzt (alte Hashes werden **nicht** aufbewahrt).
- **Token in der Datenbank, kein Redis/Pub-Sub:** Der Token ist eine kurzlebige DB-Zeile mit
  Ablaufzeitpunkt; dieses Feature hat **keinen** Live-Pfad und published keine Events. (Bestätigte
  Kern-Entscheidung.)
- **Vertrauensanker UUID:** Authentifizierung gegen die stabile Identität; der Anzeigename ist nur
  Eingabe und wird über den bestehenden, groß-/kleinschreibungs-indizierten Namens-Lookup aufgelöst.
- **Passwort-Policy (entschieden, Q4):** 8–64 Zeichen, keine erzwungenen Zeichenklassen (moderne,
  längen-orientierte Regel); 64er-Obergrenze unter der 72-Byte-Hash-Grenze; backend-autoritativ.
- **Recovery-/Spam-Schutz (entschieden, Q5):** konfigurierbarer Cooldown pro Identität und Zweck auf die
  Token-Erzeugung, analog dem bestehenden Report-Cooldown — kein zusätzliches Tages-Cap in Slice 1.
- **Ban-Interaktion (bestätigter Default, Q2):** Ein Spieler mit aktiver Zugangs-Sperre kann den Server
  nicht betreten und damit keinen `/web`-Befehl auslösen → kein Web-Zugang während der Sperre. Gewollte
  natürliche Konsequenz (gesperrt = kein Web nötig); **kein** separater Web-Pfad für Gesperrte.
- **Name-Recycling (festgehaltene Regel, Q3):** Bei mehrdeutigem/recyceltem Namen gewinnt die Identität
  mit dem jüngsten letzten Server-Besuch. Praktisch wirksam erst im Login-Slice, hier als Regel fixiert.
- **Token-Hygiene (bestätigter Default, Q6):** Die Ablaufprüfung beim Einlösen ist die maßgebliche
  Sicherheitsgrenze; ein periodisches Aufräumen abgelaufener Token-Zeilen ist optionale Hygiene (kann die
  bereits im Projekt vorhandene Scheduler-Fähigkeit nutzen), nicht sicherheitskritisch.
- **Selbstbedienung ohne Permission-Gate** für die eigenen `/web link`/`resetPassword`-Pfade (der
  Server-Login beweist die Identität bereits) — in Slice 1 gibt es keine privilegierten/fremd-bezogenen
  Pfade (unlink verschoben), daher wird der Permission-Port hier nicht benötigt.
- Genau **eine** Webinterface-Instanz (Next.js) als Web-Konsument; deren Web-Endpunkt-Daten sind
  backend-intern (nicht Teil des Plugin-Contracts), da das Webinterface kein Plugin-Client ist.

## Out of Scope (dieser Slice)

- **Laufende JWT-Login-Session** (Token-Ausstellung/-Prüfung beim Web-Login) — separater Folge-Slice;
  diese Spec liefert nur die durable Credential-Bindung, auf der der Login aufsetzt.
- **E-Mail jeglicher Art** und E-Mail-basiertes Recovery — Wiederherstellung läuft ausschließlich über
  `/web resetPassword` ingame.
- **Redis/Pub-Sub** für dieses Feature — kein Live-Push, keine Cache-Invalidierung.
- **Rollen-/Rechte-UI** und jede konkrete fachliche Web-Seite — erst nach dem Login-Slice.
- **Webshop-Bezug** jeglicher Art.
- **`/web unlink`** vollständig (Entscheidung Q1) — kommt als eigener Konto-Management-Slice, inkl. der
  dann zu klärenden Recovery-Umweg-/Berechtigungsfrage.

## Dependencies

- Bestehende Spieler-Identität (UUID + indizierter Name, durch den Session-Join gepflegt) als
  Auflösungs- und Bindungsquelle.
- Ein nicht umkehrbares Passwort-Hash-Verfahren (Industrie-Standard) — im Backend neu einzuführen, hinter
  einer Abstraktion gekapselt (Detail des Plans, nicht dieser Spec).
- Die bestehende, konfigurierbare Cooldown-/Zeit-Fähigkeit (wie beim Report-Cooldown) für FR-022.
