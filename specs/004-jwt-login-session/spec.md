# Feature Specification: JWT-Login-Session (Web-Login gegen web_account)

**Feature Branch**: `004-jwt-login-session`

**Created**: 2026-06-24

**Status**: Draft

**Input**: User description: "JWT-Login-Session — laufender Web-Login gegen den web_account aus der Bridge. Ein Spieler mit web_account loggt sich im Webinterface mit MC-Name + Passwort ein, erhält eine Session (Access + Refresh), die über längere Zeit gültig bleibt."

---

## Übersicht & Abgrenzung

Sechster Slice der Web-Account-Linie und direkter Folge-Slice der **Web-Auth-Bridge** (`003-web-auth-bridge`),
die `web_account` (`player_uuid`, `password_hash`) und den `PasswordHasher`-Port (BCrypt) bereits gebaut hat.
Dieser Slice baut die **laufende Login-Session**: Ein Spieler authentifiziert sich im Webinterface und arbeitet
danach über eine zeitlich begrenzte, erneuerbare Session gegen die Web-Endpoints.

**Migrations-Frage (Constitution §17):** Greenfield — kein Altplugin-Vorgänger. Scope wird frisch geschnitten.

**Persistenz-Wahl (Constitution §6):** **state-stored** (Reports-/Bridge-Muster). Eine Login-Session ist ein
Identitäts-/Sitzungs-Datum, kein wert-/urteilskritisches Aggregat mit Replay-Bedarf → keine Event-Sourcing.
Der Access-Token ist **stateless** (gar nicht persistiert); nur der Refresh-Token wird state-stored.

**Autorität (Constitution §12):** Der Access-Token trägt **ausschließlich Identität** (player_uuid). Rechte werden
zur Request-Zeit über den bestehenden `PermissionResolver`-Port aufgelöst — **kein zweiter Permission-Pfad**.

---

## Clarifications

### Session 2026-06-24

- Q: Refresh-Rotation — gleichzeitige/wiederholte Nutzung desselben gültigen Refresh-Tokens (paralleler Tab,
  Netzwerk-Retry)? → A: **Strikt** — jede erneute Nutzung eines bereits rotierten Tokens gilt als Diebstahls-Signal
  (kein Grace-Fenster, kein idempotenter No-op). Fehlalarm bei harmlosem Doppel-Submit wird in Kauf genommen
  (Spieler loggt sich neu ein).
- Q: Umfang der Invalidierung bei erkanntem Replay — nur die betroffene Rotations-Familie oder alle Tokens des
  Spielers? → A: **Alle aktiven Refresh-Tokens der player_uuid** (alle Geräte). Kein Familien-/Lineage-Konzept im
  Schema nötig — Invalidierung läuft über `player_uuid`; `rotated_from` dient nur der Replay-Erkennung, nicht der
  Eingrenzung. Konsistent zu D4 (Passwort-Reset killt ebenfalls alle).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Einloggen mit MC-Name + Passwort (Priority: P1)

Ein Spieler, der ingame über `/web link` einen Web-Account angelegt hat, öffnet das Webinterface, gibt seinen
**aktuellen Minecraft-Namen** und sein Passwort ein und erhält eine gültige Session. Ab da kann er gegen die
Web-Endpoints arbeiten, ohne sich erneut einzuloggen, bis die Session abläuft.

**Why this priority**: Ohne Login existiert kein Webinterface-Zugang — das ist das Fundament, auf dem jeder
spätere Web-Feature-Slice (Rank-Management-UI etc.) aufsetzt. Allein lieferbar und demonstrierbar.

**Independent Test**: Einen `web_account` über die Bridge anlegen, dann mit korrektem Namen+Passwort einloggen →
ein verwendbares Access-Token + ein Refresh-Token zurück; mit falschem Passwort → einheitlicher Fehler.

**Acceptance Scenarios**:

1. **Given** ein Spieler mit verknüpftem `web_account` und korrektem Passwort, **When** er sich mit seinem
   aktuellen MC-Namen + Passwort einloggt, **Then** erhält er ein Access-Token (kurze Lebensdauer, Subject =
   seine player_uuid) und ein Refresh-Token (lange Lebensdauer).
2. **Given** ein Spieler mit `web_account`, **When** er ein falsches Passwort eingibt, **Then** schlägt der Login
   mit einem **einheitlichen** Fehler fehl, der nicht verrät, ob Name oder Passwort falsch war.
3. **Given** ein eingeloggter Spieler mit gültigem Access-Token, **When** er einen geschützten Web-Endpoint
   aufruft, **Then** wird seine Identität aus dem Token gelesen und die konkrete Berechtigung über den
   `PermissionResolver` geprüft (nicht aus dem Token).
4. **Given** ein abgelaufenes Access-Token, **When** der Spieler einen geschützten Endpoint aufruft, **Then**
   wird der Request abgewiesen (nicht authentifiziert), bis er ein frisches Token via Refresh holt.

---

### User Story 2 - Session verlängern (Refresh mit Rotation) (Priority: P1)

Während der Spieler arbeitet, läuft das kurzlebige Access-Token ab. Mit seinem Refresh-Token holt er ohne
erneute Passworteingabe ein neues Access-Token. Jeder Refresh **rotiert** auch den Refresh-Token: das alte wird
ungültig, ein neues ausgegeben. So bleibt die Session über lange Zeit am Leben, aber ein gestohlenes Refresh-Token
hat ein begrenztes Fenster und wird beim nächsten legitimen Refresh entwertet.

**Why this priority**: „Session bleibt lange gültig, ohne ständiges Neu-Einloggen" ist die zentrale Anforderung;
ohne Refresh wäre die Session auf die Access-TTL beschränkt. P1, weil Story 1 ohne Refresh kein nutzbares
Sitzungserlebnis ergibt.

**Independent Test**: Mit einem frischen Refresh-Token refreshen → neues Access+Refresh-Paar; das alte
Refresh-Token erneut einreichen → wird als bereits rotiert erkannt.

**Acceptance Scenarios**:

1. **Given** ein gültiges, nicht abgelaufenes, nicht rotiertes Refresh-Token, **When** der Spieler refresht,
   **Then** erhält er ein neues Access-Token und ein neues Refresh-Token, und das eingereichte Refresh-Token ist
   danach ungültig (rotiert).
2. **Given** ein abgelaufenes Refresh-Token, **When** der Spieler refresht, **Then** wird der Refresh abgewiesen
   und er muss sich neu einloggen.
3. **Given** ein Refresh-Token, das bereits einmal rotiert wurde (Replay), **When** es erneut eingereicht wird,
   **Then** wertet das System dies als **Token-Diebstahls-Signal**, invalidiert die **gesamte Token-Kette dieses
   Spielers** (alle aktiven Refresh-Tokens) und gibt einen Fehler zurück; der Spieler muss sich neu einloggen.
4. **Given** ein syntaktisch ungültiges oder unbekanntes Refresh-Token, **When** es eingereicht wird, **Then**
   wird der Refresh mit demselben einheitlichen „ungültig"-Fehler abgewiesen (keine Existenz-Auskunft).

---

### User Story 3 - Ausloggen (aktuelle Session beenden) (Priority: P2)

Der Spieler beendet bewusst seine Session. Sein aktuelles Refresh-Token wird serverseitig entwertet; das
kurzlebige Access-Token läuft von selbst ab. „Alle Sessions abmelden" (Multi-Device) ist bewusst **verschoben**.

**Why this priority**: Sauberer Logout ist erwartetes Sessionsverhalten und gehört zum MVP-Komfort, ist aber
nicht so kritisch wie Login/Refresh — ein Spieler kann eine Session auch durch Ablauf „beenden". P2.

**Independent Test**: Einloggen → ausloggen mit dem Refresh-Token → derselbe Refresh-Token kann danach kein neues
Access-Token mehr holen.

**Acceptance Scenarios**:

1. **Given** eine aktive Session, **When** der Spieler sich ausloggt, **Then** wird sein aktuelles Refresh-Token
   serverseitig entwertet und ein anschließender Refresh damit schlägt fehl.
2. **Given** ein bereits entwertetes/abgelaufenes Refresh-Token, **When** Logout damit aufgerufen wird, **Then**
   antwortet das System idempotent „beendet" (kein Fehler — Logout ist nicht enumerierbar).

---

### Edge Cases

- **Login ohne Web-Account**: Ein Spieler, der nie `/web link` gemacht hat, versucht sich einzuloggen → **gleicher
  einheitlicher „Credentials ungültig"-Fehler** wie bei falschem Passwort (D3, keine Enumeration).
- **Namens-Mehrdeutigkeit / Namenswechsel**: Der MC-Name ist nicht eindeutig über die Zeit (Spieler können
  Namen wechseln; ein freigewordener Name kann von einem anderen Spieler übernommen werden). Login löst den
  Namen über `LOWER(name)` auf und nimmt bei Mehrdeutigkeit die Zeile mit dem **jüngsten `last_seen`** (die in
  der Bridge festgenagelte Regel). Folge: Ein Spieler, der seinen Namen geändert hat, loggt sich mit seinem
  **aktuellen** Namen ein, nicht mit dem alten.
- **Name unbekannt**: Der eingegebene Name existiert in keiner `player`-Zeile → behandelt wie „Login
  fehlgeschlagen", einheitlicher Fehler (keine Auskunft über Spieler-Existenz; gekoppelt an D3).
- **Passwort-Reset während aktiver Sessions**: Der Spieler setzt sein Passwort über `/web resetPassword`
  (Bridge) neu → **alle aktiven Refresh-Tokens des Spielers werden invalidiert** (D4); laufende Sessions müssen
  sich neu einloggen.
- **Refresh-Token-Diebstahl / Doppel-Submit**: Jede erneute Nutzung eines bereits rotierten Tokens — egal ob
  Angriff oder harmloser gleichzeitiger Doppel-Submit (paralleler Tab, Retry) — wird **strikt** als
  Diebstahls-Signal gewertet und löst die Invalidierung aus (Story 2, Szenario 3). Kein Grace-Fenster/No-op
  (Clarification 2026-06-24); der seltene Fehlalarm kostet nur einen erneuten Login.
- **Wiederholte Login-Fehlversuche (Brute Force)**: In diesem Slice **kein** In-App-Schutz (D5/FR-021) —
  dokumentierte Lücke; jeder Fehlversuch liefert denselben einheitlichen Fehler.
- **Abgelaufene Refresh-Tokens stauen sich** in der Tabelle → Hygiene-Aufräumung (analog zum
  `web_link_token`-Purge der Bridge), nicht sicherheitskritisch.
- **Geänderte Rechte während laufender Session**: Da der Access-Token keine Rechte trägt und der
  `PermissionResolver` pro Request frisch auflöst, wirken Rechte-Änderungen sofort beim nächsten Request —
  ohne Re-Login. (Erwartetes Verhalten, kein Sonderfall-Code.)

---

## Requirements *(mandatory)*

### Functional Requirements

**Login**

- **FR-001**: Das System MUSS einen Login-Endpoint anbieten, der einen Minecraft-Namen und ein Passwort
  entgegennimmt und bei Erfolg ein Access-Token und ein Refresh-Token zurückgibt.
- **FR-002**: Das System MUSS den eingegebenen Namen über eine case-insensitive Auflösung (`LOWER(name)`) zur
  player_uuid auflösen und bei mehreren Treffern die Zeile mit dem **jüngsten `last_seen`** wählen (Regel aus der
  Bridge, nutzt `idx_player_name_lower`).
- **FR-003**: Das System MUSS das Passwort gegen den gespeicherten `password_hash` des zugehörigen `web_account`
  über den bestehenden `PasswordHasher`-Port (BCrypt-`matches`) prüfen. Es führt **keine** eigene Hash-Logik ein.
- **FR-004**: Das System MUSS bei falschem Passwort, unbekanntem Namen ODER fehlendem `web_account` (vorbehaltlich
  D3) mit einem **einheitlichen** Fehler antworten, der nicht unterscheidbar macht, welcher Teil falsch war
  (keine User-Enumeration über die generische Credential-Prüfung).
- **FR-005**: Das ausgestellte Access-Token MUSS **ausschließlich Identität** tragen (Subject = player_uuid) und
  **keine** Rollen/Rechte. Berechtigungen werden nie aus dem Token gelesen.
- **FR-006**: Das System MUSS die Identität für geschützte Web-Endpoints aus einem gültigen Access-Token
  bestimmen und die konkrete Berechtigung **ausschließlich** über den `PermissionResolver`-Port prüfen.
- **FR-007**: Das System MUSS abgelaufene oder ungültige (Signatur/Format) Access-Tokens auf geschützten
  Endpoints als nicht authentifiziert abweisen.

**Refresh & Rotation**

- **FR-008**: Das System MUSS einen Refresh-Endpoint anbieten, der ein Refresh-Token entgegennimmt und bei
  Gültigkeit ein **neues** Access+Refresh-Paar ausgibt.
- **FR-009**: Das System MUSS ein Refresh-Token nur akzeptieren, wenn es (a) serverseitig existiert, (b) nicht
  abgelaufen ist und (c) noch nicht rotiert/entwertet wurde.
- **FR-010**: Das System MUSS bei jedem erfolgreichen Refresh das eingereichte Refresh-Token entwerten
  (Rotation) und das neue Token als dessen Nachfolger kennzeichnen (Herkunfts-Verkettung).
- **FR-011**: Das System MUSS das **erneute Einreichen eines bereits rotierten** Refresh-Tokens als
  Diebstahls-Signal behandeln und daraufhin **alle aktiven Refresh-Tokens desselben Spielers** (über die
  `player_uuid`, geräte-/familien-übergreifend) invalidieren sowie einen Fehler zurückgeben. `rotated_from` dient
  nur der Replay-Erkennung, nicht der Eingrenzung des Invalidierungs-Umfangs — kein Familien-Konzept im Schema.
- **FR-012**: Das System MUSS Refresh-Tokens **nie im Klartext** persistieren (nur als nicht umkehrbarer Hash),
  konsistent zum Token-at-rest-Verfahren der Bridge.
- **FR-013**: Das System MUSS bei ungültigem/unbekanntem/abgelaufenem Refresh-Token mit einem einheitlichen
  „ungültig"-Fehler antworten, ohne Existenz preiszugeben.

**Logout** *(siehe D1 — in diesem Slice enthalten, Minimal-Variante)*

- **FR-014**: Das System MUSS einen Logout-Endpoint anbieten, der das übergebene Refresh-Token serverseitig
  entwertet; das Access-Token läuft durch seine kurze TTL aus.
- **FR-015**: Logout MUSS idempotent sein: ein bereits entwertetes/unbekanntes/abgelaufenes Refresh-Token führt
  zu einer Erfolgs-Antwort, nicht zu einem Fehler.

**Lebensdauer & Sicherheit**

- **FR-016**: Access- und Refresh-Tokens MÜSSEN definierte, konfigurierbare Lebensdauern haben: **Access 15
  Minuten, Refresh 30 Tage** (Defaults, über Konfiguration änderbar). Access kurzlebig, Refresh langlebig.
- **FR-017**: Das Signatur-Geheimnis MUSS aus der Umgebung/Konfiguration stammen und niemals im Code oder in
  Antworten erscheinen.
- **FR-018**: Das System MUSS bei einem Passwort-Reset über die Bridge (`/web resetPassword`) **alle aktiven
  Refresh-Tokens des betroffenen Spielers invalidieren** (Reset = alle alten Sessions raus). Dies ist ein
  additiver Eingriff in den RESET-Redeem-Pfad der Bridge, der bestehende Bridge-Logik nicht umbaut.
- **FR-019**: Das System SOLL abgelaufene Refresh-Tokens periodisch aufräumen (Hygiene; analog
  `web_link_token`-Purge), ohne dass dies sicherheitskritisch für die Korrektheit ist.
- **FR-021**: Das System führt in diesem Slice **bewusst keinen** In-App-Brute-Force-Schutz (Rate-Limit/Lockout)
  auf dem Login ein — dokumentierte Sicherheits-Lücke; Drosselung erfolgt später bzw. auf Reverse-Proxy-/Web-Ebene.

**Audit**

- **FR-020**: Das System MUSS sicherheitsrelevante Session-Ereignisse (erfolgreicher Login, Refresh-Rotation,
  erkannter Token-Replay/Familien-Invalidierung, Logout) im bestehenden `web_auth_audit`-Trail der Bridge
  append-only protokollieren — **niemals** Passwort, Token oder Token-Hash im Klartext.

### Key Entities *(include if feature involves data)*

- **Login-Session (konzeptuell)**: Die laufende Web-Sitzung eines Spielers, repräsentiert durch ein kurzlebiges
  Access-Token (zustandslos, nur Identität) plus eine erneuerbare Kette von Refresh-Tokens. Gehört genau einem
  Spieler (player_uuid).
- **Refresh-Token (persistiert)**: Ein langlebiges, einmal-rotierbares Geheimnis. Attribute: nicht umkehrbarer
  Hash des Tokens (Identität des Tokens, nie Klartext), zugehörige player_uuid, Ausstellungszeit, Ablaufzeit,
  Herkunft (`rotated_from` — welches Vorgänger-Token es ersetzt hat; dient ausschließlich der Replay-/
  Diebstahls-Erkennung, **nicht** der Eingrenzung der Invalidierung), Entwertungs-/Rotations-Status. Mehrere
  aktive Refresh-Tokens pro Spieler sind möglich (mehrere Geräte/Browser); bei einem Diebstahls-Signal werden sie
  **alle** über die player_uuid invalidiert (kein separates Familien-/Lineage-Feld nötig).
- **Access-Token (nicht persistiert)**: Zustandsloses, signiertes Identitäts-Token mit Subject = player_uuid und
  einer Ablaufzeit. Wird ausgestellt und verifiziert, aber nicht gespeichert.
- **web_account** *(bestehend, Bridge)*: Liefert den `password_hash` zur Credential-Prüfung. Wird gelesen, nicht
  verändert (außer indirekt über den Bridge-Reset).
- **player** *(bestehend)*: Quelle der Name→UUID-Auflösung (`LOWER(name)` + jüngster `last_seen`).

---

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Ein Spieler mit verknüpftem Web-Account kann sich mit aktuellem MC-Namen + korrektem Passwort
  erfolgreich einloggen und eine Session erhalten (100 % der korrekten Versuche gelingen).
- **SC-002**: Ein falsches Passwort, ein unbekannter Name und (vorbehaltlich D3) ein fehlender Web-Account führen
  zu einer Antwort, aus der ein Außenstehender **nicht** ableiten kann, welcher Teil der Eingabe falsch war.
- **SC-003**: Eine Session bleibt über die volle Refresh-Lebensdauer hinweg nutzbar, ohne erneute Passwort-
  eingabe, solange regelmäßig refresht wird (kein erzwungener Re-Login innerhalb des Fensters).
- **SC-004**: Ein erneut eingereichtes, bereits rotiertes Refresh-Token macht in 100 % der Fälle alle aktiven
  Sessions desselben Spielers ungültig und zwingt zum Neu-Login.
- **SC-005**: Aus der Datenbank ist zu keinem Zeitpunkt ein einlösbares Refresh-Token im Klartext rekonstruierbar
  (Token nur als nicht umkehrbarer Hash gespeichert).
- **SC-006**: Eine Berechtigungs-Änderung wirkt ohne Re-Login beim nächsten Request (Rechte kommen pro Request
  aus dem Resolver, nicht aus dem Token). *In diesem Slice nur **negativ** verifizierbar (das Access-JWT trägt
  keine Authority-/Rollen-Claims — siehe T025); die volle Wirk-Prüfung „Resolver-Änderung → nächster Request"
  erfolgt in Slice 6, sobald ein resolver-gestützter Web-Endpoint existiert.*
- **SC-007**: Alle bestehenden Vertical Slices (Economy, Punishment, Report, Permission, Web-Auth-Bridge) laufen
  nach Einführung dieses Slices **unverändert grün** — insbesondere bleiben die bestehenden Plugin↔Backend-REST-
  Endpoints in ihrer aktuellen Erreichbarkeit unberührt (die JWT-Authentifizierung gilt nur für die
  Web-Session-Endpoints, nicht für den server-internen Plugin-Verkehr). *(Konstitutions-Schutz, analog SC-001 des
  Permission-Slices.)*

---

## Entschiedene Punkte *(geklärt am 2026-06-24 — fließen in Plan/Tasks)*

- **D1 — Logout: JA, Minimal-Variante.** Ein Endpoint entwertet das übergebene Refresh-Token (FR-014/FR-015);
  das Access-Token läuft über seine TTL aus. „Alle Sessions beenden" (Multi-Device) ist verschoben — die Tabelle
  trägt es bereits, der Endpoint kommt im Konto-Management-Slice.
- **D2 — Token-Lebensdauern: Access 15 Min, Refresh 30 Tage** (rollend durch Rotation), konfigurierbar (FR-016).
- **D3 — Login ohne Web-Account: einheitlicher Fehler.** Ein nie verlinkter Spieler bekommt dieselbe
  „Credentials ungültig"-Antwort wie bei falschem Passwort/Namen — **keine User-Enumeration** (verrät nicht, ob
  ein MC-Name einen Web-Account hat). Onboarding-Hinweis „zuerst `/web link`" wird **nicht** über die
  Login-Antwort gegeben (FR-004). *Folge:* die Onboarding-Führung muss an anderer Stelle passieren (z. B. statische
  Webseiten-Hilfe), nicht über differenzierte Login-Fehler.
- **D4 — Passwort-Reset (Bridge) → alle Sessions invalidieren.** Ein Reset über `/web resetPassword` entwertet
  **alle** Refresh-Tokens des betroffenen Spielers (FR-018). Erfordert einen **kleinen additiven Eingriff in den
  RESET-Redeem-Pfad der Bridge** (Cross-Slice-Berührung — im Plan explizit als additive Erweiterung, kein
  Umbau bestehender Bridge-Logik, ausweisen).
- **D5 — Brute-Force-Schutz: verschoben (dokumentierte Lücke).** Kein In-App-Rate-Limit/Lockout in diesem Slice
  (FR-021). Drosselung kann später bzw. auf Reverse-Proxy-/Web-Ebene erfolgen. Bewusst als Sicherheits-Lücke
  ausgewiesen, nicht stillschweigend ausgelassen.

---

## Was wegfällt / NICHT in diesem Slice *(Scope-Grenzen)*

- **Keine konkreten Web-Feature-Endpoints** (Rank-Management-UI etc.) — das ist Slice 6+. Dieser Slice liefert
  nur Login/Refresh/Logout + die Identitäts-Verrohrung, gegen die spätere Endpoints arbeiten.
- **Keine sofortige Access-Token-Revocation / Blacklist** — kein Unlink-Trigger; das kurze Access-TTL ist das
  Revocation-Fenster. (Refresh-Tokens sind dagegen serverseitig sofort entwertbar.)
- **Kein „Alle Geräte abmelden"-Endpoint** (verschoben in Konto-Management).
- **Kein OAuth / Discord-Login, keine E-Mail, keine E-Mail-Recovery.**
- **Kein Plugin-Anteil** — rein Backend (+ später Web-Frontend). Die `/web`-Commands leben in der Bridge.
- **Kein asymmetrisches Signieren (RS256)** — HS256/symmetrisch, da das Backend sowohl ausstellt als auch
  verifiziert (kein separater Verifizier-Service).

---

## Assumptions

- **Wiederverwendung statt Neubau (Constitution §10):** `web_account` + dessen Repository, der `PasswordHasher`-
  Port (BCrypt), die Name→UUID-Regel (in der Bridge fixiert, hier erstmals implementiert), der
  `PermissionResolver`-Port und der `web_auth_audit`-Trail werden **angesteckt**, nicht neu gebaut.
- **Token-at-rest:** Refresh-Tokens werden wie die Bridge-Tokens als nicht umkehrbarer Hash gespeichert (das
  konkrete Hash-Verfahren entscheidet der Plan; die Bridge-Linie ist SHA-256 für hochentropische Tokens).
- **Single-Server (Constitution §14):** Eine Backend-Instanz stellt aus und verifiziert; daher symmetrisches
  Signieren ausreichend, kein verteilter Schlüsseltausch nötig.
- **Identität ≠ Rechte:** Der gesamte Slice setzt voraus, dass Autorisierung ausschließlich über den
  `PermissionResolver` läuft; ein eingeloggter Spieler ohne passende Rechte erhält bei geschützten Endpoints den
  etablierten „nicht berechtigt"-Fehler (kein zweiter Pfad).
- **Web-Frontend (Next.js) ist Konsument**, wird in diesem Slice nicht mitgebaut; der Token-Transport-Mechanismus
  zum Browser (Header vs. httpOnly-Cookie) wird mit den Antworten festgelegt und in den Plan übernommen.
- **Bestehende Plugin-REST-Endpoints bleiben außerhalb der JWT-Authentifizierung** (server-interner,
  vertrauenswürdiger Verkehr) — die Auth gilt nur den Web-Session-Endpoints (siehe SC-007).
