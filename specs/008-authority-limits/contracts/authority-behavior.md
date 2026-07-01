# Contract — Autoritäts-Verhalten (008)

**Keine neuen Endpunkte, keine neuen DTOs.** Dieses Feature verschärft das Verhalten bestehender
`/api/web/permission/**`-Endpunkte. Contract = zusätzliche Vorbedingungen und Fehler.

Grundlagen: `authorityWeight(actor)` = höchstes `weight` der reachable Rollen (aktive Grants +
Vererbung); `topWeight` = höchstes `weight` aller Rollen; Top-Tier ⟺ `authorityWeight == topWeight`.
Schwelle: verwaltbar ist `weight < authorityWeight` (non-top) bzw. `≤` (Top-Tier). Autorität ist
**weight-only** — `*` erhöht sie nicht.

## Writes — zusätzliche Ablehnungen

| Endpunkt | Zusätzliche Vorbedingung | Fehler bei Verstoß |
|----------|--------------------------|--------------------|
| `POST /roles` (create) | neue `weight` ≤ eigene Autorität (non-top strikt <) | 403 `authority_ceiling` |
| `PUT /roles/{id}` (update) | alte **und** neue `weight` verwaltbar; keine Anhebung über Autorität | 403 `authority_ceiling`; 409 `last_top_tier` (Top-Weight-Absenkung, die Top-Tier leert) |
| `DELETE /roles/{id}` | `rolle.weight` verwaltbar | 403 `authority_ceiling`; 409 `last_top_tier` (leert Top-Tier) |
| `POST /roles/{id}/permissions` (add) | `rolle.weight` verwaltbar **und** Actor hält die Permission (Wildcard → `*`) | 403 `authority_ceiling` |
| `DELETE /roles/{id}/permissions` (remove) | `rolle.weight` verwaltbar | 403 `authority_ceiling` |
| `POST /roles/{id}/inheritance` (add) | `child` **und** `parent` verwaltbar | 403 `authority_ceiling` |
| `DELETE /roles/{id}/inheritance/{parentId}` | `child` verwaltbar | 403 `authority_ceiling` |
| `POST /players/{uuid}/roles` (grant) | `rolle.weight` verwaltbar **und** Ziel-Autorität verwaltbar | 403 `authority_ceiling` |
| `DELETE /players/{uuid}/roles/{roleId}` (revoke) | `rolle.weight` + Ziel-Autorität verwaltbar | 403 `authority_ceiling`; 409 `last_top_tier` (letzter Top-Tier) |
| `POST /players/{uuid}/permissions` (grant) | Ziel-Autorität verwaltbar **und** Actor hält die Permission (Wildcard → `*`) | 403 `authority_ceiling` |
| `DELETE /players/{uuid}/permissions` (revoke) | Ziel-Autorität verwaltbar | 403 `authority_ceiling` |

Reihenfolge je Methode: bestehendes `permission.*`-Gate (→ 403 `permission_denied`, falls es ganz
fehlt) **zuerst**, dann die Autoritäts-Guards. Bestehende Default-Rolle-/Zyklus-/Dependency-Checks
bleiben.

## Reads — Filter/Gate

| Endpunkt | Verhalten |
|----------|-----------|
| `GET /api/web/permission/roles` (+ Picker) | Ergebnis enthält nur Rollen mit `weight < authorityWeight` (Top-Tier `≤`). Silent-Filter, kein Fehler. |
| `GET /api/web/permission/roles/{id}` (+ `/permissions`, `/inheritance`) | 403 `authority_ceiling`, wenn `rolle.weight ≥ authorityWeight` (Top-Tier: `>`). Kein Umgehen des Listen-Filters per direkter ID. |
| `GET /api/web/permission/players/{uuid}/effective` | 403, wenn Ziel-Autorität `≥` Actor-Autorität (non-top). **Self-Ausnahme:** eigener Account (`uuid==actor`) immer erlaubt. |
| `GET /api/web/permission/catalog` (008) | unverändert (vollständig, informativ). |
| `GET /api/web/players/search`, Spieler-Stammdaten | **ungefiltert** — Spieler bleiben auffindbar/sichtbar. |
| `GET /api/web/me` | **unbegrenzt** — eigener (auch hoher) Rang immer sichtbar. |

## Fehlercodes (neu)

| Situation | Status | Body `error` |
|-----------|--------|--------------|
| Aktion überschreitet die eigene Autorität (Rang/Ziel/Delegation) | **403** | `authority_ceiling` |
| Aktion würde den letzten Top-Tier-Inhaber entfernen | **409** | `last_top_tier` |
| (bestehend) Grant fehlt ganz | 403 | `permission_denied` |

## Beispiele

- Admin (Weight 100, ohne `*`) → `POST /players/{x}/permissions {permission:"*"}` → **403**
  `authority_ceiling` (Wildcard ohne `*`).
- Admin (Weight 100) → `POST /players/{x}/roles {roleId: Owner(200)}` → **403** `authority_ceiling`
  (Rolle über eigener Stufe).
- Mod (Weight 50) → `GET /permission/roles` → Liste ohne Admin/Owner (weight ≥ 50 gefiltert).
- Mod → `GET /permission/players/{admin}/effective` → **403**; `GET /players/search?name=adm` →
  findet den Admin trotzdem.
- Letzter Owner → `DELETE /players/{self}/roles/{Owner}` → **409** `last_top_tier`.
