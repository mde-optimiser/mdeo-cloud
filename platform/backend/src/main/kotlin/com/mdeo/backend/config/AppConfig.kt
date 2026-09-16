package com.mdeo.backend.config

import java.util.concurrent.TimeUnit

/**
 * Main application configuration containing all configuration sections.
 *
 * @property serverPort The port number on which the server will listen
 * @property database Database connection configuration
 * @property session Session management configuration
 * @property cors Cross-Origin Resource Sharing configuration
 * @property defaultAdmin Default administrator account configuration
 * @property defaultNewUserCanCreateProject Whether newly registered users can create projects by default
 * @property plugin Plugin system configuration
 * @property timeouts How long the backend waits on the services it calls
 */
data class AppConfig(
    val serverPort: Int,
    val database: DatabaseConfig,
    val session: SessionConfig,
    val cors: CorsConfig,
    val defaultAdmin: DefaultAdminConfig,
    val defaultNewUserCanCreateProject: Boolean,
    val plugin: PluginConfig,
    val jwt: JwtConfig,
    val fileData: FileDataConfig,
    val timeouts: TimeoutConfig
) {
    companion object {
        /**
         * Loads application configuration from environment variables with fallback defaults.
         *
         * @return A fully configured AppConfig instance
         */
        fun load(): AppConfig {
            val environment = System.getenv("ENVIRONMENT") ?: "development"
            val isProduction = environment.equals("production", ignoreCase = true)
            
            return AppConfig(
                serverPort = System.getenv("SERVER_PORT")?.toIntOrNull() ?: 8080,
                database = DatabaseConfig(
                    url = System.getenv("DATABASE_URL") 
                        ?: "jdbc:postgresql://localhost:5432/mdeo",
                    user = System.getenv("DATABASE_USER") ?: "mdeo",
                    password = System.getenv("DATABASE_PASSWORD") ?: "mdeo",
                    maxPoolSize = System.getenv("DATABASE_MAX_POOL_SIZE")?.toIntOrNull() ?: 10
                ),
                session = SessionConfig(
                    maxIdleSeconds = System.getenv("SESSION_MAX_IDLE_SECONDS")?.toLongOrNull()
                        ?: TimeUnit.DAYS.toSeconds(1),
                    maxAbsoluteSeconds = System.getenv("SESSION_MAX_ABSOLUTE_SECONDS")?.toLongOrNull()
                        ?: TimeUnit.DAYS.toSeconds(30),
                    cookieSecure = System.getenv("COOKIE_SECURE")?.toBoolean() ?: true,
                    encryptionKey = System.getenv("SESSION_ENCRYPTION_KEY")
                        ?: "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff",
                    sameSite = System.getenv("COOKIE_SAMESITE") ?: "Strict"
                ),
                cors = CorsConfig(
                    allowedHosts = run {
                        val corsHosts = System.getenv("CORS_ALLOWED_HOSTS")
                            ?.split(",")
                            ?.map { it.trim() }
                            ?: listOf("localhost:4242", "localhost:5173", "127.0.0.1:4242", "127.0.0.1:5173")
                        
                        if (isProduction) {
                            val hasLocalhostOrigins = corsHosts.any { host ->
                                host.contains("localhost") || host.contains("127.0.0.1")
                            }
                            require(!hasLocalhostOrigins) {
                                "Production environment detected but CORS configuration contains localhost origins. " +
                                "Please set CORS_ALLOWED_HOSTS environment variable with production domains only."
                            }
                        }
                        
                        corsHosts
                    }
                ),
                defaultAdmin = DefaultAdminConfig(
                    username = System.getenv("ADMIN_USERNAME") ?: "admin",
                    password = System.getenv("ADMIN_PASSWORD") ?: "admin"
                ),
                defaultNewUserCanCreateProject =
                    System.getenv("DEFAULT_NEW_USER_CREATE_PROJECT")?.toBoolean() ?: false,
                plugin = PluginConfig(
                    baseUrl = System.getenv("PLUGIN_BASE_URL"),
                    internalBaseUrl = System.getenv("INTERNAL_PLUGIN_BASE_URL") 
                        ?: System.getenv("PLUGIN_BASE_URL"),
                    forceHttp1 = System.getenv("PLUGIN_FORCE_HTTP1")?.toBoolean() ?: false,
                    defaultPluginUrls = System.getenv("DEFAULT_PLUGIN_URLS")
                        ?.split(",")
                        ?.map { it.trim() }
                        ?.filter { it.isNotEmpty() }
                        ?: emptyList()
                ),
                jwt = JwtConfig(
                    expirationSeconds = System.getenv("JWT_EXPIRATION_SECONDS")?.toLongOrNull()
                        ?: TimeUnit.HOURS.toSeconds(1),
                    executionExpirationSeconds = System.getenv("JWT_EXECUTION_EXPIRATION_SECONDS")?.toLongOrNull()
                        ?: TimeUnit.DAYS.toSeconds(7),
                    issuer = System.getenv("JWT_ISSUER") ?: "mdeo-platform",
                    privateKey = System.getenv("JWT_PRIVATE_KEY"),
                    publicKey = System.getenv("JWT_PUBLIC_KEY")
                ),
                fileData = FileDataConfig.load(),
                timeouts = TimeoutConfig.load()
            )
        }
    }
}

/**
 * Database connection configuration.
 *
 * @property url The JDBC connection URL
 * @property user The database username
 * @property password The database password
 * @property maxPoolSize Maximum number of connections in the pool
 */
data class DatabaseConfig(
    val url: String,
    val user: String,
    val password: String,
    val maxPoolSize: Int
)

/**
 * Session management configuration.
 *
 * @property maxIdleSeconds Idle timeout in seconds – the cookie Max-Age is refreshed to this
 *   value on every authenticated request (sliding window).
 * @property maxAbsoluteSeconds Hard upper bound on session lifetime in seconds, measured from
 *   [UserSession.createdAt]. Once this threshold is exceeded the session is invalidated
 *   regardless of recent activity.
 * @property cookieSecure Whether to use secure cookies (HTTPS only)
 * @property encryptionKey The key used for session encryption/signing
 * @property sameSite The SameSite attribute for session cookies
 */
data class SessionConfig(
    val maxIdleSeconds: Long,
    val maxAbsoluteSeconds: Long,
    val cookieSecure: Boolean,
    val encryptionKey: String,
    val sameSite: String
)

/**
 * Cross-Origin Resource Sharing (CORS) configuration.
 *
 * @property allowedHosts List of allowed host origins
 */
data class CorsConfig(
    val allowedHosts: List<String>
)

/**
 * Default administrator account configuration.
 *
 * @property username The default admin username
 * @property password The default admin password
 */
data class DefaultAdminConfig(
    val username: String,
    val password: String
)

/**
 * Plugin system configuration.
 *
 * @property baseUrl Base URL for resolving relative plugin URLs (public URL exposed to frontend)
 * @property internalBaseUrl Base URL for internal backend-to-plugin communication
 * @property forceHttp1 Whether to force HTTP/1.1 for plugin requests
 * @property defaultPluginUrls List of plugin URLs to initialize as default plugins at startup
 */
data class PluginConfig(
    val baseUrl: String,
    val internalBaseUrl: String,
    val forceHttp1: Boolean,
    val defaultPluginUrls: List<String> = emptyList()
)

/**
 * JWT configuration for plugin authentication.
 *
 * @property expirationSeconds JWT token expiration time in seconds (default 1 hour)
 * @property executionExpirationSeconds Expiration time in seconds for the token handed to an execution
 *   node when an execution is submitted (default 7 days). The node keeps this token for the whole run
 *   and needs it to report the terminal state, so it must outlive the longest expected execution.
 * @property issuer JWT issuer identifier
 * @property privateKey Base64-encoded RSA private key (PKCS8 format), optional - will be generated if not provided
 * @property publicKey Base64-encoded RSA public key (X.509 format), optional - will be generated if not provided
 */
data class JwtConfig(
    val expirationSeconds: Long,
    val executionExpirationSeconds: Long,
    val issuer: String,
    val privateKey: String? = null,
    val publicKey: String? = null
)

/**
 * File data computation configuration.
 *
 * Two different lifetimes: how long the backend waits for a plugin to answer, and how long a
 * computation stays recorded as running — which is how long the token handed to the plugin keeps
 * being accepted. They share a default but are separate settings, so a deployment can let tokens
 * outlive a slow HTTP answer without waiting longer for it, or the other way round.
 *
 * @property computationTimeoutSeconds How long the backend waits for a plugin to compute file data
 *           (`FILE_DATA_COMPUTATION_TIMEOUT_SECONDS`, default 5 minutes)
 * @property computationBindingSeconds How long a computation, and the token bound to it, stays live
 *           before it is treated as abandoned (`FILE_DATA_COMPUTATION_BINDING_SECONDS`, default
 *           [computationTimeoutSeconds])
 */
data class FileDataConfig(
    val computationTimeoutSeconds: Long,
    val computationBindingSeconds: Long = computationTimeoutSeconds
) {
    companion object {
        /**
         * Reads the configuration from the environment.
         *
         * @param environment Where to read from
         * @return The configuration
         */
        fun load(environment: Map<String, String> = System.getenv()): FileDataConfig {
            val timeout = environment["FILE_DATA_COMPUTATION_TIMEOUT_SECONDS"]?.toLongOrNull()
                ?: TimeUnit.MINUTES.toSeconds(5)
            return FileDataConfig(
                computationTimeoutSeconds = timeout,
                computationBindingSeconds = environment["FILE_DATA_COMPUTATION_BINDING_SECONDS"]?.toLongOrNull() ?: timeout
            )
        }
    }
}

/**
 * How long the backend waits on the services it calls, in one place.
 *
 * Sessions have no timeout here: a call on a session can take as long as the work takes, and
 * liveness comes from keepalives instead.
 *
 * @property pluginRequestSeconds A one-shot request to a language plugin
 *           (`PLUGIN_REQUEST_TIMEOUT_SECONDS`, default 5 minutes)
 * @property executionStartSeconds Starting an execution on a plugin or execution service
 *           (`EXECUTION_START_TIMEOUT_SECONDS`, default 5 minutes)
 * @property executionReadSeconds Reading an execution's state, summary or files, or cancelling or
 *           deleting it (`EXECUTION_READ_TIMEOUT_SECONDS`, default 1 minute)
 * @property manifestFetchSeconds Fetching a plugin's manifest (`PLUGIN_MANIFEST_TIMEOUT_SECONDS`,
 *           default 30 seconds)
 * @property connectSeconds Opening a connection to any service (`SERVICE_CONNECT_TIMEOUT_SECONDS`,
 *           default 10 seconds)
 */
data class TimeoutConfig(
    val pluginRequestSeconds: Long = TimeUnit.MINUTES.toSeconds(5),
    val executionStartSeconds: Long = TimeUnit.MINUTES.toSeconds(5),
    val executionReadSeconds: Long = TimeUnit.MINUTES.toSeconds(1),
    val manifestFetchSeconds: Long = 30,
    val connectSeconds: Long = 10
) {
    companion object {
        /**
         * Reads the configuration from the environment, falling back to the defaults.
         *
         * @param environment Where to read from
         * @return The configuration
         */
        fun load(environment: Map<String, String> = System.getenv()): TimeoutConfig {
            val defaults = TimeoutConfig()
            fun seconds(name: String, default: Long) = environment[name]?.toLongOrNull()?.takeIf { it > 0 } ?: default
            return TimeoutConfig(
                pluginRequestSeconds = seconds("PLUGIN_REQUEST_TIMEOUT_SECONDS", defaults.pluginRequestSeconds),
                executionStartSeconds = seconds("EXECUTION_START_TIMEOUT_SECONDS", defaults.executionStartSeconds),
                executionReadSeconds = seconds("EXECUTION_READ_TIMEOUT_SECONDS", defaults.executionReadSeconds),
                manifestFetchSeconds = seconds("PLUGIN_MANIFEST_TIMEOUT_SECONDS", defaults.manifestFetchSeconds),
                connectSeconds = seconds("SERVICE_CONNECT_TIMEOUT_SECONDS", defaults.connectSeconds)
            )
        }
    }
}
