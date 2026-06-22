# Project Constitution — MC-Platform (Backend + Plugin)

> Die architektonische DNA des Projekts. Jede Spec, jeder Plan, jede Task ordnet sich diesen
> Prinzipien unter. Bei Konflikt zwischen einer Feature-Anforderung und diesen Prinzipien gewinnt
> die Constitution — oder das Prinzip wird hier bewusst und begründet geändert, nicht im Feature
> umgangen.

## I. Grundarchitektur (nicht verhandelbar)

1. **Backend ist Single Source of Truth.** Das Spring-Boot-Backend hält die Wahrheit über alle
   persistenten Daten. Das Paper-1.21-Plugin ist reiner Client. Das Plugin hat KEINE eigene
   Datenbank, KEIN Spring, und liest NIEMALS interne Redis-Datenstrukturen (z.B. Hot-Cache-HASHes)
   direkt — es subscribt nur auf definierte Pub/Sub-Channels.

2. **Writes/Commands gehen über REST ans Backend. Live-Updates kommen lesend über Redis-Pub/Sub.**
   Das Plugin mutiert niemals lokal und schreibt später — jede Zustandsänderung läuft synchron
   durchs Backend; Änderungen propagieren als Pub/Sub-Event zurück in lokale Caches.

3. **Strikte Abhängigkeitsrichtung: Plugin → plugin-protocol, niemals zurück.** Kein Code aus dem
   Backend-Repo wird im Plugin referenziert außer über das publizierte `plugin-protocol`-Artefakt
   (Maven Local, `com.mcplatform`, SNAPSHOT). Diese Repo-Trennung ist eine FEATURE, kein Hindernis:
   sie erzwingt den sauberen Contract-Schnitt.

4. **`plugin-protocol` bleibt dependency-frei (nur JDK).** Kein Spring, jOOQ, Lettuce, kein
   JSON-Framework. Der publizierte POM hat KEINEN `<dependencies>`-Block. JSON-Mapping lebt im
   Plugin und im Backend, nie im geteilten Contract. Wire-Formate laufen über den generischen
   `MessageEnvelope` + `MessageCodec<T>`, nicht über handgeschriebene Feature-Sonderformate.

## II. Hexagonal / DDD (Backend)

5. **Schichten:** core-domain (framework-frei, reine Geschäftslogik) → application (Use Cases +
   Ports) → infra-persistence (jOOQ + Flyway) / infra-cache (Lettuce) → api-rest / api-realtime →
   app (Composition Root). Geschäftsregeln leben in core-domain, nie in Controllern oder Adaptern.

6. **Persistenz-Wahl pro Feature begründen.** Event-sourced (Audit-Trail, Idempotenz,
   Concurrency-Sicherheit — wie Economy) ODER state-stored CRUD + Audit (selten ändernde Config —
   wie server_config). Die Wahl wird in der Spec/dem Plan begründet, nicht aus Gewohnheit kopiert.

7. **Idempotenz bei zustandsändernden Operationen** über einen Transaktions-/Korrelations-
   Schlüssel (deterministisch ableitbar wo möglich), abgesichert per Unique-Constraint. Doppelte
   Zustellung darf nie doppelt wirken.

## III. Plugin-Schichtung

8. **Schichten:** platform (Bukkit/Paper-Berührung — Listener, Commands, Scheduler-Abstraktion;
   Menü-Framework) → transport (generisch: BackendClient/REST, EventBus/Redis, version-aware
   FeatureCache) → feature (eine Domäne je PluginFeature) → protocol (geteilter Contract). Features
   berühren Bukkit NUR über die platform-Schicht; der Bukkit-Main-Thread wird NIE durch I/O
   blockiert (REST/Redis async, Übergänge über die Scheduler-Abstraktion).

9. **Ein Feature = ein Anstecken.** Ein neues Feature entsteht als: neues Feature-Package + ein
   Eintrag in der FeatureRegistry (+ ggf. Channel/DTO/Endpoint im protocol + Menü übers Framework).
   Wenn ein Feature eine bestehende GENERISCHE Klasse (FeatureCache, EventBus, BackendClient,
   MenuBuilder, PluginFeature, Scheduler, MessageEnvelope) ÄNDERN müsste, ist das ein MUSTER-LECK:
   STOPP, melden, bewerten — eine echte fehlende Generalisierung wird als eigener kleiner Refactor
   ZUVOR gemacht, nicht ins Feature geschmuggelt.

## IV. Wiederverwendung vor Neubau (kritisch)

10. **Nutze die bestehenden generischen Bausteine — erfinde nichts neu.** Vor dem Bau eines
    Features wird geprüft, was bereits existiert (Economy-Event-Store-Muster, PermissionResolver-
    Port, FeatureCache, EventBus, BackendClient/EndpointDescriptor, Menü-Framework/MenuBuilder).
    Bevorzuge etablierte Bibliotheken und bestehende interne Abstraktionen gegenüber selbstgebauten
    Lösungen. Wo etwas Generisches fehlt, wird es generalisiert — nicht feature-lokal dupliziert.

11. **Menüs laufen über das Menü-Framework** (MenuBuilder, zentraler MenuManager, LIVE/STATIC pro
    Menü), streng nach `docs/MENU_DESIGN.md`. Kein manuelles Inventory-Click-Handling pro Feature,
    keine §-/ChatColor-Strings (Adventure-Components). LIVE-Menüs melden sich beim Close sauber ab
    (kein Beobachter-Leak).

## V. Sicherheit & Autorität

12. **Berechtigungen sind backend-autoritativ** über den `PermissionResolver`-Port. Das Plugin darf
    ein optimistisches UI-Gate haben (gesperrte Aktionen ausblenden), aber die echte Prüfung liegt
    immer im Backend (z.B. 403). Die konkrete Backing-Quelle (DB-Rollen-Tabelle heute, LuckPerms
    später) ist hinter dem Port austauschbar — eine spätere LuckPerms-Anbindung betrifft nur diese
    eine Klasse.

13. **Geldähnliche Werte (Economy) maximal absichern:** synchrone Debits mit Guthabenprüfung in der
    DB-Transaktion, lückenloser event-sourced Audit-Trail, Geld nie als Float (BIGINT, kleinste
    Einheit). Jede Geld-Mutation trägt eine aussagekräftige `source`.

## VI. Projekt-spezifische Entscheidungen (Stand jetzt)

14. **Single-Server.** Das System läuft (vorerst) auf EINEM Paper-Node. Multi-Server/Velocity ist
    NICHT der treibende Designfall. Daraus folgt: KEIN Distributed-Locking, KEIN Cross-Server-
    Item-Dupe-Schutz, keine Cross-Server-Presence nötig. Das alte RAM-Cache+Flush-Modell wird
    trotzdem durch „Plugin schreibt durchs Backend" ersetzt — als Qualitäts-/Crash-Sicherheits-
    Verbesserung, nicht aus Multi-Server-Zwang. Redis-Pub/Sub bleibt der Live-Pfad zwischen Backend
    und Plugin/Webinterface (Cache-Invalidierung, Live-Push), nicht zwischen Game-Servern.

15. **Externer Webshop ist ein legitimer zweiter Schreib-Akteur für Economy.** Käufe aus dem
    externen Webshop schreiben Guthaben als event-sourced Credit mit `source = 'WEBSHOP'` und der
    Bestell-ID als Idempotenz-Schlüssel (doppelte Webhooks schreiben nie doppelt gut). Das
    Economy-Backend ist Source of Truth für den BALANCE-Stand; der Webshop ist eine Quelle von
    Credit-Events, kein paralleler Balance-Speicher.

16. **Moderation sauber aufteilen:**
    - **Spieler-bezogene Strafen** (Ban/Tempban/Permaban/Warn/Spieler-Chatban-„Mute") → bestehendes
      **Punishments-Backend** (ein weiterer PunishmentType wo nötig). Nicht doppelt bauen.
    - **Globalmute** (serverweiter Chat-/PM-Schalter, KEINE Einzelspieler-Strafe), Chatclear,
      Wordfilter-Toggle, Broadcast → **Server-/Moderation-Tools** (Server-Zustand, nicht
      Punishments).
    - **Reports** (Spieler meldet Spieler — Anschuldigung, Lebenszyklus offen→bearbeitet→
      erledigt/abgelehnt) und **Support/Tickets** (gleicher Lebenszyklus) → eigenes kleines
      **Moderation/Tickets-Modul**, getrennt von Punishments. Punishments kann referenziert werden
      („dieser Report führte zu diesem Ban"), bleibt aber konzeptuell getrennt: Anschuldigung ≠
      Urteil.

17. **Selektiver Import.** Das alte Plugin (91 inventarisierte Features) wird NICHT vollständig
    übernommen — grob ~80% werden migriert, ~20% bewusst verworfen. „Brauche ich nicht mehr" ist
    eine erwartete, legitime Antwort pro Feature. Jede Feature-Spec beginnt mit der Frage „migrieren
    wir das überhaupt, und wenn ja, im vollen Umfang?". Verworfene Features werden im Inventar als
    solche markiert, nicht stillschweigend mitgezogen.

18. **Verhalten & Design 1:1, Technik NICHT.** Beim Import wird das Spieler-sichtbare Verhalten und
    das Menü-Design (siehe `docs/MENU_DESIGN.md`) übernommen, aber nicht der alte Code: NMS,
    Reflection, §-Farbcodes, manuelles Inventory-Handling, eigene RAM-Cache+Flush-Datenhaltung
    fallen weg und werden durch die neuen Wege ersetzt. Jede Import-Spec benennt explizit, was vom
    Alten WEGFÄLLT.

## VII. Arbeitsweise

19. **Vertical Slices.** Features werden als durchgehende Schichten gebaut (Domäne → Persistenz →
    REST → Plugin → Menü), die den vollen Stack ausüben — nicht horizontal Schicht für Schicht über
    alle Features.

20. **Ein Feature komplett, bevor das nächste startet.** Bei vielen zu migrierenden Features ist
    paralleles Anfangen verboten — ein Feature durch alle Phasen + getestet + Build grün, dann das
    nächste. Das schützt den „ein Feature = ein Anstecken"-Beweis und den Überblick.

21. **Architektur-Prüfung vor Code.** Jedes nennenswerte Feature wird zuerst entworfen und gegen
    diese Constitution geprüft (welche Schichten, welche Wiederverwendung, welche Muster-Lecks),
    BEVOR Code entsteht. Der Mensch verifiziert an jedem Checkpoint, statt erst den fertigen Code zu
    reviewen.

22. **Tests pro Schicht, Build grün als Definition of Done.** Domain-Regeln, Use-Case (mit Fakes),
    Integration (Testcontainers), E2E (inkl. relevanter Fehlerpfade). Ein Feature gilt erst als
    fertig, wenn `./gradlew build` grün ist und das Inventar abgehakt wurde. Bei protocol-Änderungen:
    `publishToMavenLocal`, dann im Plugin `build --refresh-dependencies`.

23. **PROGRESS.md bleibt das laufende Architektur-/Status-Journal** im bestehenden Stil (was steht /
    bewusste Grenzen / technische Notizen / Tests grün). Wird nach jedem Feature nachgezogen.
