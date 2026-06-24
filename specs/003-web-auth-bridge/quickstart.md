# Quickstart — Web-Auth-Bridge (Build / Test / DoD)

## Build & Test
```bash
# Backend (Repo-Root)
./gradlew build                      # Domain + Use-Case + jOOQ-Integration (Testcontainers) + E2E + JSON-Contract

# Nach protocol-Änderung (neue webauth-DTOs/Endpoints):
./gradlew :plugin-protocol:publishToMavenLocal
# danach im Plugin-Repo:  ./gradlew build --refresh-dependencies
```
Voraussetzung: Docker (Testcontainers-Postgres für jOOQ-/E2E-Tests; jOOQ-Codegen-Container).

## Smoke-Pfad (E2E, manuell oder im Test)
1. Spieler joint (Session-Join legt `player`-Zeile an).
2. `POST /api/players/{uuid}/web-auth/link-token` → `TokenResponse{token,…}` (200).
3. `POST /api/web-auth/redeem {token, password:"genug-lang"}` → **204**; `web_account` existiert mit
   BCrypt-Hash, `web_link_token`-Zeile weg, `web_auth_audit`-Zeile `ACCOUNT_CREATED`.
4. Zweiter `link-token` für dieselbe UUID → **409** `web_account_exists`.
5. `reset-token` → Token → `redeem` mit neuem Passwort → **204**; `password_updated_at` neu, Audit
   `PASSWORD_RESET`.
6. Alten/abgelaufenen Token einlösen → **410**; Passwort < 8 oder > 64 → **422**; Cooldown → **429**.

## Definition of Done (Constitution §22)
- [ ] Tests grün je Schicht: Domain (`PasswordPolicy`, `LinkToken.isExpired`), Use-Case (Fakes:
      409/410/422/429-Pfade, redeem LINK/RESET, single-use, Audit, kein Klartext-Log), jOOQ-Integration
      (DELETE-vor-INSERT, atomarer Redeem, `expires_at`-Filter, FK, SHA-256-Roundtrip, `deleteExpired`),
      E2E (`app`), DTO-JSON-Contract (`@JsonTest`: Feldnamen, **kein** Hash/email im Wire).
- [ ] `./gradlew build` grün (Backend).
- [ ] `:plugin-protocol:publishToMavenLocal` ausgeführt; POM **ohne** `<dependencies>`.
- [ ] **Bestätigt: keine generische Klasse geändert** — `PlatformProtocol.create()` byte-identisch,
      `protocol/core` unberührt; nur additive Beans (`PersistenceConfig`, `WebAuthConfig`) und eine neue
      Dependency `spring-security-crypto` **nur** in `app`.
- [ ] `infra-persistence` weiterhin **Spring-frei** (BCrypt liegt in `app`).
- [ ] PROGRESS.md-Status nachgezogen („Web-Auth-Bridge — fünftes Feature").
- [ ] FEATURE_INVENTORY.md: neuer Infra-Eintrag/Notiz (Greenfield, kein Alt-Vorgänger).

## Entschiedene Punkte (2026-06-24, vor Implement bestätigt)
- **R3** Token-at-rest als SHA-256-Hash (statt Rohwert) — **bestätigt**.
- **R4** BCrypt-Impl in `app` statt `infra-persistence` — **bestätigt** (hält infra Spring-frei).
- **Flyway V11** (nicht V9) — faktische nächste freie Version.
