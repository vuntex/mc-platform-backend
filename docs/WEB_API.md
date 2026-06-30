# Web-API-Referenz (Quelle der Wahrheit fürs Frontend)

> Generiert aus dem **echten Code** (Controller, DTO-Records, `PermissionEndpoints`, `SecurityConfig`,
> ExceptionHandler) am 2026-06-25. Bei Konflikt gewinnt der Code. Ausgelesene Quellen siehe Abschnitt
> „Ausgelesene Quellen" am Ende.
>
> **Geltungsbereich:** alle vom Webinterface genutzten Endpoints. Plugin-/intern-only-Endpoints sind als
> `🔌 PLUGIN-ONLY` markiert. JSON-Feldnamen entsprechen den Record-Komponenten (Jackson, unverändert).

---

## 0. Auth-Mechanik (für den Frontend-Client)

- **Zwei Pfad-Klassen** (`SecurityConfig`):
  - `/api/web/**` → **JWT-pflichtig** (`authenticated()`). Fehlt/ungültig der Token → **401** (leerer
    Body via `response.sendError`, **nicht** das `{error,message}`-Format).
  - **Alles andere** (`/api/web-auth/**`, `/api/players/**`, interne Plugin-Endpoints) → `permitAll`
    (keine JWT-Prüfung in der Security-Chain). Schreibrechte werden dort separat über den
    `PermissionResolver` geprüft, nicht über die Chain.
- **Access-JWT:** im Header `Authorization: Bearer <accessToken>`. Stateless, HS256, trägt nur die
  Identität (Subject = Spieler-UUID). Rechte kommen zur Laufzeit aus dem `PermissionResolver`
  (Constitution §12) — der JWT enthält **keine** Rollen/Permissions.
- **Refresh-Token:** reist **ausschließlich** in einem Cookie, nie im Body.
  - Cookie-Name `mcweb_refresh` (konfigurierbar `mcplatform.webauth.refresh-cookie.name`).
  - `HttpOnly; Secure; SameSite=Strict; Path=/api/web-auth/session` (Pfad konfigurierbar
    `…refresh-cookie.path`, Default `/api/web-auth/session`).
  - Default-TTL 30 Tage (`mcplatform.webauth.refresh-ttl-days`).
- **CSRF-Guard auf den Cookie-Endpoints:** `refresh` und `logout` verlangen den Header **`X-Refresh`**.
  `requireCsrfHeader` prüft **nur Präsenz** (nicht-leerer Wert) — der **Wert wird nicht verglichen**.
  Fehlt/leer → **403** (Spring-Default-Error-Body, **nicht** `{error,message}`).
- **CORS:** genau ein erlaubter Origin (Default `http://localhost:3000`,
  `mcplatform.webauth.cors.allowed-origin`), Methoden `GET/POST/PUT/DELETE/OPTIONS`, erlaubte Header
  `Authorization, Content-Type, X-Refresh`, `allowCredentials=true` (für das httpOnly-Cookie).
- **actor/issued_by:** Bei allen schreibenden `/api/web/**`-Endpoints kommt der handelnde Admin
  **immer aus dem JWT-Principal**, **nie aus dem Body**. Die Web-Write-DTOs haben bewusst kein
  `actor`-Feld.

⚠ **Frontend:** `login` liefert den Refresh-Cookie via `Set-Cookie` — der Browser speichert ihn
automatisch (httpOnly, für JS unsichtbar). Für `refresh`/`logout` muss der Client den Header `X-Refresh`
mitsenden (beliebiger nicht-leerer Wert genügt) **und** `credentials: 'include'` setzen, damit das
Cookie mitgeht.

---

## 1. Auth / Session

### POST `/api/web-auth/login`
- **Auth:** öffentlich (permitAll).
- **Request:** `LoginRequest` `{ username, password }`.
- **Response 200:** `TokenPairResponse` + `Set-Cookie: mcweb_refresh=…`.
- **Fehler:** 401 `web_auth_invalid_credentials` (uniform bei jedem Credential-Fehler — keine
  User-Enumeration).

### POST `/api/web-auth/session/refresh`
- **Auth:** Refresh-Cookie + Header `X-Refresh` (Präsenz).
- **Request:** kein Body. Cookie `mcweb_refresh` wird gelesen.
- **Response 200:** `TokenPairResponse` + **neuer** `Set-Cookie` (Rotation).
- **Fehler:** 403 (fehlender `X-Refresh`, Spring-Default-Body) · 401 `web_auth_refresh_invalid`
  (fehlend/ungültig) · 401 `web_auth_session_revoked` (Reuse erkannt → ganze Kette invalidiert).

### POST `/api/web-auth/session/logout`
- **Auth:** Header `X-Refresh` (Präsenz); Cookie optional (idempotent).
- **Request:** kein Body.
- **Response 204:** leer, setzt `mcweb_refresh` mit `Max-Age=0` (Cookie gelöscht).
- **Fehler:** 403 (fehlender `X-Refresh`).

### POST `/api/web-auth/redeem`
- **Auth:** öffentlich (permitAll) — die Autorisierung steckt im einmaligen Token selbst.
- **Request:** `RedeemRequest` `{ token, password }` (Passwort neu setzen, klartext über TLS).
- **Response 204:** leer.
- **Fehler:** 410 `web_auth_token_invalid` (Token unbekannt/abgelaufen/verbraucht — uniform) ·
  422 `password_invalid` (Passwort-Policy) · 409 `web_account_conflict` (Race).

### POST `/api/players/{uuid}/web-auth/link-token` 🔌 PLUGIN-ONLY
- In-game aufgerufen (Session beweist Identität). **Request:** kein Body. **Response 200:**
  `TokenResponse` (`purpose="LINK"`). Fehler: 409 `web_account_exists`, 429 `web_auth_cooldown`.

### POST `/api/players/{uuid}/web-auth/reset-token` 🔌 PLUGIN-ONLY
- In-game. **Response 200:** `TokenResponse` (`purpose="RESET"`). Fehler: 409 `web_account_missing`,
  429 `web_auth_cooldown`.

---

## 2. Identität — `GET /api/web/me`
- **Auth:** JWT-only (kein Permission-Gate). Identität = UUID aus dem Token.
- **Request:** keiner.
- **Response 200:** `MeResponse` `{ uuid, name, permissions[], primaryRole }`.
  - `permissions` ist die **flache** effektive Permission-Menge des Aufrufers (inkl. Wildcards
    `*`/`feature.*`) — nur für UI-Button-Gating; der echte Check bleibt backend-autoritativ (403).
  - **Keine** Herkunft/`sources` hier (das gibt es nur auf der Player-Effective-Sicht, siehe §6).
- ⚠ **Frontend:** `name` kann `null` sein (Spieler hat noch keine `player`-Zeile). `primaryRole` ist
  nie null — bei keinem aktiven Rang die Default-Rolle.

---

## 3. Spieler-Suche — `GET /api/web/players/search`
- **Auth:** JWT + Gate **`permission.read`** (sonst 403 `permission_denied`).
- **Query-Parameter:**
  - `name` (String, **required** vom Sinn her; leer/whitespace → leeres Array statt Fehler).
  - `limit` (int, optional, Default **20**, hart gedeckelt auf **50**; <1 wird auf 1 angehoben).
- **Response 200:** `PlayerSummary[]` (`{ uuid, name }`), Präfix-Match case-insensitive. Nur
  Anzeige-Daten, nie Rollen/Permissions.

---

## 4. Rollen — `/api/web/permission/roles`

Gemeinsam: JWT-pflichtig. Lesen → `permission.read`; Schreiben → granulare `permission.*`-Gates
(unten je Endpoint). Bei fehlendem Gate **403 `permission_denied`**.

### GET `/api/web/permission/roles`
- **Gate:** `permission.read`. **Response 200:** `RoleResponse[]`.

### GET `/api/web/permission/roles/{id}`
- **Gate:** `permission.read`. **Response 200:** `RoleResponse`. **404** `role_not_found`.

### POST `/api/web/permission/roles`
- **Gate:** `permission.role.create`. **Request:** `RoleWriteRequest`. **Response 200:** `RoleResponse`
  (frisch angelegt, DB-vergebene `id`). **409** `role_name_conflict` (Name case-insensitive belegt).
- Hinweis: `isDefault` wird nie über die API gesetzt (immer `false` bei Create).

### PUT `/api/web/permission/roles/{id}`
- **Gate:** `permission.role.edit`. **Request:** `RoleWriteRequest`. **Response 200:** `RoleResponse`.
- **Fehler:** 404 `role_not_found` · 409 `role_name_conflict` · 409 `default_role_protected`
  (Default-Rolle darf nicht deaktiviert werden) · 422 `permission_invalid` (Validierung,
  z.B. `displayIcon`).
- Hinweis: `isDefault` ist immutable (Server bewahrt den gespeicherten Wert).

### DELETE `/api/web/permission/roles/{id}`
- **Gate:** `permission.role.delete`. **Response 204** (leer).
- **Fehler:** 409 `default_role_protected` (Default) · **409 `role_inherited`** (Rolle wird von anderen
  Rollen geerbt → dort erst die Vererbung entfernen, siehe §5) · (404, falls Rolle fehlt).
- Verhalten: kaskadiert je aktivem Halter einen REVOKE (+ Audit + Live-Push), dann Löschung.

---

## 5. Rollen-Permissions — `/api/web/permission/roles/{id}/permissions`

### GET `…/permissions`
- **Gate:** `permission.read`. **Response 200:** `RoleResponse` (das `permissions`-Feld = die **eigenen**
  konfigurierten Permission-Strings der Rolle, **nicht** transitiv aufgelöst).

### POST `…/permissions`
- **Gate:** `permission.role.edit`. **Request:** `RolePermissionWriteRequest` `{ permission }`.
- **Response 200:** `RoleResponse` (aktualisiert). **422 `permission_invalid`** bei ungültigem
  Permission-String. Idempotent (doppeltes Hinzufügen = no-op).

### DELETE `…/permissions`  ⚠ DELETE-mit-Body
- **Gate:** `permission.role.edit`. **Request-Body:** `RolePermissionWriteRequest` `{ permission }`.
- **Response 200:** `RoleResponse` (aktualisiert).
- ⚠ **Frontend:** Das ist ein **DELETE mit JSON-Body** (kein Pfad-/Query-Param). Der HTTP-Client muss
  beim DELETE einen Body senden (manche `fetch`-Wrapper unterdrücken das).

---

## 6. Rollen-Vererbung — `/api/web/permission/roles/{id}/inheritance`

> „Rolle `{id}` erbt die **Permissions** von `parentRoleId`." Transitiv, reine Union, keine Gewichtung.
> Es werden **nur Permissions** vererbt — keine Darstellungs-/Meta-Felder.

### GET `/api/web/permission/roles/{id}/inheritance`
- **Gate:** `permission.read`.
- **Response 200:** `long[]` — die **direkten** Eltern-Rollen-IDs von `{id}` (nicht die transitiven).
  JSON: Array von Zahlen, z.B. `[2, 5]`.
- **404** `role_not_found`, falls `{id}` nicht existiert.

### POST `/api/web/permission/roles/{id}/inheritance`
- **Gate:** **`permission.role.edit.inherit`** (eigenes granulares Gate, getrennt von `role.edit`).
- **Request:** `InheritanceWriteRequest` `{ parentRoleId }`.
- **Response 200:** `RoleResponse` von `{id}` (mit aktualisiertem `inheritedRoleIds`).
- **Fehler:**
  - **409 `role_inheritance_cycle`** — `parentRoleId == id` (Selbstbezug) **oder** `parentRoleId` erbt
    bereits transitiv `{id}` (Kante würde Zyklus schließen). Graph bleibt unverändert.
  - **409 `default_role_protected`** — `{id}` ist die Default-Rolle (Default ist ein **Blatt**, kann
    nicht erben). Andere Rollen dürfen **von** Default erben.
  - 404 `role_not_found` — `{id}` oder `parentRoleId` existiert nicht.
- Idempotent: existierende Kante erneut setzen → 200, kein Duplikat.

### DELETE `/api/web/permission/roles/{id}/inheritance/{parentId}`
- **Gate:** `permission.role.edit.inherit`.
- **Pfad:** `parentId` ist die zu entfernende Eltern-Rolle (Pfad-Variable, **kein** Body).
- **Response 200:** `RoleResponse` von `{id}` (aktualisiert).
- Idempotent: nicht existierende Kante → 200, no-op.

### Wie sich Vererbung in bestehenden Responses zeigt (WICHTIG fürs Frontend)
- **`RoleResponse.inheritedRoleIds`** (`List<Long>`): die **direkten** Eltern-Rollen-IDs der Rolle.
  `RoleResponse.permissions` bleibt die **eigene** (nicht transitiv aufgelöste) Permission-Liste.
  → Eine *role-level* transitiv-aufgelöste Permission-Menge **mit Herkunft** liefert das Backend
  **nicht** als eigenes Feld; das Frontend rekonstruiert den Rollen-Graphen über `inheritedRoleIds`.
- **`PlayerPermissionsResponse.effectivePermissions`** (`List<String>`): die **flache, transitiv
  aufgelöste Union** der effektiven Permissions des Spielers (eigene Rollen + deren Vererbung +
  Direkt-Grants + ggf. Default-Fallback). Dies ist die autoritative Allow/Deny-Menge.
- **`PlayerPermissionsResponse.sources`** (`List<EffectivePermissionEntry>`): **pro Permission die
  Herkunft** — `own` (direkt am Spieler gegrantet) und `inheritedFromRoleIds` (vollständige Menge aller
  beitragenden Rollen-IDs; bei Diamant **alle** Quellen, nicht eine willkürliche). Das ist der Weg fürs
  „geerbt von Rolle X"-Debugging im UI.
- ⚠ **Frontend:** „geerbt vs. eigen" gibt es **pro Spieler** (`sources`), nicht pro Rolle. Für die
  Rollen-Ansicht stehen nur `permissions` (eigen) + `inheritedRoleIds` (Kanten) zur Verfügung.

### DEFAULT-Rolle erkennen
- Verlässlich über das Flag **`RoleResponse.isDefault == true`**. (Der Name `"DEFAULT"` ist geseedet,
  aber das Flag ist der autoritative, umbenennungs-sichere Weg — die Default-Rolle darf umbenannt
  werden.) Es gibt genau **eine** Default-Rolle (DB-Unique-Constraint).
- ⚠ **Frontend (Default-Warnung):** Beim Anlegen/Bearbeiten einer Rolle warnt das **Backend nicht**,
  wenn die Rolle Default **nicht** erbt (bewusste Entscheidung). Will das UI warnen „Spieler mit nur
  dieser Rolle haben keine Basis-Permissions", muss es selbst prüfen, ob die Default-Rolle-ID in
  `inheritedRoleIds` steht.

---

## 7. Spieler-Grants — `/api/web/permission/players/{uuid}/…`

Gemeinsam: JWT-pflichtig; `issued_by`/Akteur immer aus dem JWT. Alle Grant-Antworten sind die
**aktualisierte effektive Sicht** `PlayerPermissionsResponse`.

### POST `/api/web/permission/players/{uuid}/roles`
- **Gate:** `permission.grant.role`. **Request:** `GrantRoleWriteRequest` `{ roleId, expiresInSeconds?, reason? }`.
- **Response 200:** `PlayerPermissionsResponse`.
- **Fehler:** 409 `default_role_protected` (Default kann nicht gegrantet werden) · 404 `role_not_found`
  · 422 (Ablauf in der Vergangenheit, `permission_invalid`/`bad_request`).
- ⚠ **Frontend: Dauer rein, Zeitpunkt raus.** Das **Request**-DTO nimmt `expiresInSeconds` (relative
  **Dauer** ab jetzt; `null` = permanent). Die **Response** (`ActiveGrant.expiresAtEpochMilli`) gibt
  einen absoluten **Zeitpunkt** zurück. Nicht verwechseln.

### DELETE `/api/web/permission/players/{uuid}/roles/{roleId}`  ⚠ reason als Query
- **Gate:** `permission.grant.role`.
- **Query:** `reason` (String, **optional**, `required=false`).
- **Response 200:** `PlayerPermissionsResponse`. Idempotent (Revoke eines nicht aktiven Grants → ok).
- **Fehler:** 409 `default_role_protected` (Default ist kein echter Grant).
- ⚠ **Frontend:** `reason` wandert hier als **Query-Parameter** (`?reason=…`), nicht im Body.

### POST `/api/web/permission/players/{uuid}/permissions`
- **Gate:** `permission.grant.permission`. **Request:** `GrantPermissionWriteRequest`
  `{ permission, expiresInSeconds?, reason? }`. **Response 200:** `PlayerPermissionsResponse`.
- **Fehler:** 422 `permission_invalid` (Syntax) · 422 (Ablauf in der Vergangenheit).
- ⚠ **Frontend:** ebenfalls `expiresInSeconds` (Dauer rein).

### DELETE `/api/web/permission/players/{uuid}/permissions`  ⚠ DELETE-mit-Body
- **Gate:** `permission.grant.permission`. **Request-Body:** `RevokePermissionWriteRequest`
  `{ permission, reason? }`. **Response 200:** `PlayerPermissionsResponse`. Idempotent.
- ⚠ **Frontend:** DELETE **mit JSON-Body**.

### GET `/api/web/permission/players/{uuid}/effective`
- **Gate:** `permission.read`. **Response 200:** `PlayerPermissionsResponse`.
- Besonderheit: Hält der Spieler **keinen** aktiven Rollen-Grant, synthetisiert das Backend in `roles`
  einen Anzeige-Eintrag der **DEFAULT-Rolle** (`ActiveGrant` mit `expiresAtEpochMilli=null`,
  `issuedBy=null`, `issuedByName=null`, `reason=null`) — kein echter DB-Grant, nur Anzeige.

---

## 8. Fehler-Format & Status-Codes

**Body-Form (von den @ExceptionHandler-Advices):** flaches JSON-Objekt
```json
{ "error": "<maschinen-code>", "message": "<menschlicher Text>" }
```
Es gibt **kein** numerisches `code`-Feld und kein `timestamp`/`path` in diesen Antworten.

**Ausnahmen von der Body-Form:**
- **401** (fehlender/ungültiger JWT auf `/api/web/**`): kommt aus dem Security-EntryPoint
  (`sendError`) → **Spring-Default-Body bzw. leer**, NICHT `{error,message}`.
- **403 wegen fehlendem `X-Refresh`** (refresh/logout): `ResponseStatusException` → **Spring-Default-
  Error-Body** (`{timestamp,status,error,message,path}`), NICHT `{error,message}`.
  (Das **gate-bedingte** 403 dagegen ist `{ "error":"permission_denied", … }`.)

**Web-relevante Codes (Auslöser → Status, `error`-Wert):**

| Status | `error` | Auslöser |
|---|---|---|
| 400 | `bad_request` | `IllegalArgumentException` (global) |
| 401 | — / leer | fehlender/ungültiger Access-JWT auf `/api/web/**` |
| 401 | `web_auth_invalid_credentials` | Login fehlgeschlagen (uniform) |
| 401 | `web_auth_refresh_invalid` | Refresh-Token fehlt/ungültig |
| 401 | `web_auth_session_revoked` | Refresh-Token-Reuse → Kette invalidiert |
| 403 | `permission_denied` | fehlendes Permission-Gate (read/`permission.*`) |
| 403 | (Spring-Default) | fehlender `X-Refresh`-Header bei refresh/logout |
| 404 | `role_not_found` | Rolle (oder Parent) existiert nicht |
| 409 | `role_name_conflict` | Rollenname (case-insensitive) belegt |
| 409 | `default_role_protected` | Default löschen/deaktivieren/granten/erben-lassen |
| 409 | `role_inheritance_cycle` | Vererbungs-Kante würde Zyklus erzeugen |
| 409 | `role_inherited` | Rolle löschen, die von anderen geerbt wird |
| 409 | `web_account_exists` / `web_account_missing` / `web_account_conflict` | Web-Auth-Konto-Zustände |
| 410 | `web_auth_token_invalid` | Link/Reset-Token ungültig/abgelaufen/verbraucht |
| 422 | `permission_invalid` | ungültiger Permission-String / Role-Validierung / Ablauf in der Vergangenheit |
| 422 | `password_invalid` | Passwort-Policy bei `redeem` |
| 429 | `web_auth_cooldown` | Token-Anforderung im Cooldown (🔌 plugin-Pfad) |

---

## 9. DTO-Glossar (alphabetisch, voll ausgeschrieben)

Typ-Notation: `Long`/`UUID` = nullable Wrapper/Objekt; `long`/`int`/`boolean` = primitiv (nie null).

### `ActiveGrant` (`protocol.permission`)
| Feld | Typ | Bedeutung |
|---|---|---|
| `label` | String | Rollenname (Rollen-Grant) **oder** Permission-String (Direkt-Grant) |
| `expiresAtEpochMilli` | Long | Ablauf-**Zeitpunkt** (epoch ms); `null` = permanent |
| `issuedBy` | UUID | UUID des grantenden Admins; `null` beim synthetischen Default-Eintrag |
| `issuedByName` | String | gecachter Name von `issuedBy`; `null` wenn nicht auflösbar / System / kein Spieler-Row |
| `reason` | String | optionaler Grund; kann `null` sein |

### `EffectivePermissionEntry` (`protocol.permission`)
| Feld | Typ | Bedeutung |
|---|---|---|
| `permission` | String | die Permission (kann Wildcard sein) |
| `own` | boolean | `true` = direkt am Spieler gegrantet (kein Rollen-Ursprung) |
| `inheritedFromRoleIds` | List\<Long\> | **vollständige** Menge der Rollen-IDs, die diese Permission liefern (leer wenn rein `own`) |

### `GrantPermissionWriteRequest` (`protocol.permission.web`) — Request
| Feld | Typ | Bedeutung |
|---|---|---|
| `permission` | String | die zu grantende Permission |
| `expiresInSeconds` | Long | **Dauer** ab jetzt in Sekunden; `null` = permanent |
| `reason` | String | optional |

### `GrantRoleWriteRequest` (`protocol.permission.web`) — Request
| Feld | Typ | Bedeutung |
|---|---|---|
| `roleId` | long | zu grantende Rolle |
| `expiresInSeconds` | Long | **Dauer** ab jetzt; `null` = permanent |
| `reason` | String | optional |

### `InheritanceWriteRequest` (`protocol.permission.web`) — Request
| Feld | Typ | Bedeutung |
|---|---|---|
| `parentRoleId` | long | die Rolle, deren Permissions geerbt werden sollen |

### `LoginRequest` (`protocol.webauth`) — Request
| Feld | Typ | Bedeutung |
|---|---|---|
| `username` | String | aktueller Minecraft-Name |
| `password` | String | Klartext-Passwort (nur über TLS, nie persistiert) |

### `MeResponse` (`protocol.webauth`)
| Feld | Typ | Bedeutung |
|---|---|---|
| `uuid` | UUID | Identität aus dem Token-Subject |
| `name` | String | gecachter MC-Name; `null` wenn keine Spieler-Zeile existiert |
| `permissions` | List\<String\> | **flache** effektive Menge des Aufrufers (UI-Gating; kann Wildcards enthalten) |
| `primaryRole` | `PrimaryRole` | Anzeige-Rang (nie null; Default-Fallback) |

### `PlayerPermissionsResponse` (`protocol.permission`)
| Feld | Typ | Bedeutung |
|---|---|---|
| `player` | UUID | der betrachtete Spieler |
| `roles` | List\<`ActiveGrant`\> | aktive Rollen-Grants (bei 0 echten Grants: synthetischer Default-Eintrag) |
| `permissions` | List\<`ActiveGrant`\> | aktive Direkt-Permission-Grants |
| `effectivePermissions` | List\<String\> | **flache, transitiv aufgelöste** Union (autoritative Allow/Deny-Menge) |
| `sources` | List\<`EffectivePermissionEntry`\> | Herkunft je effektiver Permission (own / inheritedFromRoleIds) |
| `display` | `RoleDisplay` | gewählte Darstellung (Rang-Anzeige) |

### `PlayerSummary` (`protocol.player`)
| Feld | Typ | Bedeutung |
|---|---|---|
| `uuid` | UUID | Spieler-UUID |
| `name` | String | gecachter MC-Name |

### `PrimaryRole` (`protocol.webauth`)
| Feld | Typ | Bedeutung |
|---|---|---|
| `name` | String | technischer Rollenname |
| `displayName` | String | Anzeigename |
| `color` | String | Farbe (kann `null`) |
| `weight` | int | Gewicht (Anzeige-Priorität) |

### `RedeemRequest` (`protocol.webauth`) — Request
| Feld | Typ | Bedeutung |
|---|---|---|
| `token` | String | einmaliger Link/Reset-Token |
| `password` | String | neues Klartext-Passwort |

### `RevokePermissionWriteRequest` (`protocol.permission.web`) — Request (DELETE-Body)
| Feld | Typ | Bedeutung |
|---|---|---|
| `permission` | String | zu entziehende Direkt-Permission |
| `reason` | String | optional |

### `RoleDisplay` (`protocol.permission`)
| Feld | Typ | Bedeutung |
|---|---|---|
| `displayName` | String | Anzeigename des gewählten Rangs |
| `color` | String | Farbe (kann `null`) |
| `prefix` | String | Prefix (kann `null`) |
| `suffix` | String | Suffix (kann `null`) |
| `tabListColor` | String | Tab-Listen-Farbe (kann `null`) |
| `tabListIcon` | String | Tab-Listen-Icon-Ref (kann `null`) |
| `displayIcon` | String | opake Icon-Referenz `<typ>:<payload>` (kann `null`) |

### `RolePermissionWriteRequest` (`protocol.permission.web`) — Request (auch DELETE-Body)
| Feld | Typ | Bedeutung |
|---|---|---|
| `permission` | String | hinzuzufügende/zu entfernende Permission der Rolle |

### `RoleResponse` (`protocol.permission`)
| Feld | Typ | Bedeutung |
|---|---|---|
| `id` | long | Rollen-ID |
| `name` | String | technischer Name (unique, case-insensitive) |
| `displayName` | String | Anzeigename |
| `color` | String | Farbe (nullable) |
| `prefix` | String | Prefix (nullable) |
| `suffix` | String | Suffix (nullable) |
| `tabListColor` | String | Tab-Listen-Farbe (nullable) |
| `tabListIcon` | String | Tab-Listen-Icon (nullable) |
| `displayIcon` | String | opake Icon-Ref (nullable) |
| `weight` | int | Anzeige-Gewicht (beeinflusst nur Display, nie Auflösung) |
| `teamRank` | boolean | Team-Rang-Flag (Anzeige/Auswahl) |
| `active` | boolean | aktiv? (inaktive Rolle trägt nichts zur eigenen Auflösung/Anzeige bei) |
| `isDefault` | boolean | **die** Default-Rolle? (autoritativer Erkennungs-Weg) |
| `permissions` | List\<String\> | die **eigenen** konfigurierten Permissions (nicht transitiv aufgelöst) |
| `inheritedRoleIds` | List\<Long\> | die **direkten** Eltern-Rollen-IDs (geerbte Rollen) |

### `RoleWriteRequest` (`protocol.permission.web`) — Request
| Feld | Typ | Bedeutung |
|---|---|---|
| `name` | String | technischer Name |
| `displayName` | String | Anzeigename |
| `color` | String | Farbe (nullable) |
| `prefix` | String | Prefix (nullable) |
| `suffix` | String | Suffix (nullable) |
| `tabListColor` | String | Tab-Listen-Farbe (nullable) |
| `tabListIcon` | String | Tab-Listen-Icon (nullable) |
| `displayIcon` | String | opake Icon-Ref (nullable) |
| `weight` | int | Gewicht |
| `teamRank` | boolean | Team-Rang-Flag |
| `active` | boolean | aktiv-Flag |
| — | — | **kein `actor`-Feld** (Akteur aus JWT); `isDefault` nicht setzbar |

### `TokenPairResponse` (`protocol.webauth`)
| Feld | Typ | Bedeutung |
|---|---|---|
| `accessToken` | String | kurzlebiger Access-JWT (Bearer) |
| `accessExpiresAtEpochMilli` | long | Ablauf des Access-Tokens (epoch ms) |
| `refreshExpiresAtEpochMilli` | long | Ablauf des Refresh-Tokens (epoch ms) — Refresh-Wert selbst ist NICHT im Body (nur Cookie) |

### `TokenResponse` (`protocol.webauth`) — 🔌 plugin-Pfad
| Feld | Typ | Bedeutung |
|---|---|---|
| `token` | String | roher Token für den Web-Link |
| `purpose` | String | `"LINK"` oder `"RESET"` |
| `expiresAtEpochMilli` | long | Ablauf (epoch ms) |

---

## Ausgelesene Quellen

**Controller (`api-rest`):** `WebSessionController`, `WebAuthController`, `WebMeController`,
`WebPlayerController`, `WebPermissionController`. (Nicht-Web/Plugin: `PermissionController`,
`PlayerController`, `PlayerSessionController`, `EconomyController`, `EconomyHistoryController`,
`PunishmentController`, `ReportController`, `PingController` — bewusst ausgeklammert.)

**Security/Mapper:** `security/SecurityConfig`, `security/JwtAuthenticationFilter` (Referenz),
`support/PermissionMapper`, `support/WebPermissionMapper`, `support/WebAuthMapper` (Referenz).

**ExceptionHandler:** `WebAuthExceptionHandler`, `PermissionExceptionHandler`, `PunishmentExceptionHandler`
(globales 403 `permission_denied`), `EconomyExceptionHandler` (globales 400 `bad_request`).

**DTOs (`plugin-protocol`):** `permission.{ActiveGrant, EffectivePermissionEntry, PlayerPermissionsResponse,
RoleDisplay, RoleResponse}`; `permission.web.{GrantRoleWriteRequest, GrantPermissionWriteRequest,
RevokePermissionWriteRequest, RolePermissionWriteRequest, RoleWriteRequest, InheritanceWriteRequest}`;
`webauth.{LoginRequest, TokenPairResponse, TokenResponse, RedeemRequest, MeResponse, PrimaryRole}`;
`player.PlayerSummary`. Endpoint-Konstanten quergelesen: `PermissionEndpoints` (inkl.
LIST/ADD/REMOVE_INHERITANCE).

## Als „[unklar]" markierte Stellen

Keine. Alle dokumentierten Endpoints, DTO-Felder, Gates und Statuscodes waren im Code eindeutig
ableitbar. Einzige bewusst benannte Form-Abweichungen (kein Rätsel, sondern Fakt): die **401**
(Security-EntryPoint) und das **`X-Refresh`-403** (`ResponseStatusException`) nutzen den
Spring-Default-Error-Body statt des `{error,message}`-Schemas.
