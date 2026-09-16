package com.mdeo.backend.routes

import com.mdeo.backend.plugins.*
import com.mdeo.backend.service.CallerDeadline
import com.mdeo.backend.service.JwtService
import com.mdeo.backend.service.LanguagePluginRequestService
import com.mdeo.backend.service.ProjectPermission
import com.mdeo.backend.service.ProjectService
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.JsonElement
import java.util.*

fun Route.languagePluginRequestRoutes(
    languagePluginRequestService: LanguagePluginRequestService,
    projectService: ProjectService,
    jwtService: JwtService
) {
    route("/api/projects/{projectId}/request/{languageId}/{key}") {
        post {
            val session = call.getUserSession()
            val jwtPrincipal = call.getJwtPrincipal()

            val projectId = call.parameters["projectId"]?.let {
                try { UUID.fromString(it) } catch (e: Exception) { null }
            }
            if (projectId == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid project ID"))
                return@post
            }

            var callerJwt: String? = null

            if (session != null) {
                val userId = try { UUID.fromString(session.userId) } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid user ID"))
                    return@post
                }

                if (!projectService.hasProjectPermission(projectId, userId, call.isAdmin(), ProjectPermission.READ)) {
                    call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Access denied"))
                    return@post
                }
            } else if (jwtPrincipal != null) {
                if (jwtPrincipal.projectId != projectId.toString()) {
                    call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Token not valid for this project"))
                    return@post
                }
                if (JwtService.SCOPE_FILE_DATA_READ !in jwtPrincipal.scopes) {
                    call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Token missing required scope"))
                    return@post
                }
                val authHeader = call.request.headers[HttpHeaders.Authorization]
                if (authHeader != null && authHeader.startsWith("Bearer ")) {
                    callerJwt = authHeader.substring(7)
                }
            } else {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Authentication required"))
                return@post
            }

            val languageId = call.parameters["languageId"]
            if (languageId.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing language ID"))
                return@post
            }

            val key = call.parameters["key"]
            if (key.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing request key"))
                return@post
            }

            val body = try {
                call.receive<JsonElement>()
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid JSON body"))
                return@post
            }

            val result = languagePluginRequestService.executeRequest(
                projectId,
                languageId,
                key,
                body,
                callerJwt,
                CallerDeadline.fromHeader(call.request.headers[CallerDeadline.HEADER])
            )

            call.respondApiResult(result)
        }
    }
}
