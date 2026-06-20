// application: use cases and ports (the hexagon's middle). Allowed: core-domain only.
plugins {
    id("mcplatform.java-conventions")
}

dependencies {
    api(project(":core-domain"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}
