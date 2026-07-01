# PERMISSION_API — Web Rollen-/Permission-Verwaltung

Frontend-Referenz für die Rang-/Permission-Verwaltung im Webinterface (`/api/web/permission/**`,
Slices 002/005/006 + Katalog + **008 Autoritäts-Grenzen**). Ergänzt `ECO_API.md` (Economy).

---

## 1. Grundlagen

- **Base-URL (lokal):** `http://localhost:8080`
- **Alle Endpunkte unter `/api/web/**`** — hinter der JWT-Chain. `Authorization: Bearer <accessToken>`
  (Access-Token aus dem Web-Login). Ohne/ungültig → **401** (ohne Body).
- **Zwei Autorisierungs-Ebenen (beide backend-autoritativ):**
  1. **Granulares Gate** — der Account braucht die jeweilige Permission (`permission.read`,
     `permission.role.create/edit/edit.inherit/delete`, `permission.grant.role`,
     `permission.grant.permission`). Fehlt sie → **403 `permission_denied`**.
  2. **Autoritäts-Ceiling (008)** — zusätzlich begrenzt das Rollen-**`weight`**, *wie weit* die
     Berechtigung reicht. Überschreitung → **403 `authority_ceiling`**.
- **Autoritäts-Modell (008), das das UI kennen muss:**
  - `authorityWeight` = höchstes `weight` der eigenen Rollen. „Top-Tier" = höchstes `weight` im System.
  - Man kann Rollen/Spieler nur **unterhalb** der eigenen Stufe verwalten/vergeben (Top-Tier: auch die
    eigene Stufe). Nie eine Rolle **über** der eigenen Stufe anlegen.
  - Man kann nur Permissions vergeben, die man **selbst hält**; **jede Wildcard (`x.*` / `*`) nur, wenn
    man selbst `*` hält.**
  - **Reads sind begrenzt:** die Rollen-Liste enthält nur verwaltbare Rollen; eine höhere Einzel-Rolle
    oder der Permissions-Tab eines höher-/gleichrangigen Spielers → **403** (der **eigene**
    Permissions-Tab ist immer sichtbar). Spieler-**Suche**, Stammdaten und **`/api/web/me`** sind
    **nicht** begrenzt.
  - Der **letzte** Top-Tier-Inhaber kann nicht entmachtet werden → **409 `last_top_tier`**.
- **Wichtig fürs UI:** Die Rollen-Liste ist bereits serverseitig gefiltert → der Rollen-Picker zeigt
  automatisch nur zuweisbare Rollen. Das UI muss die Autorität **nicht selbst berechnen** — es genügt,
  die gefilterten Listen zu nutzen und die Fehlercodes sauber zu behandeln. Optionales Button-Gating
  über `/api/web/me` (siehe unten).

---

## 2. Endpunkte

### Identität
```
GET /api/web/me
```
→ `MeResponse` — wer bin ich (uuid, Name, meine effektiven Permissions, mein Primär-Rang). **Immer
unbegrenzt** (auch hohe Ränge). Nutze `permissions` fürs optimistische Button-Gating und
`primaryRole.weight` als Anhalt für die eigene Stufe.

### Permission-Katalog (Übersicht aller Web-Permissions)
```
GET /api/web/permission/catalog
```
→ `PermissionCatalogResponse` — nach Thema gruppierte, deutsch beschriebene Web-Permissions. Ideal für
den Rollen-Editor („welche Rechte gibt es, was tun sie"). Gegated über `permission.read`.

### Rollen
```
GET    /api/web/permission/roles                 → RoleResponse[]   (nur verwaltbare Rollen, gefiltert)
GET    /api/web/permission/roles/{id}            → RoleResponse     (403 authority_ceiling wenn höher)
POST   /api/web/permission/roles                 (RoleWriteRequest) → RoleResponse
PUT    /api/web/permission/roles/{id}            (RoleWriteRequest) → RoleResponse
DELETE /api/web/permission/roles/{id}            → 204
```
- Neue/geänderte `weight` darf die eigene Autorität nicht überschreiten → sonst 403.
- Default-Rolle: nicht löschbar/deaktivierbar → 409 `default_role_protected`. Namenskonflikt (case-
  insensitiv) → 409 `role_name_conflict`. Ungültige Rolle → 422 `permission_invalid`.
- Löschen einer Rolle, die von anderen geerbt wird → 409 `role_inherited`. Löschen der letzten
  Top-Tier-Rolle → 409 `last_top_tier`.

### Rollen-Permissions
```
GET    /api/web/permission/roles/{id}/permissions   → RoleResponse   (403 wenn Rolle höher)
POST   /api/web/permission/roles/{id}/permissions   (RolePermissionWriteRequest) → RoleResponse
DELETE /api/web/permission/roles/{id}/permissions   (RolePermissionWriteRequest, Body!) → RoleResponse
```
- Hinzufügen: nur Permissions, die der Actor hält; Wildcard nur mit `*` → sonst 403 `authority_ceiling`.
  Ungültiges Format → 422 `permission_invalid`.

### Rollen-Vererbung
```
GET    /api/web/permission/roles/{id}/inheritance                → long[]  (Eltern-Rollen-IDs; 403 wenn Rolle höher)
POST   /api/web/permission/roles/{id}/inheritance                (InheritanceWriteRequest) → RoleResponse
DELETE /api/web/permission/roles/{id}/inheritance/{parentId}     → RoleResponse
```
- Zyklus → 409 `role_inheritance_cycle`. Default-Rolle als Kind → 409 `default_role_protected`.
  Braucht `permission.role.edit.inherit`. Beide Enden müssen ≤ eigener Autorität sein → sonst 403.

### Spieler-Grants
```
POST   /api/web/permission/players/{uuid}/roles                  (GrantRoleWriteRequest) → PlayerPermissionsResponse
DELETE /api/web/permission/players/{uuid}/roles/{roleId}?reason= → PlayerPermissionsResponse
POST   /api/web/permission/players/{uuid}/permissions            (GrantPermissionWriteRequest) → PlayerPermissionsResponse
DELETE /api/web/permission/players/{uuid}/permissions            (RevokePermissionWriteRequest, Body!) → PlayerPermissionsResponse
GET    /api/web/permission/players/{uuid}/effective              → PlayerPermissionsResponse
```
- Rolle/Ziel-Spieler müssen unterhalb der eigenen Autorität liegen → sonst 403 `authority_ceiling`.
- Permission-Grant: nur was man hält, Wildcard nur mit `*`. Expiry in der Vergangenheit → 422.
- `effective` eines höher-/gleichrangigen Spielers → **403 `authority_ceiling`** (Permissions-Tab
  gesperrt). **Ausnahme:** den **eigenen** Account (`uuid == eigene uuid`) darf man IMMER einsehen,
  unabhängig von der Autorität — der eigene Permissions-Tab ist nie gesperrt. Default-Rolle
  vergeben/entziehen → 409 `default_role_protected`.
- Die `effective`-Antwort synthetisiert die DEFAULT-Rolle, wenn der Spieler sonst keine aktive Rolle
  hat (damit der aktuelle Rang immer sichtbar ist).

### Spieler-Suche (ungefiltert)
```
GET /api/web/players/search?name={prefix}   → PlayerSummary[]
```
- Case-insensitive Präfix-Suche, **nicht** autoritäts-gefiltert (auch höhere Ränge auffindbar).
  Braucht `permission.read`.

---

## 3. Fehlercodes

JSON `{ "error": "<code>", "message": "..." }` (außer 401 ohne Body).

| Situation | Status | `error` |
|-----------|--------|---------|
| kein/ungültiges Token | **401** | — |
| Gate fehlt (z. B. `permission.role.edit`) | **403** | `permission_denied` |
| **Autorität überschritten** (Rang/Ziel/Delegation, 008) | **403** | `authority_ceiling` |
| **letzter Top-Tier** würde entfernt (008) | **409** | `last_top_tier` |
| Default-Rolle geschützt | 409 | `default_role_protected` |
| Rollenname-Konflikt | 409 | `role_name_conflict` |
| Vererbungs-Zyklus | 409 | `role_inheritance_cycle` |
| Rolle wird noch geerbt (Delete) | 409 | `role_inherited` |
| Rolle nicht gefunden | 404 | `role_not_found` |
| ungültige Rolle/Permission | 422 | `permission_invalid` |

**UI-Mapping-Empfehlung:** `authority_ceiling` → „Dafür reicht dein Rang nicht." · `last_top_tier` →
„Der letzte Owner/Top-Rang kann nicht entfernt werden." · `permission_denied` → „Dir fehlt die
Berechtigung." · 409-Rollenfälle → jeweils spezifische Meldung.

---

## 4. TypeScript-Typen (zum Kopieren)

```ts
export interface MeResponse {
  uuid: string;
  name: string | null;
  permissions: string[];                 // eigene effektive Permissions (Button-Gating)
  primaryRole: { name: string; displayName: string; color: string | null; weight: number };
}

export interface RoleResponse {
  id: number;
  name: string;
  displayName: string;
  color: string | null;
  prefix: string | null;
  suffix: string | null;
  tabListColor: string | null;
  tabListIcon: string | null;
  displayIcon: string | null;
  weight: number;                        // Autoritäts-/Hierarchie-Achse
  teamRank: boolean;
  active: boolean;
  isDefault: boolean;
  permissions: string[];                 // direkt konfigurierte Permissions der Rolle
  inheritedRoleIds: number[];            // direkte Eltern-Rollen
}

export interface RoleWriteRequest {      // POST/PUT /roles  (actor kommt aus dem Token, NIE aus dem Body)
  name: string;
  displayName: string;
  color?: string | null;
  prefix?: string | null;
  suffix?: string | null;
  tabListColor?: string | null;
  tabListIcon?: string | null;
  displayIcon?: string | null;
  weight: number;
  teamRank: boolean;
  active: boolean;
}

export interface RolePermissionWriteRequest { permission: string; }
export interface InheritanceWriteRequest { parentRoleId: number; }
export interface GrantRoleWriteRequest { roleId: number; expiresInSeconds?: number | null; reason?: string | null; }
export interface GrantPermissionWriteRequest { permission: string; expiresInSeconds?: number | null; reason?: string | null; }
export interface RevokePermissionWriteRequest { permission: string; reason?: string | null; }

export interface ActiveGrant {
  label: string;                         // Rollen-Name bzw. Permission-String
  expiresAtEpochMilli: number | null;    // null = permanent
  issuedBy: string | null;               // UUID des Ausstellers
  issuedByName: string | null;           // aufgelöster Name (oder null)
  reason: string | null;
}
export interface EffectivePermissionEntry {
  permission: string;
  own: boolean;                          // direkt (true) oder geerbt (false)
  inheritedFromRoleIds: number[];        // Herkunfts-Rollen bei geerbten Permissions
}
export interface RoleDisplay {
  displayName: string; color: string | null; prefix: string | null; suffix: string | null;
  tabListColor: string | null; tabListIcon: string | null; displayIcon: string | null;
}
export interface PlayerPermissionsResponse {
  player: string;                        // UUID
  roles: ActiveGrant[];
  permissions: ActiveGrant[];            // direkte Permission-Grants
  effectivePermissions: string[];        // aufgelöste Gesamt-Menge
  sources: EffectivePermissionEntry[];   // Provenienz je Permission
  display: RoleDisplay;                  // Anzeige des Primär-Rangs
}

export interface PermissionCatalogResponse {
  groups: { key: string; displayName: string; description: string;
            permissions: { key: string; description: string }[] }[];
}
export interface PlayerSummary { uuid: string; name: string; }
```

---

## 5. UI-Verhalten (empfohlen)

- **Rollen-Picker / Rollen-Liste:** direkt aus `GET /roles` — bereits gefiltert, also nur zuweisbare
  Rollen anzeigen. Kein Client-Filtern nach `weight` nötig.
- **Rollen-Editor:** `GET /catalog` als Referenz aller Web-Permissions (gruppiert, beschrieben) neben
  den `permissions` der Rolle. Beim Hinzufügen einer Permission optional vorab ausgrauen, was der
  eingeloggte Nutzer laut `/me.permissions` nicht selbst hält (Wildcards nur bei eigenem `*`) — der
  Server lehnt es sonst mit `authority_ceiling` ab.
- **Spieler-Verwaltung:** Suche über `/api/web/players/search` (ungefiltert). Beim Öffnen des
  Permissions-Tabs `GET /players/{uuid}/effective` — bei **403** eine „Kein Zugriff auf die Rechte
  dieses Spielers"-Ansicht zeigen (Stammdaten bleiben sichtbar). Nach jedem Grant/Revoke die
  zurückgegebene `PlayerPermissionsResponse` direkt zum Aktualisieren nutzen (kein Extra-Fetch).
- **DELETE mit Body:** `roles/{id}/permissions` und `players/{uuid}/permissions` erwarten einen
  Request-Body — mit `fetch`/axios explizit `body`/`data` bei DELETE senden.
- **Fehler zentral behandeln:** ein Interceptor mappt `error`-Code → Toast/Meldung (Tabelle oben);
  403/409 sind erwartbare, fachliche Fälle — nicht als „unerwarteter Fehler" behandeln.
- **Backend bleibt Wahrheit:** UI-Gating ist nur Komfort; jede Aktion kann trotzdem 403/409 liefern.
