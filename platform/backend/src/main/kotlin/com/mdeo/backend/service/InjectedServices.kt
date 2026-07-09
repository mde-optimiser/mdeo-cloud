package com.mdeo.backend.service

import com.mdeo.backend.config.AppConfig

/**
 * Interface defining all injectable services and configuration.
 * 
 * This interface is implemented by a lazy-delegated object in Application.kt,
 * allowing services to access each other and configuration without explicit
 * constructor parameters, while supporting circular dependencies through lazy initialization.
 * 
 * Services implement this interface using Kotlin's delegation pattern:
 * `class MyService(services: InjectedServices) : InjectedServices by services`
 */
interface InjectedServices {
    /**
     * Application configuration containing all configuration sections.
     */
    val config: AppConfig

    /**
     * Service for managing user accounts and authentication.
     */
    val userService: UserService

    /**
     * Service for managing plugins and their associations with projects.
     */
    val pluginService: PluginService

    /**
     * Service for managing files and directories within projects.
     */
    val fileService: FileService

    /**
     * Service for managing projects and project ownership.
     */
    val projectService: ProjectService

    /**
     * Service for managing file metadata within projects.
     */
    val metadataService: MetadataService

    /**
     * Service for JWT token generation and verification.
     */
    val jwtService: JwtService

    /**
     * Service for computing and caching file data with dependency tracking.
     */
    val fileDataService: FileDataService

    /**
     * Service for managing executions within projects.
     */
    val executionService: ExecutionService

    /**
     * Service for managing WebSocket connections and broadcasting notifications.
     */
    val webSocketNotificationService: WebSocketNotificationService

    /**
     * Service for executing language plugin requests.
     */
    val languagePluginRequestService: LanguagePluginRequestService
}
