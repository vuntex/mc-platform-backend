// api-realtime: SSE endpoints for live updates. Allowed: application + Spring Web (SSE) + Spring Security.
plugins {
    id("mcplatform.java-conventions")
}

dependencies {
    implementation(project(":application"))

    implementation(platform(libs.spring.boot.bom))
    implementation(libs.spring.boot.starter.web)
    // Spring Security: web-only SSE endpoints under /api/web/** read the JWT principal
    // (@AuthenticationPrincipal) and gate via the PermissionResolver port (Constitution §12). The
    // SecurityFilterChain itself stays in :api-rest; this only lets the realtime controller see identity.
    implementation(libs.spring.boot.starter.security)
}
