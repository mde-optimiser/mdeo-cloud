package com.mdeo.backend.routes

import com.mdeo.backend.plugins.getUserSession
import com.mdeo.backend.plugins.isAdmin
import com.mdeo.backend.service.*
import com.mdeo.common.model.ApiError
import com.mdeo.common.model.ErrorCodes
import com.mdeo.common.transport.ExecutionWsProtocol
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.util.*

private val logger = LoggerFactory.getLogger("WebSocketRoutes")

/**
 * Configures WebSocket routes for real-time notifications and file system operations.
 * Requires session authentication to establish a connection.
 *
 * @param webSocketService Service for managing WebSocket connections
 * @param projectService Service for validating project access
 * @param fileService Service for file CRUD operations
 * @param metadataService Service for file metadata operations
 * @param executionService Service for execution result operations
 */
fun Route.webSocketRoutes(
    webSocketService: WebSocketNotificationService,
    projectService: ProjectService,
    fileService: FileService,
    metadataService: MetadataService,
    executionService: ExecutionService
) {
    webSocket("/api/ws") {
        val session = call.getUserSession()
        if (session == null) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Not authenticated"))
            return@webSocket
        }
        
        val connectionId = UUID.randomUUID().toString()
        val handler = WebSocketMessageHandler(
            connectionId = connectionId,
            userId = session.userId,
            isGlobalAdmin = call.isAdmin(),
            webSocketService = webSocketService,
            projectService = projectService,
            fileService = fileService,
            metadataService = metadataService,
            executionService = executionService,
            session = this
        )
        
        handler.handleConnection()
    }
}

/**
 * Handler for processing WebSocket messages for a single connection.
 * Manages the connection lifecycle, message processing, and delegates
 * file system operations to [WebSocketFileHandler].
 *
 * @property connectionId Unique identifier for this connection
 * @property userId The authenticated user's ID
 * @property isGlobalAdmin Whether the user has global admin permission
 * @property webSocketService Service for managing subscriptions
 * @property projectService Service for validating project access
 * @property fileService Service for file CRUD operations
 * @property metadataService Service for file metadata operations
 * @property executionService Service for execution result operations
 * @property session The WebSocket session
 */
private class WebSocketMessageHandler(
    private val connectionId: String,
    private val userId: String,
    private val isGlobalAdmin: Boolean,
    private val webSocketService: WebSocketNotificationService,
    private val projectService: ProjectService,
    private val fileService: FileService,
    private val metadataService: MetadataService,
    private val executionService: ExecutionService,
    private val session: DefaultWebSocketServerSession
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val fileHandler = WebSocketFileHandler(
        connectionId = connectionId,
        userId = userId,
        isGlobalAdmin = isGlobalAdmin,
        fileService = fileService,
        metadataService = metadataService,
        projectService = projectService,
        webSocketService = webSocketService
    )
    private val executionHandler = WebSocketExecutionHandler(
        connectionId = connectionId,
        userId = userId,
        isGlobalAdmin = isGlobalAdmin,
        executionService = executionService,
        projectService = projectService,
        webSocketService = webSocketService
    )
    
    /**
     * Main connection handler loop.
     * Registers the connection, processes incoming messages, and cleans up on disconnect.
     */
    suspend fun handleConnection() {
        webSocketService.registerConnection(connectionId, session, userId)
        
        try {
            processIncomingMessages()
        } finally {
            webSocketService.removeConnection(connectionId)
        }
    }
    
    /**
     * Processes all incoming WebSocket messages until the connection closes.
     */
    private suspend fun processIncomingMessages() {
        for (frame in session.incoming) {
            when (frame) {
                is Frame.Text -> handleTextFrame(frame)
                is Frame.Close -> return
                else -> {}
            }
        }
    }
    
    /**
     * Handles a text frame by parsing and processing the message.
     *
     * @param frame The text frame to process
     */
    private suspend fun handleTextFrame(frame: Frame.Text) {
        try {
            val text = frame.readText()
            processMessage(text)
        } catch (e: Exception) {
            logger.warn("Failed to process WebSocket message", e)
        }
    }
    
    /**
     * Parses and routes a message to the appropriate handler based on type.
     *
     * @param text The raw JSON message text
     */
    private suspend fun processMessage(text: String) {
        val jsonElement = json.parseToJsonElement(text)
        val type = jsonElement.jsonObject["messageType"]?.jsonPrimitive?.content

        // Execution result requests use the protocol shared with the plugin and execution
        // services rather than this connection's own message types, so they are decoded with
        // that protocol's codec. They are also handled off the read loop: a bulk load streams
        // a whole result set and would otherwise hold up the file operations the editor needs
        // to keep saving in the meantime.
        if (type != null && type.startsWith("exec/")) {
            val request = ExecutionWsProtocol.decode(text)
            session.launch { executionHandler.handle(request) }
            return
        }

        when (type) {
            "event/subscribe" -> handleSubscribe(jsonElement)
            "event/unsubscribe" -> handleUnsubscribe(jsonElement)
            "init/request" -> handleInitRequest(jsonElement)
            "file/subscribeFiles" -> fileHandler.handleSubscribeFiles(json.decodeFromJsonElement(jsonElement))
            "file/loadProject" -> fileHandler.handleLoadProject(json.decodeFromJsonElement(jsonElement))
            "file/readFile" -> fileHandler.handleReadFile(json.decodeFromJsonElement(jsonElement))
            "file/writeFile" -> fileHandler.handleWriteFile(json.decodeFromJsonElement(jsonElement))
            "file/mkdir" -> fileHandler.handleMkdir(json.decodeFromJsonElement(jsonElement))
            "file/readdir" -> fileHandler.handleReaddir(json.decodeFromJsonElement(jsonElement))
            "file/stat" -> fileHandler.handleStat(json.decodeFromJsonElement(jsonElement))
            "file/deleteFile" -> fileHandler.handleDelete(json.decodeFromJsonElement(jsonElement))
            "file/rename" -> fileHandler.handleRename(json.decodeFromJsonElement(jsonElement))
            "file/getFileVersion" -> fileHandler.handleGetFileVersion(json.decodeFromJsonElement(jsonElement))
            "file/readMetadata" -> fileHandler.handleReadMetadata(json.decodeFromJsonElement(jsonElement))
            "file/writeMetadata" -> fileHandler.handleWriteMetadata(json.decodeFromJsonElement(jsonElement))
            else -> logger.warn("Unknown message type: $type")
        }
    }
    
    /**
     * Handles a subscription request after validating project access.
     *
     * @param jsonElement The parsed subscribe message
     */
    private suspend fun handleSubscribe(jsonElement: JsonElement) {
        val message = json.decodeFromJsonElement<SubscribeMessage>(jsonElement)
        val projectId = parseProjectId(message.projectId) ?: return
        
        if (!validateProjectAccess(projectId)) {
            logger.warn("User $userId attempted to subscribe to unauthorized project $projectId")
            return
        }
        
        webSocketService.subscribe(connectionId, projectId)
    }
    
    /**
     * Handles an unsubscription request.
     *
     * @param jsonElement The parsed unsubscribe message
     */
    private suspend fun handleUnsubscribe(jsonElement: JsonElement) {
        val message = json.decodeFromJsonElement<UnsubscribeMessage>(jsonElement)
        val projectId = parseProjectId(message.projectId) ?: return

        webSocketService.unsubscribe(connectionId, projectId)
    }

    /**
     * Handles an init request: validates project access, caches file-operation permissions,
     * subscribes the connection to execution-state events, and sends an [InitReplyMessage]
     * with project settings. Sends [FileErrorMessage] on failure.
     *
     * @param jsonElement The parsed init request message
     */
    private suspend fun handleInitRequest(jsonElement: JsonElement) {
        val message = json.decodeFromJsonElement<InitRequestMessage>(jsonElement)
        val projectId = parseProjectId(message.projectId) ?: run {
            logger.warn("Invalid project ID in init/request: ${message.projectId}")
            return
        }
        val userUuid = try { UUID.fromString(userId) } catch (_: Exception) { null } ?: run {
            logger.warn("Invalid user ID in init/request: $userId")
            return
        }

        val hasRead = projectService.hasProjectPermission(projectId, userUuid, isGlobalAdmin, ProjectPermission.READ)
        if (!hasRead) {
            logger.warn("User $userId denied access to project $projectId in init/request")
            webSocketService.sendMessage(
                connectionId,
                FileErrorMessage(message.requestId, ApiError(ErrorCodes.FORBIDDEN, "Access denied to project $projectId"))
            )
            return
        }

        val hasWrite = projectService.hasProjectPermission(projectId, userUuid, isGlobalAdmin, ProjectPermission.WRITE)
        val hasExecute = projectService.hasProjectPermission(projectId, userUuid, isGlobalAdmin, ProjectPermission.EXECUTE)

        webSocketService.cachePermission(connectionId, projectId, hasRead, hasWrite)
        webSocketService.subscribe(connectionId, projectId)

        val project = projectService.getProject(projectId) ?: run {
            logger.warn("Project $projectId not found in init/request")
            webSocketService.sendMessage(
                connectionId,
                FileErrorMessage(message.requestId, ApiError(ErrorCodes.NOT_FOUND, "Project $projectId not found"))
            )
            return
        }

        webSocketService.sendMessage(connectionId, InitReplyMessage(message.requestId, project, hasWrite, hasExecute))
    }

    /**
     * Parses a project ID string to UUID.
     *
     * @param projectIdString The project ID as a string
     * @return The parsed UUID or null if invalid
     */
    private fun parseProjectId(projectIdString: String): UUID? {
        return try {
            UUID.fromString(projectIdString)
        } catch (e: Exception) {
            logger.warn("Invalid project ID: $projectIdString")
            null
        }
    }
    
    /**
     * Validates that the user has access to the specified project.
     *
     * @param projectId The project UUID to check
     * @return True if the user has access, false otherwise
     */
    private fun validateProjectAccess(projectId: UUID): Boolean {
        val userUuid = try {
            UUID.fromString(userId)
        } catch (e: Exception) {
            return false
        }
        return projectService.hasProjectPermission(projectId, userUuid, isGlobalAdmin, ProjectPermission.READ)
    }
}
