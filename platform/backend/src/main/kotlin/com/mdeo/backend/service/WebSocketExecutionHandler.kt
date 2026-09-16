package com.mdeo.backend.service

import com.mdeo.common.model.*
import com.mdeo.common.transport.*
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import org.slf4j.LoggerFactory
import java.util.*

/**
 * Handles execution result requests received over the browser's WebSocket connection.
 *
 * The workbench used to fetch every execution result file with its own HTTP request, each of
 * which re-authenticated the session and re-checked project permissions before beginning the
 * two further hops to the service that stores the results. These requests ride the connection
 * the workbench already holds, reuse the permission cache [WebSocketNotificationService]
 * keeps for it, and add a bulk load that returns a whole result set under one request id.
 *
 * @property connectionId The WebSocket connection ID
 * @property userId The authenticated user's ID
 * @property isGlobalAdmin Whether the user has global admin permissions
 * @property executionService Service for execution operations
 * @property projectService Service for project access validation
 * @property webSocketService Service for sending messages and managing the permission cache
 */
class WebSocketExecutionHandler(
    private val connectionId: String,
    private val userId: String,
    private val isGlobalAdmin: Boolean,
    private val executionService: ExecutionService,
    private val projectService: ProjectService,
    private val webSocketService: WebSocketNotificationService
) {
    private val logger = LoggerFactory.getLogger(WebSocketExecutionHandler::class.java)

    /**
     * Handles one execution request from the browser.
     *
     * Unlike the service-to-service hops, this connection is authenticated by its session, so
     * requests carry no token of their own — the project permission is what is checked, and
     * it comes from the cache the connection already maintains.
     *
     * @param request The decoded request
     */
    suspend fun handle(request: ExecutionWsMessage) {
        val context = request.executionContext()
        if (context == null) {
            logger.warn("Ignoring unsupported execution WS message: ${request::class.simpleName}")
            return
        }

        val label = "${request::class.simpleName}[${request.requestId}]"

        val projectId = parseUuid(context.projectId)
        val executionId = parseUuid(context.executionId)
        if (projectId == null || executionId == null) {
            logger.warn(
                "Execution WS request {} rejected: unparseable project '{}' or execution '{}'",
                label, context.projectId, context.executionId
            )
            sendError(request.requestId, ErrorCodes.BAD_REQUEST, "Invalid project or execution ID")
            return
        }

        if (!hasReadPermission(projectId)) {
            logger.warn("Execution WS request {} denied: no read permission on project {}", label, projectId)
            sendError(request.requestId, ErrorCodes.FORBIDDEN, "Access denied to project $projectId")
            return
        }

        try {
            dispatch(request, projectId, executionId)
        } catch (e: Exception) {
            logger.error("Execution WS request $label failed", e)
            sendError(request.requestId, ErrorCodes.INTERNAL, e.message ?: "Internal error")
        }
    }

    private suspend fun dispatch(request: ExecutionWsMessage, projectId: UUID, executionId: UUID) {
        when (request) {
            is ExecutionSummaryWsRequest -> respondWith(request.requestId) {
                val summary = executionService.getExecutionSummary(projectId, executionId).orThrow()
                ExecutionWsProtocol.json.encodeToJsonElement(ExecutionSummaryPayload(summary))
            }

            is ExecutionFileTreeWsRequest -> respondWith(request.requestId) {
                val tree = executionService.getExecutionWithTree(projectId, executionId).orThrow()
                ExecutionWsProtocol.json.encodeToJsonElement(
                    ExecutionFileTreePayload(tree.fileTree ?: emptyList())
                )
            }

            is ExecutionFileWsRequest -> respondWith(request.requestId) {
                val bytes = executionService.getExecutionFile(projectId, executionId, request.path).orThrow()
                ExecutionWsProtocol.json.encodeToJsonElement(
                    ExecutionFilePayload(String(bytes, Charsets.UTF_8))
                )
            }

            is ExecutionFilesWsRequest -> {
                val files = executionService.streamExecutionFiles(
                    projectId,
                    executionId,
                    request.paths
                ) { path, content ->
                    webSocketService.sendMessage(
                        connectionId,
                        ExecutionFileDataMessage(request.requestId, path, content)
                    )
                }
                respondWith(request.requestId) {
                    ExecutionWsProtocol.json.encodeToJsonElement(ExecutionFileTreePayload(files.orThrow()))
                }
            }

            else -> sendError(
                request.requestId,
                ErrorCodes.BAD_REQUEST,
                "Unsupported request: ${request::class.simpleName}"
            )
        }
    }

    private suspend fun respondWith(requestId: String, produce: suspend () -> JsonElement?) {
        try {
            webSocketService.sendMessage(connectionId, ExecutionWsResponse(requestId, produce()))
        } catch (e: ApiResultException) {
            sendError(requestId, e.code, e.message ?: "Request failed")
        }
    }

    /**
     * Checks the connection's cached read permission for a project, reloading it from the
     * database when the cache entry is missing or has expired.
     */
    private suspend fun hasReadPermission(projectId: UUID): Boolean {
        if (webSocketService.hasCachedReadPermission(connectionId, projectId)) {
            return true
        }

        val userUuid = parseUuid(userId) ?: return false
        val hasRead = projectService.hasProjectPermission(
            projectId, userUuid, isGlobalAdmin, ProjectPermission.READ
        )
        if (!hasRead) {
            return false
        }
        val hasWrite = projectService.hasProjectPermission(
            projectId, userUuid, isGlobalAdmin, ProjectPermission.WRITE
        )
        webSocketService.cachePermission(connectionId, projectId, true, hasWrite)
        return true
    }

    private suspend fun sendError(requestId: String, code: String, message: String) {
        webSocketService.sendMessage(connectionId, ExecutionWsError(requestId, ApiError(code, message)))
    }

    private fun parseUuid(value: String?): UUID? {
        if (value == null) return null
        return try {
            UUID.fromString(value)
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Unwraps a result, turning a failure into an exception the request responder converts into
 * an [ExecutionWsError] carrying the original error code.
 *
 * @return The success value
 */
private fun <T> ApiResult<T>.orThrow(): T = when (this) {
    is ApiResult.Success -> value
    is ApiResult.Failure -> throw ApiResultException(error)
}

/**
 * Carries an [ApiError] as an exception so it can cross the response-producing lambda.
 *
 * @property code The protocol error code the failure maps to
 */
private class ApiResultException(error: ApiError) : RuntimeException(error.message) {
    val code: String = when (error.code) {
        ErrorCodes.EXECUTION_NOT_FOUND, ErrorCodes.FILE_NOT_FOUND, ErrorCodes.PLUGIN_NOT_FOUND ->
            ErrorCodes.NOT_FOUND
        else -> ErrorCodes.INTERNAL
    }
}

private fun ExecutionWsMessage.executionContext(): ExecutionWsContext? = when (this) {
    is ExecutionSummaryWsRequest -> context
    is ExecutionFileTreeWsRequest -> context
    is ExecutionFileWsRequest -> context
    is ExecutionFilesWsRequest -> context
    is ExecutionCancelWsRequest -> context
    is ExecutionDeleteWsRequest -> context
    else -> null
}
