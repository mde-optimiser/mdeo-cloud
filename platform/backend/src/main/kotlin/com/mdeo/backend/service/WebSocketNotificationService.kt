package com.mdeo.backend.service

import com.mdeo.common.model.Execution
import com.mdeo.common.model.FileEntry
import com.mdeo.common.model.Project
import com.mdeo.common.transport.ExecutionWsMessage
import com.mdeo.common.transport.ExecutionWsProtocol
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Base interface for all WebSocket messages exchanged between client and server.
 */
@Serializable
sealed interface WebSocketMessage {
    @SerialName("messageType")
    val type: String
}

/**
 * Message sent by client to subscribe to a project's events.
 *
 * @property projectId The UUID of the project to subscribe to
 */
@Serializable
data class SubscribeMessage(
    val projectId: String
) : WebSocketMessage {
    @SerialName("messageType")
    override val type: String = "event/subscribe"
}

/**
 * Message sent by client to unsubscribe from a project's events.
 *
 * @property projectId The UUID of the project to unsubscribe from
 */
@Serializable
data class UnsubscribeMessage(
    val projectId: String
) : WebSocketMessage {
    @SerialName("messageType")
    override val type: String = "event/unsubscribe"
}

/**
 * Notification message sent by server when an execution state changes.
 *
 * @property execution The updated execution data
 */
@Serializable
data class ExecutionStateChangedMessage(
    val execution: Execution
) : WebSocketMessage {
    @SerialName("messageType")
    override val type: String = "event/executionStateChanged"
}

/**
 * Notification that a project's files changed from outside the connections
 * subscribed to it, most commonly a git push. The platform has no per-file
 * change broadcast at all today (an edit in one browser tab is equally
 * invisible to another), so this is deliberately coarse: it says only that
 * something changed, not what, and a client that cares reloads whatever it
 * currently has open rather than trying to reconcile a diff it never saw.
 *
 * @property projectId The project whose files changed
 */
@Serializable
data class FilesChangedMessage(
    val projectId: String
) : WebSocketMessage {
    @SerialName("messageType")
    override val type: String = "event/filesChanged"
}

// ─── Initialization Messages ──────────────────────────────────────────────────

/**
 * Request to initialize a project session over WebSocket.
 * The server validates the user's access, caches file-operation permissions,
 * subscribes the connection to execution-state events, and replies with
 * [InitReplyMessage] containing project settings.
 *
 * @property requestId Unique ID for correlating the response
 * @property projectId The UUID of the project to initialize
 */
@Serializable
data class InitRequestMessage(
    val requestId: String,
    val projectId: String
) : WebSocketMessage {
    @SerialName("messageType")
    override val type: String = "init/request"
}

/**
 * Reply sent by the server after a successful [InitRequestMessage].
 * Contains project metadata and the current user's effective permissions.
 *
 * @property requestId The ID of the originating init request
 * @property project The project data
 * @property canWrite Whether the authenticated user has write permission
 * @property canExecute Whether the authenticated user has execute permission
 */
@Serializable
data class InitReplyMessage(
    val requestId: String,
    val project: Project,
    val canWrite: Boolean,
    val canExecute: Boolean
) : WebSocketMessage {
    @SerialName("messageType")
    override val type: String = "init/reply"
}


/**
 * Request to authorize file operations for a project.
 * On success the server caches permissions for this connection
 * so individual file operations skip re-verification.
 *
 * @property requestId Unique ID for correlating the response
 * @property projectId The UUID of the project to authorize
 */
@Serializable
data class SubscribeFilesMessage(
    val requestId: String,
    val projectId: String
) : WebSocketMessage {
    @SerialName("messageType")
    override val type: String = "file/subscribeFiles"
}

/**
 * Request to load the full project file tree, contents, and metadata.
 * The server responds with a sequence of streaming messages:
 * [ProjectLoadDirectoryStructureMessage], then multiple [ProjectLoadFileDataMessage]
 * and [ProjectLoadFileMetadataMessage], and finally [ProjectLoadCompleteMessage].
 *
 * @property requestId Unique ID for correlating the streamed responses
 * @property projectId The UUID of the project to load
 */
@Serializable
data class LoadProjectMessage(
    val requestId: String,
    val projectId: String
) : WebSocketMessage {
    @SerialName("messageType")
    override val type: String = "file/loadProject"
}

/**
 * Request to read a file's content and version.
 *
 * @property requestId Unique ID for correlating the response
 * @property projectId The UUID of the project
 * @property path The file path relative to the project root
 */
@Serializable
data class ReadFileMessage(
    val requestId: String,
    val projectId: String,
    val path: String
) : WebSocketMessage {
    @SerialName("messageType")
    override val type: String = "file/readFile"
}

/**
 * Request to write content to a file.
 *
 * @property requestId Unique ID for correlating the response
 * @property projectId The UUID of the project
 * @property path The file path relative to the project root
 * @property content Base64-encoded file content
 * @property create Whether to create the file if it does not exist
 * @property overwrite Whether to overwrite the file if it exists
 * @property expectedVersion When set, the write fails with a version conflict unless the file's
 *   current version matches exactly. Optional so an older client omitting it keeps writing
 *   unconditionally, same as before this existed.
 */
@Serializable
data class WriteFileMessage(
    val requestId: String,
    val projectId: String,
    val path: String,
    val content: String,
    val create: Boolean = true,
    val overwrite: Boolean = false,
    val expectedVersion: Int? = null
) : WebSocketMessage {
    @SerialName("messageType")
    override val type: String = "file/writeFile"
}

/**
 * Request to create a directory.
 *
 * @property requestId Unique ID for correlating the response
 * @property projectId The UUID of the project
 * @property path The directory path to create
 */
@Serializable
data class MkdirMessage(
    val requestId: String,
    val projectId: String,
    val path: String
) : WebSocketMessage {
    @SerialName("messageType")
    override val type: String = "file/mkdir"
}

/**
 * Request to read directory contents.
 *
 * @property requestId Unique ID for correlating the response
 * @property projectId The UUID of the project
 * @property path The directory path to read
 */
@Serializable
data class ReaddirMessage(
    val requestId: String,
    val projectId: String,
    val path: String
) : WebSocketMessage {
    @SerialName("messageType")
    override val type: String = "file/readdir"
}

/**
 * Request to get the file type of a path.
 *
 * @property requestId Unique ID for correlating the response
 * @property projectId The UUID of the project
 * @property path The path to stat
 */
@Serializable
data class StatMessage(
    val requestId: String,
    val projectId: String,
    val path: String
) : WebSocketMessage {
    @SerialName("messageType")
    override val type: String = "file/stat"
}

/**
 * Request to delete a file or directory.
 *
 * @property requestId Unique ID for correlating the response
 * @property projectId The UUID of the project
 * @property path The path to delete
 * @property recursive Whether to recursively delete directory contents
 */
@Serializable
data class DeleteFileMessage(
    val requestId: String,
    val projectId: String,
    val path: String,
    val recursive: Boolean = false
) : WebSocketMessage {
    @SerialName("messageType")
    override val type: String = "file/deleteFile"
}

/**
 * Request to rename or move a file or directory.
 *
 * @property requestId Unique ID for correlating the response
 * @property projectId The UUID of the project
 * @property from The current path
 * @property to The new path
 * @property overwrite Whether to overwrite the destination if it exists
 */
@Serializable
data class RenameMessage(
    val requestId: String,
    val projectId: String,
    val from: String,
    val to: String,
    val overwrite: Boolean = false
) : WebSocketMessage {
    @SerialName("messageType")
    override val type: String = "file/rename"
}

/**
 * Request to get the version of a file.
 *
 * @property requestId Unique ID for correlating the response
 * @property projectId The UUID of the project
 * @property path The file path
 */
@Serializable
data class GetFileVersionMessage(
    val requestId: String,
    val projectId: String,
    val path: String
) : WebSocketMessage {
    @SerialName("messageType")
    override val type: String = "file/getFileVersion"
}

/**
 * Request to read metadata for a file.
 *
 * @property requestId Unique ID for correlating the response
 * @property projectId The UUID of the project
 * @property path The file path
 */
@Serializable
data class ReadMetadataMessage(
    val requestId: String,
    val projectId: String,
    val path: String
) : WebSocketMessage {
    @SerialName("messageType")
    override val type: String = "file/readMetadata"
}

/**
 * Request to write metadata for a file.
 *
 * @property requestId Unique ID for correlating the response
 * @property projectId The UUID of the project
 * @property path The file path
 * @property metadata The metadata object to write
 */
@Serializable
data class WriteMetadataMessage(
    val requestId: String,
    val projectId: String,
    val path: String,
    val metadata: JsonObject
) : WebSocketMessage {
    @SerialName("messageType")
    override val type: String = "file/writeMetadata"
}

// ─── File System Response Messages (Server → Client) ─────────────────

/**
 * Generic success response for a file system request.
 *
 * @property requestId The ID of the request this response is for
 * @property data Optional response payload (type varies per request)
 */
@Serializable
data class FileResponseMessage(
    val requestId: String,
    val data: kotlinx.serialization.json.JsonElement? = null
) : WebSocketMessage {
    @SerialName("messageType")
    override val type: String = "file/response"
}

/**
 * Error response for a file system request.
 *
 * @property requestId The ID of the request that caused the error
 * @property code The error code (e.g. "FileNotFound")
 * @property message Human-readable error description
 */
@Serializable
data class FileErrorMessage(
    val requestId: String,
    val code: String,
    val message: String
) : WebSocketMessage {
    @SerialName("messageType")
    override val type: String = "file/error"
}

/**
 * Streaming message containing the full directory structure of a project.
 * Sent as the first part of a [LoadProjectMessage] response.
 *
 * @property requestId The ID of the originating loadProject request
 * @property entries List of all file/directory entries with their paths and types
 */
@Serializable
data class ProjectLoadDirectoryStructureMessage(
    val requestId: String,
    val entries: List<ProjectFileEntry>
) : WebSocketMessage {
    @SerialName("messageType")
    override val type: String = "file/projectLoadDirectoryStructure"
}

/**
 * A file or directory entry in the project tree with its full path.
 *
 * @property path The full path relative to project root
 * @property type The file type constant (see [com.mdeo.common.model.FileType])
 */
@Serializable
data class ProjectFileEntry(
    val path: String,
    val type: Int
)

/**
 * Streaming message containing a single file's content and version.
 * Sent as part of a [LoadProjectMessage] response.
 *
 * @property requestId The ID of the originating loadProject request
 * @property path The file path relative to project root
 * @property content The text content of the file
 * @property version The current file version
 */
@Serializable
data class ProjectLoadFileDataMessage(
    val requestId: String,
    val path: String,
    val content: String,
    val version: Int
) : WebSocketMessage {
    @SerialName("messageType")
    override val type: String = "file/projectLoadFileData"
}

/**
 * Streaming message containing a single file's metadata.
 * Sent as part of a [LoadProjectMessage] response.
 *
 * @property requestId The ID of the originating loadProject request
 * @property path The file path relative to project root
 * @property metadata The metadata object for the file
 */
@Serializable
data class ProjectLoadFileMetadataMessage(
    val requestId: String,
    val path: String,
    val metadata: JsonObject
) : WebSocketMessage {
    @SerialName("messageType")
    override val type: String = "file/projectLoadFileMetadata"
}

/**
 * Marker message indicating that the project load stream is complete.
 * Sent as the final part of a [LoadProjectMessage] response.
 *
 * @property requestId The ID of the originating loadProject request
 */
@Serializable
data class ProjectLoadCompleteMessage(
    val requestId: String
) : WebSocketMessage {
    @SerialName("messageType")
    override val type: String = "file/projectLoadComplete"
}

/**
 * Cached permission grant for a specific project on a WebSocket connection.
 *
 * @property hasRead Whether the user has read permission
 * @property hasWrite Whether the user has write permission
 * @property expiresAt When this permission cache entry expires
 */
data class ProjectPermissionCache(
    val hasRead: Boolean,
    val hasWrite: Boolean,
    val expiresAt: Instant
) {
    companion object {
        /**
         * Default time-to-live for cached permission grants. 
         */
        val TTL: java.time.Duration = java.time.Duration.ofMinutes(5)
    }

    /**
     * Checks whether this cache entry is still valid.
     *
     * @return true if the entry has not expired
     */
    fun isValid(): Boolean = Instant.now().isBefore(expiresAt)
}


/**
 * Represents an active WebSocket connection with its subscriptions and permission cache.
 *
 * @property session The WebSocket session
 * @property userId The ID of the authenticated user
 * @property subscribedProjects Set of project IDs this connection is subscribed to for execution events
 * @property permissionCache Cached permission grants keyed by project ID
 */
data class WebSocketConnection(
    val session: DefaultWebSocketServerSession,
    val userId: String,
    val subscribedProjects: MutableSet<UUID> = mutableSetOf(),
    val permissionCache: MutableMap<UUID, ProjectPermissionCache> = mutableMapOf()
)

/**
 * Service for managing WebSocket connections and broadcasting notifications.
 * Handles multiple simultaneous connections per user and project subscriptions.
 * Also provides permission caching for file system operations.
 */
class WebSocketNotificationService {
    private val logger = LoggerFactory.getLogger(WebSocketNotificationService::class.java)
    val json = Json { encodeDefaults = true }
    
    private val connections = ConcurrentHashMap<String, WebSocketConnection>()
    private val connectionMutex = Mutex()

    /**
     * Registers a new WebSocket connection.
     *
     * @param connectionId Unique identifier for this connection
     * @param session The WebSocket session
     * @param userId The authenticated user's ID
     */
    suspend fun registerConnection(
        connectionId: String,
        session: DefaultWebSocketServerSession,
        userId: String
    ) {
        connectionMutex.withLock {
            connections[connectionId] = WebSocketConnection(session, userId)
            logger.info("WebSocket connection registered: $connectionId for user $userId")
        }
    }

    /**
     * Removes a WebSocket connection and cleans up its subscriptions.
     *
     * @param connectionId The connection ID to remove
     */
    suspend fun removeConnection(connectionId: String) {
        connectionMutex.withLock {
            connections.remove(connectionId)
            logger.info("WebSocket connection removed: $connectionId")
        }
    }

    /**
     * Subscribes a connection to a project's events.
     *
     * @param connectionId The connection ID
     * @param projectId The project UUID to subscribe to
     * @return True if subscription was successful, false if connection not found
     */
    suspend fun subscribe(connectionId: String, projectId: UUID): Boolean {
        return connectionMutex.withLock {
            val connection = connections[connectionId] ?: return@withLock false
            connection.subscribedProjects.add(projectId)
            logger.info("Connection $connectionId subscribed to project $projectId")
            true
        }
    }

    /**
     * Unsubscribes a connection from a project's events.
     *
     * @param connectionId The connection ID
     * @param projectId The project UUID to unsubscribe from
     * @return True if unsubscription was successful, false if connection not found
     */
    suspend fun unsubscribe(connectionId: String, projectId: UUID): Boolean {
        return connectionMutex.withLock {
            val connection = connections[connectionId] ?: return@withLock false
            connection.subscribedProjects.remove(projectId)
            logger.info("Connection $connectionId unsubscribed from project $projectId")
            true
        }
    }

    /**
     * Broadcasts an execution state change to all connections subscribed to the project.
     *
     * @param projectId The project UUID where the execution belongs
     * @param execution The updated execution data
     */
    suspend fun broadcastExecutionStateChange(projectId: UUID, execution: Execution) {
        val message = ExecutionStateChangedMessage(execution)
        val messageJson = json.encodeToString(message as WebSocketMessage)

        broadcastToProject(projectId, messageJson)
    }

    /**
     * Broadcasts that a project's files changed from outside its subscribed
     * connections, so open clients know to reload rather than silently
     * saving over content they never saw change underneath them.
     *
     * @param projectId The project whose files changed
     */
    suspend fun broadcastFilesChanged(projectId: UUID) {
        val message = FilesChangedMessage(projectId.toString())
        val messageJson = json.encodeToString(message as WebSocketMessage)

        broadcastToProject(projectId, messageJson)
    }

    /**
     * Sends a JSON message to all connections subscribed to a specific project.
     *
     * @param projectId The target project UUID
     * @param messageJson The JSON-encoded message to send
     */
    private suspend fun broadcastToProject(projectId: UUID, messageJson: String) {
        val targetConnections = getSubscribedConnections(projectId)
        
        for (connection in targetConnections) {
            sendMessageSafely(connection, messageJson)
        }
    }

    /**
     * Gets all connections currently subscribed to a project.
     *
     * @param projectId The project UUID
     * @return List of connections subscribed to the project
     */
    private suspend fun getSubscribedConnections(projectId: UUID): List<WebSocketConnection> {
        return connectionMutex.withLock {
            connections.values.filter { projectId in it.subscribedProjects }
        }
    }

    /**
     * Safely sends a message to a connection, handling any errors.
     *
     * @param connection The target connection
     * @param messageJson The JSON message to send
     */
    private suspend fun sendMessageSafely(connection: WebSocketConnection, messageJson: String) {
        try {
            connection.session.send(Frame.Text(messageJson))
        } catch (e: Exception) {
            logger.warn("Failed to send WebSocket message to connection", e)
        }
    }


    /**
     * Stores a permission grant in the cache for a connection.
     *
     * @param connectionId The connection ID
     * @param projectId The project UUID whose permission is being cached
     * @param hasRead Whether the user has read permission
     * @param hasWrite Whether the user has write permission
     */
    suspend fun cachePermission(
        connectionId: String,
        projectId: UUID,
        hasRead: Boolean,
        hasWrite: Boolean
    ) {
        connectionMutex.withLock {
            val connection = connections[connectionId] ?: return@withLock
            connection.permissionCache[projectId] = ProjectPermissionCache(
                hasRead = hasRead,
                hasWrite = hasWrite,
                expiresAt = Instant.now().plus(ProjectPermissionCache.TTL)
            )
        }
    }

    /**
     * Checks whether a connection has a valid cached read permission for a project.
     *
     * @param connectionId The connection ID
     * @param projectId The project UUID to check
     * @return true if a valid read permission is cached
     */
    suspend fun hasCachedReadPermission(connectionId: String, projectId: UUID): Boolean {
        return connectionMutex.withLock {
            val connection = connections[connectionId] ?: return@withLock false
            val cache = connection.permissionCache[projectId] ?: return@withLock false
            if (!cache.isValid()) {
                connection.permissionCache.remove(projectId)
                return@withLock false
            }
            cache.hasRead
        }
    }

    /**
     * Checks whether a connection has a valid cached write permission for a project.
     *
     * @param connectionId The connection ID
     * @param projectId The project UUID to check
     * @return true if a valid write permission is cached
     */
    suspend fun hasCachedWritePermission(connectionId: String, projectId: UUID): Boolean {
        return connectionMutex.withLock {
            val connection = connections[connectionId] ?: return@withLock false
            val cache = connection.permissionCache[projectId] ?: return@withLock false
            if (!cache.isValid()) {
                connection.permissionCache.remove(projectId)
                return@withLock false
            }
            cache.hasWrite
        }
    }

    /**
     * Sends a WebSocket message object to a specific connection.
     *
     * @param connectionId The connection ID to send to
     * @param message The message to send
     */
    suspend fun sendMessage(connectionId: String, message: WebSocketMessage) {
        val messageJson = json.encodeToString(message)
        val connection = connections[connectionId] ?: return
        sendMessageSafely(connection, messageJson)
    }

    /**
     * Sends a shared execution protocol message to a specific connection.
     *
     * Execution result messages are defined once and spoken on every hop between the browser
     * and the service that stores the results, so they are not part of this service's own
     * message hierarchy. They travel the same connection and are told apart by the same
     * `messageType` field.
     *
     * @param connectionId The connection ID to send to
     * @param message The message to send
     */
    suspend fun sendMessage(connectionId: String, message: ExecutionWsMessage) {
        val connection = connections[connectionId] ?: return
        sendMessageSafely(connection, ExecutionWsProtocol.encode(message))
    }
}
