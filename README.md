# MC Platform — Backend

Spring-Boot-Backend (Single Source of Truth) für einen Paper-1.21-Minecraft-Server.
Architektur, Datenmodell und Entscheidungen: siehe [PROGRESS.md](PROGRESS.md).

> Aktueller Stand: **lauffähiges Gerüst** — Multi-Module-Build, Schema-Migrationen,
> jOOQ-Codegen, Health-Endpoint. Noch **keine** Geschäftslogik / Economy-Operationen.

## Voraussetzungen

- JDK 21 (Build nutzt eine Java-21-Toolchain)
- Docker (für `docker-compose`, jOOQ-Codegen und die Testcontainers-Tests)

## Lokal starten

```bash
# 1. Env vorbereiten
cp .env.example .env

# 2. Postgres + Redis hochfahren
docker-compose up -d

# 3. Backend bauen (Codegen + Tests laufen mit)
./gradlew build

# 4. Backend starten (Profil 'local' ist Default)
./gradlew :app:bootRun

# 5. Health prüfen
curl http://localhost:8080/actuator/health   # -> {"status":"UP", ...}
```

Weitere Skeleton-Endpoints: `GET /api/ping` (REST), `GET /api/events` (SSE-Platzhalter).

> **Port 5432 schon belegt?** (z. B. lokales Postgres) — in `.env` `DB_PORT=5433` setzen.
> `docker-compose` und das `local`-Profil lesen denselben Wert, der Port bleibt also konsistent.

## Module (Hexagonal / DDD)

| Modul | Rolle | Darf abhängen von |
|-------|-------|-------------------|
| `core-domain` | reine Domäne (Economy, Player, Config) | nur JDK |
| `application` | Use Cases + Ports | `core-domain` |
| `plugin-protocol` | geteilte DTOs Plugin↔Backend | nur JDK |
| `infra-persistence` | Postgres-Adapter (jOOQ + Flyway) | `application` + jOOQ/Flyway/Postgres |
| `infra-cache` | Redis-Adapter (Lettuce) | `application` + Lettuce |
| `api-rest` | REST-Controller | `application` + Spring Web |
| `api-realtime` | SSE | `application` + Spring Web |
| `app` | Bootstrap, Spring-Config, `main` | alle |

Die Abhängigkeitsrichtung wird dadurch erzwungen, dass jedes Modul in seiner
`build.gradle.kts` ausschließlich die erlaubten Dependencies deklariert.

## plugin-protocol lokal publizieren (für das separate Plugin-Repo)

`plugin-protocol` (reine DTOs + Wire-Contract, nur JDK) wird via `maven-publish` nach
**Maven Local** (`~/.m2`) installiert, damit ein separates Plugin-Repo es als normale
Dependency ziehen kann — Vorstufe zur späteren privaten Registry.

Koordinaten: `com.mcplatform:plugin-protocol:0.1.0-SNAPSHOT`
(SNAPSHOT, weil sich das Protokoll noch ändert; landet unter
`~/.m2/repository/com/mcplatform/plugin-protocol/0.1.0-SNAPSHOT/`).

**Alltags-Workflow nach jeder Protokoll-Änderung:**

```bash
# 1. Im Backend-Repo: neu publizieren
./gradlew :plugin-protocol:publishToMavenLocal

# 2. Im Plugin-Repo: SNAPSHOT frisch ziehen (Gradle cached SNAPSHOTs sonst)
./gradlew build --refresh-dependencies
```

Im Plugin-Repo muss `mavenLocal()` in den `repositories` stehen und die Dependency
`implementation("com.mcplatform:plugin-protocol:0.1.0-SNAPSHOT")` deklariert sein.

## Datenbank & jOOQ

- Schema: Flyway-Migrationen unter
  `infra-persistence/src/main/resources/db/migration` (`V1` Schema, `V2` Seed `COINS`).
- jOOQ-Code wird beim Build aus dem **von Flyway migrierten Schema** generiert. Der
  `dev.monosoul.jooq-docker`-Plugin startet dafür einen Wegwerf-Postgres-Container —
  es wird **keine laufende DB** benötigt, nur Docker.

## Tests

`./gradlew test` startet via Testcontainers Postgres + Redis, lässt Flyway laufen und
prüft, dass alle Tabellen existieren, die Seed-Währung vorhanden ist und
`/actuator/health` mit 200 antwortet (siehe `app/.../SmokeTest.java`).
