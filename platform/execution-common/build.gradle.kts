plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(project(":common"))
    // Script ASTs and external-call sessions, shared by the stacks that run scripts
    implementation(project(":expression"))
    implementation(project(":script"))
    
    // Ktor Server
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.auth.jwt)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.status.pages)
    
    // Ktor Client (for backend API calls)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    
    // JWT for service-to-service authentication
    implementation(libs.auth0.jwt)
    implementation(libs.auth0.jwks)
    
    // Database - JDBC
    implementation(libs.exposed.core)
    implementation(libs.exposed.dao)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.java.time)
    implementation(libs.postgresql.jdbc)
    implementation(libs.hikari)
    
    // Logging
    implementation(libs.logback)
    
    // Kotlinx
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.serialization.cbor)
    implementation(libs.kotlinx.coroutines.core)
    
    // Testing
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.mockk)
    testImplementation(libs.junit.jupiter)
}
