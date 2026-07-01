# Feature Specification: Autoritäts-Grenzen für die Rollen-/Permission-Verwaltung

**Feature Branch**: `008-authority-limits`

**Created**: 2026-07-01

**Status**: Draft

**Input**: User description: "Autoritäts-Grenzen für die Rollen-/Permission-Verwaltung (Privilege-Escalation-Schutz im Webinterface) — weight-basierte Autoritäts-Achse, die begrenzt, wie weit Read/Write/Grant reichen."

## Migrieren wir das — und in welchem Umfang? *(mandatory)*

Kein Legacy-Import, sondern eine **Sicherheits-Verschärfung** der bestehenden Permission-/Rang-
Verwaltung im Webinterface (`/api/web/permission/**`, Slices 002/005/006). Heute sind die Gates binär:
wer `permission.grant.role`/`permission.grant.permission`/`permission.role.*` hält, darf damit **alles** —
sich selbst `*` geben, sich oder anderen einen höheren Rang (z. B. Owner) zuweisen, hohe Ränge sehen.
Es fehlt eine **Autoritäts-Achse**, die begrenzt, *wie weit* eine Berechtigung reicht.

**In Scope:** Eine zusätzliche, backend-autoritative Autoritäts-Prüfschicht über den bestehenden
Gates — bei Schreib-/Grant-Operationen (Ablehnung mit 403) **und** bei den Management-Reads
(gefilterte Ergebnisse / 403). Keine neue Domäne, keine neuen Endpunkte; bestehende Endpunkte
verhalten sich nur strenger.

**Bewusst NICHT in diesem Feature:** Neue Rollen/„Owner"-Seed (die Top-Stufe ist dynamisch das aktuell
höchste Weight); Änderung der Berechtigungs-Modell-Grundlagen (Vererbung, Default-Fallback bleiben);
Plugin-seitige Permission-Prüfung (unverändert).

## Clarifications

### Session 2026-07-01

- Q: Schwelle non-top vs. Top-Tier? → A: **Strikt `<`** für non-top (man kann die eigene Stufe NICHT
  verwalten); **`≤`** nur für Top-Tier (eigene Stufe verwaltbar). Niemand — auch Top-Tier nicht — darf
  eine Rolle über dem aktuellen Maximal-Weight anlegen/anheben.
- Q: Wer ist „Top-Tier"? → A: Inhaber der Rolle mit dem **aktuell höchsten Weight** im System (heute
  faktisch Admin; später Owner). Keine eigene „Owner"-Rolle nötig.
- Q: Wildcard vergeben? → A: Jede Wildcard (`X.*` oder `*`) darf nur vergeben, wer **selbst `*`** hält.
  Konkrete (nicht-Wildcard) Permissions: vergebbar, wenn der Actor sie effektiv hält.
- Q: Read auch begrenzen? → A: Ja — Management-Listen gefiltert; `/api/web/me` (eigener Rang) bleibt
  unbegrenzt; Spieler mit Autorität ≥ der eigenen sind nicht einsehbar (403).
- Q: Verhältnis `*` vs. weight-basierte Rang-Autorität? → A: **Unabhängige Achsen.** Weight ist die
  einzige Rang-Autorität und das Ceiling gilt auch für `*`-Inhaber; `*` regelt ausschließlich, welche
  Permissions delegiert werden dürfen. Ein „Superadmin" braucht Top-Weight **und** `*`.
- Q: Genaue Definition des Lockout-Schutzes? → A: **Ergebnisbasiert.** Jede Operation (Rollen-Entzug,
  Rollen-Löschung, Weight-Absenkung einer Top-Rolle, Self-Demote), die dazu führen würde, dass **kein**
  Account mehr eine Rolle mit dem aktuellen Top-Weight hält, wird mit 409 abgelehnt.
- Q: Read-Filter bei der Spieler-Suche? → A: **Suche + allgemeine Spieler-Details bleiben ungefiltert**
  (Spieler bleiben auffindbar/sichtbar). Gesperrt ist nur der **Permissions-/Rollen-Tab** (die
  Permission-Detailansicht) eines Spielers mit Autorität ≥ der eigenen → 403. Die Read-Begrenzung
  betrifft also die Rang-/Permission-Sicht, nicht die Spieler-Stammdaten.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - „Du kannst nur delegieren, was du selbst hast" (Priority: P1)

Ein Staff-Mitglied mit Permission-/Rollen-Rechten kann einer Rolle oder einem Spieler **nur**
Permissions geben, die es selbst effektiv besitzt — und insbesondere **kein `*`/keine Wildcard**, wenn
es nicht selbst `*` hält. Damit kann sich niemand über die eigenen Rechte hinaus erhöhen.

**Why this priority**: Direkter Schutz gegen die schwerste Eskalation (Self-`*`/beliebige Rechte) und
in sich testbar — unabhängig von der Rang-Hierarchie.

**Independent Test**: Ein Actor ohne `*` versucht (a) einer Rolle `*` zu geben, (b) einem Spieler eine
Permission direkt zu geben, die er nicht hält → beides 403; eine Permission, die er hält, → erlaubt.

**Acceptance Scenarios**:

1. **Given** ein Actor, der `*` **nicht** hält, **When** er einer Rolle oder einem Spieler `*` oder
   eine `X.*`-Wildcard zuweisen will, **Then** wird die Aktion mit 403 abgelehnt.
2. **Given** ein Actor, der die Permission `economy.read` hält, aber nicht `economy.write`, **When** er
   versucht, `economy.write` zu vergeben, **Then** 403; **When** er `economy.read` vergibt, **Then**
   erlaubt.
3. **Given** ein Actor mit `*`, **When** er eine Wildcard/`*` vergibt, **Then** erlaubt.

---

### User Story 2 - Rang-Hierarchie bei Rollen-Verwaltung & -Vergabe (Priority: P1)

Ein Staff-Mitglied kann nur Rollen **unterhalb** der eigenen Stufe verwalten und vergeben; es kann
sich oder anderen keinen Rang auf/über der eigenen Stufe zuweisen und keine Rolle über dem aktuellen
Maximum erschaffen. So kann z. B. ein Admin sich nicht selbst Owner geben.

**Why this priority**: Zweiter Kern-Eskalationspfad (Rang-Erhöhung). Eigenständig testbar.

**Independent Test**: Ein non-top Actor (Autorität W) versucht, eine Rolle mit Weight ≥ W zu
bearbeiten/zu löschen/zuzuweisen → 403; eine Rolle mit Weight < W → erlaubt. Eine neue Rolle mit
Weight > W anlegen → 403.

**Acceptance Scenarios**:

1. **Given** ein non-top Actor mit Autorität W, **When** er eine Rolle mit `weight ≥ W` anlegt,
   bearbeitet, löscht, deren Permissions/Vererbung ändert, **Then** 403; bei `weight < W` erlaubt.
2. **Given** derselbe Actor, **When** er einem Spieler eine Rolle mit `weight ≥ W` zuweisen oder
   entziehen will, **Then** 403; bei `weight < W` erlaubt.
3. **Given** ein Actor, **When** er eine Rolle mit `weight >` seiner eigenen Autorität anlegen/anheben
   will, **Then** 403 — auch als Top-Tier (kein Rang über dem Maximum).
4. **Given** ein Ziel-Spieler, dessen aktuelle Autorität `≥` der des Actors ist, **When** der Actor
   dessen Rollen ändern will, **Then** 403.

---

### User Story 3 - Begrenzte Sichtbarkeit hoher Ränge (Read) (Priority: P2)

Ein Staff-Mitglied sieht in den Verwaltungs-Ansichten nur Ränge bis zu seiner eigenen Stufe — hohe
Ränge (über der eigenen) erscheinen nicht in Rollen-Listen/-Pickern, und den **Permissions-/Rollen-Tab**
eines höher-autorisierten Spielers kann es nicht öffnen. Spieler bleiben aber **auffindbar** (Suche/
Stammdaten ungefiltert), und den **eigenen** Rang sieht jeder weiterhin.

**Why this priority**: Verhindert Informations-Leak über hohe Ränge/Rechte und „verleitet" nicht zu
Aktionen, die ohnehin abgelehnt würden. Baut auf der gleichen Autoritäts-Achse auf, ist aber von den
Writes unabhängig.

**Independent Test**: Ein non-top Actor ruft die Rollen-Liste ab → enthält keine Rolle mit Weight ≥
seiner Stufe; öffnet den Permissions-Tab eines höher-autorisierten Spielers → 403; findet denselben
Spieler aber weiterhin über die Suche; ruft `/me` ab → sieht den eigenen (auch hohen) Rang.

**Acceptance Scenarios**:

1. **Given** ein non-top Actor mit Autorität W, **When** er die Rollen-Liste/den Rollen-Picker abruft,
   **Then** enthält das Ergebnis ausschließlich Rollen mit `weight < W` (Top-Tier: `≤`).
1a. **Given** derselbe Actor, **When** er eine einzelne Rolle mit `weight ≥ W` **direkt per ID**
   (Detail/Permissions/Vererbung) abruft, **Then** 403 (kein Umgehen der Listen-Filterung).
2. **Given** ein Spieler mit Autorität `≥ W`, **When** der Actor dessen Permissions-/Rollen-Detail
   (Permissions-Tab, effektive Permissions/Rollen) abruft, **Then** 403.
3. **Given** derselbe höher-autorisierte Spieler, **When** der Actor die Spieler-Suche oder allgemeine
   Spieler-Stammdaten abruft, **Then** erscheint/lädt der Spieler normal (keine Filterung).
4. **Given** ein beliebiger authentifizierter Account, **When** er den eigenen Identitäts-/Rang-Read
   (`/api/web/me`) abruft, **Then** wird der eigene Rang **immer** geliefert (keine Filterung).

---

### User Story 4 - Top-Tier-Selbstverwaltung & Lockout-Schutz (Priority: P2)

Die höchste Stufe (Top-Tier) darf — als bewusste Ausnahme — die eigene Stufe verwalten (sonst wäre sie
nicht administrierbar). Gleichzeitig darf das System nicht unverwaltbar werden: der **letzte** Inhaber
der Top-Stufe kann nicht aus ihr entfernt werden.

**Why this priority**: Macht das Modell betriebsfähig (Bootstrap/Selbstverwaltung), schließt aber die
Lockout-Lücke. Hängt von US2 ab (gleiche Achse), daher P2.

**Independent Test**: Ein Top-Tier-Account verwaltet eine andere Top-Tier-Rolle/-Person → erlaubt;
der Versuch, den letzten Top-Tier-Inhaber zu entmachten (Revoke/Delete/Self-Demote) → abgelehnt.

**Acceptance Scenarios**:

1. **Given** ein Top-Tier-Actor und ein weiterer Top-Tier-Inhaber, **When** er Rollen auf eigener Stufe
   verwaltet, **Then** erlaubt (`≤`-Regel).
2. **Given** der **letzte** Inhaber der Top-Stufe, **When** ihm diese Rolle entzogen, die Rolle gelöscht
   oder er sich selbst entmachtet, **Then** wird die Aktion mit einem Konflikt (409) abgelehnt — das
   System behält mindestens einen Top-Tier.
3. **Given** ein Top-Tier-Inhaber, der **nicht** der letzte ist, **When** er sich selbst die Top-Rolle
   entzieht, **Then** erlaubt.

### Edge Cases

- Actor ohne jede Rolle (Autorität = Default-Weight 0) → kann faktisch nichts verwalten/vergeben.
- Zwei Accounts auf gleicher (non-top) Stufe → können sich gegenseitig nicht verwalten/umranken.
- Rolle bearbeiten, sodass ihr Weight über die eigene Autorität stiege → abgelehnt (Regel „kein Rang
  über Max" / nicht über eigene Stufe).
- Eigene Rolle (== eigene Stufe) als non-top bearbeiten (z. B. sich Rechte zur eigenen Rolle hinzufügen)
  → abgelehnt (strikt `<`).
- Direkter Permission-Grant vs. Rollen-Grant: die Subset-Regel (US1) greift bei **Permission**-Vergabe
  (an Rolle ODER Spieler); die Weight-Regel (US2) greift bei **Rollen**-Vergabe und Rollen-Management.
- Default-Rolle: unterste Stufe (Weight 0), weiterhin nicht direkt vergebbar; von den Autoritäts-Filtern
  nicht „versteckt" in einer Weise, die den bestehenden Default-Fallback bricht.

## Requirements *(mandatory)*

### Functional Requirements

**Autoritäts-Grundlagen**

- **FR-001**: Das System MUSS für jeden handelnden Account eine Autoritätsstufe `authorityWeight`
  bestimmen = höchstes `weight` aller effektiv gehaltenen Rollen (inkl. Vererbung); ohne Rang gilt der
  Default-Rang-Weight (0).
- **FR-002**: Das System MUSS „Top-Tier" als das **aktuell höchste vergebene Rollen-`weight`** im
  System definieren; ein Account ist Top-Tier, wenn `authorityWeight(actor)` diesem Maximum entspricht.
- **FR-002a**: Rang-Autorität MUSS **ausschließlich** aus `authorityWeight` (Rollen-Weight) abgeleitet
  werden. Das Halten von `*` (oder einer anderen Wildcard) verändert die Rang-Autorität **nicht** — das
  Weight-Ceiling (FR-003..FR-006, FR-009/010) gilt auch für `*`-Inhaber. `*` beeinflusst nur die
  Delegations-Regel (FR-008).

**Rang-Hierarchie (US2)**

- **FR-003**: Rollen-Management (Anlegen, Bearbeiten, Löschen, Vererbung ändern, Role-Permission
  hinzufügen/entfernen) MUSS erfordern, dass die betroffene Rolle `weight < authorityWeight(actor)`
  (non-top) bzw. `≤` (Top-Tier) hat; sonst 403.
- **FR-004**: Das System MUSS verhindern, dass eine Rolle mit `weight > authorityWeight(actor)`
  angelegt oder auf einen solchen Weight angehoben wird — auch für Top-Tier (kein Rang über dem
  Maximum); sonst 403.
- **FR-005**: Eine Rolle einem Spieler zuweisen/entziehen MUSS erfordern, dass `rolle.weight <
  authorityWeight(actor)` (Top-Tier `≤`); sonst 403.
- **FR-006**: Eine Rollen-/Permission-Änderung an einem Ziel-Spieler MUSS erfordern, dass die aktuelle
  Autorität des Ziels `< authorityWeight(actor)` (Top-Tier `≤`) ist; sonst 403.

**Delegations-Subset (US1)**

- **FR-007**: Eine Permission darf einer Rolle oder einem Spieler nur hinzugefügt/gewährt werden, wenn
  der Actor sie selbst effektiv hält (wildcard-aware Auflösung); sonst 403.
- **FR-008**: Eine Wildcard-Permission (`*` oder endend auf `.*`) darf nur vergeben werden, wenn der
  Actor `*` effektiv hält; sonst 403.

**Sichtbarkeit / Read (US3)**

- **FR-009**: Rollen-Listen/-Picker MÜSSEN auf Rollen mit `weight < authorityWeight(actor)` (Top-Tier
  `≤`) gefiltert werden.
- **FR-009a**: Auch der **Einzel-Rollen-Read** (eine Rolle per ID, ihre Permissions, ihre Vererbung)
  MUSS für eine Rolle mit `weight ≥ authorityWeight(actor)` (non-top; Top-Tier: `>`) mit 403 abgelehnt
  werden — sonst ließe sich eine aus der Liste gefilterte hohe Rolle per direkter ID doch abrufen.
- **FR-010**: Das Abrufen der **Permissions-/Rollen-Detailansicht** (Permissions-Tab, effektive
  Permissions/Rollen) eines Spielers mit Autorität `≥ authorityWeight(actor)` (non-top; Top-Tier `>`)
  MUSS mit 403 abgelehnt werden. **Ausnahme:** der **eigene** Account (actor == target) ist IMMER
  einsehbar — jeder darf seine eigenen Permissions sehen, unabhängig von der Autorität.
- **FR-010a**: Die **Spieler-Suche** und **allgemeine Spieler-Stammdaten** bleiben **ungefiltert** —
  Spieler bleiben auffindbar und sichtbar, unabhängig von ihrer Autorität. Die Begrenzung betrifft nur
  die Rang-/Permission-Sicht (FR-010), nicht die Spieler-Grunddaten.
- **FR-011**: Der eigene Identitäts-/Rang-Read (`/api/web/me`) MUSS unbegrenzt bleiben (eigener Rang
  immer sichtbar, keine Filterung).

**Durchsetzung & Querschnitt**

- **FR-012**: Alle Regeln MÜSSEN backend-autoritativ durchgesetzt werden (nicht nur als UI-Gate);
  Autoritäts-Verstoß bei Writes/Grants → 403.
- **FR-013**: Die Prüfungen MÜSSEN unabhängig vom Pfad gelten (Rollen-Verwaltung, Rollen-Grant,
  direkter Permission-Grant) — kein Pfad darf die Autoritäts-Schicht umgehen.
- **FR-014**: Die Autoritäts-Schicht kommt **zusätzlich** zu den bestehenden Gates (`permission.read`,
  `permission.role.*`, `permission.grant.*`) — diese bleiben Voraussetzung, die Autorität begrenzt
  zusätzlich die Reichweite.

**Lockout-Schutz & Selbstverwaltung (US4)**

- **FR-015**: **Ergebnisbasierter Lockout-Schutz:** Jede Operation — Rollen-Entzug, Rollen-Löschung,
  Weight-Absenkung einer Top-Rolle, Self-Demotion — MUSS mit einem Konflikt (409) abgelehnt werden,
  wenn danach **kein** Account mehr eine Rolle mit dem aktuellen Top-Weight hielte (Anzahl Top-Tier-
  Inhaber fiele auf 0). Das System behält so stets mindestens einen Top-Tier. Der Check betrachtet das
  **Ergebnis** der Operation, unabhängig vom gewählten Pfad.
- **FR-016**: Ein Top-Tier-Account DARF sich selbst die Top-Rolle entziehen, **außer** er ist der
  letzte Inhaber (dann greift FR-015).

### Key Entities *(include if feature involves data)*

- **Rolle**: trägt `weight` (Autoritäts-/Hierarchie-Achse) und Permissions; bestehend, unverändert.
- **Actor (handelnder Staff-Account)**: identifiziert über die Web-Session; abgeleitete
  `authorityWeight` (höchstes Rollen-Weight) + effektive Permission-Menge.
- **Ziel-Spieler**: der Account, dessen Rollen/Permissions verwaltet werden; hat eine eigene aktuelle
  Autorität (höchstes Rollen-Weight).
- **Top-Stufe (Top-Tier)**: das aktuell höchste vergebene Rollen-`weight` im System (dynamisch).
- **Permission**: String, ggf. Wildcard (`*`, `X.*`); „Halten" wird wildcard-aware bestimmt.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Ein Account ohne `*` kann über **keinen** Pfad (Rolle bearbeiten, Spieler direkt) `*` oder
  eine Wildcard vergeben — 0 erfolgreiche Fälle in der Akzeptanzprüfung.
- **SC-002**: Ein non-top Account kann weder eine Rolle noch einen Spieler auf oder über seiner eigenen
  Stufe verwalten/umranken; jeder Versuch endet mit 403.
- **SC-003**: In den Management-Listen erscheinen für einen non-top Account **keine** Rollen mit Weight
  ≥ seiner eigenen Stufe; der **Permissions-Tab** eines höher-autorisierten Spielers ist gesperrt (403),
  während die Spieler-Suche/-Stammdaten ihn weiterhin liefern.
- **SC-004**: Der letzte Top-Tier-Inhaber kann nicht entmachtet werden (Versuch → 409); das System
  bleibt jederzeit verwaltbar (immer ≥ 1 Top-Tier).
- **SC-005**: Ein Account kann ausschließlich Permissions vergeben, die er selbst effektiv hält — 0
  Fälle, in denen eine nicht-gehaltene Permission erfolgreich delegiert wird.
- **SC-006**: Der eigene Rang ist über `/api/web/me` für jeden authentifizierten Account abrufbar,
  unabhängig von seiner Autoritätsstufe.

## Assumptions

- `weight` ist die **einzige** Autoritäts-Achse (kein zusätzliches „level"-Feld); `authorityWeight`
  leitet sich aus den Rollen (inkl. Vererbung) ab, nicht aus Direkt-Permissions.
- Top-Tier ist **dynamisch** (Inhaber des aktuell höchsten Rollen-Weights); es muss keine dedizierte
  „Owner"-Rolle existieren.
- Strikte Schwelle: `<` für non-top, `≤` ausschließlich für Top-Tier.
- Die Subset-Prüfung (FR-007) nutzt die **bestehende** effektive-Permission-Auflösung (wildcard-aware);
  zum Vergeben einer Wildcard ist `*` nötig (FR-008).
- Die Autoritäts-Regeln sind eine **zusätzliche Schicht** über den bestehenden Gates und ersetzen diese
  nicht; bestehende 002/005/006-Funktionalität bleibt, nur strenger.
- Read-Begrenzung betrifft (a) die Rollen-Listen/-Picker (weight-gefiltert) und (b) die Permissions-/
  Rollen-Detailansicht eines Spielers (403 bei Autorität ≥). **Nicht** betroffen: Spieler-Suche,
  allgemeine Spieler-Stammdaten und `/api/web/me` (eigener Rang) — die bleiben ungefiltert.
- Default-Rolle bleibt unverändert (unterste Stufe, weiterhin nicht direkt vergebbar — Slice 006/005).
- „Letzter Top-Tier" = letzter verbleibender Inhaber der Rolle(n) mit dem aktuell höchsten Weight.
- Konkrete Schichten/Klassen (Durchsetzung in der Application-/Resolver-Leseseite, Fehlercodes,
  bestehende `PermissionAdminService`/Query-Eingriffe) werden im `/speckit-plan`-Schritt fixiert.
