package com.mdeo.execution.common.api

import com.mdeo.common.transport.acceptCompressedResponses
import com.mdeo.common.model.PluginTarget
import com.mdeo.common.transport.SessionConnection
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * The backend's answer to a session connect request.
 *
 * @property url WebSocket URL of the session endpoint
 * @property protocol Name of the protocol spoken on the session
 * @property versions Protocol versions the plugin side can speak, most preferred first
 * @property token Bearer token that authorizes exactly this session
 * @property expiresAt Epoch second at which the token stops being accepted
 */
@Serializable
private data class SessionConnectResponse(
    val url: String,
    val protocol: String,
    val versions: List<Int>,
    val token: String,
    val expiresAt: Long
)

/**
 * Resolves where a session lives and gets a token to open it.
 *
 * An execution node never addresses a plugin directly: it asks the backend, with the token it
 * holds for the run, and the backend decides whether the project has that target at all and
 * what it is allowed to speak. The answer is cached for the run, because the address and the
 * protocol do not change while a run lasts — but the token in it does expire, and a reconnect
 * needs a valid one, so the token is fetched again once it is close to its expiry.
 *
 * @param baseUrl Base URL of the backend API
 * @param tokenLifetimeMarginSeconds How long before its expiry a cached token is refetched
 */
class SessionResolver(
    private val baseUrl: String,
    private val tokenLifetimeMarginSeconds: Long = DEFAULT_TOKEN_MARGIN_SECONDS
) : AutoCloseable {

    companion object {
        /**
         * How long before its expiry a cached token is considered spent. A dial that starts
         * just inside the margin must still finish inside the token's real lifetime.
         */
        const val DEFAULT_TOKEN_MARGIN_SECONDS = 60L
    }

    private val logger = LoggerFactory.getLogger(SessionResolver::class.java)

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = BACKEND_REQUEST_TIMEOUT_MS
            socketTimeoutMillis = BACKEND_REQUEST_TIMEOUT_MS
        }
        acceptCompressedResponses()
    }

    private val cache = ConcurrentHashMap<String, SessionConnection>()
    private val mutex = Mutex()

    /**
     * Resolves one session of one target.
     *
     * @param projectId The project the execution belongs to
     * @param target The addressed target, e.g. `contrib:script-functions`
     * @param sessionName The session name
     * @param runToken The token the node holds for the run
     * @return Everything needed to dial the session
     * @throws SessionResolutionException when the project has no such session, or the backend
     *         could not be reached
     */
    suspend fun resolve(
        projectId: String,
        target: PluginTarget,
        sessionName: String,
        runToken: String
    ): SessionConnection {
        val key = "$projectId/$target/$sessionName"
        cache[key]?.let { cached ->
            if (cached.expiresAt - tokenLifetimeMarginSeconds > Instant.now().epochSecond) {
                return cached
            }
        }

        return mutex.withLock {
            cache[key]?.let { cached ->
                if (cached.expiresAt - tokenLifetimeMarginSeconds > Instant.now().epochSecond) {
                    return@withLock cached
                }
            }
            val fresh = fetch(projectId, target, sessionName, runToken)
            cache[key] = fresh
            fresh
        }
    }

    /**
     * Asks the backend for a session, without consulting the cache.
     */
    private suspend fun fetch(
        projectId: String,
        target: PluginTarget,
        sessionName: String,
        runToken: String
    ): SessionConnection {
        val url = "$baseUrl/projects/$projectId/sessions/${target.kind.wire}/${target.id}/$sessionName/connect"
        val response = try {
            client.post(url) {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer $runToken")
            }
        } catch (e: Exception) {
            throw SessionResolutionException("Could not reach the backend to open $target/$sessionName", e)
        }

        if (response.status != HttpStatusCode.OK) {
            throw SessionResolutionException(
                "The backend refused to open $target/$sessionName: ${response.status}. " +
                        "Check that the plugin providing $target is enabled for this project and " +
                        "declares a session named '$sessionName'."
            )
        }

        val body = response.body<SessionConnectResponse>()
        logger.info("Resolved session $target/$sessionName to ${body.url} (${body.protocol})")
        return SessionConnection(
            url = body.url,
            protocol = body.protocol,
            versions = body.versions,
            token = body.token,
            expiresAt = body.expiresAt
        )
    }

    /**
     * Closes the HTTP client and drops everything cached.
     */
    override fun close() {
        cache.clear()
        client.close()
    }
}

/**
 * Raised when a session cannot be resolved.
 *
 * The message names the target and the session, because the fix is almost always in the
 * project's plugin list or in the contribution's declaration rather than in the script.
 *
 * @param message What could not be resolved, and what to check
 * @param cause The underlying failure, when there was one
 */
class SessionResolutionException(message: String, cause: Throwable? = null) : Exception(message, cause)
