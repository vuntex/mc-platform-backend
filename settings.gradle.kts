pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "mc-platform-backend"

// Module structure per PROGRESS.md section 5.
// Dependency direction is enforced by each module declaring ONLY what it is allowed to depend on:
//   core-domain      -> (nothing but JDK)
//   application      -> core-domain
//   plugin-protocol  -> (nothing but JDK)
//   infra-persistence-> application + jOOQ + Flyway + Postgres driver
//   infra-cache      -> application + Lettuce
//   api-rest         -> application + Spring Web
//   api-realtime     -> application + Spring Web (SSE)
//   app              -> wires everything (Spring Boot bootstrap)
include(
    "core-domain",
    "application",
    "plugin-protocol",
    "infra-persistence",
    "infra-cache",
    "api-rest",
    "api-realtime",
    "app",
)
