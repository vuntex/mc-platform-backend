// core-domain: pure business logic. NO framework dependencies (no Spring, no jOOQ, no Lettuce).
// Allowed: JDK only (plus a test stack).
plugins {
    id("mcplatform.java-conventions")
}

dependencies {
    // Main: nothing but the JDK.

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}
