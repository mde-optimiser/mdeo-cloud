package com.mdeo.backend.routes

import com.mdeo.common.auth.Scopes
import com.mdeo.common.transport.respondError
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
                call.respondError(HttpStatusCode.BadRequest, "Invalid project ID")
                return@post
            }

            var callerJwt: String? = null

            if (session != null) {
                val userId = try { UUID.fromString(session.userId) } catch (e: Exception) {
                    call.respondError(HttpStatusCode.BadRequest, "Invalid user ID")
                    return@post
                }

                if (!projectService.hasProjectPermission(projectId, userId, call.isAdmin(), ProjectPermission.READ)) {
                    call.respondError(HttpStatusCode.Forbidden, "Access denied")
                    return@post
                }
            } else if (jwtPrincipal != null) {
                if (jwtPrincipal.projectId != projectId.toString()) {
                    call.respondError(HttpStatusCode.Forbidden, "Token not valid for this project")
                    return@post
                }
                if (Scopes.PLUGIN_REQUEST_SEND !in jwtPrincipal.scopes) {
                    call.respondError(HttpStatusCode.Forbidden, "Token missing required scope")
                    return@post
                }
                val authHeader = call.request.headers[HttpHeaders.Authorization]
                if (authHeader != null && authHeader.startsWith("Bearer ")) {
                    callerJwt = authHeader.substring(7)
                }
            } else {
                call.respondError(HttpStatusCode.Unauthorized, "Authentication required")
                return@post
            }

            val languageId = call.parameters["languageId"]
            if (languageId.isNullOrBlank()) {
                call.respondError(HttpStatusCode.BadRequest, "Missing language ID")
                return@post
            }

            val key = call.parameters["key"]
            if (key.isNullOrBlank()) {
                call.respondError(HttpStatusCode.BadRequest, "Missing request key")
                return@post
            }

            val body = try {
                call.receive<JsonElement>()
            } catch (e: Exception) {
                call.respondError(HttpStatusCode.BadRequest, "Invalid JSON body")
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
