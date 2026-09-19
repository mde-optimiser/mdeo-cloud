package com.mdeo.scriptexecution

import com.mdeo.common.transport.installHttpCompression
import com.mdeo.execution.common.auth.configureJwtAuth
import com.mdeo.execution.common.config.configureExecutionTransport
import com.mdeo.execution.common.config.configureSerialization
import com.mdeo.execution.common.config.configureStatusPages
import com.mdeo.execution.common.routes.healthRoutes
import com.mdeo.scriptexecution.config.*
import com.mdeo.scriptexecution.database.DatabaseFactory
import com.mdeo.scriptexecution.routes.executionRoutes
import com.mdeo.scriptexecution.service.BackendApiService
import com.mdeo.scriptexecution.service.ExecutionService
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
    DatabaseFactory.init(appConfig.database)
    
    val backendApiService = BackendApiService(appConfig.backendApiUrl)
    val executionService = ExecutionService(
        backendApiService,
        appConfig.executionTimeoutMs,
        this,
        appConfig.sessionConnectTimeoutMillis
    )
    
    monitor.subscribe(ApplicationStopped) {
        DatabaseFactory.close()
        backendApiService.close()
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
        healthRoutes { DatabaseFactory.checkConnection() }
        executionRoutes(executionService)
    }
    
    logger.info("Script execution service started on port ${appConfig.serverPort}")
}
