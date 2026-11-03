package com.mdeo.backend.routes

import com.mdeo.common.auth.Scopes
import com.mdeo.common.transport.RequestBodyTooLargeException
import com.mdeo.common.transport.receiveBoundedText
import com.mdeo.common.transport.respondError
import com.mdeo.backend.config.appJson
import com.mdeo.backend.plugins.*
import com.mdeo.backend.service.CallerDeadline
import com.mdeo.backend.service.JwtService
import com.mdeo.backend.service.PluginRequestService
import com.mdeo.backend.service.ProjectPermission
import com.mdeo.backend.service.ProjectService
import com.mdeo.common.model.PluginTarget
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.util.*

/**
 * Configures the one-shot plugin request endpoint.
 *
 * A request is addressed at `<kind>/<targetId>/<key>`, the same way a session is: language ids
 * and contribution ids share one namespace — `config-mdeo` is both — so the id alone would not
 * say what is being addressed.
 *
 * @param pluginRequestService Service forwarding the request to the plugin that serves the target
 * @param projectService Service checking what a signed-in user may reach
 * @param jwtService Service issuing the token the plugin is called with
 */
fun Route.pluginRequestRoutes(
    pluginRequestService: PluginRequestService,
    projectService: ProjectService,
    jwtService: JwtService
) {
    route("/api/projects/{projectId}/request/{kind}/{targetId}/{key}") {
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
                callerJwt = PluginRequestService.delegatedToken(
                    call.request.headers[HttpHeaders.Authorization],
                    call.request.headers[PluginRequestService.DELEGATE_TOKEN_HEADER]
                )
            } else {
                call.respondError(HttpStatusCode.Unauthorized, "Authentication required")
                return@post
            }

            val kind = call.parameters["kind"] ?: ""
            val targetId = call.parameters["targetId"] ?: ""
            val target = PluginTarget.ofOrNull(kind, targetId)
            if (target == null) {
                call.respondError(HttpStatusCode.BadRequest, "Not a plugin target: '$kind:$targetId'")
                return@post
            }

            val key = call.parameters["key"]
            if (key.isNullOrBlank()) {
                call.respondError(HttpStatusCode.BadRequest, "Missing request key")
                return@post
            }

            val body = try {
                appJson.parseToJsonElement(call.receiveBoundedText())
            } catch (e: RequestBodyTooLargeException) {
                call.respondError(HttpStatusCode.PayloadTooLarge, e.message!!)
                return@post
            } catch (e: Exception) {
                call.respondError(HttpStatusCode.BadRequest, "Invalid JSON body")
                return@post
            }

            val result = pluginRequestService.executeRequest(
                projectId,
                target,
                key,
                body,
                callerJwt,
                CallerDeadline.fromHeader(call.request.headers[CallerDeadline.HEADER]),
                jwtPrincipal?.payload?.getClaim(JwtService.CLAIM_COMPUTATION_ID)?.asString()
                    ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            )

            call.respondApiResult(result)
        }
    }
}
