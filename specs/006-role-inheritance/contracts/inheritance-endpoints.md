# Phase 1 — Contracts: Rollen-Vererbung REST + Wire

Alle Web-Endpoints liegen unter `/api/web/permission/**` hinter der JWT-Security-Chain (Slice 004);
`actor` = `PlayerId` aus dem Token-Principal (nie aus dem Body). Gating backend-autoritativ über
`PermissionResolver`. Plugin-seitige Pendants als `EndpointDescriptor`-Konstanten in
`PermissionEndpoints` unter `/api/permission/**`.

## Endpoints

### 1. Vererbte Rollen auflisten

```
GET /api/web/permission/roles/{id}/inheritance
Gate: permission.read
200 → long[]                      # die DIREKTEN Eltern-Rollen-IDs von {id}
404 → Rolle {id} existiert nicht
403 → Gate fehlt   401 → kein/ungültiges Token
```

### 2. Vererbung hinzufügen ({id} erbt von {parentRoleId})

```
POST /api/web/permission/roles/{id}/inheritance
Gate: permission.role.edit.inherit
Body: InheritanceWriteRequest { "parentRoleId": <long> }
200 → RoleResponse                # {id} inkl. aktualisierter inheritedRoleIds
404 → {id} oder {parentRoleId} existiert nicht
409 → würde Zyklus erzeugen (child==parent ODER parent erbt bereits child)
409 → {id} ist die Default-Rolle (Default ist Blatt, FR-013)
422 → ungültiger Body
403 / 401
```
Idempotenz: existiert die Kante bereits → 200, kein Duplikat (FR-014).

### 3. Vererbung entfernen

```
DELETE /api/web/permission/roles/{id}/inheritance/{parentRoleId}
Gate: permission.role.edit.inherit
200 → RoleResponse                # {id} inkl. aktualisierter inheritedRoleIds
404 → {id} existiert nicht
200 → existierte die Kante nicht: idempotenter No-Op (FR-014)
403 / 401
```

### 4. Effektive Sicht mit Herkunft (transitiv aufgelöst)

Bestehende Endpoints, nach dem Umbau transitiv + mit Provenienz:

```
GET /api/web/permission/players/{uuid}/effective
Gate: permission.read
200 → PlayerPermissionsResponse {
        ... ,
        effectivePermissions: string[],            # flach, unverändert (autoritativer Allow/Deny, FR-022)
        sources: EffectivePermissionEntry[]         # NEU — Provenienz je Permission (FR-022a)
      }

GET /api/web/permission/roles/{id}            # RoleResponse jetzt mit inheritedRoleIds
```

### 5. Auswirkung auf bestehende Lösch-/Edit-Endpoints

```
DELETE /api/web/permission/roles/{id}
409 → {id} wird von anderen Rollen geerbt (FR-015) — Meldung benennt die abhängigen Rollen
POST|DELETE /api/web/permission/roles/{id}/permissions
→ Live-Push jetzt an {id} + transitiv abhängige Rollen-Holder (FR-020a)
```

## Wire-DTOs (plugin-protocol, JDK-only, additiv)

```java
// web/InheritanceWriteRequest.java  (neu)
public record InheritanceWriteRequest(long parentRoleId) {}

// EffectivePermissionEntry.java  (neu)
public record EffectivePermissionEntry(
        String permission,
        boolean own,                       // direkt an der betrachteten Rolle/dem Spieler gesetzt
        List<Long> inheritedFromRoleIds    // vollständige Quellenmenge (FR-022a); leer wenn rein own
) {}

// RoleResponse.java  (+ Feld, additiv)
public record RoleResponse(
        long id, String name, String displayName, String color, String prefix, String suffix,
        String tabListColor, String tabListIcon, String displayIcon,
        int weight, boolean teamRank, boolean active, boolean isDefault,
        List<String> permissions,
        List<Long> inheritedRoleIds        // NEU — direkte Eltern
) {}

// PlayerPermissionsResponse.java  (+ Feld, additiv)
public record PlayerPermissionsResponse(
        UUID player, List<ActiveGrant> roles, List<ActiveGrant> permissions,
        List<String> effectivePermissions,           // unverändert flach
        RoleDisplay display,
        List<EffectivePermissionEntry> sources        // NEU — Provenienz
) {}
```

`PermissionEndpoints` (+ Konstanten): `LIST_INHERITANCE` (GET, `Void`→`long[]`), `ADD_INHERITANCE`
(POST, `InheritanceWriteRequest`→`RoleResponse`), `REMOVE_INHERITANCE` (DELETE
`/api/permission/roles/{id}/inheritance/{parentId}`, `Void`→`RoleResponse`).

## Pub/Sub (unverändert)

Channel `mc:permission:changed` (`PermissionChannels.CHANGED`), Payload `PermissionChangedEvent`,
`changeType = ROLE_CONFIG_CHANGED`. **Kein** neuer Channel/Event. Fan-out: Holder von
`{geänderte Rolle} ∪ dependents(...)`, je ein Event pro Spieler-UUID.

## Fehler-Mapping (Spring `@ControllerAdvice`, bestehend erweitert)

| Exception | HTTP |
|---|---|
| `RoleNotFoundException` | 404 |
| `RoleInheritanceCycleException` (neu) | 409 |
| `RoleInheritedException` (neu, FR-015) | 409 |
| `DefaultRoleProtectedException` (Default als child, FR-013) | 409 |
| `PermissionDeniedException` | 403 |
| (fehlendes/ungültiges JWT) | 401 |
