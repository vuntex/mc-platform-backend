// api-rest: REST controllers. Allowed: application + Spring Web.
plugins {
    id("mcplatform.java-conventions")
}

dependencies {
    implementation(project(":application"))
    // Shared, dependency-free request/response DTOs + endpoint descriptors (single wire source).
    implementation(project(":plugin-protocol"))

    // Spring Boot BOM as a platform (version alignment only; the bootable app lives in :app).
    implementation(platform(libs.spring.boot.bom))
    implementation(libs.spring.boot.starter.web)
}
