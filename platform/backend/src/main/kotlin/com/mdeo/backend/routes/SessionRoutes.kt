package com.mdeo.backend.routes

import com.mdeo.common.transport.respondError
import com.mdeo.backend.plugins.*
import com.mdeo.backend.service.JwtService
import com.mdeo.backend.service.PluginService
import com.mdeo.common.model.PluginTarget
import com.mdeo.common.model.PluginTargetKind
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.*

/**
 * What a caller needs to open a session, handed back by the connect endpoint.
 *
 * Everything here is owned by the platform: where the session lives, what it is allowed to
 * speak, what authorizes it and how long that authorization lasts. What travels once the
 * connection is open is the plugin's own business.
 *
 * @property url WebSocket URL of the session endpoint
 * @property protocol Name of the protocol spoken on the session
 * @property versions Protocol versions the plugin side can speak, most preferred first
 * @property token Bearer token that authorizes exactly this session
 * @property expiresAt Epoch second at which [token] stops being accepted
 */
@Serializable
data class SessionConnectResponse(
    val url: String,
    val protocol: String,
    val versions: List<Int>,
    val token: String,
    val expiresAt: Long
)

/**
 * Configures the session connect endpoint.
 *
 * A session is reached at `<kind>/<targetId>/<name>`: the kind is spelled out because language
 * ids and contribution ids share one namespace — `config-mdeo` is both — so the id alone would
 * not say what is being addressed.
 *
 * Only an execution may open a session. The caller authenticates with the token it holds for
 * the run, and the token it gets back is bound to that same execution, so it stops working as
 * soon as the run ends.
 *
 * @param pluginService Service resolving targets and their declared sessions
 * @param jwtService Service issuing the session token
 */
fun Route.sessionRoutes(
    pluginService: PluginService,
    jwtService: JwtService
) {
    route("/api/projects/{projectId}/sessions/{kind}/{targetId}/{name}/connect") {
        /**
         * Resolves one session of one target and issues the token that opens it.
         *
         * @param projectId Path parameter for project UUID
         * @param kind Path parameter for the target kind, `lang` or `contrib`
         * @param targetId Path parameter for the language or contribution id
         * @param name Path parameter for the session name
         * @return [SessionConnectResponse], or a failure explaining which part did not resolve
         */
        post {
            val jwtPrincipal = call.getJwtPrincipal()
            if (jwtPrincipal == null) {
                call.respondError(HttpStatusCode.Unauthorized, "Execution token required")
                return@post
            }

            val projectId = call.parameters["projectId"]?.let {
                try { UUID.fromString(it) } catch (e: Exception) { null }
            }
            if (projectId == null) {
                call.respondError(HttpStatusCode.BadRequest, "Invalid project ID")
                return@post
            }

            if (jwtPrincipal.projectId != projectId.toString()) {
                call.respondError(HttpStatusCode.Forbidden, "Token not valid for this project")
                return@post
            }

            if (JwtService.SCOPE_EXECUTION_READ !in jwtPrincipal.scopes) {
                call.respondError(HttpStatusCode.Forbidden, "Token missing required scope")
                return@post
            }

            val executionId = jwtPrincipal.payload.getClaim(JwtService.CLAIM_EXECUTION_ID)?.asString()?.let {
                try { UUID.fromString(it) } catch (e: Exception) { null }
            }
            if (executionId == null) {
                call.respondError(HttpStatusCode.Forbidden, "Token is not bound to an execution, so it cannot open a session")
                return@post
            }

            val kind = call.parameters["kind"] ?: ""
            val targetId = call.parameters["targetId"] ?: ""
            val sessionName = call.parameters["name"] ?: ""

            val target = PluginTarget.parseOrNull("$kind:$targetId")
            if (target == null) {
                call.respondError(HttpStatusCode.BadRequest, "Not a plugin target: '$kind:$targetId'")
                return@post
            }

            val resolved = pluginService.findSession(projectId, target, sessionName)
            if (resolved == null) {
                call.respondError(HttpStatusCode.NotFound, "No session '$sessionName' declared by $target in this project. " +
                                "Check that the plugin providing $target is enabled and that it " +
                                "declares a session named '$sessionName'.")
                return@post
            }

            val ttlSeconds = jwtService.sessionConnectTokenTtlSeconds()
            val token = jwtService.generateSessionConnectToken(
                projectId, executionId, target, sessionName, ttlSeconds
            )

            call.respond(
                SessionConnectResponse(
                    url = sessionWebSocketUrl(resolved.pluginUrl, target, sessionName),
                    protocol = resolved.sessionType.protocol,
                    versions = resolved.sessionType.versions,
                    token = token,
                    expiresAt = Instant.now().plusSeconds(ttlSeconds).epochSecond
                )
            )
        }
    }

    route("/api/projects/{projectId}/sessions/{kind}/{targetId}/{name}/contribution-plugins") {
        /**
         * Lists the contribution plugins a `lang:` session has to load.
         *
         * Every request to a language service carries the project's contribution plugins in its
         * body, because the backend relays it. A session is dialed directly, so the language
         * service fetches the same list here while it opens the connection. It is fetched with
         * the session token rather than sent by the caller, so the caller cannot choose what is
         * loaded.
         *
         * @param projectId Path parameter for project UUID
         * @param kind Path parameter for the target kind, which must be `lang`
         * @param targetId Path parameter for the language id
         * @param name Path parameter for the session name
         * @return The server contribution plugins registered for the language in this project
         */
        get {
            val jwtPrincipal = call.getJwtPrincipal()
            if (jwtPrincipal == null) {
                call.respondError(HttpStatusCode.Unauthorized, "Session token required")
                return@get
            }

            val projectId = call.parameters["projectId"]?.let {
                try { UUID.fromString(it) } catch (e: Exception) { null }
            }
            if (projectId == null) {
                call.respondError(HttpStatusCode.BadRequest, "Invalid project ID")
                return@get
            }

            if (jwtPrincipal.projectId != projectId.toString()) {
                call.respondError(HttpStatusCode.Forbidden, "Token not valid for this project")
                return@get
            }

            if (JwtService.SCOPE_SESSION_CONNECT !in jwtPrincipal.scopes) {
                call.respondError(HttpStatusCode.Forbidden, "Token missing required scope")
                return@get
            }

            val kind = call.parameters["kind"] ?: ""
            val targetId = call.parameters["targetId"] ?: ""
            val sessionName = call.parameters["name"] ?: ""

            val target = PluginTarget.parseOrNull("$kind:$targetId")
            if (target == null || target.kind != PluginTargetKind.LANGUAGE) {
                call.respondError(HttpStatusCode.BadRequest, "Only language targets load contribution plugins, not '$kind:$targetId'")
                return@get
            }

            val claimedTarget = jwtPrincipal.payload.getClaim(JwtService.CLAIM_TARGET)?.asString()
            val claimedSession = jwtPrincipal.payload.getClaim(JwtService.CLAIM_SESSION)?.asString()
            if (claimedTarget != target.toString() || claimedSession != sessionName) {
                call.respondError(HttpStatusCode.Forbidden, "Token was issued for a different session")
                return@get
            }

            call.respond(pluginService.getContributionPluginsForLanguage(projectId, target.id))
        }
    }
}

/**
 * Builds the WebSocket URL of one session from the plugin's base URL.
 *
 * @param pluginBaseUrl Base URL the plugin is served at
 * @param target The addressed target
 * @param sessionName The session name
 * @return The `ws`/`wss` URL of the session endpoint
 */
internal fun sessionWebSocketUrl(pluginBaseUrl: String, target: PluginTarget, sessionName: String): String {
    val trimmed = pluginBaseUrl.trimEnd('/')
    val scheme = when {
        trimmed.startsWith("https://") -> "wss://" + trimmed.removePrefix("https://")
        trimmed.startsWith("http://") -> "ws://" + trimmed.removePrefix("http://")
        else -> trimmed
    }
    return "$scheme/ws/sessions/${target.kind.wire}/${target.id}/$sessionName"
}
