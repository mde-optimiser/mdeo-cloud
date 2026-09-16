package com.mdeo.backend.routes

import com.mdeo.common.transport.respondError
import com.mdeo.backend.plugins.*
import com.mdeo.backend.service.ExecutionService
import com.mdeo.backend.service.JwtService
import com.mdeo.backend.service.ProjectPermission
import com.mdeo.backend.service.ProjectService
import com.mdeo.common.model.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.util.*

/**
 * Configures execution management routes for session-authenticated users.
 *
 * @param executionService Service for execution operations
 * @param projectService Service for project access validation
 */
fun Route.executionRoutes(
    executionService: ExecutionService,
    projectService: ProjectService
) {
    route("/api/projects/{projectId}/executions") {
        /**
         * Lists all executions for a project.
         *
         * @param projectId Path parameter for project UUID
         * @return List of executions
         */
        get {
            val projectId =
                call.validateProjectAccessSessionOnly(projectService, ProjectPermission.READ) ?: return@get
            
            val executions = executionService.listExecutions(projectId)
            call.respond(executions)
        }
        
        /**
         * Creates a new execution.
         *
         * @param projectId Path parameter for project UUID
         * @param body CreateExecutionRequest with file path and data
         * @return Created execution
         */
        post {
            val projectId =
                call.validateProjectAccessSessionOnly(projectService, ProjectPermission.EXECUTE) ?: return@post
            
            val request = call.receive<CreateExecutionRequest>()
            val result = executionService.createExecution(projectId, request.filePath, request.data)
            
            call.respondApiResult(result)
        }
        
        /**
         * Deletes all executions for a project.
         *
         * @param projectId Path parameter for project UUID
         * @return Success or failure
         */
        delete {
            val projectId =
                call.validateProjectAccessSessionOnly(projectService, ProjectPermission.EXECUTE) ?: return@delete
            
            val result = executionService.deleteAllExecutions(projectId)
            call.respondApiResult(result)
        }
        
        /**
         * Gets an execution with its file tree.
         *
         * @param projectId Path parameter for project UUID
         * @param executionId Path parameter for execution UUID
         * @return Execution with file tree
         */
        get("{executionId}") {
            val projectId =
                call.validateProjectAccessSessionOnly(projectService, ProjectPermission.READ) ?: return@get
            
            val executionId = call.parameters["executionId"]?.let { 
                try { UUID.fromString(it) } catch (e: Exception) { null }
            }
            if (executionId == null) {
                call.respondError(HttpStatusCode.BadRequest, "Invalid execution ID")
                return@get
            }
            
            val result = executionService.getExecutionWithTree(projectId, executionId)
            call.respondApiResult(result)
        }
        
        /**
         * Gets the summary for an execution.
         *
         * @param projectId Path parameter for project UUID
         * @param executionId Path parameter for execution UUID
         * @return Summary content
         */
        get("{executionId}/summary") {
            val projectId =
                call.validateProjectAccessSessionOnly(projectService, ProjectPermission.READ) ?: return@get
            
            val executionId = call.parameters["executionId"]?.let { 
                try { UUID.fromString(it) } catch (e: Exception) { null }
            }
            if (executionId == null) {
                call.respondError(HttpStatusCode.BadRequest, "Invalid execution ID")
                return@get
            }
            
            val result = executionService.getExecutionSummary(projectId, executionId)
            when (result) {
                is ApiResult.Success -> call.respond(mapOf("summary" to result.value))
                is ApiResult.Failure -> call.respondError(HttpStatusCode.BadRequest, result.error)
            }
        }
        
        /**
         * Gets a result file for an execution.
         *
         * @param projectId Path parameter for project UUID
         * @param executionId Path parameter for execution UUID
         * @param path Variable path segments for file path
         * @return File contents
         */
        get("{executionId}/files/{path...}") {
            val projectId =
                call.validateProjectAccessSessionOnly(projectService, ProjectPermission.READ) ?: return@get
            
            val executionId = call.parameters["executionId"]?.let { 
                try { UUID.fromString(it) } catch (e: Exception) { null }
            }
            if (executionId == null) {
                call.respondError(HttpStatusCode.BadRequest, "Invalid execution ID")
                return@get
            }
            
            val pathParts = call.parameters.getAll("path") ?: emptyList()
            val path = pathParts.joinToString("/")
            
            val result = executionService.getExecutionFile(projectId, executionId, path)
            when (result) {
                is ApiResult.Success -> call.respondBytes(result.value)
                is ApiResult.Failure -> call.respondError(HttpStatusCode.BadRequest, result.error)
            }
        }
        
        /**
         * Cancels an execution.
         *
         * @param projectId Path parameter for project UUID
         * @param executionId Path parameter for execution UUID
         * @return Success or failure
         */
        post("{executionId}/cancel") {
            val projectId =
                call.validateProjectAccessSessionOnly(projectService, ProjectPermission.EXECUTE) ?: return@post
            
            val executionId = call.parameters["executionId"]?.let { 
                try { UUID.fromString(it) } catch (e: Exception) { null }
            }
            if (executionId == null) {
                call.respondError(HttpStatusCode.BadRequest, "Invalid execution ID")
                return@post
            }
            
            val result = executionService.cancelExecution(projectId, executionId)
            call.respondApiResult(result)
        }
        
        /**
         * Deletes an execution (implies cancel if running).
         *
         * @param projectId Path parameter for project UUID
         * @param executionId Path parameter for execution UUID
         * @return Success or failure
         */
        delete("{executionId}") {
            val projectId =
                call.validateProjectAccessSessionOnly(projectService, ProjectPermission.EXECUTE) ?: return@delete
            
            val executionId = call.parameters["executionId"]?.let { 
                try { UUID.fromString(it) } catch (e: Exception) { null }
            }
            if (executionId == null) {
                call.respondError(HttpStatusCode.BadRequest, "Invalid execution ID")
                return@delete
            }
            
            val result = executionService.deleteExecution(projectId, executionId)
            call.respondApiResult(result)
        }
    }
}

/**
 * Configures JWT-authenticated execution state update route.
 * This endpoint is used by services to update execution state via JWT.
 *
 * @param executionService Service for execution operations
 * @param jwtService Service for JWT operations
 */
fun Route.executionStateRoutes(
    executionService: ExecutionService,
    jwtService: JwtService
) {
    route("/api/executions/{executionId}/state") {
        /**
         * Updates the state of an execution.
         * Requires JWT authentication with execution:write scope.
         *
         * @param executionId Path parameter for execution UUID
         * @param body UpdateExecutionStateRequest with new state and optional progress
         * @return Updated execution
         */
        patch {
            val jwtPrincipal = call.getJwtPrincipal()
            
            if (jwtPrincipal == null) {
                call.respondError(HttpStatusCode.Unauthorized, "JWT authentication required")
                return@patch
            }
            
            if (JwtService.SCOPE_EXECUTION_WRITE !in jwtPrincipal.scopes) {
                call.respondError(HttpStatusCode.Forbidden, "Token missing execution:write scope")
                return@patch
            }
            
            val executionId = call.parameters["executionId"]?.let { 
                try { UUID.fromString(it) } catch (e: Exception) { null }
            }
            if (executionId == null) {
                call.respondError(HttpStatusCode.BadRequest, "Invalid execution ID")
                return@patch
            }
            
            val tokenExecutionId = jwtPrincipal.payload.getClaim(JwtService.CLAIM_EXECUTION_ID)?.asString()
            if (tokenExecutionId != executionId.toString()) {
                call.respondError(HttpStatusCode.Forbidden, "Token not valid for this execution")
                return@patch
            }
            
            val request = call.receive<UpdateExecutionStateRequest>()
            val result = executionService.updateExecutionState(
                executionId,
                request.state,
                request.progressText
            )
            
            call.respondApiResult(result)
        }
    }

    route("/api/executions/{executionId}/metadata") {
        /**
         * Updates metadata of an execution.
         * Requires JWT authentication with execution:write scope.
         *
         * @param executionId Path parameter for execution UUID
         * @param body UpdateExecutionMetadataRequest with metadata object
         * @return Updated execution
         */
        patch {
            val jwtPrincipal = call.getJwtPrincipal()

            if (jwtPrincipal == null) {
                call.respondError(HttpStatusCode.Unauthorized, "JWT authentication required")
                return@patch
            }

            if (JwtService.SCOPE_EXECUTION_WRITE !in jwtPrincipal.scopes) {
                call.respondError(HttpStatusCode.Forbidden, "Token missing execution:write scope")
                return@patch
            }

            val executionId = call.parameters["executionId"]?.let {
                try {
                    UUID.fromString(it)
                } catch (e: Exception) {
                    null
                }
            }
            if (executionId == null) {
                call.respondError(HttpStatusCode.BadRequest, "Invalid execution ID")
                return@patch
            }

            val tokenExecutionId = jwtPrincipal.payload.getClaim(JwtService.CLAIM_EXECUTION_ID)?.asString()
            if (tokenExecutionId != executionId.toString()) {
                call.respondError(HttpStatusCode.Forbidden, "Token not valid for this execution")
                return@patch
            }

            val request = call.receive<UpdateExecutionMetadataRequest>()
            val result = executionService.updateExecutionMetadata(
                executionId,
                request.metadata
            )

            call.respondApiResult(result)
        }
    }
}

/**
 * Validates project access using session authentication only (no JWT).
 * Used for execution operations that should only be accessible via session.
 *
 * @param projectService Service for project access validation
 * @return Project UUID if validation succeeds, null otherwise
 */
private suspend fun ApplicationCall.validateProjectAccessSessionOnly(
    projectService: ProjectService,
    requiredPermission: ProjectPermission
): UUID? {
    val session = getUserSession()
    if (session == null) {
        respondError(HttpStatusCode.Unauthorized, "Not authenticated")
        return null
    }
    
    val projectId = parameters["projectId"]?.let { 
        try { UUID.fromString(it) } catch (e: Exception) { null }
    }
    if (projectId == null) {
        respondError(HttpStatusCode.BadRequest, "Invalid project ID")
        return null
    }
    
    val userId = try { UUID.fromString(session.userId) } catch (e: Exception) {
        respondError(HttpStatusCode.BadRequest, "Invalid user ID")
        return null
    }
    
    if (!projectService.hasProjectPermission(projectId, userId, isAdmin(), requiredPermission)) {
        respondError(HttpStatusCode.Forbidden, "Access denied")
        return null
    }
    
    return projectId
}
