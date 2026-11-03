package com.mdeo.scriptexecution.routes

import com.mdeo.common.auth.Scopes
import com.mdeo.execution.common.auth.*
import com.mdeo.execution.common.routes.*
import com.mdeo.execution.common.routes.ErrorResponses.respondBadRequest
import com.mdeo.execution.common.routes.ErrorResponses.respondInternalError
import com.mdeo.execution.common.routes.RouteUtils.getUuidParam
import com.mdeo.scriptexecution.service.ExecutionService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("ExecutionRoutes")

/**
 * Configures all routes for the script execution service.
 * Includes creation endpoint and inherits common routes from execution-common.
 *
 * @param executionService Service for execution operations
 */
fun Route.executionRoutes(executionService: ExecutionService) {
    baseExecutionRoutes(executionService = executionService)
    
    authenticate(AUTH_JWT) {
        route("/api/executions") {
            createExecutionRoute(executionService)
        }
    }
}

/**
 * Route handler for creating new script executions.
 */
private fun Route.createExecutionRoute(executionService: ExecutionService) {
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
            logger.error("Invalid request body", e)
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

        val executionMatch = validateExecutionIdMatch(executionId.toString(), principal)
        if (executionMatch is AuthorizationResult.Denied) {
            call.respondAuthError(executionMatch)
            return@post
        }

        val jwtToken = call.getJwtToken() ?: run {
            call.respondBadRequest("Missing JWT token")
            return@post
        }

        try {
            val name = executionService.createAndStartExecution(
                executionId,
                projectId,
                request.filePath,
                request.data,
                jwtToken
            )
            call.respond(HttpStatusCode.Created, CreateExecutionResponse(name))
        } catch (e: IllegalArgumentException) {
            logger.warn("Invalid request: ${e.message}")
            call.respondBadRequest(e.message ?: "Invalid request")
        } catch (e: Exception) {
            logger.error("Failed to create execution", e)
            call.respondInternalError(e.message ?: "Internal error")
        }
    }
}
