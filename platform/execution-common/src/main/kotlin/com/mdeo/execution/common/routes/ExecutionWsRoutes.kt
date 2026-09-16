package com.mdeo.execution.common.routes

import com.mdeo.common.model.ErrorCodes
import com.mdeo.common.transport.*
import com.mdeo.execution.common.auth.JwtPrincipalData
import com.mdeo.execution.common.auth.WsTokenVerifier
import com.mdeo.execution.common.auth.hasScope
import com.mdeo.execution.common.service.ExecutionScopes
import com.mdeo.execution.common.service.ExecutionService
import com.mdeo.execution.common.service.ExecutionServiceWithFileTree
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import kotlinx.serialization.json.encodeToJsonElement
import org.slf4j.LoggerFactory
import java.util.UUID
import com.mdeo.common.model.FileEntry as SharedFileEntry

/**
 * Serves the execution result endpoints of [ExecutionWsProtocol] alongside the HTTP routes.
 *
 * Reading an execution result over HTTP costs one request per file per hop, and the plugin
 * service in front of this one pays that cost again on its own side. This endpoint answers
 * the same questions over a connection the caller keeps between requests, and adds
 * [ExecutionFilesWsRequest], which streams a whole result set back under one request id.
 *
 * The connection itself is anonymous. Authorization comes from the token on each request, so
 * an idle connection can be dropped and reopened without anything being re-established.
 *
 * @param verifier Verifies the token supplied with each request
 * @param executionService The service backing this execution service's results
 */
fun Route.executionWebSocketRoutes(
    verifier: WsTokenVerifier,
    executionService: ExecutionService
) {
    val logger = LoggerFactory.getLogger("ExecutionWsRoutes")

    webSocket(ExecutionWsProtocol.ENDPOINT_PATH) {
        logger.debug("Execution WS connection opened from {}", call.request.local.remoteHost)
        try {
            ExecutionWsServer.serve(this) { request, responder ->
                try {
                    handleExecutionWsRequest(verifier, executionService, request, responder)
                } catch (e: ExecutionWsException) {
                    logger.warn(
                        "Execution WS request {}[{}] rejected with {}: {}",
                        request::class.simpleName, request.requestId, e.code, e.message
                    )
                    throw e
                }
            }
        } finally {
            logger.debug("Execution WS connection closed")
        }
    }
}

private suspend fun handleExecutionWsRequest(
    verifier: WsTokenVerifier,
    executionService: ExecutionService,
    request: ExecutionWsMessage,
    responder: ExecutionWsRequestResponder
) {
    val context = request.context()
        ?: throw ExecutionWsException(ErrorCodes.BAD_REQUEST, "Unsupported request: ${request::class.simpleName}")

    val requiredScope = when (request) {
        is ExecutionCancelWsRequest -> ExecutionScopes.EXECUTION_CANCEL
        is ExecutionDeleteWsRequest -> ExecutionScopes.EXECUTION_DELETE
        else -> ExecutionScopes.EXECUTION_READ
    }

    val principal = authorize(verifier, context, requiredScope)
    val executionId = try {
        UUID.fromString(context.executionId)
    } catch (e: Exception) {
        throw ExecutionWsException(ErrorCodes.BAD_REQUEST, "Invalid execution ID: ${context.executionId}")
    }

    // The token is issued for one execution; a connection shared by several of them must not
    // let a token for one address the results of another.
    if (principal.executionId != null && principal.executionId != context.executionId) {
        throw ExecutionWsException(ErrorCodes.FORBIDDEN, "Token is not valid for this execution")
    }

    when (request) {
        is ExecutionSummaryWsRequest -> {
            val summary = executionService.getSummary(executionId)
                ?: throw ExecutionWsException(ErrorCodes.NOT_FOUND, "Execution not found")
            responder.respond(ExecutionWsProtocol.json.encodeToJsonElement(ExecutionSummaryPayload(summary)))
        }

        is ExecutionCancelWsRequest -> {
            executionService.cancelExecution(executionId)
            responder.respond()
        }

        is ExecutionDeleteWsRequest -> {
            executionService.deleteExecution(executionId)
            responder.respond()
        }

        is ExecutionFileTreeWsRequest -> {
            val files = executionService.requireFileTree().getFileTree(executionId, null)
                ?: throw ExecutionWsException(ErrorCodes.NOT_FOUND, "Execution not found")
            responder.respond(
                ExecutionWsProtocol.json.encodeToJsonElement(ExecutionFileTreePayload(files.toShared()))
            )
        }

        is ExecutionFileWsRequest -> {
            val content = executionService.requireFileTree().getFileContents(executionId, request.path)
                ?: throw ExecutionWsException(ErrorCodes.NOT_FOUND, "File not found: ${request.path}")
            responder.respond(ExecutionWsProtocol.json.encodeToJsonElement(ExecutionFilePayload(content)))
        }

        is ExecutionFilesWsRequest -> {
            streamFiles(executionService.requireFileTree(), executionId, request, responder)
        }

        else -> throw ExecutionWsException(
            ErrorCodes.BAD_REQUEST,
            "Unsupported request: ${request::class.simpleName}"
        )
    }
}

/**
 * Streams the requested files of an execution, then terminates the request with the tree
 * describing what was sent.
 *
 * A file that cannot be read is skipped rather than failing the whole load: a partially
 * readable result set is still worth showing, and the tree the caller receives back tells
 * it exactly which files it did not get.
 */
private suspend fun streamFiles(
    executionService: ExecutionServiceWithFileTree,
    executionId: UUID,
    request: ExecutionFilesWsRequest,
    responder: ExecutionWsRequestResponder
) {
    val logger = LoggerFactory.getLogger("ExecutionWsRoutes")

    val tree = executionService.getFileTree(executionId, null)
        ?: throw ExecutionWsException(ErrorCodes.NOT_FOUND, "Execution not found")

    val requested = request.paths?.toSet()
    val sent = mutableListOf<SharedFileEntry>()

    for (entry in tree) {
        if (entry.type != FileEntry.TYPE_FILE) {
            sent.add(SharedFileEntry(entry.name, entry.type))
            continue
        }
        if (requested != null && entry.name !in requested) {
            continue
        }
        val content = try {
            executionService.getFileContents(executionId, entry.name)
        } catch (e: Exception) {
            logger.warn("Skipping unreadable execution file ${entry.name}: ${e.message}")
            null
        }
        if (content != null) {
            responder.sendFile(entry.name, content)
            sent.add(SharedFileEntry(entry.name, entry.type))
        }
    }

    responder.respond(ExecutionWsProtocol.json.encodeToJsonElement(ExecutionFileTreePayload(sent)))
}

private suspend fun authorize(
    verifier: WsTokenVerifier,
    context: ExecutionWsContext,
    requiredScope: String
): JwtPrincipalData {
    val principal = verifier.verify(context.auth)
        ?: throw ExecutionWsException(ErrorCodes.FORBIDDEN, "Missing or invalid token")
    if (!principal.hasScope(requiredScope)) {
        throw ExecutionWsException(ErrorCodes.FORBIDDEN, "Token missing $requiredScope scope")
    }
    return principal
}

private fun ExecutionService.requireFileTree(): ExecutionServiceWithFileTree {
    return this as? ExecutionServiceWithFileTree
        ?: throw ExecutionWsException(
            ErrorCodes.BAD_REQUEST,
            "This execution service does not expose result files"
        )
}

private fun ExecutionWsMessage.context(): ExecutionWsContext? = when (this) {
    is ExecutionSummaryWsRequest -> context
    is ExecutionFileTreeWsRequest -> context
    is ExecutionFileWsRequest -> context
    is ExecutionFilesWsRequest -> context
    is ExecutionCancelWsRequest -> context
    is ExecutionDeleteWsRequest -> context
    else -> null
}

private fun List<FileEntry>.toShared(): List<SharedFileEntry> = map { SharedFileEntry(it.name, it.type) }
