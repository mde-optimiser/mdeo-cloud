package com.mdeo.optimizerexecution.config

import com.mdeo.execution.common.config.BaseExecutionConfig
import com.mdeo.execution.common.config.DatabaseConfig
import com.mdeo.execution.common.config.ExecutionServiceConfig

/**
 * Application configuration for the optimizer execution service.
 *
 * Extends [ExecutionServiceConfig] with multi-node optimizer settings loaded from
 * environment variables.
 *
 * @param serverPort HTTP port the service listens on.
 * @param database JDBC database connection settings.
 * @param backendApiUrl Base URL for the platform backend API.
 * @param jwtIssuer Expected JWT issuer for authentication.
 * @param scriptTimeoutMs Default per-script evaluation timeout in milliseconds.
 * @param transformationTimeoutMs Default per-transformation execution timeout in milliseconds.
 * @param nodeId Zero-based identifier of this node in a multi-node deployment.
 * @param peers Base URLs of peer optimizer-execution instances (excluding self).
 * @param workerThreads Number of concurrent worker threads for local evaluation.
 * @param nodeUrl The publicly reachable base URL of this node (e.g. `http://node-0:8080`).
 *        Used as the `baseUrl` when creating a [com.mdeo.optimizerexecution.worker.WorkerClient]
 *        for the local node so that peer nodes can contact this instance directly in
 *        container/federated deployments where `localhost` is not routable.
 * @param sessionConnectTimeoutMillis How long dialling a plugin session may take before it
 *        counts as unreachable.
 */
data class AppConfig(
    override val serverPort: Int,
    override val database: DatabaseConfig,
    override val backendApiUrl: String,
    override val jwtIssuer: String,
    val scriptTimeoutMs: Long,
    val transformationTimeoutMs: Long,
    val nodeId: Int,
    val peers: List<String>,
    val workerThreads: Int,
    val nodeUrl: String,
    val sessionConnectTimeoutMillis: Long
) : ExecutionServiceConfig {
    companion object {
        private const val DEFAULT_NODE_ID = 0
        private const val DEFAULT_WORKER_THREADS = 1
        private const val DEFAULT_SCRIPT_TIMEOUT_MS = 1000L
        private const val DEFAULT_TRANSFORMATION_TIMEOUT_MS = 1000L
        private const val DEFAULT_NODE_URL = "http://localhost:8080"

        /**
         * Load application configuration from environment variables.
         *
         * Multi-node settings are read from:
         * - `NODE_ID` – integer node identifier (default [DEFAULT_NODE_ID])
         * - `PEERS` – comma-separated list of peer base URLs (default empty)
         * - `WORKER_THREADS` – local worker thread count (default [DEFAULT_WORKER_THREADS])
         * - `NODE_URL` – publicly reachable base URL of this node (default [DEFAULT_NODE_URL])
         * - `SCRIPT_TIMEOUT_MS` – default per-script timeout in milliseconds
         * - `TRANSFORMATION_TIMEOUT_MS` – default per-transformation timeout in milliseconds
         *
         * @return A fully populated [AppConfig].
         */
        fun load(): AppConfig {
            val baseConfig = BaseExecutionConfig.fromEnvironment()

            val nodeId = System.getenv("NODE_ID")?.toIntOrNull() ?: DEFAULT_NODE_ID
            val peers = System.getenv("PEERS")
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?: emptyList()
            val workerThreads = System.getenv("WORKER_THREADS")?.toIntOrNull() ?: DEFAULT_WORKER_THREADS
            val nodeUrl = System.getenv("NODE_URL") ?: DEFAULT_NODE_URL

            return AppConfig(
                serverPort = baseConfig.serverPort,
                database = baseConfig.database,
                backendApiUrl = baseConfig.backendApiUrl,
                jwtIssuer = baseConfig.jwtIssuer,
                scriptTimeoutMs = System.getenv("SCRIPT_TIMEOUT_MS")?.toLongOrNull()
                    ?: DEFAULT_SCRIPT_TIMEOUT_MS,
                transformationTimeoutMs = System.getenv("TRANSFORMATION_TIMEOUT_MS")?.toLongOrNull()
                    ?: DEFAULT_TRANSFORMATION_TIMEOUT_MS,
                nodeId = nodeId,
                peers = peers,
                workerThreads = workerThreads,
                nodeUrl = nodeUrl,
                sessionConnectTimeoutMillis = baseConfig.sessionConnectTimeoutMillis
            )
        }
    }
}
