package com.mdeo.pluginservice.request

import com.mdeo.common.auth.Scopes
import com.mdeo.common.model.PluginRequest
import com.mdeo.common.model.PluginResponse
import com.mdeo.common.model.PluginTarget
import com.mdeo.common.transport.RequestBodyTooLargeException
import com.mdeo.common.transport.receiveBoundedText
import com.mdeo.common.transport.respondError
import com.mdeo.pluginservice.PluginTokenVerifier
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("com.mdeo.pluginservice.request")

/**
 * Read and written without content negotiation, so a plugin service that installs none — or one
 * that configures it for its own routes — answers this endpoint the same way either way.
 */
private val requestJson = Json { ignoreUnknownKeys = true }

/**
 * Serves the one-shot request endpoint: `POST /request/{kind}/{targetId}/{key}`.
 *
 * The address is the one the backend forwards to, and the same shape a session is reached at, so
 * a contribution answers a plain call exactly the way a language plugin does. Unlike a session,
 * nothing is negotiated and nothing is kept: the request is authorized, answered and forgotten.
 *
 * @param handlers What this service answers, keyed by target and then by request key
 * @param verifier Verifies the token of each request
 */
fun Route.requestEndpoint(
    handlers: Map<PluginTarget, Map<String, RequestHandler>>,
    verifier: PluginTokenVerifier
) {
    post("/request/{kind}/{targetId}/{key}") {
        val kind = call.parameters["kind"].orEmpty()
        val targetId = call.parameters["targetId"].orEmpty()
        val key = call.parameters["key"].orEmpty()

        val target = PluginTarget.ofOrNull(kind, targetId)
        if (target == null) {
            call.respondError(HttpStatusCode.NotFound, "Not a plugin target: '$kind:$targetId'")
            return@post
        }
        val label = "$target/$key"

        val token = call.request.headers[HttpHeaders.Authorization]
            ?.takeIf { it.startsWith("Bearer ") }
            ?.removePrefix("Bearer ")
        val claims = token?.let { verifier.verify(it) }
        if (token == null || claims == null) {
            call.respondError(HttpStatusCode.Unauthorized, "Missing or invalid token")
            return@post
        }
        if (Scopes.PLUGIN_REQUEST_SEND !in claims.scopes) {
            call.respondError(HttpStatusCode.Forbidden, "Token missing ${Scopes.PLUGIN_REQUEST_SEND} scope")
            return@post
        }

        val handler = handlers[target]?.get(key)
        if (handler == null) {
            call.respondError(HttpStatusCode.NotFound, "This service answers no request $label")
            return@post
        }

        val payload = try {
            requestJson.decodeFromString(PluginRequest.serializer(), call.receiveBoundedText())
        } catch (e: RequestBodyTooLargeException) {
            call.respondError(HttpStatusCode.PayloadTooLarge, e.message!!)
            return@post
        } catch (e: Exception) {
            call.respondError(HttpStatusCode.BadRequest, "The request body could not be read")
            return@post
        }

        // The token says which project the caller speaks for; the body only repeats it, so a
        // mismatch means the body is not the one this token was issued for.
        val projectId = claims.projectId
        if (projectId == null || projectId != payload.project) {
            call.respondError(HttpStatusCode.Forbidden, "Token is not valid for project '${payload.project}'")
            return@post
        }

        val answer = try {
            handler.handle(RequestContext(projectId, target, key, payload.body, token))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // What went wrong inside the handler is for this service's log, not for the caller.
            logger.error("Request $label failed", e)
            call.respondError(HttpStatusCode.InternalServerError, "The request could not be answered")
            return@post
        }

        call.respondText(
            requestJson.encodeToString(PluginResponse.serializer(), PluginResponse(data = answer)),
            ContentType.Application.Json
        )
    }
}

/**
 * Everything a plugin service knows about one one-shot request.
 *
 * @property projectId The project the request was made for
 * @property target The addressed target
 * @property key The request key, the last segment of the address
 * @property body What the caller sent, in whatever shape the two sides agreed on
 * @property token The token the request arrived with, to pass on to the backend
 */
data class RequestContext(
    val projectId: String,
    val target: PluginTarget,
    val key: String,
    val body: JsonElement,
    val token: String
)

/**
 * Answers one request key of one target.
 */
fun interface RequestHandler {
    /**
     * Answers one request.
     *
     * @param context The request
     * @return What to send back, [JsonNull] for nothing
     */
    suspend fun handle(context: RequestContext): JsonElement
}
