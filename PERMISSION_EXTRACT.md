# Permission-/Rank-System: Bestandsaufnahme

Hinweis zum Scope: In dieser Codebasis wurde kein Bukkit/Spigot/Paper-Plugin gefunden (`plugin.yml`, `JavaPlugin`, `org.bukkit`, Vault/LuckPerms, NMS/Reflection: keine Treffer). Die Extraktion beschreibt daher das vorhandene Spring-Boot/JPA-Backend-Modell für Minecraft-Ränge/Permissions plus die vorhandenen Web-Backend-Rollen/Authorities.

## Relevante Klassen

| Pfad | Zweck |
| --- | --- |
| `src/main/java/net/luneshine/backend/model/minecraft/permission/Permission.java` | Stammdatensatz einer Minecraft-Permission mit Name, Serverbezug, Permission-String, Beschreibung und Audit-Feldern. |
| `src/main/java/net/luneshine/backend/model/minecraft/permission/PermissionHistory.java` | Audit-Tabelle für Änderungen an Name, Permission-String und Beschreibung einer Permission. |
| `src/main/java/net/luneshine/backend/model/minecraft/permission/PermissionAction.java` | Enum für Permission-History-Aktionen (`ADDED`, `REMOVED`). |
| `src/main/java/net/luneshine/backend/model/minecraft/permission/player/PlayerPermission.java` | Direkte Permission-Zuweisung an einen Spieler inklusive Aussteller, Ablaufzeit und Grund. |
| `src/main/java/net/luneshine/backend/model/minecraft/permission/player/PlayerPermissionKey.java` | Composite-Key für `player_permission` aus Spieler und Permission. |
| `src/main/java/net/luneshine/backend/model/minecraft/permission/player/PlayerPermissionHistory.java` | Audit-Zeile für hinzugefügte/entfernte Spieler-Permissions. |
| `src/main/java/net/luneshine/backend/model/minecraft/permission/player/PlayerPermissionHistoryKey.java` | Composite-Key für `player_permission_history` aus Spieler und Permission. |
| `src/main/java/net/luneshine/backend/model/minecraft/permission/rank/Rank.java` | Globaler Rang-Stammdatensatz mit Name, Aktiv-Flag, Team-Rang-Flag und serverbezogenen Daten. |
| `src/main/java/net/luneshine/backend/model/minecraft/permission/rank/RankData.java` | Server-/Darstellungsdaten eines Rangs: Display-Namen, Farben, Prefix/Suffix, Tablist-Felder, Icon, Default-Flag und Permissions. |
| `src/main/java/net/luneshine/backend/model/minecraft/permission/rank/RankServerData.java` | Verknüpfung eines Rangs mit einem Server und genau einem `RankData`-Datensatz inklusive serverbezogenem Aktiv-Flag. |
| `src/main/java/net/luneshine/backend/model/minecraft/permission/rank/RankServerDataKey.java` | Composite-Key für `rank_server_data` aus Rank, Server und RankData. |
| `src/main/java/net/luneshine/backend/model/minecraft/permission/rank/RankDataPermission.java` | Join-Entity zwischen `RankData` und `Permission` mit Audit-Feldern für Hinzufügung. |
| `src/main/java/net/luneshine/backend/model/minecraft/permission/rank/RankDataPermissionKey.java` | Composite-Key für `rank_data_permission` aus RankData und Permission. |
| `src/main/java/net/luneshine/backend/model/minecraft/Player.java` | Spieler-Stammdatensatz; enthält keine Rank-Beziehung, aber wird von `PlayerPermission` referenziert. |
| `src/main/java/net/luneshine/backend/model/minecraft/server/Server.java` | Server-Stammdatensatz; wird für serverbezogene Permissions und RankData referenziert. |
| `src/main/java/net/luneshine/backend/repository/jpa/PermissionRepository.java` | JPA-Repository für `Permission`. |
| `src/main/java/net/luneshine/backend/repository/jpa/PlayerPermissionRepository.java` | JPA-Repository für direkte Spieler-Permissions; enthält Query für Permissions eines Spielers auf einem Server. |
| `src/main/java/net/luneshine/backend/repository/jpa/RankRepository.java` | JPA-Repository für Ränge; enthält serverbezogene Rank-Suche und ein `@EntityGraph` zum Eager Fetching von RankData, Icons und Permissions. |
| `src/main/java/net/luneshine/backend/repository/jpa/RankDataRepository.java` | JPA-Repository für `RankData`. |
| `src/main/java/net/luneshine/backend/repository/jpa/RankServerDataRepository.java` | Repository zum Finden von `RankServerData` per Rank-ID und Server-ID. |
| `src/main/java/net/luneshine/backend/repository/jpa/RankDataPermissionRepository.java` | JPA-Repository für die Join-Entity zwischen RankData und Permission. |
| `src/main/java/net/luneshine/backend/service/RankService.java` | Service-Interface für Erstellen, Aktualisieren, Löschen und Finden von Rängen sowie Hinzufügen serverbezogener RankData. |
| `src/main/java/net/luneshine/backend/service/impl/RankServiceImpl.java` | Implementierung der Rank-Verwaltung inklusive Duplicate-Check, Default-Werten und Erstellung von RankData/RankServerData. |
| `src/main/java/net/luneshine/backend/controller/RankController.java` | REST-Endpunkte für Rank-CRUD und RankData je Server. |
| `src/main/java/net/luneshine/backend/dto/minecraft/RankDto.java` | API-DTO für Rang-Stammdaten und referenzierte RankServerData. |
| `src/main/java/net/luneshine/backend/dto/minecraft/RankDataDto.java` | API-DTO für serverbezogene RankData inklusive Display-, Legacy- und Icon-Feldern. |
| `src/main/java/net/luneshine/backend/converter/impl/RankConverter.java` | Konvertiert `Rank` zu `RankDto` und reduziert `RankServerData` auf IDs/Enabled-Status. |
| `src/main/java/net/luneshine/backend/converter/impl/RankServerDataConverter.java` | Konvertiert `RankServerData` zu `RankDataDto`. |
| `src/main/java/net/luneshine/backend/actions/rank/*` | Update-Action-Klassen für Rank-Änderungen; aktiv verdrahtet ist im Service nur die Namensänderung über `GeneralRankUpdateAction`. |
| `src/main/java/net/luneshine/backend/model/Role.java` | Web-Backend-Rolle mit flacher Liste von `RegisteredAuthority`-Werten. |
| `src/main/java/net/luneshine/backend/model/authority/RegisteredAuthority.java` | Feste Authority-Liste für Web-Backend-Funktionen (`user:*`, `rank:*`, `player:*`, `server:*` usw.). |
| `src/main/java/net/luneshine/backend/model/User.java` | Web-Backend-User mit mehreren Rollen; baut Spring-Security-Authorities aus Rollen oder aus JWT-Authorities. |
| `src/main/java/net/luneshine/backend/model/key/AuthenticationKey.java` | Technischer Auth-Key mit eigener Authority-Liste für RSA/Bearer-Zugriffe. |
| `src/main/java/net/luneshine/backend/configuration/SecurityConfiguration.java` | Spring-Security-Konfiguration: Cookie-/RSA-Filter, stateless Sessions, `anyRequest().authenticated()`, Method Security. |
| `src/main/java/net/luneshine/backend/authentication/filter/CookieAuthenticationFilter.java` | Liest das `user-token`-Cookie und setzt eine PreAuthenticatedAuthentication im SecurityContext. |
| `src/main/java/net/luneshine/backend/authentication/filter/RsaAuthenticationFilter.java` | Liest Bearer-Token, authentifiziert über den Provider und setzt den SecurityContext. |
| `src/main/java/net/luneshine/backend/authentication/provider/AuthenticationProviderImpl.java` | Delegiert PreAuthenticatedAuthentication an `AuthenticationService.authenticate(token, type)`. |
| `src/main/java/net/luneshine/backend/service/impl/AuthenticationServiceImpl.java` | Löst Redis-Token zu JWT/UserDetails auf und erzeugt Spring-Authentication mit Authorities. |
| `src/main/java/net/luneshine/backend/service/impl/JwtServiceImpl.java` | Serialisiert/deserialisiert User- bzw. AuthKey-Authorities in JWT-Claims. |
| `src/main/java/net/luneshine/backend/listener/SchemaSetupListener.java` | Führt beim Start `foreign_keys.sql` aus, um ausgewählte Foreign Keys mit `ON DELETE`-Verhalten zu setzen. |
| `src/main/resources/foreign_keys.sql` | Ergänzt/ersetzt Foreign Keys für Rank-, Permission- und History-Tabellen auf `user_entity`. |

## 1. Datenmodell

### Persistenzbasis

- Die Datenhaltung ist JPA/Hibernate-basiert, nicht YAML-basiert (`src/main/resources/application.yaml`).
- Hibernate steht im Runtime-Profil auf `spring.jpa.hibernate.ddl-auto: update`; Tests nutzen H2 mit `create-drop`.
- `foreign_keys.sql` wird beim Application-Start durch `SchemaSetupListener` ausgeführt und setzt mehrere User-FKs auf `ON DELETE SET NULL`.
- Tabellen ohne explizites `@Table` folgen erkennbar Spring/Hibernate-Naming (`RankData` -> `rank_data`, `RankServerData` -> `rank_server_data`).

### Minecraft-Rank-/Permission-Tabellen

#### `rank` (`Rank.java`)

| Spalte | Typ/Constraints aus Code | Bedeutung |
| --- | --- | --- |
| `id` | generated `Long`, Primary Key | Rank-ID |
| `name` | unique, nullable=false | eindeutiger Rangname |
| `enabled` | boolean | globaler Aktiv-Status |
| `team_rank` | boolean | Markierung als Team-/Staff-Rang |
| `created_at` | `Timestamp`, `@CreationTimestamp` | Erstellzeit |
| `created_by` | FK auf `user_entity(id)`, `ON DELETE SET NULL` laut `foreign_keys.sql` | erstellender Backend-User |

#### `rank_data` (`RankData.java`)

| Spalte | Typ/Constraints aus Code | Bedeutung |
| --- | --- | --- |
| `id` | generated `Long`, Primary Key | RankData-ID |
| `display_name` | unique, nullable=false | moderner Anzeigename |
| `legacy_display_name` | unique, nullable=false | Legacy-Anzeigename |
| `color` | `VARCHAR(30)` | moderne Farbe |
| `legacy_color` | `VARCHAR(6)` | Legacy-Farbe |
| `prefix` | Länge 100 | moderner Prefix |
| `legacy_prefix` | Länge 30 | Legacy-Prefix |
| `suffix` | Länge 100 | moderner Suffix |
| `legacy_suffix` | Länge 30 | Legacy-Suffix |
| `tab_list_color` | `VARCHAR(30)` | moderne Tablist-Farbe |
| `legacy_tab_list_color` | `VARCHAR(6)` | Legacy-Tablist-Farbe |
| `tab_list_icon` | `VARCHAR(50)` | modernes Tablist-Icon |
| `legacy_tab_list_icon` | `VARCHAR(4)` | Legacy-Tablist-Icon |
| `is_default` | boolean | Default-Rank-Markierung auf RankData-Ebene |
| `icon_id` | impliziter FK auf `MinecraftIcon` | Icon für Rank-Darstellung |

Beziehungen: `RankData` ist die inverse Seite einer `@OneToOne`-Beziehung zu `RankServerData` und hat `@OneToMany(mappedBy = "rankData")` zu `RankDataPermission`.

#### `rank_server_data` (`RankServerData.java`, `RankServerDataKey.java`)

Composite-ID aus:

| Spalte | Beziehung | Bedeutung |
| --- | --- | --- |
| `rank_id` | FK auf `rank(id)` | globaler Rank |
| `server_id` | FK auf `server(id)` | Server-Kontext |
| `rank_data_id` | FK auf `rank_data(id)` | serverbezogene Anzeige-/Permission-Daten |

Zusätzlich:

| Spalte | Typ | Bedeutung |
| --- | --- | --- |
| `enabled` | boolean | Aktiv-Status dieses Rangs auf diesem Server |

#### `permission` (`Permission.java`)

| Spalte | Typ/Constraints aus Code | Bedeutung |
| --- | --- | --- |
| `id` | generated `Long`, Primary Key | Permission-ID |
| `name` | unique, nullable=false | eindeutiger Anzeigename/Identifier |
| `server_id` | FK auf `server(id)`, nullable nicht verboten | Serverbezug; `null` wäre technisch möglich |
| `permission_string` | nullable=false | eigentliche Permission-Node als String |
| `description` | `TEXT` | Beschreibung |
| `creator_id` | FK auf `user_entity(id)`, `ON DELETE SET NULL` laut `foreign_keys.sql` | erstellender Backend-User |
| `created_at` | `Timestamp`, `@CreationTimestamp` | Erstellzeit |
| `last_updated` | `Timestamp`, `@UpdateTimestamp` | letzte Änderung |

#### `rank_data_permission` (`RankDataPermission.java`, `RankDataPermissionKey.java`)

Composite-ID aus:

| Spalte | Beziehung | Bedeutung |
| --- | --- | --- |
| `rank_data_id` | FK auf `rank_data(id)` | RankData |
| `permission_id` | FK auf `permission(id)` | Permission |

Zusätzlich:

| Spalte | Typ/Constraints aus Code | Bedeutung |
| --- | --- | --- |
| `added_at` | `Timestamp`, `@CreationTimestamp` | Hinzufügezeit |
| `added_by` | FK auf `user_entity(id)`, `ON DELETE SET NULL` laut `foreign_keys.sql` | hinzufügender Backend-User |

#### `player_permission` (`PlayerPermission.java`, `PlayerPermissionKey.java`)

Composite-ID aus:

| Spalte | Beziehung | Bedeutung |
| --- | --- | --- |
| `player_id` | FK auf `player(id)` | Spieler; `Player.id` ist eine UUID |
| `permission_id` | FK auf `permission(id)` | direkte Permission |

Zusätzlich:

| Spalte | Typ/Constraints aus Code | Bedeutung |
| --- | --- | --- |
| `issued_at` | `Timestamp`, `@CreationTimestamp` | Vergabezeit |
| `issuer_id` | FK auf `user_entity(id)`, `ON DELETE SET NULL` laut `foreign_keys.sql` | ausstellender Backend-User |
| `expiry_time` | nullable `Timestamp` | Ablaufzeit für direkte Spieler-Permission |
| `reason` | `TEXT` | Vergabegrund |

Auffälligkeit: `PlayerPermissionRepository` ist als `JpaRepository<PlayerPermission, Long>` typisiert, obwohl die Entity `@IdClass(PlayerPermissionKey.class)` verwendet; außerdem nimmt die Query `findPermissionsByPlayerIdAndServerId` einen `Long playerId`, während `Player.id` eine UUID ist.

#### `player_permission_history` (`PlayerPermissionHistory.java`, `PlayerPermissionHistoryKey.java`)

Composite-ID aus `player_id` und `permission_id`; zusätzliche Felder:

| Spalte | Typ/Constraints aus Code | Bedeutung |
| --- | --- | --- |
| `action` | `PermissionAction`; kein `@Enumerated` angegeben | `ADDED`/`REMOVED`, vermutlich Provider-Default statt expliziter String-Speicherung |
| `issued_at` | nullable=false, `@CreatedDate` | Zeitpunkt der History-Zeile |
| `issued_by` | FK auf `user_entity(id)`, `ON DELETE SET NULL` laut `foreign_keys.sql` | ausführender Backend-User |

Auffälligkeit: Da der Composite-Key nur aus Spieler und Permission besteht, sind mehrere History-Zeilen für dieselbe Spieler-/Permission-Kombination nicht eindeutig modelliert.

#### `permission_history` (`PermissionHistory.java`)

| Spalte | Typ/Constraints aus Code | Bedeutung |
| --- | --- | --- |
| `id` | generated `Long`, Primary Key | History-ID |
| `permission_id` | FK auf `permission(id)` | betroffene Permission |
| `name_before`, `name_after` | String | Namensänderung |
| `permission_string_before`, `permission_string_after` | String | Permission-String-Änderung |
| `description_before`, `description_after` | `TEXT` | Beschreibungsänderung |
| `editor_id` | FK auf `user_entity(id)`, `ON DELETE SET NULL` laut `foreign_keys.sql` | bearbeitender Backend-User |
| `edited_at` | `Timestamp`, `@CreationTimestamp` | Änderungszeit |

### Web-Backend-Rollen/Authorities

Diese Struktur ist getrennt vom Minecraft-`Permission`-Modell.

#### `role` (`Role.java`)

| Spalte | Typ/Constraints aus Code | Bedeutung |
| --- | --- | --- |
| `id` | generated `Long`, Primary Key | Rollen-ID |
| `name` | nullable=false, unique | Rollenname |
| `is_default` | nullable=false boolean | Default-Rollenmarkierung |

#### `role_granted_authorities` (`Role.java`, `RegisteredAuthority.java`)

| Spalte | Typ/Constraints aus Code | Bedeutung |
| --- | --- | --- |
| `role_id` | FK auf `role(id)` | Rolle |
| `authority` | `RegisteredAuthority`, `EnumType.STRING` | exakte Authority wie `user:read`, `rank:create`, `player:permission:edit` |

#### `user_role` (`User.java`)

| Spalte | Beziehung | Bedeutung |
| --- | --- | --- |
| `user_id` | FK auf `user_entity(id)` | Backend-User |
| `role_id` | FK auf `role(id)` | Backend-Rolle |

#### `authentication_key` / `authentication_key_authorities` (`AuthenticationKey.java`)

`AuthenticationKey` speichert technische Keys mit `name`, `description`, `public_key` und optionalem `issuer`; `authentication_key_authorities` speichert je Key eine Liste von `RegisteredAuthority`-Werten als Strings, mit Unique Constraint auf `(authentication_key_id, authority)`.

## 2. Fähigkeiten als Anforderungs-Checkliste

| Fähigkeit | Befund im alten Code | Quellen |
| --- | --- | --- |
| Hierarchie mit Vererbung oder flache Rollen? | Flach: Weder `Rank`/`RankData` noch `Role` enthalten Parent-/Inheritance-Felder; Rank-Permissions hängen direkt an `RankData`, Web-Authorities direkt an Rollen/AuthKeys. | `Rank.java`, `RankData.java`, `RankDataPermission.java`, `Role.java`, `AuthenticationKey.java` |
| Ein Rang pro Spieler oder mehrere gleichzeitig? | Keine Spieler-Rang-Zuweisung gefunden; `Player` hat keine Rank-Beziehung und es gibt keine `player_rank`-Entity/Tabelle. Direkte Spieler-Permissions sind mehrere pro Spieler möglich, Web-User können mehrere Rollen haben. | `Player.java`, `PlayerPermission.java`, `User.java` |
| Wildcards (`feature.*`)? | Kein Wildcard-Matching gefunden; `permission_string` ist nur ein String-Feld, und Web-Authorities werden exakt per `hasAuthority('...')` geprüft. | `Permission.java`, `PlayerPermissionRepository.java`, Controller mit `@PreAuthorize` |
| Negationen (`-feature.x`)? | Keine Deny-/Negationsmodellierung gefunden; `PermissionAction.REMOVED` ist nur History-Aktion, keine negative Permission. | `PermissionAction.java`, `PlayerPermissionHistory.java`, `RankDataPermission.java` |
| Temporäre Ränge? | Keine temporären Ränge gefunden; nur direkte Spieler-Permissions haben `expiry_time`, und im Code wurde keine Enforcement-Query oder Ablaufbereinigung gefunden. | `PlayerPermission.java`, `PlayerPermissionRepository.java` |
| Per-Welt- oder globale Permissions? | Kein Weltbezug gefunden; Permissions und RankData sind serverbezogen. `Permission.server` ist nullable, aber die einzige direkte Player-Permission-Query filtert strikt auf `permission.server.id = :serverId` und zeigt keinen Global-Fallback. | `Permission.java`, `RankServerData.java`, `PlayerPermissionRepository.java` |
| Prefix/Suffix/Display-Kopplung an Ränge? | Ja: DisplayName, LegacyDisplayName, Color, LegacyColor, Prefix/Suffix, Tablist-Farbe/Icon und MinecraftIcon hängen an `RankData` und damit über `RankServerData` an Rank+Server. | `RankData.java`, `RankServerData.java`, `RankDataDto.java`, `RankServerDataConverter.java` |
| Laufzeitprüfung (`hasPermission`-Pfad)? | Kein Minecraft-`hasPermission`-Pfad gefunden. Für Web-Backend-Endpoints läuft Prüfung über Spring Security: Filter -> `AuthenticationProviderImpl` -> `AuthenticationServiceImpl` -> `JwtServiceImpl`/`getAuthorities()` -> `@PreAuthorize(hasAuthority(...))`. | `SecurityConfiguration.java`, `CookieAuthenticationFilter.java`, `RsaAuthenticationFilter.java`, `AuthenticationProviderImpl.java`, `AuthenticationServiceImpl.java`, `JwtServiceImpl.java`, `User.java`, `AuthenticationKey.java` |
| Rank-CRUD-Berechtigung? | `RegisteredAuthority` enthält `rank:create/read/edit/delete`, aber `RankController` selbst hat keine `@PreAuthorize`-Annotationen; dort greift nur die globale Authentifizierung aus `SecurityConfiguration`. | `RegisteredAuthority.java`, `RankController.java`, `SecurityConfiguration.java` |

## 3. Umsetzungsdetails, die erhaltenswert sind

- `RankRepository.findAllBy()` löst das N+1-Problem beim Laden von Rängen, RankServerData, RankData, Icons und Permissions über ein `@EntityGraph` in einer Query.
- `Rank` und `RankData` sind getrennt, sodass ein globaler Rangname pro Server unterschiedliche Anzeige-/Permission-Daten über `RankServerData` haben kann.
- `RankServerData.enabled` und `Rank.enabled` trennen serverbezogene Aktivierung von globaler Rank-Aktivierung im Datenmodell.
- `RankDataPermission` modelliert Rank-Permissions als Join-Entity mit `addedAt`/`addedBy`, sodass die Herkunft einzelner Rank-Permissions auditierbar ist.
- `PlayerPermission` trennt direkte Spieler-Permissions von Rank-Permissions und speichert dazu `issuer`, `issuedAt`, `expiryTime` und `reason`.
- `foreign_keys.sql` setzt bei Audit-Verweisen auf User `ON DELETE SET NULL`, sodass Rank-/Permission-/History-Daten beim Löschen eines Backend-Users erhalten bleiben.
- `UserServiceImpl.registerUser()` bootstrapped beim ersten Backend-User automatisch eine `Admin`-Rolle mit allen `RegisteredAuthority`-Werten.
- `RankServiceImpl.createRank()` verhindert doppelte Rangnamen case-insensitive über `RankRepository.existsByNameIgnoreCase(...)`.
- `JwtServiceImpl` serialisiert Authorities in JWT-Claims; `User.getAuthorities()` und `AuthenticationKey.getAuthorities()` können dadurch ohne erneutes Rollenladen Authorities aus dem Token rekonstruieren.
- RSA/AuthKey-Zugriffe werden über Redis-Token indiziert; `AuthenticationServiceImpl` legt `rsa:<token>` mit 2h TTL ab und zählt Key-Nutzungen pro IPv4-Adresse.

## 4. Altlasten / Was weg kann

- In dieser Codebasis wurden keine Bukkit-/Spigot-/Paper-Anteile, keine NMS-/Reflection-Zugriffe und kein lokaler `Player#hasPermission`-Hook gefunden; falls diese im alten Plugin existieren, liegen sie nicht in diesem Repository.
- Legacy-Darstellung ist direkt im Rank-Modell gespeichert (`legacy_display_name`, `legacy_color`, `legacy_prefix`, `legacy_suffix`, `legacy_tab_list_*`); Tests verwenden klassische `&`-Colorcodes wie `&b`/`&c`.
- Präsentation und Berechtigung sind stark gekoppelt: `RankData` hält sowohl Prefix/Suffix/Tablist/Icon als auch die zugehörigen Permissions.
- Die Anwendung verwaltet Schema direkt über Hibernate `ddl-auto: update` plus Startzeit-SQL (`SchemaSetupListener`), statt ein separates, versioniertes Schema als Quelle der Wahrheit zu verwenden.
- Minecraft-Permissions (`Permission.permissionString`) und Web-Backend-Authorities (`RegisteredAuthority`) sind zwei getrennte Berechtigungssysteme ohne sichtbare Brücke.
- `RankController` nutzt trotz vorhandener `rank:*`-Authorities keine `@PreAuthorize`-Checks und ist nur allgemein authentifizierungspflichtig.
- `UserConverter` prüft lokal auf die Authority `user:`, während die registrierten Authorities `user:read`, `user:edit`, `user:delete` heißen; dieser Check wirkt dadurch nicht autoritativ.
- `RsaAuthenticationFilter` schluckt Authentifizierungsfehler breit und setzt bei Fehlern einfach keine Authentication.
- `PlayerPermissionRepository` ist mit falschem ID-Typ (`Long`) typisiert und seine Player-Query nimmt `Long playerId`, obwohl `Player.id` eine UUID ist.
- `PlayerPermissionHistory` kann wegen Composite-Key nur aus Spieler+Permission keine mehrfachen Events derselben Kombination eindeutig speichern.
- `PermissionAction` in `PlayerPermissionHistory` ist nicht explizit mit `@Enumerated(EnumType.STRING)` annotiert; die Speicherung ist dadurch provider-default-abhängig.
- `RankEnabledUpdateAction` und `RankServerDataEnabledUpdateAction` existieren, sind aber in `GeneralRankUpdateAction` nicht verdrahtet; `RankServiceImpl.updateRank()` aktualisiert sichtbar nur den Namen.
- `PermissionHistory` und `PlayerPermissionHistory` sind modelliert, aber im untersuchten Code wurde kein Service-/Repository-Schreibpfad gefunden, der diese History-Einträge aktiv erzeugt.

## Offene Fragen

- Liegt das eigentliche alte Minecraft-Plugin mit Bukkit-`hasPermission`-Resolver, `plugin.yml` und möglicher YAML-Konfiguration in einem anderen Repository?
- Wie werden Spieler Rängen zugewiesen? In dieser Codebasis gibt es keine Player-Rank-Relation.
- Soll `Permission.server == null` globale Permissions bedeuten? Die gefundene Player-Permission-Query berücksichtigt nur konkrete `server_id`.
- Werden Wildcards oder Negationen als Konvention in `permission_string` gespeichert, obwohl kein Resolver im Code liegt?
- Wer erzwingt `PlayerPermission.expiryTime` zur Laufzeit? Im Backend wurde keine Ablaufprüfung gefunden.
- Welche Quelle ist für Minecraft zur Laufzeit autoritativ: die Rank-/Permission-Tabellen dieses Backends oder ein separates Plugin-/Proxy-System?

## Zielmodell für den Neubau (Entscheidungen)

1. **Server-Bezug entfällt:** Die alte Dreiteilung aus `rank`, `rank_data` und `rank_server_data` wird zu einer einzigen flachen Rollen-Tabelle zusammengeführt: Name, Darstellungsdaten und Rollen-Permissions hängen direkt an der Rolle. Begründung: Das neue System ist Single-Node und braucht keine serverbezogenen RankData-Varianten; die Bestandsaufnahme zeigt, dass der alte Serverbezug ausschließlich über `RankServerData` und `Permission.server` modelliert war.

2. **Eine einheitliche Permission-Welt:** Es gibt keine getrennte Web-Authority-Enum wie `RegisteredAuthority`; Web-Admin-Aktionen werden normale Permissions am selben autoritativen `PermissionResolver`-Port, z. B. `permission.role.edit`. Ziel ist, dass ein späterer Webinterface-Login auf dieselbe Spieler-Identität aufgelöst wird und dadurch automatisch dieselben Rechte nutzt; die Account-Verknüpfung selbst ist ein späteres separates Feature und nicht Teil dieses Permission-Systems. Begründung: Die Bestandsaufnahme zeigt zwei getrennte Welten (`Permission.permissionString` für Minecraft vs. `RegisteredAuthority` für Web), die im Neubau bewusst zusammengeführt werden.

3. **Mehrere Ränge pro Spieler, zeitlich unabhängig:** Ein Spieler kann mehrere aktive Rang-Mitgliedschaften gleichzeitig haben, jede mit eigenem optionalem `expires_at`. Wenn ein Premium-Spieler zusätzlich 3 Tage Epic erhält, verschwindet nach Ablauf nur die Epic-Mitgliedschaft und Premium bleibt; wenn ein Spieler Supporter wird, behält er parallel sein Epic. Es gibt keine Downgrade- oder Zurücksetz-Logik: Ablauf bedeutet ausschließlich das Verschwinden genau einer Mitgliedschaftszeile, sonst nichts.

4. **Default-Rang:** Es gibt genau einen Default-Rang, den das Backend automatisch anlegt; jeder Spieler hat ihn implizit. Das greift die alte Idee des `is_default`-Flags aus `RankData` auf, aber die konkrete Modellierung als echte Mitgliedschaftszeile oder als Fallback ohne Zeile bleibt in der Spec noch zu klären.

5. **Permissions rein additiv (Union), keine Negationen:** Effektive Permissions sind die Vereinigungsmenge aller aktiven Rollen-Permissions plus aller aktiven direkten Spieler-Permission-Grants. Wildcards wie `feature.*` und `*` sind erlaubt, Deny-/Negationsregeln wie `-feature.x` bewusst nicht. Begründung: Eine reine Union ist vorhersehbar, konfliktfrei und ohne Prioritätsregeln testbar; im alten Modell wurde keine Negationslogik gefunden.

6. **Rollen-Gewicht nur für Darstellung:** Da Permissions additiv gemerged werden, beeinflussen `weight`/`priority` und Team-/Staff-Markierungen nur die Anzeige, nicht die Permission-Auflösung. Bei mehreren aktiven Rängen liefert ein aktiver Team-/Staff-Rang die Darstellung; sonst gewinnt die Rolle mit dem höchsten `weight`/`priority`-Wert. Das übernimmt die alte Trennung zwischen Berechtigungen und Darstellungsfeldern aus `RankData` sowie die alte `team_rank`-Markierung, beschränkt deren Wirkung aber ausdrücklich auf Prefix/Farbe/Tablist im Chat.

7. **Live-Ablauf mit Push:** Läuft eine Rang-Mitgliedschaft oder ein Permission-Grant ab, während der Spieler online ist, muss der Entzug sofort wirksam werden. Das Plugin erhält dafür ein Live-Update über Redis Pub/Sub; ein bloßes implizites `isActive(now)` wie bei Punishments reicht nicht aus, weil zusätzlich ein Mechanismus ablaufende Einträge erkennen und ein Änderungs-Event auslösen muss. Die konkrete Umsetzung, z. B. Scheduler oder zeitgenaues Scheduling, bleibt der Spec überlassen; die Anforderung "Ablauf wirkt live" steht fest.

8. **"Grant" als gemeinsames Konzept:** Spielerbezogene Zuweisungen folgen einem gemeinsamen Muster: Rang-Grant (`Spieler -> Rolle`) und Permission-Grant (`Spieler -> einzelne Permission`). Beide tragen dieselben Audit-/Lebenszyklus-Felder: `issued_by`, `issued_at`, `expiry_time` (`nullable` = permanent) und `reason`. Strikt getrennt davon ist die Rollen-Permission-Konfiguration, also welche Permissions zu einer Rolle gehören; sie ist Stammdaten-Pflege der Rolle, kein zeitlich begrenzter Grant, und hat eigene Audit-Felder wie `added_by`/`added_at` ohne Ablauf.

9. **Erhaltenswerte Ideen aus dem alten System:** Übernommen werden die Trennung von Permission-Identität als Stammdatum (`name`, `permission_string`, Beschreibung) und Permission-Zuweisung als Join mit Audit, die Audit-Felder pro Grant (`issuer`, `issued_at`, `expiry_time`, `reason`) sowie eine Änderungs-Historie als eigene Tabelle analog `config`/`config_audit`. Diese Punkte entsprechen den beobachteten Mustern in `Permission`, `RankDataPermission`, `PlayerPermission` und den History-Entities.

10. **Bewusst verworfen:** Nicht übernommen werden Hibernate `ddl-auto` plus Start-SQL; stattdessen ist Flyway das versionierte Schema. Ebenfalls entfallen Legacy-§/&-Color-Felder, die doppelte Permission-Welt aus Web und Ingame sowie nicht durchgesetzte Controller-Permissions. Die Prüfung gehört in den Service bzw. den autoritativen Resolver und nicht nur in Controller-Annotationen; die Bestandsaufnahme zeigt als Gegenbeispiel, dass im alten `RankController` trotz vorhandener `rank:*`-Authorities keine `@PreAuthorize`-Checks gesetzt waren.

11. **Bestehenden Port erweitern, nicht neu bauen:** Dieses Feature erweitert die bestehende `PermissionResolver`-Implementierung (`JooqPermissionResolver`) hinter dem bereits existierenden `PermissionResolver`-Port im neuen Backend, also demselben Port, an dem die Features Punishments und Reports bereits hängen. Die Port-Signatur für die Prüfung "hat dieser Spieler diese Permission" bleibt unverändert; geändert wird ausschließlich die Implementierung dahinter: Union über mehrere aktive Ränge, Wildcard-Matching und aktive Grants mit Ablauf. Damit ist dieses Foundation-Feature für die bestehenden Konsumenten transparent. Die in der Bestandsaufnahme oben als fehlend notierte Spieler-Rang-Zuweisung wird hier erstmals als Rang-Grant eingeführt.

### Noch zu klären

- Default-Rang als echte Mitgliedschaftszeile pro Spieler oder als Fallback ohne Zeile?
- Anzeige-Auswahl, wenn mehrere aktive Team-Ränge gleichzeitig vorliegen oder zwei Rollen dasselbe `weight` haben: Tie-Break nach ID, neuestem Grant, alphabetisch?
- Konkreter Live-Ablauf-Mechanismus: Scheduler-Intervall oder zeitgenaues Scheduling pro Ablaufzeitpunkt?
- Audit-Tiefe bei Rang-Zuweisungen final bestätigen: wer/wann/warum plus `reason` für jeden Rang-Grant?
