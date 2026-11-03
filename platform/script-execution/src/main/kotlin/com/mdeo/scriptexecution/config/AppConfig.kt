package com.mdeo.scriptexecution.config

import com.mdeo.execution.common.config.BaseExecutionConfig
import com.mdeo.execution.common.config.DatabaseConfig
import com.mdeo.execution.common.config.ExecutionServiceConfig

/**
 * Main application configuration for the script execution service.
 * Implements ExecutionServiceConfig from execution-common for standardized configuration.
 *
 * @property serverPort The port number on which the server will listen
 * @property database Database connection configuration
 * @property backendApiUrl Base URL for the backend API to fetch typed ASTs and JWKS
 * @property jwtIssuer JWT issuer identifier
 * @property executionTimeoutMs Timeout for script execution in milliseconds
 * @property sessionConnectTimeoutMillis How long dialling a plugin session may take
 */
data class AppConfig(
    override val serverPort: Int,
    override val database: DatabaseConfig,
    override val backendApiUrl: String,
    override val jwtIssuer: String,
    val executionTimeoutMs: Long,
    val sessionConnectTimeoutMillis: Long
) : ExecutionServiceConfig {
    companion object {
        private const val DEFAULT_EXECUTION_TIMEOUT_MS = 30000L

        /**
         * Loads application configuration from environment variables with fallback defaults.
         *
         * @return A fully configured AppConfig instance
         */
        fun load(): AppConfig {
            val baseConfig = BaseExecutionConfig.fromEnvironment()

            return AppConfig(
                serverPort = baseConfig.serverPort,
                database = baseConfig.database,
                backendApiUrl = baseConfig.backendApiUrl,
                jwtIssuer = baseConfig.jwtIssuer,
                executionTimeoutMs = System.getenv("EXECUTION_TIMEOUT_MS")?.toLongOrNull()
                    ?: DEFAULT_EXECUTION_TIMEOUT_MS,
                sessionConnectTimeoutMillis = baseConfig.sessionConnectTimeoutMillis
            )
        }
    }
}
