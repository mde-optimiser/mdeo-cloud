package com.mdeo.backend.routes

import com.mdeo.backend.plugins.*
import com.mdeo.backend.service.CallerDeadline
import com.mdeo.backend.service.FileDataService
import com.mdeo.backend.service.JwtService
import com.mdeo.backend.service.ProjectPermission
import com.mdeo.backend.service.ProjectService
import com.mdeo.common.model.*
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.util.*

/**
 * Configures file data computation routes.
 *
 * @param fileDataService Service for file data computation
 * @param projectService Service for project access validation
 * @param jwtService Service for JWT operations
 */
fun Route.fileDataRoutes(
    fileDataService: FileDataService,
    projectService: ProjectService,
    jwtService: JwtService
) {
    route("/api/projects/{projectId}/file-data/{key}") {
        /**
         * Gets computed file data for a specific file.
         * Requires either path or language parameter, but not both.
         *
         * @param projectId Path parameter for project UUID
         * @param key Path parameter for data key (e.g., "ast")
         * @param path Query parameter for file path (mutually exclusive with language)
         * @param language Query parameter for language ID, assumes root path (mutually exclusive with path)
         * @return ApiResult with computed data or failure
         */
        get {
            val session = call.getUserSession()
            val jwtPrincipal = call.getJwtPrincipal()
            
            val projectId = call.parameters["projectId"]?.let { 
                try { UUID.fromString(it) } catch (e: Exception) { null }
            }
            if (projectId == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid project ID"))
                return@get
            }
            
            if (session != null) {
                val userId = try { UUID.fromString(session.userId) } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid user ID"))
                    return@get
                }
                
                if (!projectService.hasProjectPermission(projectId, userId, call.isAdmin(), ProjectPermission.READ)) {
                    call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Access denied"))
                    return@get
                }
            } else if (jwtPrincipal != null) {
                if (jwtPrincipal.projectId != projectId.toString()) {
                    call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Token not valid for this project"))
                    return@get
                }
                if (JwtService.SCOPE_FILE_DATA_READ !in jwtPrincipal.scopes) {
                    call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Token missing required scope"))
                    return@get
                }
            } else {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Authentication required"))
                return@get
            }
            
            val key = call.parameters["key"]
            if (key.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing data key"))
                return@get
            }
            
            val path = call.request.queryParameters["path"]
            val language = call.request.queryParameters["language"]
            
            if (path.isNullOrBlank() && language.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Either path or language parameter is required"))
                return@get
            }
            
            if (!path.isNullOrBlank() && !language.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "path and language parameters are mutually exclusive"))
                return@get
            }
            
            val callerComputationId = jwtPrincipal?.payload?.getClaim(JwtService.CLAIM_COMPUTATION_ID)?.asString()
                ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            val deadline = CallerDeadline.fromHeader(call.request.headers[CallerDeadline.HEADER])
            val result = fileDataService.getFileData(projectId, path, language, key, callerComputationId, deadline)
            call.respondApiResult(result)
        }
    }
}
