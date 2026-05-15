package com.mdeo.optimizerexecution

import com.mdeo.execution.common.auth.configureJwtAuth
import com.mdeo.execution.common.config.configureSerialization
import com.mdeo.execution.common.config.configureStatusPages
import com.mdeo.execution.common.database.DatabaseFactory
import com.mdeo.execution.common.routes.healthRoutes
import com.mdeo.optimizerexecution.config.AppConfig
import com.mdeo.optimizerexecution.database.OptimizerTables
import com.mdeo.optimizerexecution.routes.optimizerRoutes
import com.mdeo.optimizerexecution.routes.workerRoutes
import com.mdeo.optimizerexecution.service.OrchestratorRegistry
import com.mdeo.optimizerexecution.service.OptimizerApiClient
import com.mdeo.optimizerexecution.service.OptimizerExecutionService
import com.mdeo.optimizerexecution.worker.WorkerService
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.slf4j.event.Level

/** Logger instance for application-level lifecycle events. */
private val logger = LoggerFactory.getLogger("Application")

/**
 * Application entry point: loads configuration and starts the embedded Netty server.
 */
fun main() {
    val config = AppConfig.load()
    embeddedServer(Netty, port = config.serverPort, host = "0.0.0.0") {
        module(config)
    }.start(wait = true)
}

/**
 * Configures the Ktor application module: initialises the database, installs plugins,
 * and wires all HTTP and WebSocket routes.
 *
 * @param appConfig The application configuration.
 */
fun Application.module(appConfig: AppConfig) {
    val databaseFactory = DatabaseFactory()
    databaseFactory.init(appConfig.database)
    OptimizerTables.createTables()

    val apiClient = OptimizerApiClient(appConfig.backendApiUrl)
    val orchestratorRegistry = OrchestratorRegistry()
    val workerService = WorkerService(appConfig.workerThreads, appConfig.scriptTimeoutMs, appConfig.transformationTimeoutMs, appConfig.serverPort)
    val executionService = OptimizerExecutionService(apiClient, this, appConfig, orchestratorRegistry, workerService)

    monitor.subscribe(ApplicationStopped) {
        runBlocking { workerService.close() }
        databaseFactory.close()
        apiClient.close()
    }

    install(CallLogging) { level = Level.INFO }
    install(WebSockets) {
        pingPeriodMillis = 30_000L
        timeoutMillis = 15_000L
    }
    configureSerialization()
    configureStatusPages()
    configureJwtAuth(appConfig.backendApiUrl, appConfig.jwtIssuer)

    routing {
        healthRoutes { databaseFactory.checkConnection() }
        optimizerRoutes(executionService)
        workerRoutes(workerService, orchestratorRegistry)
    }

    logger.info("Optimizer execution service started on port ${appConfig.serverPort}")
}
