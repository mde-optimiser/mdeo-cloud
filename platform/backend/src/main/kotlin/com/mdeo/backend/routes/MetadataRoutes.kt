package com.mdeo.backend.routes

import com.mdeo.common.transport.respondError
import com.mdeo.backend.plugins.*
import com.mdeo.backend.service.MetadataService
import com.mdeo.backend.service.ProjectPermission
import com.mdeo.backend.service.ProjectService
import com.mdeo.common.model.*
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.JsonObject
import java.util.*

/**
 * Configures metadata management routes for project files.
 *
 * @param metadataService Service for metadata operations
 * @param projectService Service for project access validation
 */
fun Route.metadataRoutes(metadataService: MetadataService, projectService: ProjectService) {
    route("/api/projects/{projectId}/metadata") {
        /**
         * Reads metadata for a file.
         *
         * @param projectId Path parameter for project UUID
         * @param path Variable path segments for file path
         * @return ApiResult with metadata as JsonObject or failure
         */
        get("{path...}") {
            val session = call.getUserSession()
            if (session == null) {
                call.respondError(HttpStatusCode.Unauthorized, "Not authenticated")
                return@get
            }
            
            val projectId = call.parameters["projectId"]?.let { 
                try { UUID.fromString(it) } catch (e: Exception) { null }
            }
            if (projectId == null) {
                call.respondError(HttpStatusCode.BadRequest, "Invalid project ID")
                return@get
            }
            
            val userId = try { UUID.fromString(session.userId) } catch (e: Exception) {
                call.respondError(HttpStatusCode.BadRequest, "Invalid user ID")
                return@get
            }

            if (!projectService.hasProjectPermission(projectId, userId, call.isAdmin(), ProjectPermission.READ)) {
                call.respondError(HttpStatusCode.Forbidden, "Access denied")
                return@get
            }

            val pathParts = call.parameters.getAll("path") ?: emptyList()
            val path = pathParts.joinToString("/")
            
            val result = metadataService.readMetadata(projectId, path)
            call.respondApiResult(result)
        }
        
        /**
         * Writes metadata for a file.
         *
         * @param projectId Path parameter for project UUID
         * @param path Variable path segments for file path
         * @param body JsonObject containing metadata to write
         * @return ApiResult indicating success or failure
         */
        put("{path...}") {
            val session = call.getUserSession()
            if (session == null) {
                call.respondError(HttpStatusCode.Unauthorized, "Not authenticated")
                return@put
            }
            
            val projectId = call.parameters["projectId"]?.let { 
                try { UUID.fromString(it) } catch (e: Exception) { null }
            }
            if (projectId == null) {
                call.respondError(HttpStatusCode.BadRequest, "Invalid project ID")
                return@put
            }
            
            val userId = try { UUID.fromString(session.userId) } catch (e: Exception) {
                call.respondError(HttpStatusCode.BadRequest, "Invalid user ID")
                return@put
            }

            if (!projectService.hasProjectPermission(projectId, userId, call.isAdmin(), ProjectPermission.WRITE)) {
                call.respondError(HttpStatusCode.Forbidden, "Access denied")
                return@put
            }

            val pathParts = call.parameters.getAll("path") ?: emptyList()
            val path = pathParts.joinToString("/")
            
            val metadata = call.receive<JsonObject>()
            val result = metadataService.writeMetadata(projectId, path, metadata)
            call.respondApiResult(result)
        }
    }
    
    route("/api/projects/{projectId}/executions/{executionId}/metadata") {
        /**
         * Reads metadata for an execution result file.
         *
         * @param projectId Path parameter for project UUID
         * @param executionId Path parameter for execution UUID
         * @param path Variable path segments for file path
         * @return ApiResult with metadata as JsonObject or failure
         */
        get("{path...}") {
            val session = call.getUserSession()
            if (session == null) {
                call.respondError(HttpStatusCode.Unauthorized, "Not authenticated")
                return@get
            }
            
            val projectId = call.parameters["projectId"]?.let { 
                try { UUID.fromString(it) } catch (e: Exception) { null }
            }
            if (projectId == null) {
                call.respondError(HttpStatusCode.BadRequest, "Invalid project ID")
                return@get
            }
            
            val userId = try { UUID.fromString(session.userId) } catch (e: Exception) {
                call.respondError(HttpStatusCode.BadRequest, "Invalid user ID")
                return@get
            }

            if (!projectService.hasProjectPermission(projectId, userId, call.isAdmin(), ProjectPermission.READ)) {
                call.respondError(HttpStatusCode.Forbidden, "Access denied")
                return@get
            }

            val executionId = call.parameters["executionId"]?.let {
                try { UUID.fromString(it) } catch (e: Exception) { null }
            }
            if (executionId == null) {
                call.respondError(HttpStatusCode.BadRequest, "Invalid execution ID")
                return@get
            }
            
            val pathParts = call.parameters.getAll("path") ?: emptyList()
            val path = pathParts.joinToString("/")
            
            val result = metadataService.readExecutionFileMetadata(executionId, path)
            call.respondApiResult(result)
        }
        
        /**
         * Writes metadata for an execution result file.
         *
         * @param projectId Path parameter for project UUID
         * @param executionId Path parameter for execution UUID
         * @param path Variable path segments for file path
         * @param body JsonObject containing metadata to write
         * @return ApiResult indicating success or failure
         */
        put("{path...}") {
            val session = call.getUserSession()
            if (session == null) {
                call.respondError(HttpStatusCode.Unauthorized, "Not authenticated")
                return@put
            }
            
            val projectId = call.parameters["projectId"]?.let { 
                try { UUID.fromString(it) } catch (e: Exception) { null }
            }
            if (projectId == null) {
                call.respondError(HttpStatusCode.BadRequest, "Invalid project ID")
                return@put
            }
            
            val userId = try { UUID.fromString(session.userId) } catch (e: Exception) {
                call.respondError(HttpStatusCode.BadRequest, "Invalid user ID")
                return@put
            }

            if (!projectService.hasProjectPermission(projectId, userId, call.isAdmin(), ProjectPermission.WRITE)) {
                call.respondError(HttpStatusCode.Forbidden, "Access denied")
                return@put
            }

            val executionId = call.parameters["executionId"]?.let {
                try { UUID.fromString(it) } catch (e: Exception) { null }
            }
            if (executionId == null) {
                call.respondError(HttpStatusCode.BadRequest, "Invalid execution ID")
                return@put
            }
            
            val pathParts = call.parameters.getAll("path") ?: emptyList()
            val path = pathParts.joinToString("/")
            
            val metadata = call.receive<JsonObject>()
            val result = metadataService.writeExecutionFileMetadata(executionId, path, metadata)
            call.respondApiResult(result)
        }
    }
}
