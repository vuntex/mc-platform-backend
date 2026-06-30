# CLAUDE.md
- Lies immer zuerst PROGRESS.md für den aktuellen Stand und die Architektur.
- Hexagonal/DDD: core-domain hat KEINE Framework-Imports (kein Spring, kein jOOQ).
- Economy ist event-sourced. Niemals balance direkt mutieren — immer über ein Event.
- Geld ist BIGINT, nie Float.
- Jede Code-Änderung, die den Stand verschiebt, wird in PROGRESS.md nachgezogen.
- Erst Tests/lauffähig, dann in die Breite.

## Was dieses Projekt ist

Ein Minecraft-Server-Platform-Projekt: Spring-Boot-**Backend** (Single Source of Truth) +
Paper-1.21-**Plugin** (reiner Client), getrennt in zwei Repos, verbunden über das geteilte,
dependency-freie `plugin-protocol` (Maven Local). Aktueller Stand und Architektur-Historie stehen
in **PROGRESS.md** — bei Session-Start lesen. Wir migrieren schrittweise ein großes altes
1.8.9-Plugin in diese neue Architektur (Inventar: **FEATURE_INVENTORY.md**).

## Verbindliche Grundlagen (immer zuerst lesen)

1. **`.specify/memory/constitution.md`** — die architektonische DNA. NICHT verhandelbar. Jede Spec/
   Plan/Task ordnet sich unter. Bei Konflikt gewinnt die Constitution.
2. **PROGRESS.md** — aktueller Architektur-/Status-Stand (Backend-Repo).
3. **FEATURE_INVENTORY.md** — Bestandsaufnahme + Migrations-Reihenfolge + offene Fragen des alten
   Plugins.
4. **docs/MENU_DESIGN.md** (Plugin-Repo) — verbindlicher Stil-Brief für alle Inventar-Menüs.

## Spec-Kit-Workflow (pro Feature)

Jedes Feature durchläuft die Kette, jeder Schritt ist ein menschlicher Checkpoint:
- `/speckit.specify` → spec.md (WAS gebaut wird — Verhalten, Scope, „migrieren wir das überhaupt?")
- `/speckit.plan` → plan.md (WIE — Schichten, Datenmodell, Wiederverwendung, protocol-Ergänzungen)
- `/speckit.tasks` → tasks.md (phasierte, geordnete Tasks)
- `/speckit.implement` → Code, Task für Task

Behandle den ersten Entwurf NIE als final — nutze die Interaktion, um zu klären und Lücken/
Edge-Cases aufzudecken, bevor es weitergeht.

## Eiserne Regeln (aus der Constitution, hier als Schnellreferenz)

- **Backend = Wahrheit. Plugin = Client.** Plugin: keine DB, kein Spring, keine direkten Redis-
  HASH-Reads. Writes über REST, Live-Updates lesend über Pub/Sub.
- **plugin-protocol bleibt JDK-only** — kein Spring/jOOQ/Lettuce/JSON. POM ohne `<dependencies>`.
  JSON-Mapping nur in Plugin/Backend. Wire über `MessageEnvelope`/`MessageCodec<T>`.
- **Abhängigkeitsrichtung Plugin → protocol, nie zurück.** Backend-Code nur via publiziertes
  Artefakt nutzen.
- **Main-Thread nie blockieren.** REST/Redis async, Übergänge über die Scheduler-Abstraktion.
- **Wiederverwenden statt neu bauen.** Vor jedem Feature prüfen, welche generischen Bausteine schon
  existieren (FeatureCache, EventBus, BackendClient/EndpointDescriptor, MenuBuilder,
  PermissionResolver, Economy-Event-Store-Muster). Etablierte Libs/interne Abstraktionen bevorzugen.
- **Ein Feature = ein Anstecken.** Neues Package + FeatureRegistry-Eintrag (+ protocol/Menü). Musst
  du eine GENERISCHE Klasse ändern → STOPP, als Muster-Leck melden, ggf. eigener Vor-Refactor.
- **Berechtigungen backend-autoritativ** über PermissionResolver-Port. UI-Gate nur Komfort.
- **Menüs übers Framework** (MenuBuilder/MenuManager, Adventure-Components, LIVE/STATIC), nach
  MENU_DESIGN.md. LIVE-Menüs beim Close abmelden.

## Projekt-Entscheidungen (Stand jetzt)

- **Single-Server** — kein Distributed-Locking, kein Cross-Server-Dupe-Schutz nötig. Pub/Sub ist
  der Live-Pfad Backend↔Plugin/Webinterface, nicht zwischen Game-Servern.
- **Externer Webshop** schreibt Economy-Credits als Events mit `source='WEBSHOP'`, idempotent über
  Bestell-ID. Economy-Backend bleibt Balance-Wahrheit.
- **Moderation getrennt:** Spieler-Strafen → Punishments-Backend; Globalmute/Chatclear/Wordfilter/
  Broadcast → Server-/Moderation-Tools (Server-Zustand); Reports + Support/Tickets → eigenes
  Moderation/Tickets-Modul (Anschuldigung ≠ Urteil).
- **Selektiver Import (~80% migrieren, ~20% verwerfen).** Jede Feature-Spec startet mit „migrieren
  wir das, und in welchem Umfang?". „Brauche ich nicht" ist eine legitime Antwort.
- **Verhalten & Design 1:1, Technik nicht.** NMS/§-Codes/manuelles Inventory/eigene Datenhaltung
  fallen weg. Jede Import-Spec benennt explizit, was wegfällt.

## Definition of Done

- Tests pro Schicht grün (Domain, Use-Case, Integration/Testcontainers, E2E inkl. Fehlerpfade).
- `./gradlew build` grün (Backend und Plugin).
- Bei protocol-Änderung: `:plugin-protocol:publishToMavenLocal` (Backend), dann im Plugin
  `build --refresh-dependencies`.
- PROGRESS.md-Status-Abschnitt nachgezogen, FEATURE_INVENTORY.md-Eintrag abgehakt.
- Bestätigt: keine generische Klasse geändert (oder Muster-Leck explizit dokumentiert + begründet).

## Repo-/Fenster-Hinweis

Zwei getrennte Repos = zwei Arbeitskontexte. Backend-Arbeit (Domäne/Persistenz/REST/protocol) und
Plugin-Arbeit (feature/transport/platform/Menü) nicht vermischen. protocol-Änderungen entstehen im
Backend-Repo, werden publiziert, dann im Plugin gezogen. Niemals das alte Plugin und das neue als
ein gemeinsames Projekt behandeln.


<!-- SPECKIT START -->
For additional context about technologies to be used, project structure,
shell commands, and other important information, read the current plan:
`specs/006-role-inheritance/plan.md` (Feature: Rollen-Vererbung — eine Rolle erbt transitiv die Permissions anderer Rollen (M:N, reine Union, kein Meta-Feld). Transitivität im `JooqPermissionResolver`-Hot-Path als rekursive `reachable_roles`-CTE + im View-Path als neue reine Domain-Schicht `RoleHierarchy` (vor das unveränderte `EffectivePermissions`); Default bleibt exklusiver Fallback (Blatt); Zyklus-Schutz zweistufig (409 + Visited-Set); Live-Push über bestehenden `ROLE_CONFIG_CHANGED`-Pfad an die Reverse-Closure; neue `role_inheritance`-Tabelle (V15); Gate `permission.role.edit.inherit`; Branch `006-role-inheritance`).
<!-- SPECKIT END -->
