package com.mdeo.backend

import com.mdeo.backend.config.AppConfig
import com.mdeo.backend.config.configureCors
import com.mdeo.backend.config.configureSerialization
import com.mdeo.backend.config.configureStatusPages
import com.mdeo.backend.database.DatabaseFactory
import com.mdeo.backend.plugins.*
import com.mdeo.backend.git.GitRepositoryService
import com.mdeo.backend.git.ssh.GitSshCommandFactory
import com.mdeo.backend.git.ssh.GitSshPublickeyAuthenticator
import com.mdeo.backend.git.ssh.GitSshServer
import com.mdeo.backend.routes.*
import com.mdeo.backend.service.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import io.ktor.server.websocket.*
import io.ktor.util.*
import kotlinx.coroutines.*
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
    
    /**
     * Lazy-initialized service container implementing InjectedServices.
     * Uses lazy delegation to handle circular dependencies between services.
     */
    val services = object : InjectedServices {
        override val config: AppConfig = appConfig
        override val userService: UserService by lazy { UserService(this) }
        override val pluginService: PluginService by lazy { PluginService(this) }
        override val fileService: FileService by lazy { FileService(this) }
        override val projectService: ProjectService by lazy { ProjectService(this) }
        override val metadataService: MetadataService by lazy { MetadataService(this) }
        override val jwtService: JwtService by lazy { JwtService(this) }
        override val tokenBindingService: TokenBindingService by lazy { TokenBindingService(this) }
        override val fileDataService: FileDataService by lazy { FileDataService(this) }
        override val executionService: ExecutionService by lazy { ExecutionService(this) }
        override val webSocketNotificationService: WebSocketNotificationService by lazy { WebSocketNotificationService() }
        override val languagePluginRequestService: LanguagePluginRequestService by lazy { LanguagePluginRequestService(this) }
        override val authRateLimiter: AuthRateLimiter by lazy { AuthRateLimiter() }
        override val personalAccessTokenService: PersonalAccessTokenService by lazy { PersonalAccessTokenService(this) }
        override val sshKeyService: SshKeyService by lazy { SshKeyService(this) }
        override val oAuthCodeService: OAuthCodeService by lazy { OAuthCodeService() }
        val gitRepositoryService: GitRepositoryService by lazy {
            GitRepositoryService(
                fileService,
                pluginService,
                appConfig.git.maxPushPackSizeBytes,
                appConfig.git.maxProjectStorageBytes
            )
        }
    }
    
    services.jwtService.init()

    runBlocking {
        services.userService.createDefaultAdmin(appConfig.defaultAdmin.username, appConfig.defaultAdmin.password)
        services.pluginService.initializeDefaultPlugins(appConfig.plugin.defaultPluginUrls)
    }

    // A separate listener from the Ktor/Netty one below, authenticated by
    // SSH public key instead of HTTP basic credentials, serving the same
    // two git operations gitRoutes serves over HTTP. start() is
    // non-blocking (MINA SSHD binds its own I/O thread), so this can run
    // here rather than needing to be hoisted out to main().
    val gitSshServer = GitSshServer(
        port = appConfig.git.sshPort,
        hostKeyPem = appConfig.git.sshHostKey,
        publickeyAuthenticator = GitSshPublickeyAuthenticator(services.sshKeyService),
        commandFactory = GitSshCommandFactory(
            services.gitRepositoryService,
            services.projectService,
            services.sshKeyService,
            services.webSocketNotificationService,
            appConfig.git.maxPushPackSizeBytes
        )
    )
    gitSshServer.start()

    monitor.subscribe(ApplicationStopped) {
        gitSshServer.stop()
        DatabaseFactory.close()
    }
    
    install(CallLogging) {
        level = Level.INFO
    }
    
    install(Sessions) {
        val secretSignKey = appConfig.session.encryptionKey.hexToByteArray()
        cookie<UserSession>("MDEO_SESSION") {
            cookie.path = "/"
            cookie.httpOnly = true
            cookie.secure = appConfig.session.cookieSecure
            cookie.maxAgeInSeconds = appConfig.session.maxIdleSeconds
            cookie.extensions["SameSite"] = appConfig.session.sameSite
            transform(SessionTransportTransformerMessageAuthentication(secretSignKey))
        }
    }
    
    configureSerialization()
    configureCors(appConfig.cors)
    configureStatusPages()
    configureAuthentication(
        services.jwtService,
        services.userService,
        services.tokenBindingService,
        appConfig.session
    )
    
    install(WebSockets) {
        pingPeriodMillis = 30_000
        timeoutMillis = 60_000
    }
    
    routing {
        healthRoutes()
        gitConfigRoutes(
            appConfig.git.sshPort,
            appConfig.git.sshPublicHost,
            appConfig.git.sshPubliclyReachable,
            appConfig.git.oauthAuthorizePath,
            appConfig.git.oauthTokenPath
        )
        authRoutes(services.userService, services.jwtService, services.authRateLimiter, appConfig.trustedProxyHops)
        // Unauthenticated by design: the browser GCM opens has no session
        // yet, and signing in is the whole point of the page.
        oauthRoutes(
            services.personalAccessTokenService,
            services.oAuthCodeService,
            appConfig.git.oauthClientId,
            java.time.Duration.ofSeconds(appConfig.git.oauthCodeTtlSeconds),
            appConfig.session.maxAbsoluteSeconds,
            appConfig.git.oauthTokenPath,
            appConfig.git.oauthAuthorizePath
        )

        // Outside the session and JWT blocks on purpose: git clients cannot
        // present either, so these routes authenticate the HTTP basic
        // credentials themselves.
        gitRoutes(
            services.gitRepositoryService,
            services.projectService,
            services.userService,
            services.personalAccessTokenService,
            services.authRateLimiter,
            services.webSocketNotificationService,
            appConfig.git.maxPushPackSizeBytes,
            appConfig.trustedProxyHops
        )

        authenticate(AUTH_SESSION, AUTH_JWT, optional = true) {
            fileRoutes(services.fileService, services.projectService)
            fileDataRoutes(services.fileDataService, services.projectService, services.jwtService)
            languagePluginRequestRoutes(services.languagePluginRequestService, services.projectService, services.jwtService)
            executionStateRoutes(services.executionService, services.jwtService)
        }

        authenticate(AUTH_SESSION) {
            webSocketRoutes(
                services.webSocketNotificationService,
                services.projectService,
                services.fileService,
                services.metadataService,
                services.executionService
            )
            projectRoutes(services.projectService)
            metadataRoutes(services.metadataService, services.projectService)
            pluginRoutes(services.pluginService, services.projectService)
            adminRoutes(services.userService)
            userRoutes(services.userService, services.projectService)
            executionRoutes(services.executionService, services.projectService)
            patRoutes(services.personalAccessTokenService)
            sshKeyRoutes(services.sshKeyService)
        }
    }
}
