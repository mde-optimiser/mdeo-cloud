package com.mdeo.pluginservice.session

import com.mdeo.common.model.PluginTarget
import com.mdeo.pluginservice.ServedSession
import io.ktor.http.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

/**
 * Path every session endpoint lives under, followed by `<kind>/<targetId>/<name>`.
 */
const val SESSION_PATH_PREFIX = "/ws/sessions"

/**
 * Scope a token must carry to open a session.
 */
const val SESSION_CONNECT_SCOPE = "session:connect"

private val logger = LoggerFactory.getLogger("com.mdeo.pluginservice.session")

/**
 * Serves the session endpoint: `GET /ws/sessions/{kind}/{targetId}/{name}`, upgraded to a WebSocket.
 *
 * The connection is authorized once, when it opens. The token must carry `session:connect` and
 * name exactly this target and session, as tokens issued by the backend's connect endpoint do.
 * Keepalives are handled by the WebSockets plugin, see [installSessionWebSockets].
 *
 * @param sessions What this service serves, keyed by target address and then by session name
 * @param verifier Verifies the token of each connection
 */
fun Route.sessionEndpoint(
    sessions: Map<String, Map<String, ServedSession>>,
    verifier: SessionTokenVerifier
) {
    webSocket("$SESSION_PATH_PREFIX/{kind}/{targetId}/{name}") {
        val kind = call.parameters["kind"].orEmpty()
        val targetId = call.parameters["targetId"].orEmpty()
        val sessionName = call.parameters["name"].orEmpty()

        val target = PluginTarget.parseOrNull("$kind:$targetId")
        if (target == null) {
            refuse(SessionCloseCodes.NOT_FOUND, "Not a session address: $kind/$targetId/$sessionName")
            return@webSocket
        }
        val label = "$target/$sessionName"

        val token = call.request.headers[HttpHeaders.Authorization]
            ?.takeIf { it.startsWith("Bearer ") }
            ?.removePrefix("Bearer ")
            ?: call.request.queryParameters["token"]
        val claims = token?.let { verifier.verify(it) }
        if (token == null || claims == null) {
            refuse(SessionCloseCodes.UNAUTHORIZED, "Missing or invalid token")
            return@webSocket
        }
        if (SESSION_CONNECT_SCOPE !in claims.scopes) {
            refuse(SessionCloseCodes.UNAUTHORIZED, "Token missing $SESSION_CONNECT_SCOPE scope")
            return@webSocket
        }
        // The token names the one session it opens, so a token issued for one target cannot be
        // spent on another served by the same service.
        if (claims.target != target.toString() || claims.session != sessionName) {
            refuse(
                SessionCloseCodes.UNAUTHORIZED,
                "Token is for ${claims.target ?: "no target"}/${claims.session ?: "no session"}, not for $label"
            )
            return@webSocket
        }
        val projectId = claims.projectId
        val executionId = claims.executionId
        if (projectId == null || executionId == null) {
            refuse(SessionCloseCodes.UNAUTHORIZED, "Token names no project or no execution")
            return@webSocket
        }

        val served = sessions[target.toString()]?.get(sessionName)
        if (served == null) {
            refuse(SessionCloseCodes.NOT_FOUND, "This service serves no session $label")
            return@webSocket
        }

        val version = negotiateVersion(call.request.queryParameters.getAll("v").orEmpty(), served.type.versions)
        if (version == null) {
            refuse(
                SessionCloseCodes.VERSION_MISMATCH,
                "Session $label speaks ${served.type.protocol} version " +
                        "${served.type.versions.joinToString(", ")}, and none of those was requested"
            )
            return@webSocket
        }

        val context = OpenSession(this, projectId, executionId, target, sessionName, version, token)

        val peer = try {
            served.handler.open(context)
        } catch (e: Exception) {
            logger.error("Session $label failed to open", e)
            refuse(SessionCloseCodes.UNAVAILABLE, "Session could not be opened")
            return@webSocket
        }

        var endReason = "Connection ended"
        try {
            for (frame in incoming) {
                if (frame !is Frame.Binary && frame !is Frame.Text) continue
                try {
                    peer.onMessage(frame.readBytes())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error("Session $label failed on a message", e)
                }
            }
            endReason = closeReason.await()?.let { it.message.ifEmpty { "Closed with code ${it.code}" } }
                ?: endReason
        } catch (e: CancellationException) {
            endReason = "Connection dropped"
            throw e
        } finally {
            try {
                peer.onClose(endReason)
            } catch (e: Exception) {
                logger.error("Session $label failed while closing", e)
            }
        }
    }
}

/**
 * The [SessionContext] of one connection that passed the handshake.
 */
private class OpenSession(
    private val socket: DefaultWebSocketServerSession,
    override val projectId: String,
    override val executionId: String,
    override val target: PluginTarget,
    override val sessionName: String,
    override val version: Int,
    override val token: String
) : SessionContext {
    override suspend fun send(data: ByteArray) = socket.send(Frame.Binary(true, data))

    override suspend fun close(reason: String) = socket.close(CloseReason(CloseReason.Codes.NORMAL, reason))
}

/**
 * Picks the protocol version both sides can speak.
 *
 * The caller lists the versions it speaks in `?v=`, most preferred first, and the first one the
 * plugin also declares wins. A caller that names none gets the plugin's preferred version.
 *
 * @param requested The raw values of every `v` parameter
 * @param declared The versions the plugin declares
 * @return The agreed version, or null when there is no overlap
 */
internal fun negotiateVersion(requested: List<String>, declared: List<Int>): Int? {
    val wanted = requested.flatMap { it.split(",") }.mapNotNull { it.trim().toIntOrNull() }
    if (wanted.isEmpty()) {
        return declared.firstOrNull()
    }
    return wanted.firstOrNull { it in declared }
}

private suspend fun DefaultWebSocketServerSession.refuse(code: Short, reason: String) {
    logger.warn("Refusing session: $reason")
    close(CloseReason(code, reason))
}
