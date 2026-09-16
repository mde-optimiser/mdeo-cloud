plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(libs.kotlinx.serialization.json)

    // WebSocket transport for the shared execution protocol: the client side is used by the
    // backend, the server side by the execution services. Exposed as `api` so consumers of
    // the protocol do not have to repeat them.
    api(libs.ktor.client.core)
    api(libs.ktor.client.cio)
    api(libs.ktor.client.websockets)
    api(libs.ktor.server.core)
    api(libs.ktor.server.websockets)
    // Compression of HTTP bodies and WebSocket messages on every hop, see HttpCompression.kt
    api(libs.ktor.server.compression)
    api(libs.ktor.client.encoding)
    api(libs.kotlinx.coroutines.core)

    implementation(libs.logback)

    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.junit.jupiter)
}
