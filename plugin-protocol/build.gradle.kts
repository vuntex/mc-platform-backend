// plugin-protocol: shared DTOs + wire contract for plugin <-> backend. Pure data, NO framework deps.
// Separately publishable; consumed by the plugin repo. Variante B: published to Maven Local as a
// precursor to a private registry. `java-library` is already applied by the convention plugin;
// `maven-publish` makes the module installable into ~/.m2 via :plugin-protocol:publishToMavenLocal.
plugins {
    id("mcplatform.java-conventions")
    `maven-publish`
}

dependencies {
    // Main: nothing but the JDK.

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// group + version are inherited from the convention plugin (com.mcplatform : 0.1.0-SNAPSHOT).
// artifactId defaults to the module name -> "plugin-protocol".
publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
        }
    }
}
