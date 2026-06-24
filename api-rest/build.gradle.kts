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
    // Spring Security: the JWT auth filter + SecurityFilterChain live here (web layer). The filter depends
    // only on the TokenVerifier port — jjwt itself stays in :app. Authorization remains the
    // PermissionResolver's job; this only establishes identity (plan R4, Constitution §12).
    implementation(libs.spring.boot.starter.security)
}
