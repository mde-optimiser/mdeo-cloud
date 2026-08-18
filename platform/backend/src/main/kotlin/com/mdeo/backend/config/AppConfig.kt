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
    val git: GitConfig
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
                fileData = FileDataConfig(
                    computationTimeoutSeconds = System.getenv("FILE_DATA_COMPUTATION_TIMEOUT_SECONDS")?.toLongOrNull()
                        ?: TimeUnit.MINUTES.toSeconds(5)
                ),
                git = GitConfig(
                    maxPushPackSizeBytes = System.getenv("GIT_MAX_PUSH_PACK_SIZE_BYTES")?.toLongOrNull()
                        ?: (100L * 1024 * 1024),
                    maxProjectStorageBytes = System.getenv("GIT_MAX_PROJECT_STORAGE_BYTES")?.toLongOrNull()
                        ?: (2L * 1024 * 1024 * 1024)
                )
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
 * @property computationTimeoutSeconds Timeout in seconds before a computation is considered stale (default 5 minutes)
 */
data class FileDataConfig(
    val computationTimeoutSeconds: Long
)

/**
 * Git server configuration.
 *
 * @property maxPushPackSizeBytes Largest pack a push may send, in bytes (default 100 MiB). JGit
 *   rejects an oversized pack while unpacking it, before any of its content reaches
 *   [com.mdeo.backend.git.GitRepositoryService.applyCommitToProject], so this fails the push
 *   cleanly rather than partway through applying it.
 * @property maxProjectStorageBytes Largest total git object storage one project may hold, in
 *   bytes (default 2 GiB), checked before a push is applied. Nothing else reclaims storage a
 *   rejected push already wrote except the sweep this cap's own rejection triggers, so without
 *   it a write-capable user could otherwise grow the database without bound by pushing large
 *   rejected packs in a loop.
 */
data class GitConfig(
    val maxPushPackSizeBytes: Long,
    val maxProjectStorageBytes: Long
)
