// infra-cache: Redis adapter built directly on Lettuce. Allowed: application + Lettuce.
// No Spring in this module (the rule in PROGRESS.md section 5 lists only application + Lettuce).
plugins {
    id("mcplatform.java-conventions")
}

dependencies {
    implementation(project(":application"))
    implementation(libs.lettuce)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.testcontainers.junit)
    testRuntimeOnly(libs.junit.platform.launcher)
}
