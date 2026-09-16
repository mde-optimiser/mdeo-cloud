package com.mdeo.optimizerexecution.routes

import com.mdeo.common.auth.Scopes
import com.mdeo.execution.common.auth.*
import com.mdeo.execution.common.routes.*
import com.mdeo.execution.common.routes.ErrorResponses.respondBadRequest
import com.mdeo.execution.common.routes.ErrorResponses.respondInternalError
import com.mdeo.execution.common.routes.RouteUtils.getUuidParam
import com.mdeo.optimizerexecution.service.OptimizerExecutionService
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.slf4j.LoggerFactory

/** Logger instance for optimizer HTTP route handlers. */
private val logger = LoggerFactory.getLogger("OptimizerRoutes")

/**
 * Registers common execution routes and the optimizer-specific POST creation route.
 *
 * @param executionService The service that handles optimization execution lifecycle.
 */
fun Route.optimizerRoutes(executionService: OptimizerExecutionService) {
    executionRoutesWithFileTree(executionService = executionService)
    authenticate(AUTH_JWT) {
        route("/api/executions") {
            createOptimizationExecutionRoute(executionService)
        }
    }
}

/**
 * Registers POST `/api/executions` — creates and starts a new optimization execution.
 *
 * @param executionService The service that creates and launches the execution.
 */
private fun Route.createOptimizationExecutionRoute(executionService: OptimizerExecutionService) {
    post {
        val authResult = call.requireScope(Scopes.PLUGIN_EXECUTION_START)
        if (authResult is AuthorizationResult.Denied) {
            call.respondAuthError(authResult)
            return@post
        }
        val principal = (authResult as AuthorizationResult.Authorized).principal

        val request = try {
            call.receive<CreateExecutionRequest>()
        } catch (e: Exception) {
            call.respondBadRequest("Invalid request body")
            return@post
        }

        val executionId = RouteUtils.parseUuid(request.executionId)
        val projectId = RouteUtils.parseUuid(request.project)

        if (executionId == null || projectId == null) {
            call.respondBadRequest("Invalid executionId or project UUID")
            return@post
        }

        val projectMatch = validateProjectIdMatch(projectId.toString(), principal)
        if (projectMatch is AuthorizationResult.Denied) {
            call.respondAuthError(projectMatch)
            return@post
        }

        val jwtToken = call.getJwtToken() ?: run {
            call.respondBadRequest("Missing JWT token")
            return@post
        }

        try {
            val displayName = executionService.createAndStartExecution(
                executionId, projectId, request.filePath, request.data, jwtToken
            )
            call.respond(HttpStatusCode.Created, CreateExecutionResponse(displayName))
        } catch (e: IllegalArgumentException) {
            logger.warn("Invalid optimization request: ${e.message}")
            call.respondBadRequest(e.message ?: "Invalid request")
        } catch (e: Exception) {
            logger.error("Failed to create optimizer execution", e)
            call.respondInternalError(e.message ?: "Internal error")
        }
    }
}
