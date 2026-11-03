package com.mdeo.modeltransformationexecution

import com.mdeo.common.transport.installHttpCompression
import com.mdeo.execution.common.auth.configureJwtAuth
import com.mdeo.execution.common.config.configureExecutionTransport
import com.mdeo.execution.common.config.configureSerialization
import com.mdeo.execution.common.config.configureStatusPages
import com.mdeo.execution.common.database.DatabaseFactory
import com.mdeo.execution.common.routes.healthRoutes
import com.mdeo.modeltransformationexecution.config.AppConfig
import com.mdeo.modeltransformationexecution.database.TransformationTables
import com.mdeo.modeltransformationexecution.routes.transformationRoutes
import com.mdeo.modeltransformationexecution.service.TransformationApiClient
import com.mdeo.modeltransformationexecution.service.TransformationExecutionService
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.routing.*
import org.slf4j.LoggerFactory
import org.slf4j.event.Level

private val logger = LoggerFactory.getLogger("Application")

/**
 * Application entry point.
 * Loads configuration and starts the embedded Netty server.
 */
fun main() {
    val config = AppConfig.load()
    
    embeddedServer(Netty, port = config.serverPort, host = "0.0.0.0") {
        module(config)
    }.start(wait = true)
}

/**
 * Main application module that configures all services, plugins, and routing.
 *
 * @param appConfig Application configuration
 */
fun Application.module(appConfig: AppConfig) {
    val databaseFactory = DatabaseFactory()
    databaseFactory.init(appConfig.database)
    TransformationTables.createTables()
    
    val apiClient = TransformationApiClient(appConfig.backendApiUrl)
    val executionService = TransformationExecutionService(
        apiClient,
        appConfig.executionTimeoutMs,
        this
    )
    
    monitor.subscribe(ApplicationStopped) {
        databaseFactory.close()
        apiClient.close()
    }
    
    install(CallLogging) {
        level = Level.INFO
    }
    
    configureSerialization()
    installHttpCompression()
    configureStatusPages()
    
    configureJwtAuth(appConfig.backendApiUrl, appConfig.jwtIssuer)
    configureExecutionTransport(appConfig.backendApiUrl, appConfig.jwtIssuer)
    
    routing {
        healthRoutes { databaseFactory.checkConnection() }
        transformationRoutes(executionService)
    }
    
    logger.info("Model transformation execution service started on port ${appConfig.serverPort}")
}
