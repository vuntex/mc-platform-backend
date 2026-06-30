# Feature Specification: Rollen-Vererbung (Permission-Inheritance)

**Feature Branch**: `006-role-inheritance`

**Created**: 2026-06-25

**Status**: Draft (offene Klärungspunkte, siehe Clarifications)

**Input**: User description: "Rollen-Vererbung — eine Rolle erbt (transitiv) die Permissions einer Liste anderer Rollen. Greenfield-Erweiterung des bestehenden Permission-Systems (kein Altplugin-Import; das Alt-System nutzte LuckPerms). Mehrfach-Vererbung (M:N, explizit gesetzt), nur Permissions vererben (keine Darstellungs-/Meta-Felder), transitiv, reine Union ohne Negation/Gewichtung, Zyklus-Schutz, Default-Permissions fließen in eine echte Rolle nur wenn Default in deren Vererbungsliste steht, Live-Push bei jeder Vererbungs-Änderung."

## Migrations-Entscheidung *(Constitution Prinzip 17)*

**Migrieren wir das? — Ja, als Greenfield-Erweiterung, nicht als Import.** Das alte 1.8.9-Plugin
hatte keine eigene Vererbung; Vererbung kam dort aus LuckPerms. Wir bauen kein LuckPerms nach, sondern
ein bewusst schlankeres, eigenes Modell: reine Permission-Union über explizit gesetzte Kanten. Es wird
**kein** Alt-Code, kein LuckPerms-Konzept (Tracks, Kontexte, temporäre Nodes, Negationen, Gewichtung)
übernommen.

**Was bewusst WEGFÄLLT (gegenüber einem LuckPerms-Vollmodell):**

- Keine Gewichtung/Priorität/Negation in der Vererbung — reine additive Union, Reihenfolge irrelevant.
- Keine Vererbung von Darstellungs-/Meta-Feldern (displayName, color, prefix, suffix, tabListColor,
  tabListIcon, displayIcon, weight, teamRank, active, isDefault) — **nur** Permissions.
- Keine Kontexte/Welten/Server-Scoping (Single-Server, Constitution Prinzip 14).
- Kein Frontend in diesem Slice (der Vererbungs-Editor wird später in den pausierten Rank-UI-Slice
  integriert).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Eine Rolle erbt die Permissions einer anderen (Priority: P1)

Ein Admin möchte, dass die Rolle `Premium` automatisch alle Permissions der Rolle `Spieler` mitbringt,
ohne jede Permission doppelt pflegen zu müssen. Er fügt `Spieler` zur Vererbungsliste von `Premium`
hinzu. Ab sofort hat jeder Träger von `Premium` zusätzlich alle Permissions von `Spieler` — und, weil
Vererbung transitiv ist, auch alles, was `Spieler` seinerseits erbt.

**Why this priority**: Das ist der Kern-Nutzen des Features. Ohne ihn existiert die Vererbung nicht.
Ein lauffähiger MVP ist erreicht, sobald eine einzelne Vererbungs-Kante die effektiven Permissions
von Trägern korrekt erweitert.

**Independent Test**: Zwei Rollen anlegen (`Base` mit Permission `feature.a`, `Premium` ohne eigene
Permissions), einen Spieler nur mit `Premium` granten, Vererbung `Premium → Base` setzen, dann prüfen,
dass der Spieler `feature.a` effektiv besitzt. Kante wieder entfernen → Spieler verliert `feature.a`.

**Acceptance Scenarios**:

1. **Given** Rolle `Base` hat `feature.a`, Rolle `Premium` hat keine eigenen Permissions und ein
   Spieler hält nur `Premium`, **When** der Admin `Premium → Base` als Vererbung setzt, **Then** hat
   der Spieler effektiv `feature.a` (auch der Permission-Check `hasPermission` für `feature.a` ist
   true).
2. **Given** `A → B → C` ist als Vererbungskette gesetzt und `C` hat `feature.c`, **When** ein Spieler
   nur `A` hält, **Then** besitzt er effektiv `feature.c` (transitive Auflösung über die ganze Kette).
3. **Given** die effektive Permission-Sicht einer Rolle wird abgefragt, **When** die Rolle erbt,
   **Then** enthält die zurückgegebene Permission-Menge sowohl die eigenen als auch alle transitiv
   geerbten Permissions.
4. **Given** zwei Rollen `X` und `Y` erben beide von `Base` (Diamant: `Z → X`, `Z → Y`, `X → Base`,
   `Y → Base`), **When** ein Spieler `Z` hält, **Then** erscheint jede geerbte Permission genau einmal
   in der effektiven Menge (Mengensemantik, keine Duplikate).

---

### User Story 2 - Zyklen werden beim Setzen abgelehnt (Priority: P1)

Ein Admin versucht, eine Vererbung zu setzen, die einen Kreis erzeugen würde (z.B. `A → B` existiert
bereits, jetzt soll `B → A` gesetzt werden). Das System lehnt die Operation ab und erklärt, dass dies
einen Zyklus erzeugen würde. Der bestehende Vererbungsgraph bleibt unverändert.

**Why this priority**: Ein Zyklus macht die transitive Auflösung undefiniert/nicht-terminierend und
würde den Login-/Permission-Pfad gefährden. Der Schutz ist nicht optional und muss mit dem Kern-Slice
zusammen existieren.

**Independent Test**: `A → B` setzen (ok), dann `B → A` setzen → Ablehnung mit Konflikt-Fehler; danach
prüfen, dass `B → A` NICHT im Graph steht und `A → B` unverändert ist. Analog für eine längere Kette
(`A → B → C`, dann `C → A`).

**Acceptance Scenarios**:

1. **Given** `A → B` ist gesetzt, **When** der Admin `B → A` setzen will, **Then** wird die Operation
   mit einem Konflikt-Fehler (HTTP 409) abgelehnt und der Graph bleibt unverändert.
2. **Given** `A → B → C` ist gesetzt, **When** der Admin `C → A` setzen will, **Then** wird mit 409
   abgelehnt (transitiver Zyklus erkannt).
3. **Given** eine Rolle `A`, **When** der Admin `A → A` (Selbstreferenz) setzen will, **Then** wird mit
   409 abgelehnt.
4. **Given** trotz Schreib-Schutz existiert ein Restzyklus im Graph (z.B. durch direkten DB-Eingriff),
   **When** die effektiven Permissions aufgelöst werden, **Then** terminiert die Auflösung (jede Rolle
   wird höchstens einmal besucht) und liefert die korrekte Union der erreichbaren Permissions, ohne zu
   hängen.

---

### User Story 3 - Vererbungs-Änderung schlägt live bei betroffenen Spielern durch (Priority: P2)

Ein Admin entfernt eine Vererbungs-Kante (oder fügt sie hinzu) an einer Rolle, die viele Spieler über
mehrere Ebenen betrifft. Alle aktuell online befindlichen Spieler, deren effektive Permissions sich
dadurch ändern (direkte Träger der Rolle **und** Träger jeder Rolle, die diese Rolle transitiv erbt),
sehen die Änderung live, ohne neu einzuloggen.

**Why this priority**: Konsistent zu allen anderen Permission-Writes (Live-Pfad ist verbindlich,
Constitution Prinzip 14). Ohne Live-Push wäre der Stand bis zum Relogin inkonsistent. Sekundär ggü. der
korrekten Auflösung selbst.

**Independent Test**: Online-Spieler mit Rolle `Premium`, die transitiv von `Base` erbt; an `Base` eine
Permission ändern bzw. die Kante `Premium → Base` entfernen; beobachten, dass der Spieler-Client den
aktualisierten effektiven Stand erhält, ohne Relogin.

**Acceptance Scenarios**:

1. **Given** ein Online-Spieler hält `Premium`, das von `Base` erbt, **When** der Admin die Kante
   `Premium → Base` entfernt, **Then** erhält dieser Spieler ein Live-Signal, dass sich seine
   Permissions geändert haben.
2. **Given** `A → B → C` und Online-Spieler halten jeweils `A` bzw. `B`, **When** der Admin eine Kante
   an `C` (`C → D`) hinzufügt, **Then** erhalten **alle** Spieler, deren effektive Menge sich ändert
   (Träger von `A` und `B`), das Live-Signal — Träger nicht-betroffener Rollen nicht.

---

### User Story 4 - Vererbte Rollen einer Rolle einsehen (Priority: P3)

Ein Admin (oder ein lesendes Web-Tool) ruft die Liste der direkt vererbten Rollen einer Rolle ab, um
den konfigurierten Vererbungsgraph nachzuvollziehen.

**Why this priority**: Reine Lese-/Transparenzfunktion; nützlich fürs spätere UI und fürs Debugging,
aber nicht Voraussetzung für den Kern-Nutzen.

**Independent Test**: Für eine Rolle mit gesetzten Kanten die Liste abrufen und prüfen, dass genau die
direkt gesetzten Parent-Rollen erscheinen (nicht die transitiven — die transitive Sicht liefert der
effective-Endpoint).

**Acceptance Scenarios**:

1. **Given** `Premium` erbt direkt von `Base` und `VIP`, **When** der Admin die Vererbungsliste von
   `Premium` abruft, **Then** enthält die Antwort `Base` und `VIP` (die direkten Parents).

---

### Edge Cases

- **Doppelte Kante:** Eine bereits existierende Kante `A → B` wird erneut gesetzt → idempotent
  ignoriert, kein Fehler (siehe Assumptions; bestätigt durch FR-014).
- **Nicht existierende Rolle:** Vererbung auf/von einer nicht existierenden Rolle (ungültige `id` oder
  `parentId`) → Not-Found-Fehler (404), keine Kante entsteht.
- **Inaktive Rolle in der Kette:** Erbt eine Rolle von einer deaktivierten Rolle (`active = false`),
  fließen deren Permissions trotzdem in die Vererbung ein? → Die Vererbungs-Kante bezieht sich auf die
  Permission-Konfiguration der Parent-Rolle; `active` ist ein Darstellungs-/Mitgliedschafts-Attribut
  und wird **nicht** vererbt. Für die Permission-Union zählen die konfigurierten Permissions der
  geerbten Rolle unabhängig von deren `active`-Flag. (Der `active`-Filter wirkt weiterhin nur auf die
  **direkten Rollen-Grants** des Spielers, FR-007a aus 002 — nicht auf die geerbten Parents.) Siehe
  FR-016.
- **Entfernen einer nicht existierenden Kante:** `A → B` entfernen, wenn keine solche Kante existiert →
  idempotent (kein Fehler, Ergebnis „keine Kante vorhanden").
- **Löschen einer Rolle, die von anderen geerbt wird:** abgelehnt (409), siehe FR-015.
- **Spieler ohne aktive Rolle:** unverändert — Default-Fallback wie in 002/Default-Change; Vererbung
  ändert daran nichts (Default ist exklusiver Fallback, kein Union-Member). Siehe FR-011.
- **Tiefe/breite Graphen:** sehr lange Ketten oder große Diamanten müssen terminieren und dürfen den
  Permission-Check nicht spürbar verlangsamen (Visited-Set, Mengensemantik). Siehe SC-002/SC-004.

## Requirements *(mandatory)*

### Functional Requirements

#### Vererbungs-Modell

- **FR-001**: Eine Rolle MUSS eine (möglicherweise leere) Liste **direkt vererbter Rollen** führen
  können (Many-to-Many zwischen Rollen). Die Liste wird **explizit** gesetzt; es gibt keinen
  Automatismus entlang `weight` oder anderer Felder.
- **FR-002**: Vererbung MUSS **ausschließlich Permissions** übertragen. Darstellungs-/Meta-Felder
  (displayName, color, prefix, suffix, tabListColor, tabListIcon, displayIcon, weight, teamRank,
  active, isDefault) werden NIEMALS vererbt.
- **FR-003**: Die Auflösung MUSS **transitiv** sein: erbt `A` von `B` und `B` von `C`, so umfasst die
  effektive Permission-Menge von `A` die Permissions von `A`, `B` und `C`.
- **FR-004**: Die Auflösung MUSS eine **reine additive Union** sein — keine Negation, keine Gewichtung,
  keine Priorität. Die Reihenfolge der vererbten Rollen ist damit irrelevant und jede Permission
  erscheint in der effektiven Menge höchstens einmal (Mengensemantik).
- **FR-005**: Die bestehende Wildcard-Semantik (`*` global, `feature.*` Präfix) MUSS auf die geerbten
  Permissions identisch wie auf eigene Permissions wirken (keine Sonderbehandlung geerbter Wildcards).

#### Spieler-Resolution

- **FR-006**: Die effektiven Permissions eines Spielers MÜSSEN die Union sein aus: (a) den Permissions
  **aller** seiner aktiven direkten Rollen-Grants **inklusive deren transitiver Vererbung**, plus
  (b) seinen aktiven Direkt-Permission-Grants.
- **FR-007**: Mehrfach-Mitgliedschaft pro Spieler (mehrere aktive Rollen-Grants) MUSS erhalten bleiben;
  die transitive Hülle wird über **alle** direkten aktiven Rollen des Spielers gebildet.
- **FR-008**: Der bestehende `hasPermission`-Pfad (Backend-autoritativer Einzel-Check) MUSS die
  transitiv aufgelöste Menge berücksichtigen. Bei leerem Vererbungsgraph (keine Kanten) MUSS sich das
  Verhalten **bit-genau** wie heute (flache Rollen) verhalten (Regressions-Garantie).
- **FR-009**: Die bestehenden effektiven Sichten (effektive Permissions einer Rolle / eines Spielers)
  MÜSSEN nach dem Umbau die transitiv aufgelöste Permission-Menge liefern.

#### Zyklus-Schutz

- **FR-010**: Das Setzen einer Vererbungs-Kante, die einen Zyklus erzeugen würde (direkt: `A → A`;
  oder transitiv: `parentId` erreicht `id` bereits über bestehende Kanten), MUSS **abgelehnt** werden
  (HTTP 409 Conflict), und der Graph MUSS unverändert bleiben.
- **FR-010a**: Zusätzlich MUSS die Auflösung **defensiv** gegen Restzyklen sein: jede Rolle wird bei
  der transitiven Auflösung höchstens einmal besucht (Visited-Set), sodass selbst ein im Datenbestand
  vorhandener Zyklus die Auflösung terminieren lässt und nicht zum Hängen führt.

#### Default-Interaktion

- **FR-011**: Default-Permissions MÜSSEN in eine **echte** Rolle nur dann einfließen, wenn diese Rolle
  die Default-Rolle in ihrer (transitiven) Vererbungsliste führt. Der bestehende **Default-Fallback**
  (Spieler ohne jede aktive Rolle erhält die Default-Permissions) bleibt davon unberührt und bleibt ein
  **exklusiver** Zweig (kein Union-Member), konsistent zum abgeschlossenen Default-Change. Das heißt
  insbesondere: ein Spieler mit nur einer echten Rolle, die Default NICHT erbt, erhält die
  Default-Permissions NICHT.
- **FR-012**: Das System bietet **keine** besondere Hilfestellung gegen die Default-Konsistenz-Falle
  (Entscheidung CL-1 = c): es wird **nicht** gewarnt und Default wird **nicht** vorausgewählt, wenn
  eine echte Rolle die Default-Rolle nicht erbt. Es liegt bewusst in der Verantwortung des Admins, ob
  eine Rolle Default erben soll. (Bewusste Scope-Begrenzung; eine spätere Warn-/Vorauswahl-Komfort-
  funktion kann im pausierten Rank-UI-Slice nachgezogen werden.)
- **FR-013**: Die system-verwaltete, exklusive **Default-Rolle ist ein reines „Blatt"** (Entscheidung
  CL-3): sie führt nur ihre eigenen Permissions und MUSS **keine** Vererbungsliste führen können — der
  Versuch, der Default-Rolle eine vererbte Rolle hinzuzufügen, MUSS abgelehnt werden. Andere Rollen
  dürfen weiterhin **von** der Default-Rolle erben (Default als Parent ist erlaubt, FR-011).

#### CRUD & Lebenszyklus der Vererbungs-Kanten

- **FR-014**: Das erneute Setzen einer bereits existierenden Kante MUSS **idempotent** sein (kein
  Fehler, kein Duplikat). Das Entfernen einer nicht existierenden Kante MUSS ebenfalls idempotent sein.
- **FR-015**: Das Löschen einer Rolle, die von mindestens einer anderen Rolle (direkt) geerbt wird,
  MUSS **abgelehnt** werden (HTTP 409) mit einer Meldung, die die abhängige(n) Rolle(n) benennt; die
  Kante muss erst dort entfernt werden. (Begründung: transparenter und sicherer als stilles
  Mitlöschen der Kanten.)
- **FR-016**: Vererbung bezieht sich auf die **konfigurierten Permissions** der geerbten Rolle. Das
  `active`-Flag der geerbten (Parent-)Rolle beeinflusst die Vererbung NICHT (es ist ein
  Darstellungs-/Mitgliedschafts-Attribut und wird per FR-002 nicht vererbt). Der `active`-Filter wirkt
  unverändert nur auf die direkten Rollen-Grants des Spielers.
- **FR-017**: Jede Vererbungs-Änderung (Kante hinzufügen/entfernen) MUSS im bestehenden
  Rollen-Audit-Strang nachvollziehbar festgehalten werden (wer, wann, welche Kante, hinzugefügt/
  entfernt).

#### Berechtigungen & Schnittstelle

- **FR-018**: Alle Vererbungs-Operationen MÜSSEN hinter der bestehenden JWT-Absicherung und
  konsistent zur `/api/web/permission/**`-Fläche liegen.
- **FR-019**: Das **Lesen** der Vererbungsliste MUSS das Gate `permission.read` erfordern; das
  **Setzen/Entfernen** von Kanten MUSS ein **neues, eigenes granulares Gate** erfordern (Arbeitsname
  `permission.role.edit.inherit`), das vom allgemeinen Rollen-Edit-Gate getrennt ist. Damit ist die
  Autorität, Vererbungs-Kanten zu verändern, fein von der übrigen Rollen-Konfiguration trennbar. Das
  Gate wird als neuer Permission-Seed-Eintrag ergänzt (konsistent zum granularen Gate-Modell aus 005).

#### Live-Push

- **FR-020**: Jede Vererbungs-Änderung MUSS live auf **alle betroffenen online** Spieler durchschlagen:
  die direkten Träger der geänderten Rolle **und** die Träger jeder Rolle, die die geänderte Rolle
  transitiv erbt (die „abhängigen" Rollen). Spieler, deren effektive Permissions sich nicht ändern,
  MÜSSEN kein irreführendes Signal erhalten.
- **FR-020a**: Auch das Ändern der **eigenen Permissions** einer Rolle (bestehende
  role-permission-Operation) MUSS nach Einführung der Vererbung live auf alle transitiv abhängigen
  Träger durchschlagen: nicht nur die direkten Träger der geänderten Rolle, sondern auch die Träger
  jeder Rolle, die diese Rolle (transitiv) erbt. D.h. die in 005 bestehende Live-Push-Reichweite für
  role-permission-Edits wird durch dieses Feature auf die transitive Reverse-Closure ausgeweitet — mit
  derselben Logik wie bei Kanten-Änderungen (FR-020). Andernfalls wäre Vererbung live inkonsistent.
- **FR-021**: Der Live-Push MUSS den bestehenden Permission-Live-Pfad nutzen (gleiches Signal-Schema
  wie andere Permission-Writes); es wird **keine** neue Vererbungs-spezifische Nachricht eingeführt,
  wenn der bestehende „Permissions dieses Spielers haben sich geändert"-Mechanismus ausreicht.
- **FR-022**: Geerbte Permissions MÜSSEN in der effektiven Sicht von eigenen Permissions
  **unterscheidbar** sein (Entscheidung CL-2): je Permission wird die Herkunft ausgewiesen — „eigen"
  vs. „geerbt von Rolle X". Das gilt sowohl für die effektive Sicht einer Rolle als auch für die eines
  Spielers (dort zusätzlich: über welche direkte Rolle die Permission hereinkommt). Die flache
  Permission-Menge (wie heute) bleibt für den reinen Allow/Deny-Check (`hasPermission`) erhalten; die
  Herkunfts-Information ist eine **additive** Anreicherung der Lese-/Anzeige-Sicht und darf den
  autoritativen Check nicht verändern.
- **FR-022a**: Kommt **dieselbe** Permission über **mehrere** Vererbungspfade/Quell-Rollen herein
  (Diamant), MUSS die effektive Sicht die **vollständige Menge aller beitragenden Quell-Rollen** je
  Permission ausweisen (nicht eine willkürliche „nächste"), zusätzlich ein Flag „eigen", falls die
  betrachtete Rolle bzw. der Spieler die Permission auch direkt besitzt. Die Quellenmenge ist
  deterministisch (unabhängig von Reihenfolge/Gewichtung — konsistent zu FR-004).

### Key Entities

- **Rolle (Role)**: bestehende Entität. Erhält konzeptionell eine Liste **direkt vererbter Rollen**.
  Permissions sind das einzige vererbte Merkmal.
- **Vererbungs-Kante (Role-Inheritance-Edge)**: gerichtete Beziehung „Rolle *child* erbt von Rolle
  *parent*". Many-to-Many zwischen Rollen. Trägt mindestens: child-Rolle, parent-Rolle, sowie
  Audit-Metadaten (gesetzt von wem, wann). Keine Gewichtung, keine Reihenfolge.
- **Effektive Permission-Menge**: die abgeleitete (nicht gespeicherte) Union aus eigenen + transitiv
  geerbten Permissions einer Rolle bzw. eines Spielers.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Setzt ein Admin eine Vererbungs-Kante zwischen zwei Rollen, besitzt ein Spieler mit der
  erbenden Rolle danach **100%** der Permissions der geerbten Rolle (und ihrer transitiven Parents);
  entfernt er die Kante, verliert der Spieler genau diese geerbten Permissions wieder — ohne dass
  eigene oder anderweitig geerbte Permissions verloren gehen.
- **SC-002**: Bei leerem Vererbungsgraph ist das Ergebnis **jeder** Permission-Prüfung identisch zum
  Verhalten vor diesem Feature (0 Regressionen in den bestehenden Permission-Szenarien).
- **SC-003**: Der Versuch, einen Zyklus zu setzen (direkt oder transitiv), wird in **100%** der Fälle
  abgelehnt; nach einem abgelehnten Versuch ist der Vererbungsgraph unverändert.
- **SC-004**: Die transitive Auflösung **terminiert für jeden Graphen** (auch mit künstlich
  eingefügtem Zyklus) — das ist das harte, testbare Kriterium (Visited-Set/`UNION`-Dedup, geprüft in
  T007/Resolver-IT). Der Permission-Einzel-Check bleibt **ein einzelner DB-Round-Trip** (die rekursive
  CTE faltet die Hülle in derselben Query — kein N+1, keine zusätzliche App-seitige Schleife); eine
  feste Latenz-Schwelle wird hier **bewusst nicht** als Erfolgskriterium gesetzt (kein Lasttest in
  diesem Slice). Sollte die Tiefe je problematisch werden, ist ein optionaler Tiefen-Cap die
  Folgemaßnahme.
- **SC-005**: Nach einer Vererbungs-Änderung sehen **alle** betroffenen online Spieler den neuen
  effektiven Stand ohne Relogin; nicht betroffene Spieler erhalten kein Signal.
- **SC-006**: Jede Vererbungs-Änderung ist im Audit nachvollziehbar (wer/wann/welche Kante/Richtung).

## Assumptions

- **Doppelte Kante = idempotent** (FR-014): Das erneute Setzen einer existierenden Kante ist kein
  Fehler, sondern ein No-Op. (Klärungspunkt 3 aus der Eingabe — mit klarem Default entschieden, nicht
  als offene Frage geführt.)
- **Löschen einer geerbten Rolle = ablehnen** (FR-015): sicherer/transparenter als stilles Mitlöschen
  der Kanten. (Klärungspunkt 5 — als Default entschieden.)
- **Live-Push-Reichweite ist verhaltensseitig spezifiziert** (FR-020); die **technische**
  Umsetzung der Reichweite/Invalidierung (insb. das Berechnen der abhängigen Rollen bei tiefen
  Graphen) wird im `plan` gelöst. (Klärungspunkt 6 — bewusst als Verhaltensanforderung hier, Technik im
  Plan.)
- Es existiert **kein** persistenter Backend-Permission-Cache, der invalidiert werden müsste; die
  Auflösung ist query-time. Der client-seitige Plugin-Cache wird über den bestehenden Live-Pfad
  aktualisiert. (Befund aus der Architektur-Voranalyse; falls dies im Plan widerlegt wird, ist die
  Cache-Invalidierung dort zu adressieren.)
- Die bestehende Rollen-/Permission-/Grant-Persistenz, der Permission-Resolver-Port, das
  Audit-Muster und der Permission-Live-Pfad werden wiederverwendet, nicht neu gebaut (Constitution
  Prinzip 10). Welche generische Logik (Resolver-Kern) bewusst erweitert wird, klärt der Plan.
- Single-Server (Constitution Prinzip 14): keine Cross-Server-Aspekte.

## Clarifications

> Alle drei Punkte sind aufgelöst (2026-06-25). Die niedrig-riskanten Punkte (doppelte Kante,
> Lösch-Verhalten, Live-Push-Technik) sind als Assumptions entschieden.

### Session 2026-06-25

- Q: Bei Mehrfach-Pfaden (Diamant) — welche Herkunft weist die effektive Sicht je Permission aus? → A: Vollständige Menge **aller** beitragenden Quell-Rollen + Flag „eigen" (FR-022a).
- Q: Weitet dieses Feature den Live-Push beim Ändern der eigenen Permissions einer geerbten Rolle auf die transitiv abhängigen Träger aus? → A: Ja — Reverse-Closure-Push auch für role-permission-Edits (FR-020a).
- Q: Welches schreibende Gate verlangen Vererbungs-Operationen? → A: Neues granulares Gate `permission.role.edit.inherit` (eigener Seed-Eintrag); Lesen bleibt `permission.read` (FR-019).

### CL-1 (FR-012): Default-Konsistenz-Falle — **AUFGELÖST: (c) keine Hilfe**

Frage: Wenn eine echte Rolle die Default-Rolle NICHT erbt, haben Spieler mit nur dieser Rolle effektiv
weniger Permissions als ein Default-Spieler. Wie geht das System damit um?

**Entscheidung:** Keine Hilfestellung — kein Warnen, keine Vorauswahl. Bewusste Admin-Verantwortung
(siehe FR-012). Eine Komfort-Warnung kann später im Rank-UI-Slice nachgezogen werden.

### CL-2 (FR-022): Herkunft geerbter Permissions — **AUFGELÖST: Herkunft anzeigen**

Frage: Sollen geerbte Permissions von eigenen unterscheidbar sein oder flach verschmelzen?

**Entscheidung:** Herkunft je Permission anzeigen („eigen" / „geerbt von Rolle X"), additiv zur
flachen Menge; der autoritative `hasPermission`-Check bleibt unverändert (siehe FR-022).

### CL-3 (FR-013): Darf die Default-Rolle selbst erben? — **AUFGELÖST: Blatt**

Frage: Ist die exklusive Default-Rolle ein reines Blatt oder darf auch sie eine Vererbungsliste führen?

**Entscheidung:** Default ist ein reines Blatt (erbt nichts); andere Rollen dürfen von Default erben
(siehe FR-013).
