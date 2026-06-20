// Shared Java conventions for every module in the build.
plugins {
    `java-library`
}

group = "com.mcplatform"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.withType<JavaCompile>().configureEach {
    // Keep parameter names in the bytecode. Spring MVC needs them to resolve @PathVariable /
    // @RequestParam by name; controllers live in library modules (api-rest) that don't apply the
    // Spring Boot plugin, so we enable it here for every module instead of relying on that plugin.
    options.compilerArgs.add("-parameters")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
