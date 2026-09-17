package com.mdeo.execution.common.routes

import com.mdeo.common.auth.Scopes
import com.mdeo.execution.common.auth.*
import com.mdeo.execution.common.config.ExecutionWsVerifierKey
import com.mdeo.execution.common.service.ExecutionService
import com.mdeo.execution.common.service.ExecutionServiceWithFileTree
import com.mdeo.execution.common.routes.ErrorResponses.respondBadRequest
import com.mdeo.execution.common.routes.ErrorResponses.respondNotFound
import com.mdeo.execution.common.routes.ErrorResponses.respondInternalError
import com.mdeo.execution.common.routes.RouteUtils.getUuidParam
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.*

/**
 * Configures common execution routes for cancel, delete, and summary operations.
 * These routes are shared across all execution services.
 *
 * @param authName The authentication name to use (defaults to AUTH_JWT)
 * @param executionService The execution service implementation
 */
fun Route.baseExecutionRoutes(
    authName: String = AUTH_JWT,
    executionService: ExecutionService
) {
    val logger = LoggerFactory.getLogger("BaseExecutionRoutes")

    authenticate(authName) {
        route("/api/executions") {
            cancelExecutionRoute(logger, executionService)
            deleteExecutionRoute(logger, executionService)
            getSummaryRoute(logger, executionService)
        }
    }

    registerExecutionWebSocketRoutes(executionService)
}

/**
 * Configures execution routes with file tree support.
 * Extends base routes with file tree and file contents endpoints.
 *
 * @param authName The authentication name to use (defaults to AUTH_JWT)
 * @param executionService The execution service implementation with file tree support
 */
fun Route.executionRoutesWithFileTree(
    authName: String = AUTH_JWT,
    executionService: ExecutionServiceWithFileTree
) {
    val logger = LoggerFactory.getLogger("ExecutionRoutesWithFileTree")

    authenticate(authName) {
        route("/api/executions") {
            cancelExecutionRoute(logger, executionService)
            deleteExecutionRoute(logger, executionService)
            getSummaryRoute(logger, executionService)
            getFileTreeRoute(logger, executionService)
            getFileContentsRoute(logger, executionService)
        }
    }

    registerExecutionWebSocketRoutes(executionService)
}

/**
 * Registers the WebSocket counterpart of these routes, if the service enabled the transport
 * with `configureExecutionTransport`.
 *
 * @param executionService The execution service to serve requests from
 */
private fun Route.registerExecutionWebSocketRoutes(executionService: ExecutionService) {
    val verifier = application.attributes.getOrNull(ExecutionWsVerifierKey) ?: return
    executionWebSocketRoutes(verifier, executionService)
}

private fun Route.cancelExecutionRoute(
    logger: Logger,
    executionService: ExecutionService
) {
    post("{id}/cancel") {
        val authResult = call.requireScope(Scopes.PLUGIN_EXECUTION_CANCEL)
        if (authResult is AuthorizationResult.Denied) {
            call.respondAuthError(authResult)
            return@post
        }
        val principal = (authResult as AuthorizationResult.Authorized).principal

        val executionId = getUuidParam("id")
        if (executionId == null) {
            call.respondBadRequest("Invalid execution ID")
            return@post
        }

        val matchResult = validateExecutionIdMatch(executionId.toString(), principal)
        if (matchResult is AuthorizationResult.Denied) {
            call.respondAuthError(matchResult)
            return@post
        }

        try {
            executionService.cancelExecution(executionId)
            call.respond(HttpStatusCode.NoContent)
        } catch (e: Exception) {
            logger.error("Failed to cancel execution", e)
            call.respondInternalError()
        }
    }
}

private fun Route.deleteExecutionRoute(
    logger: Logger,
    executionService: ExecutionService
) {
    delete("{id}") {
        val authResult = call.requireScope(Scopes.PLUGIN_EXECUTION_DELETE)
        if (authResult is AuthorizationResult.Denied) {
            call.respondAuthError(authResult)
            return@delete
        }
        val principal = (authResult as AuthorizationResult.Authorized).principal

        val executionId = getUuidParam("id")
        if (executionId == null) {
            call.respondBadRequest("Invalid execution ID")
            return@delete
        }

        val matchResult = validateExecutionIdMatch(executionId.toString(), principal)
        if (matchResult is AuthorizationResult.Denied) {
            call.respondAuthError(matchResult)
            return@delete
        }

        try {
            executionService.deleteExecution(executionId)
            call.respond(HttpStatusCode.NoContent)
        } catch (e: Exception) {
            logger.error("Failed to delete execution", e)
            call.respondInternalError()
        }
    }
}

private fun Route.getSummaryRoute(
    logger: Logger,
    executionService: ExecutionService
) {
    get("{id}/summary") {
        val authResult = call.requireScope(Scopes.PLUGIN_EXECUTION_READ)
        if (authResult is AuthorizationResult.Denied) {
            call.respondAuthError(authResult)
            return@get
        }
        val principal = (authResult as AuthorizationResult.Authorized).principal

        val executionId = getUuidParam("id")
        if (executionId == null) {
            call.respondBadRequest("Invalid execution ID")
            return@get
        }

        val matchResult = validateExecutionIdMatch(executionId.toString(), principal)
        if (matchResult is AuthorizationResult.Denied) {
            call.respondAuthError(matchResult)
            return@get
        }

        try {
            val summary = executionService.getSummary(executionId)
            if (summary == null) {
                call.respondNotFound("Execution not found")
            } else {
                call.respond(ExecutionSummaryResponse(summary))
            }
        } catch (e: Exception) {
            logger.error("Failed to get summary", e)
            call.respondInternalError()
        }
    }
}

private fun Route.getFileTreeRoute(
    logger: Logger,
    executionService: ExecutionServiceWithFileTree
) {
    get("{id}/file-tree") {
        val authResult = call.requireScope(Scopes.PLUGIN_EXECUTION_READ)
        if (authResult is AuthorizationResult.Denied) {
            call.respondAuthError(authResult)
            return@get
        }
        val principal = (authResult as AuthorizationResult.Authorized).principal

        val executionId = getUuidParam("id")
        if (executionId == null) {
            call.respondBadRequest("Invalid execution ID")
            return@get
        }

        val matchResult = validateExecutionIdMatch(executionId.toString(), principal)
        if (matchResult is AuthorizationResult.Denied) {
            call.respondAuthError(matchResult)
            return@get
        }

        val path = call.request.queryParameters["path"]

        try {
            val files = executionService.getFileTree(executionId, path)
            if (files == null) {
                call.respondNotFound("Execution not found")
            } else {
                call.respond(ExecutionFileTreeResponse(files))
            }
        } catch (e: Exception) {
            logger.error("Failed to get file tree", e)
            call.respondInternalError()
        }
    }
}

private fun Route.getFileContentsRoute(
    logger: Logger,
    executionService: ExecutionServiceWithFileTree
) {
    get("{id}/files/{path...}") {
        val authResult = call.requireScope(Scopes.PLUGIN_EXECUTION_READ)
        if (authResult is AuthorizationResult.Denied) {
            call.respondAuthError(authResult)
            return@get
        }
        val principal = (authResult as AuthorizationResult.Authorized).principal

        val executionId = getUuidParam("id")
        if (executionId == null) {
            call.respondBadRequest("Invalid execution ID")
            return@get
        }

        val matchResult = validateExecutionIdMatch(executionId.toString(), principal)
        if (matchResult is AuthorizationResult.Denied) {
            call.respondAuthError(matchResult)
            return@get
        }

        val filePath = call.parameters.getAll("path")?.joinToString("/") ?: ""
        if (filePath.isEmpty()) {
            call.respondBadRequest("File path required")
            return@get
        }

        try {
            val contents = executionService.getFileContents(executionId, filePath)
            if (contents == null) {
                call.respondNotFound("File not found")
            } else {
                call.respondText(contents, ContentType.Text.Plain)
            }
        } catch (e: Exception) {
            logger.error("Failed to get file contents", e)
            call.respondInternalError()
        }
    }
}
