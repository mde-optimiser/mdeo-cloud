package com.mdeo.backend.routes

import com.mdeo.common.transport.respondError
import com.mdeo.backend.plugins.*
import com.mdeo.backend.service.FileService
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
 * Configures file and directory management routes.
 *
 * @param fileService Service for file operations
 * @param projectService Service for project access validation
 */
fun Route.fileRoutes(fileService: FileService, projectService: ProjectService) {
    route("/api/projects/{projectId}/files") {
        /**
         * Reads file contents with version information.
         *
         * @param projectId Path parameter for project UUID
         * @param path Variable path segments for file path
         * @return JSON with text content and version, or ApiResult.Failure on error
         */
        get("{path...}") {
            val projectId = call.validateProjectAccessWithJwt(projectService) ?: return@get
            
            val pathParts = call.parameters.getAll("path") ?: emptyList()
            val path = pathParts.joinToString("/")
            
            val contentResult = fileService.readFile(projectId, path)
            val versionResult = fileService.getFileVersion(projectId, path)
            
            when {
                contentResult is ApiResult.Failure -> call.respondApiResult(contentResult as ApiResult<Any>)
                versionResult is ApiResult.Failure -> call.respondApiResult(versionResult as ApiResult<Any>)
                contentResult is ApiResult.Success && versionResult is ApiResult.Success -> {
                    val textContent = String(contentResult.value, Charsets.UTF_8)
                    call.respond(FileReadResponse(textContent, versionResult.value))
                }
            }
        }
        
        /**
         * Creates or updates a file (POST variant).
         *
         * @param projectId Path parameter for project UUID
         * @param path Variable path segments for file path
         * @param create Query parameter to allow creation (default true)
         * @param overwrite Query parameter to allow overwrite (default false)
         * @param body File contents as byte array
         * @return ApiResult indicating success or failure
         */
        post("{path...}") {
            val projectId =
                call.validateProjectAccessSessionOnly(projectService, ProjectPermission.WRITE) ?: return@post
            
            val pathParts = call.parameters.getAll("path") ?: emptyList()
            val path = pathParts.joinToString("/")
            
            val content = call.receive<ByteArray>()
            val create = call.request.queryParameters["create"]?.toBoolean() ?: true
            val overwrite = call.request.queryParameters["overwrite"]?.toBoolean() ?: false
            
            val result = fileService.writeFile(projectId, path, content, create, overwrite)
            call.respondApiResult(result)
        }
        
        /**
         * Updates or creates a file (PUT variant).
         *
         * @param projectId Path parameter for project UUID
         * @param path Variable path segments for file path
         * @param create Query parameter to allow creation (default false)
         * @param overwrite Query parameter to allow overwrite (default true)
         * @param body File contents as byte array
         * @return ApiResult indicating success or failure
         */
        put("{path...}") {
            val projectId =
                call.validateProjectAccessSessionOnly(projectService, ProjectPermission.WRITE) ?: return@put
            
            val pathParts = call.parameters.getAll("path") ?: emptyList()
            val path = pathParts.joinToString("/")
            
            val content = call.receive<ByteArray>()
            val create = call.request.queryParameters["create"]?.toBoolean() ?: false
            val overwrite = call.request.queryParameters["overwrite"]?.toBoolean() ?: true
            
            val result = fileService.writeFile(projectId, path, content, create, overwrite)
            call.respondApiResult(result)
        }
        
        /**
         * Deletes a file or directory.
         *
         * @param projectId Path parameter for project UUID
         * @param path Variable path segments for file path
         * @param recursive Query parameter to enable recursive deletion (default false)
         * @return ApiResult indicating success or failure
         */
        delete("{path...}") {
            val projectId =
                call.validateProjectAccessSessionOnly(projectService, ProjectPermission.WRITE) ?: return@delete
            
            val pathParts = call.parameters.getAll("path") ?: emptyList()
            val path = pathParts.joinToString("/")
            
            val recursive = call.request.queryParameters["recursive"]?.toBoolean() ?: false
            
            val result = fileService.delete(projectId, path, recursive)
            call.respondApiResult(result)
        }
    }
    
    route("/api/projects/{projectId}/dirs") {
        /**
         * Creates a directory.
         *
         * @param projectId Path parameter for project UUID
         * @param path Variable path segments for directory path
         * @return ApiResult indicating success or failure
         */
        post("{path...}") {
            val projectId =
                call.validateProjectAccessSessionOnly(projectService, ProjectPermission.WRITE) ?: return@post
            
            val pathParts = call.parameters.getAll("path") ?: emptyList()
            val path = pathParts.joinToString("/")
            
            val result = fileService.mkdir(projectId, path)
            call.respondApiResult(result)
        }
        
        /**
         * Reads directory contents.
         *
         * @param projectId Path parameter for project UUID
         * @param path Variable path segments for directory path (defaults to root)
         * @return ApiResult with directory contents or failure
         */
        get("{path...}") {
            val projectId = call.validateProjectAccessWithJwt(projectService) ?: return@get
            
            val pathParts = call.parameters.getAll("path") ?: emptyList()
            val path = pathParts.joinToString("/").ifEmpty { "/" }
            
            val result = fileService.readdir(projectId, path)
            call.respondApiResult(result)
        }
    }
    
    route("/api/projects/{projectId}/stat") {
        /**
         * Gets file or directory metadata.
         *
         * @param projectId Path parameter for project UUID
         * @param path Variable path segments for file/directory path
         * @return ApiResult with file stats or failure
         */
        get("{path...}") {
            val projectId = call.validateProjectAccessWithJwt(projectService) ?: return@get
            
            val pathParts = call.parameters.getAll("path") ?: emptyList()
            val path = pathParts.joinToString("/")
            
            val result = fileService.stat(projectId, path)
            call.respondApiResult(result)
        }
    }
    
    route("/api/projects/{projectId}/version") {
        /**
         * Gets the version of a file.
         *
         * @param projectId Path parameter for project UUID
         * @param path Variable path segments for file path
         * @return ApiResult with file version or failure
         */
        get("{path...}") {
            val projectId = call.validateProjectAccessWithJwt(projectService) ?: return@get
            
            val pathParts = call.parameters.getAll("path") ?: emptyList()
            val path = pathParts.joinToString("/")
            
            val result = fileService.getFileVersion(projectId, path)
            call.respondApiResult(result)
        }
    }
    
    route("/api/projects/{projectId}/rename") {
        /**
         * Renames or moves a file or directory.
         *
         * @param projectId Path parameter for project UUID
         * @param from Query parameter for source path
         * @param to Query parameter for destination path
         * @param overwrite Query parameter to allow overwrite (default false)
         * @return ApiResult indicating success or failure
         */
        post {
            val projectId =
                call.validateProjectAccessSessionOnly(projectService, ProjectPermission.WRITE) ?: return@post
            
            val from = call.request.queryParameters["from"]
            val to = call.request.queryParameters["to"]
            val overwrite = call.request.queryParameters["overwrite"]?.toBoolean() ?: false
            
            if (from == null || to == null) {
                call.respondError(HttpStatusCode.BadRequest, "Missing 'from' or 'to' parameter")
                return@post
            }
            
            val result = fileService.rename(projectId, from, to, overwrite)
            call.respondApiResult(result)
        }
    }
}

/**
 * Validates project access using either session or JWT authentication.
 * JWT tokens must have the files:read scope.
 *
 * @param projectService Service for project access validation
 * @return Project UUID if validation succeeds, null otherwise
 */
private suspend fun ApplicationCall.validateProjectAccessWithJwt(projectService: ProjectService): UUID? {
    val session = getUserSession()
    val jwtPrincipal = getJwtPrincipal()
    
    val projectId = parameters["projectId"]?.let { 
        try { UUID.fromString(it) } catch (e: Exception) { null }
    }
    if (projectId == null) {
        respondError(HttpStatusCode.BadRequest, "Invalid project ID")
        return null
    }
    
    if (session != null) {
        val userId = try { UUID.fromString(session.userId) } catch (e: Exception) {
            respondError(HttpStatusCode.BadRequest, "Invalid user ID")
            return null
        }
        
        if (!projectService.hasProjectPermission(projectId, userId, isAdmin(), ProjectPermission.READ)) {
            respondError(HttpStatusCode.Forbidden, "Access denied")
            return null
        }
        
        return projectId
    } else if (jwtPrincipal != null) {
        if (jwtPrincipal.projectId != projectId.toString()) {
            respondError(HttpStatusCode.Forbidden, "Token not valid for this project")
            return null
        }
        if (JwtService.SCOPE_FILES_READ !in jwtPrincipal.scopes) {
            respondError(HttpStatusCode.Forbidden, "Token missing required scope")
            return null
        }
        return projectId
    }
    
    respondError(HttpStatusCode.Unauthorized, "Authentication required")
    return null
}

/**
 * Validates project access using session authentication only (no JWT).
 * Used for write operations that should not be accessible via JWT.
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
