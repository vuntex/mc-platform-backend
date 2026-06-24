# Phase 0 — Research: Web-Auth-Bridge

Alle Entscheidungen sind getroffen; keine offenen `NEEDS CLARIFICATION`. R3 und R4 weichen bewusst von
der Prompt-Vorgabe ab und wurden am 2026-06-24 ausdrücklich **bestätigt**.

## R1 — Persistenz: state-stored (nicht event-sourced)

- **Decision**: `web_account` + `web_link_token` + append-only `web_auth_audit` (state-stored, Reports-Muster).
- **Rationale**: Config-/Identitätsdatum, kein geld-/urteilskritisches Aggregat (Constitution §6). Audit-Bedarf
  (wer/wann angelegt/zurückgesetzt) deckt die Audit-Tabelle. Passwort wird in-place ersetzt — alte Hashes
  aufbewahren (Event-Sourcing) wäre ein Sicherheits-Anti-Pattern.
- **Alternatives**: Event-sourced (verworfen — unbegründeter Ballast, kein Replay-Bedarf).

## R2 — Token in der Datenbank (single-use, ein pro uuid/purpose)

- **Decision**: Eigene Tabelle `web_link_token`. Single-use = `DELETE` der Zeile **in derselben TX** wie der
  erfolgreiche Redeem. Ein aktiver Token je `(player_uuid, purpose)` = `DELETE WHERE player_uuid=? AND
  purpose=?` **vor** dem `INSERT`, ebenfalls in einer TX (`JooqLinkTokenRepository.issue`).
- **Rationale**: Spec-Kern-Entscheidung; kein Redis-Live-Pfad nötig. DB-TX gibt Atomarität und
  Race-Sicherheit (Single-Server, §14) ohne Distributed-Lock.
- **Alternatives**: Redis-TTL-Key (verworfen — würde Live-Infra einziehen, überlebt Flush nicht, kein
  Single-use-Garant ohne Lua); Spalte auf `web_account` (verworfen — LINK hat noch keinen Account).

## R3 — Token-at-rest: SHA-256-Hash statt Rohwert  *(entschieden 2026-06-24)*

- **Decision**: In `web_link_token` steht **nicht** der Rohtoken, sondern dessen **SHA-256-Hash**
  (`token_hash` als PK/unique). Lookup beim Redeem: präsentierten Rohtoken hashen → `WHERE token_hash=?`.
- **Rationale**: Der Token ist ein Bearer-Geheimnis; ein DB-Read-Leak erlaubt sonst direkte Einlösung.
  Hashing ist quasi gratis und braucht **kein** Salt/Slow-Hash, weil der Token bereits ≥128 Bit Entropie
  trägt (R: SecureRandom). SHA-256 ist reines JDK (`MessageDigest`) → keine neue Dependency, bleibt in
  `infra-persistence`.
- **Abweichung von der Prompt** („token PK/unique" = Rohwert): auf `token_hash` verfeinert —
  **bestätigt** (2026-06-24).
- **Alternatives**: HMAC mit Server-Secret (verworfen — Schlüsselverwaltung-Overhead ohne realen Mehrwert
  bei 10-Min-Single-use-Token); Rohwert (siehe Abweichung).

## R4 — PasswordHasher als Port; BCrypt-Impl in `app` (nicht infra)  *(entschieden 2026-06-24)*

- **Decision**: Port `PasswordHasher` in **core-domain** (framework-frei). Einzige Impl
  `BCryptPasswordHasher` (spring-security-crypto, `BCryptPasswordEncoder`) als Adapter im **`app`**-Modul.
- **Rationale**: `infra-persistence` ist heute strikt **Spring-frei** (nur jOOQ/Flyway/Postgres, verifiziert
  in der `build.gradle.kts`). `spring-security-crypto` ist ein Spring-Artefakt — es gehört daher in `app`
  (das bereits Spring nutzt), nicht in `infra-persistence`. Der Hasher hasht im Service **vor** dem Persist;
  `infra-persistence` sieht nur den fertigen Hash-String → keine Krypto-Dependency dort.
- **Abweichung**: Die Prompt sagte „BCrypt-Impl in infra". Verlegt nach `app`, um §5 („infra-persistence
  kein Spring") nicht zu brechen.
- **Port-Signatur** (wie verlangt): `String hash(String raw)` · `boolean matches(String raw, String hash)`.
  In Slice 1 wird nur `hash` ausgeübt (Login/`matches` kommt im Folge-Slice; Methode bleibt im Port).
- **Alternatives**: spring-security-crypto in `infra-persistence` (verworfen — bräche die Spring-Freiheit);
  Argon2 (verworfen — extra Dependency; BCrypt genügt, 64er-Passwortgrenze < 72 Byte umschifft die
  BCrypt-Truncation, siehe Q4/R: Passwort-Policy).

## R5 — Name→UUID-Auflösung: nicht in diesem Slice

- **Decision**: In Slice 1 wird **kein** Name→UUID-Lookup gebaut. Die `/web`-Commands laufen ingame; das
  Plugin kennt die UUID bereits und schickt sie als Pfad-Variable. Der Redeem erhält die UUID aus der
  Token-Zeile. **Kein** Lookup-Pfad existiert daher.
- **Festgehaltene Regel für den Login-Slice**: Name → UUID via `SELECT uuid FROM player WHERE LOWER(name) =
  LOWER(?) ORDER BY last_seen DESC LIMIT 1` — nutzt den **bestehenden** `idx_player_name_lower`
  (`player (LOWER(name))`, V1); bei recyceltem/mehrdeutigem Namen gewinnt der jüngste `last_seen`.
- **Wichtige Korrektur zur Prompt**: `PlayerRepository` hat heute **nur** `save` und
  `upsertReturningWhetherNew` — **kein** `findByName`. Es gibt also nichts „Bestehendes" für Name→UUID
  wiederzuverwenden; aber es wird hier auch **nicht gebraucht**. Wenn der Login-Slice es braucht, ist das
  eine **neue** Repository-Methode (kein Eingriff in diesem Slice).
- **Alternatives**: Lookup schon hier bauen (verworfen — YAGNI; gehört zum Login-Slice).

## R6 — Flyway-Version: V11  *(Korrektur zur Prompt)*

- **Decision**: Neue Migration **`V11__web_auth_schema.sql`** (eine Migration, drei Tabellen).
- **Rationale**: Vorhandene Migrationen reichen bis **V10** (`V9__permission_schema`, `V10__role_display_icon`
  vom Permission-Feature). Die Prompt-Annahme „nächste freie = V9" war überholt. Keine bestehende Migration
  wird verändert.

## R7 — Kein Codec/Channel; `PlatformProtocol.create()` unverändert

- **Decision**: Web-Auth hat **kein** Live-Event → **kein** `MessageCodec`, **kein** `Channel`, **kein**
  Eintrag in `PlatformProtocol.create()`. Der Contract-Zuwachs ist rein additiv: `WebAuthEndpoints` +
  zwei DTO-Records.
- **Rationale**: Account-Anlage/Reset brauchen keinen Live-Push ins Spiel (anders als Balance/Permission).
  Das ist das erste Feature **ohne** Eingriff in geteilten Routing-Code — der bisher „eine erlaubte Zeile"
  genannte Touch entfällt hier komplett.
- **Alternatives**: Event „account-linked" publishen (verworfen — kein Konsument, kein Nutzen in Slice 1).

## R8 — REST-Ressourcen & Status-Codes

- **Decision**:
  - `POST /api/players/{uuid}/web-auth/link-token` (Plugin) → 200 `TokenResponse`; Account existiert → **409**.
  - `POST /api/players/{uuid}/web-auth/reset-token` (Plugin) → 200 `TokenResponse`; kein Account → **409**.
  - `POST /api/web-auth/redeem` (Web) → **204**; Token ungültig/abgelaufen/verbraucht → **410** (uniform,
    leakt keine Existenz, FR-019/SC-005); Passwort verletzt 8..64 → **422**; Account-Race → **409**.
  - Cooldown auf Token-Erzeugung → **429**.
- **Rationale**: Player-genestete Ressourcen wie Economy; Redeem flach (Web kennt nur den Token). **410 Gone**
  als einheitliche Token-Ablehnung bündelt „unbekannt/abgelaufen/verbraucht" ununterscheidbar. **400** wird
  **nicht** re-deklariert (globaler Handler greift).
- **Alternatives**: 404 für fehlenden Account bei reset (verworfen — 409 hält „falscher Account-Zustand für
  diese Aktion" konsistent zu link's 409); 401/403 (verworfen — kein Auth/Permission-Gate in diesem Slice).

## R9 — Cooldown-Semantik

- **Decision**: `WebAuthService` mit `Duration cooldown` + `Clock` (Reports-Muster). Vor dem Erzeugen:
  `lastCreatedAt(uuid, purpose)` lesen; ist der jüngste Token jünger als der Cooldown → 429.
- **Rationale**: Anchor ist der `created_at` des aktuell **lebenden** Tokens (DELETE-vor-INSERT hat ihn noch
  nicht entfernt, da wir vorher prüfen). Nach Redeem/Ablauf existiert kein Anchor → wieder erlaubt; das ist
  gewollt (Spam-Schutz greift, solange ein offener Vorgang läuft).
- **Config**: `mcplatform.webauth.token-cooldown-seconds` (Default 60; 0/negativ = aus, wie Reports).
- **Alternatives**: Tages-Cap (verworfen für Slice 1, Q5).

## R10 — Token-TTL

- **Decision**: `mcplatform.webauth.token-ttl-minutes` (Default 10). `expires_at = now + TTL` bei Erzeugung.
- **Rationale**: Spec ~10 Min; konfigurierbar wie der Cooldown, kein Hardcoding.

## R11 — Abgelaufene Token: Purge ist Hygiene

- **Decision**: Sicherheit liegt im `expires_at > now`-Filter beim Redeem (R8/Live-Verortung). Ein optionaler
  `@Scheduled`-Purge ruft `LinkTokenRepository.deleteExpired(now)` über die **bestehende**
  `@EnableScheduling`-Aktivierung (`SchedulingConfig`, Permissions-Feature). Als **nicht
  sicherheitskritisch** markiert; darf entfallen/verschoben werden.
- **Alternatives**: kein Purge (verworfen nur als Default — Tabelle würde unbegrenzt wachsen; Hygiene lohnt).

## R12 — Reuse-Inventar (Zusammenfassung)

Wiederverwendet **ohne** Änderung: FK auf `player(uuid)`, `idx_player_name_lower` (erst Login),
`EndpointDescriptor`/`HttpMethod`, Plugin-`BackendClient`/`FeatureRegistry`, Cooldown-Muster (Reports),
Audit-Muster (`config_audit`), `Clock`-Bean, `@EnableScheduling`, Flyway/jOOQ-Codegen-Pipeline, globales
400-Mapping. **Neu**: `webauth`-Packages je Schicht, V11-Migration, `spring-security-crypto` (nur `app`).
**Nicht** wiederverwendet (bewusst): `PermissionResolver`, Redis/Pub-Sub, `PlatformProtocol.create()`.
