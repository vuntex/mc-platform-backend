// api-realtime: SSE endpoints for live updates. Allowed: application + Spring Web (SSE).
plugins {
    id("mcplatform.java-conventions")
}

dependencies {
    implementation(project(":application"))

    implementation(platform(libs.spring.boot.bom))
    implementation(libs.spring.boot.starter.web)
}
