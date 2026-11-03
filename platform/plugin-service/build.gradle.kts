plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    // Plugin targets and session types, shared with the backend
    api(project(":common"))

    // A plugin service is a Ktor application; authors add their own routes next to ours
    api(libs.ktor.server.core)
    api(libs.ktor.server.netty)
    api(libs.ktor.server.websockets)
    implementation(libs.ktor.server.status.pages)
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.core)

    // Verifying session tokens against the backend's JWKS
    implementation(libs.auth0.jwt)
    implementation(libs.auth0.jwks)

    implementation(libs.logback)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.junit.jupiter)
}
