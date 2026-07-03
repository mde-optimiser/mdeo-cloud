package com.mdeo.backend.routes

import com.mdeo.backend.plugins.*
import com.mdeo.backend.service.CsvImportService
import com.mdeo.backend.service.ProjectPermission
import com.mdeo.backend.service.ProjectService
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.util.*

fun Route.csvImportRoutes(
    csvImportService: CsvImportService,
    projectService: ProjectService
) {
    route("/api/projects/{projectId}/csv-import") {
        post {
            val session = call.getUserSession()
            if (session == null) {
                call.respond(HttpStatusCode.Unauthorized)
                return@post
            }

            val projectId = call.parameters["projectId"]?.let {
                try { UUID.fromString(it) } catch (e: Exception) { null }
            }
            if (projectId == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid project ID"))
                return@post
            }

            val userId = try { UUID.fromString(session.userId) } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid user ID"))
                return@post
            }

            if (!projectService.hasProjectPermission(projectId, userId, call.isAdmin(), ProjectPermission.WRITE)) {
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Access denied"))
                return@post
            }

            val basePath = call.request.queryParameters["path"]
            if (basePath.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing path parameter"))
                return@post
            }

            val className = call.request.queryParameters["className"]
                ?: basePath.substringAfterLast('/').ifBlank { "ImportedCsvClass" }

            val csvText = call.receiveText()
            if (csvText.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Request body is empty"))
                return@post
            }

            val result = csvImportService.importCsv(projectId, csvText, basePath, className)
            call.respondApiResult(result)
        }
    }
}
