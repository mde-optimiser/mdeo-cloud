package com.mdeo.execution.common.config

import com.mdeo.common.transport.SessionClient

/**
 * Base configuration interface for execution services.
 * Defines the common configuration properties required by all execution services.
 * Service-specific timeout settings are managed by each service individually.
 */
interface ExecutionServiceConfig {
    /**
     * The port number on which the server will listen.
     */
    val serverPort: Int

    /**
     * Database connection configuration.
     */
    val database: DatabaseConfig

    /**
     * Base URL for the backend API to fetch data and JWKS.
     */
    val backendApiUrl: String

    /**
     * JWT issuer identifier for token validation.
     */
    val jwtIssuer: String
}

/**
 * Default implementation of execution service configuration.
 * Provides common configuration loading from environment variables.
 *
 * @property serverPort The port number on which the server will listen
 * @property database Database connection configuration
 * @property backendApiUrl Base URL for the backend API
 * @property jwtIssuer JWT issuer identifier
 * @property sessionConnectTimeoutMillis How long dialling a plugin session may take before it
 *           counts as unreachable
 */
data class BaseExecutionConfig(
    override val serverPort: Int,
    override val database: DatabaseConfig,
    override val backendApiUrl: String,
    override val jwtIssuer: String,
    val sessionConnectTimeoutMillis: Long = SessionClient.DEFAULT_CONNECT_TIMEOUT_MILLIS
) : ExecutionServiceConfig {
    companion object {
        /**
         * Loads base configuration from environment variables with fallback defaults.
         *
         * The session connect timeout is read from `SERVICE_CONNECT_TIMEOUT_SECONDS`, the variable
         * the backend reads for the same purpose, so one setting covers every connection to a service.
         *
         * @param defaultPort Default port if SERVER_PORT environment variable is not set
         * @param defaultBackendUrl Default backend API URL
         * @param defaultJwtIssuer Default JWT issuer
         * @return A fully configured BaseExecutionConfig instance
         */
        fun fromEnvironment(
            defaultPort: Int = 8080,
            defaultBackendUrl: String = "http://localhost:8080/api",
            defaultJwtIssuer: String = "mdeo-platform"
        ): BaseExecutionConfig {
            return BaseExecutionConfig(
                serverPort = System.getenv("SERVER_PORT")?.toIntOrNull() ?: defaultPort,
                database = DatabaseConfig.fromEnvironment(),
                backendApiUrl = System.getenv("BACKEND_API_URL") ?: defaultBackendUrl,
                jwtIssuer = System.getenv("JWT_ISSUER") ?: defaultJwtIssuer,
                sessionConnectTimeoutMillis = System.getenv("SERVICE_CONNECT_TIMEOUT_SECONDS")
                    ?.toLongOrNull()?.takeIf { it > 0 }?.let { it * 1000 }
                    ?: SessionClient.DEFAULT_CONNECT_TIMEOUT_MILLIS
            )
        }
    }
}
