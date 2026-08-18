package com.mdeo.backend.service

import com.mdeo.common.model.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import org.slf4j.LoggerFactory
import java.util.*

/**
 * Handles file system operations received over WebSocket.
 * Uses the permission cache on [WebSocketNotificationService] to avoid
 * re-checking permissions on every individual request.
 *
 * @property connectionId The WebSocket connection ID
 * @property userId The authenticated user's ID
 * @property isGlobalAdmin Whether the user has global admin permissions
 * @property fileService Service for file CRUD operations
 * @property metadataService Service for file metadata operations
 * @property projectService Service for project access validation
 * @property webSocketService Service for sending messages and managing permission cache
 */
class WebSocketFileHandler(
    private val connectionId: String,
    private val userId: String,
    private val isGlobalAdmin: Boolean,
    private val fileService: FileService,
    private val metadataService: MetadataService,
    private val projectService: ProjectService,
    private val webSocketService: WebSocketNotificationService
) {
    private val logger = LoggerFactory.getLogger(WebSocketFileHandler::class.java)
    private val json = Json { encodeDefaults = true }

    /**
     * Handles a subscribeFiles request: validates project access and caches the permissions.
     *
     * @param message The subscribe files request
     */
    suspend fun handleSubscribeFiles(message: SubscribeFilesMessage) {
        val projectId = parseProjectId(message.projectId)
        if (projectId == null) {
            sendError(message.requestId, "BadRequest", "Invalid project ID: ${message.projectId}")
            return
        }

        val userUuid = parseUserId() ?: run {
            sendError(message.requestId, "BadRequest", "Invalid user ID")
            return
        }

        val hasRead = projectService.hasProjectPermission(projectId, userUuid, isGlobalAdmin, ProjectPermission.READ)
        val hasWrite = projectService.hasProjectPermission(projectId, userUuid, isGlobalAdmin, ProjectPermission.WRITE)

        if (!hasRead) {
            sendError(message.requestId, "Forbidden", "Access denied to project $projectId")
            return
        }

        webSocketService.cachePermission(connectionId, projectId, hasRead, hasWrite)
        sendSuccess(message.requestId, null)
    }

    /**
     * Handles a loadProject request: streams the entire project directory structure,
     * file contents, and file metadata to the client.
     *
     * @param message The load project request
     */
    suspend fun handleLoadProject(message: LoadProjectMessage) {
        val projectId = parseAndCheckRead(message.projectId, message.requestId) ?: return

        // Collect all file/directory entries
        val allEntries = fileService.getAllEntries(projectId)
        val projectEntries = allEntries.map { ProjectFileEntry(it.first, it.second) }

        // Send directory structure
        webSocketService.sendMessage(
            connectionId,
            ProjectLoadDirectoryStructureMessage(message.requestId, projectEntries)
        )

        // Send file data for all files
        for ((path, type) in allEntries) {
            if (type == FileType.FILE) {
                val contentResult = fileService.readFile(projectId, path)
                val versionResult = fileService.getFileVersion(projectId, path)
                if (contentResult is ApiResult.Success && versionResult is ApiResult.Success) {
                    val textContent = String(contentResult.value, Charsets.UTF_8)
                    webSocketService.sendMessage(
                        connectionId,
                        ProjectLoadFileDataMessage(message.requestId, path, textContent, versionResult.value)
                    )
                }
            }
        }

        // Send metadata for all files
        for ((path, type) in allEntries) {
            if (type == FileType.FILE) {
                val metaResult = metadataService.readMetadata(projectId, path)
                if (metaResult is ApiResult.Success) {
                    webSocketService.sendMessage(
                        connectionId,
                        ProjectLoadFileMetadataMessage(message.requestId, path, metaResult.value)
                    )
                }
            }
        }

        // Signal completion
        webSocketService.sendMessage(
            connectionId,
            ProjectLoadCompleteMessage(message.requestId)
        )
    }

    /**
     * Handles a readFile request.
     *
     * @param message The read file request
     */
    suspend fun handleReadFile(message: ReadFileMessage) {
        val projectId = parseAndCheckRead(message.projectId, message.requestId) ?: return

        val contentResult = fileService.readFile(projectId, message.path)
        val versionResult = fileService.getFileVersion(projectId, message.path)

        when {
            contentResult is ApiResult.Failure -> sendApiError(message.requestId, contentResult)
            versionResult is ApiResult.Failure -> sendApiError(message.requestId, versionResult)
            contentResult is ApiResult.Success && versionResult is ApiResult.Success -> {
                val textContent = String(contentResult.value, Charsets.UTF_8)
                val data = json.encodeToJsonElement(FileReadResponse(textContent, versionResult.value))
                sendSuccess(message.requestId, data)
            }
        }
    }

    /**
     * Handles a writeFile request.
     *
     * @param message The write file request containing Base64-encoded content
     */
    suspend fun handleWriteFile(message: WriteFileMessage) {
        val projectId = parseAndCheckWrite(message.projectId, message.requestId) ?: return

        val content = try {
            java.util.Base64.getDecoder().decode(message.content)
        } catch (e: Exception) {
            sendError(message.requestId, "BadRequest", "Invalid Base64 content")
            return
        }

        val result = fileService.writeFile(
            projectId,
            message.path,
            content,
            message.create,
            message.overwrite,
            message.expectedVersion
        )
        sendApiResult(message.requestId, result)
    }

    /**
     * Handles a mkdir request.
     *
     * @param message The mkdir request
     */
    suspend fun handleMkdir(message: MkdirMessage) {
        val projectId = parseAndCheckWrite(message.projectId, message.requestId) ?: return
        val result = fileService.mkdir(projectId, message.path)
        sendApiResult(message.requestId, result)
    }

    /**
     * Handles a readdir request.
     *
     * @param message The readdir request
     */
    suspend fun handleReaddir(message: ReaddirMessage) {
        val projectId = parseAndCheckRead(message.projectId, message.requestId) ?: return
        val result = fileService.readdir(projectId, message.path)
        when (result) {
            is ApiResult.Success -> {
                val data = json.encodeToJsonElement(result.value)
                sendSuccess(message.requestId, data)
            }
            is ApiResult.Failure -> sendApiError(message.requestId, result)
        }
    }

    /**
     * Handles a stat request.
     *
     * @param message The stat request
     */
    suspend fun handleStat(message: StatMessage) {
        val projectId = parseAndCheckRead(message.projectId, message.requestId) ?: return
        val result = fileService.stat(projectId, message.path)
        when (result) {
            is ApiResult.Success -> {
                val data = json.encodeToJsonElement(result.value)
                sendSuccess(message.requestId, data)
            }
            is ApiResult.Failure -> sendApiError(message.requestId, result)
        }
    }

    /**
     * Handles a deleteFile request.
     *
     * @param message The delete request
     */
    suspend fun handleDelete(message: DeleteFileMessage) {
        val projectId = parseAndCheckWrite(message.projectId, message.requestId) ?: return
        val result = fileService.delete(projectId, message.path, message.recursive)
        sendApiResult(message.requestId, result)
    }

    /**
     * Handles a rename request.
     *
     * @param message The rename request
     */
    suspend fun handleRename(message: RenameMessage) {
        val projectId = parseAndCheckWrite(message.projectId, message.requestId) ?: return
        val result = fileService.rename(projectId, message.from, message.to, message.overwrite)
        sendApiResult(message.requestId, result)
    }

    /**
     * Handles a getFileVersion request.
     *
     * @param message The get file version request
     */
    suspend fun handleGetFileVersion(message: GetFileVersionMessage) {
        val projectId = parseAndCheckRead(message.projectId, message.requestId) ?: return
        val result = fileService.getFileVersion(projectId, message.path)
        when (result) {
            is ApiResult.Success -> {
                val data = json.encodeToJsonElement(result.value)
                sendSuccess(message.requestId, data)
            }
            is ApiResult.Failure -> sendApiError(message.requestId, result)
        }
    }

    /**
     * Handles a readMetadata request.
     *
     * @param message The read metadata request
     */
    suspend fun handleReadMetadata(message: ReadMetadataMessage) {
        val projectId = parseAndCheckRead(message.projectId, message.requestId) ?: return
        val result = metadataService.readMetadata(projectId, message.path)
        when (result) {
            is ApiResult.Success -> {
                val data: JsonElement = result.value
                sendSuccess(message.requestId, data)
            }
            is ApiResult.Failure -> sendApiError(message.requestId, result)
        }
    }

    /**
     * Handles a writeMetadata request.
     *
     * @param message The write metadata request
     */
    suspend fun handleWriteMetadata(message: WriteMetadataMessage) {
        val projectId = parseAndCheckWrite(message.projectId, message.requestId) ?: return
        val result = metadataService.writeMetadata(projectId, message.path, message.metadata)
        sendApiResult(message.requestId, result)
    }

    // ─── Helpers ──────────────────────────────────────────────────────

    /**
     * Parses a project ID string and verifies cached read permission.
     * If the cache entry is missing or has expired, permissions are reloaded
     * from the database and re-cached before the check is performed.
     *
     * @param projectIdStr The project ID as a string
     * @param requestId The request ID for error responses
     * @return The parsed project UUID, or null if parsing or permission check fails
     */
    private suspend fun parseAndCheckRead(projectIdStr: String, requestId: String): UUID? {
        val projectId = parseProjectId(projectIdStr)
        if (projectId == null) {
            sendError(requestId, "BadRequest", "Invalid project ID: $projectIdStr")
            return null
        }
        if (!webSocketService.hasCachedReadPermission(connectionId, projectId)) {
            // Cache miss or expired — reload from the database before failing
            if (!reloadAndCachePermissions(projectId)) {
                sendError(requestId, "Forbidden", "Access denied to project $projectId")
                return null
            }
        }
        return projectId
    }

    /**
     * Parses a project ID string and verifies cached write permission.
     * If the cache entry is missing or has expired, permissions are reloaded
     * from the database and re-cached before the check is performed.
     *
     * @param projectIdStr The project ID as a string
     * @param requestId The request ID for error responses
     * @return The parsed project UUID, or null if parsing or permission check fails
     */
    private suspend fun parseAndCheckWrite(projectIdStr: String, requestId: String): UUID? {
        val projectId = parseProjectId(projectIdStr)
        if (projectId == null) {
            sendError(requestId, "BadRequest", "Invalid project ID: $projectIdStr")
            return null
        }
        if (!webSocketService.hasCachedWritePermission(connectionId, projectId)) {
            // Cache miss or expired — reload from the database before failing
            val reloaded = reloadAndCachePermissions(projectId)
            if (!reloaded || !webSocketService.hasCachedWritePermission(connectionId, projectId)) {
                sendError(requestId, "Forbidden", "Access denied to project $projectId")
                return null
            }
        }
        return projectId
    }

    /**
     * Reloads project permissions from the database and refreshes the permission cache.
     * Called when the cached permission entry is missing or has expired.
     *
     * @param projectId The project UUID to reload permissions for
     * @return true if the user has at least read permission, false otherwise
     */
    private suspend fun reloadAndCachePermissions(projectId: UUID): Boolean {
        val userUuid = parseUserId() ?: return false
        val hasRead = projectService.hasProjectPermission(projectId, userUuid, isGlobalAdmin, ProjectPermission.READ)
        if (!hasRead) return false
        val hasWrite = projectService.hasProjectPermission(projectId, userUuid, isGlobalAdmin, ProjectPermission.WRITE)
        webSocketService.cachePermission(connectionId, projectId, hasRead, hasWrite)
        logger.debug("Reloaded permissions for connection $connectionId project $projectId: read=$hasRead write=$hasWrite")
        return true
    }

    /**
     * Parses a project ID string to a UUID.
     *
     * @param projectIdStr The project ID as a string
     * @return The parsed UUID, or null if invalid
     */
    private fun parseProjectId(projectIdStr: String): UUID? {
        return try {
            UUID.fromString(projectIdStr)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Parses the user ID to a UUID.
     *
     * @return The parsed UUID, or null if invalid
     */
    private fun parseUserId(): UUID? {
        return try {
            UUID.fromString(userId)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Sends a success response with optional data payload.
     *
     * @param requestId The request ID to correlate
     * @param data Optional JSON data payload
     */
    private suspend fun sendSuccess(requestId: String, data: JsonElement?) {
        webSocketService.sendMessage(connectionId, FileResponseMessage(requestId, data))
    }

    /**
     * Sends an error response.
     *
     * @param requestId The request ID to correlate
     * @param code The error code
     * @param message The error description
     */
    private suspend fun sendError(requestId: String, code: String, message: String) {
        webSocketService.sendMessage(connectionId, FileErrorMessage(requestId, code, message))
    }

    /**
     * Sends an error response extracted from an [ApiResult.Failure].
     *
     * @param requestId The request ID to correlate
     * @param failure The failure result to extract error info from
     */
    private suspend fun sendApiError(requestId: String, failure: ApiResult.Failure) {
        sendError(requestId, failure.error.code, failure.error.message)
    }

    /**
     * Sends either a success or error response based on an [ApiResult].
     *
     * @param requestId The request ID to correlate
     * @param result The API result to send
     */
    private suspend fun sendApiResult(requestId: String, result: ApiResult<Unit>) {
        when (result) {
            is ApiResult.Success -> sendSuccess(requestId, null)
            is ApiResult.Failure -> sendApiError(requestId, result)
        }
    }
}
