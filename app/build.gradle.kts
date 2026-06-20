// app (bootstrap): wires every module together, owns the Spring configuration and the main class.
// This is the only bootable module (Spring Boot plugin -> bootJar / bootRun).
plugins {
    id("mcplatform.java-conventions")
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

dependencies {
    // Wire all modules of the hexagon.
    implementation(project(":core-domain"))
    implementation(project(":application"))
    implementation(project(":plugin-protocol"))
    implementation(project(":infra-persistence"))
    implementation(project(":infra-cache"))
    implementation(project(":api-rest"))
    implementation(project(":api-realtime"))

    // Spring Boot runtime. The Spring Boot plugin + dependency-management plugin import the
    // spring-boot-dependencies BOM, so these need no explicit versions.
    implementation(libs.spring.boot.starter)
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.actuator)
    // Redis runs through the framework-free infra-cache adapter (Lettuce), wired in CacheConfig.
    // No spring-boot-starter-data-redis: a single Redis connection path, no second Lettuce pool.

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit)
}

configurations.all {
    resolutionStrategy {
        // The jOOQ classes generated in :infra-persistence target jOOQ 3.21. The Spring Boot BOM
        // manages an older 3.19.x, which would otherwise downgrade the transitive jOOQ runtime
        // under those generated classes. Force the runtime to match the codegen version.
        force("org.jooq:jooq:${libs.versions.jooq.get()}")
    }
}
