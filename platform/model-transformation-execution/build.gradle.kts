plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
    alias(libs.plugins.shadow)
}

application {
    mainClass.set("com.mdeo.modeltransformationexecution.ApplicationKt")
}

dependencies {
    implementation(project(":common"))
    implementation(project(":expression"))
    implementation(project(":metamodel"))
    implementation(project(":model-transformation"))
    implementation(project(":execution-common"))
    
    // Ktor Server
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.auth.jwt)
    
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
    
    // Apache TinkerPop Gremlin - TinkerGraph for in-memory execution
    implementation(libs.gremlin.core)
    implementation(libs.tinkergraph.gremlin)
    
    // Logging
    implementation(libs.logback)
    
    // Kotlinx
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    
    // Testing
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.mockk)
    testImplementation(libs.junit.jupiter)
}

tasks.shadowJar {
    archiveBaseName.set("model-transformation-execution")
    archiveClassifier.set("")
    archiveVersion.set("")
    mergeServiceFiles()
}
