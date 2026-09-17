package com.mdeo.common.transport

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory

/**
 * Where a session lives and what authorizes it, as the backend handed it over.
 *
 * @property url WebSocket URL of the session endpoint
 * @property protocol Name of the protocol spoken on the session
 * @property versions Protocol versions the plugin side can speak, most preferred first
 * @property token Bearer token that authorizes exactly this session
 * @property expiresAt Epoch second at which [token] stops being accepted
 */
data class SessionConnection(
    val url: String,
    val protocol: String,
    val versions: List<Int>,
    val token: String,
    val expiresAt: Long
)

/**
 * A binary pipe to one plugin session.
 *
 * This carries bytes and nothing else. It knows how to dial the endpoint, how to authorize the
 * connection, how to keep it alive and how to get it back after it drops — none of which
 * depends on what the two sides are saying to each other. What they are saying is a protocol
 * the plugin defines, and this client neither parses nor correlates nor retries any of it.
 *
 * A reconnect therefore restores the transport, not the conversation: [onReconnect] is where
 * the owner re-establishes whatever protocol state the new connection starts without.
 *
 * @param resolve Produces a fresh [SessionConnection] for each dial. A reconnect needs a new
 *        token, so this must stay reachable for as long as the session may need to come back.
 * @param versions Protocol versions this side can speak, most preferred first
 * @param onMessage Called for every message the peer sends, on the client's own scope
 * @param onReconnect Called after the transport comes back, before any queued send goes out
 * @param onClosed Called once when the session ends for good, with the reason
 * @param maxReconnectAttempts How many times a dropped connection is redialled before giving up
 * @param httpClient WebSocket-capable client to dial with; a private one is created if omitted
 */
class SessionClient(
    private val resolve: suspend () -> SessionConnection,
    private val versions: List<Int>,
    private val onMessage: suspend (ByteArray) -> Unit,
    private val onReconnect: suspend (SessionClient) -> Unit = {},
    private val onClosed: (String) -> Unit = {},
    private val maxReconnectAttempts: Int = DEFAULT_MAX_RECONNECT_ATTEMPTS,
    httpClient: HttpClient? = null
) : AutoCloseable {

    companion object {
        /**
         * Keepalive interval. A session is exempt from request timeouts, so nothing else would
         * notice a peer that vanished without closing. The reverse proxy in front of a plugin
         * also drops connections idle for an hour, which this keeps from happening.
         */
        const val PING_INTERVAL_MILLIS = 30_000L

        /**
         * How long dialling the endpoint may take before it counts as unreachable.
         */
        const val CONNECT_TIMEOUT_MILLIS = 10_000L

        /**
         * How many times a dropped connection is redialled by default.
         */
        const val DEFAULT_MAX_RECONNECT_ATTEMPTS = 3

        /**
         * Delay before the first redial; doubled for each further attempt.
         */
        private const val RECONNECT_BASE_DELAY_MILLIS = 500L
    }

    private val logger = LoggerFactory.getLogger(SessionClient::class.java)

    private val ownsHttpClient = httpClient == null
    private val client: HttpClient = httpClient ?: HttpClient(CIO) {
        install(WebSockets) {
            pingIntervalMillis = PING_INTERVAL_MILLIS
            maxFrameSize = MAX_SERVICE_WEBSOCKET_MESSAGE_BYTES
            extensions { installDeflate(MAX_SERVICE_WEBSOCKET_MESSAGE_BYTES) }
        }
        engine {
            endpoint {
                connectTimeout = CONNECT_TIMEOUT_MILLIS
                requestTimeout = CONNECT_TIMEOUT_MILLIS
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sendMutex = Mutex()

    @Volatile
    private var session: DefaultClientWebSocketSession? = null

    @Volatile
    private var closing = false

    /**
     * The protocol version both sides agreed on, available once the session is open.
     */
    @Volatile
    var negotiatedVersion: Int = 0
        private set

    /**
     * How many times the session was opened: 1 after [open], one more after every reconnect. A
     * change tells the owner the peer lost whatever state the previous connection built up.
     */
    @Volatile
    var connectionNumber: Long = 0
        private set

    /**
     * The connection details the last successful dial used.
     */
    @Volatile
    var connection: SessionConnection? = null
        private set

    /**
     * Opens the session.
     *
     * @throws SessionException if the endpoint refuses the connection or cannot be reached
     */
    suspend fun open() {
        connect(attempt = 0)
    }

    /**
     * Sends one message to the peer.
     *
     * The bytes are whatever the plugin's protocol says they are; this only writes them.
     *
     * @param data The message to send
     * @throws SessionException if the session is closed and cannot be reopened
     */
    suspend fun send(data: ByteArray) {
        sendMutex.withLock {
            val live = session ?: reconnect()
            try {
                live.send(Frame.Binary(fin = true, data = data))
            } catch (e: Exception) {
                // A connection the peer closed cancels its outgoing channel; only a cancellation
                // of the caller itself must propagate as one.
                if (e is CancellationException && !currentCoroutineContext().isActive) throw e
                logger.warn("Session send failed, reconnecting: ${e.message}")
                try {
                    reconnect().send(Frame.Binary(fin = true, data = data))
                } catch (retry: Exception) {
                    if (retry is CancellationException && !currentCoroutineContext().isActive) throw retry
                    throw retry as? SessionException ?: SessionException("Could not send on the session", retry)
                }
            }
        }
    }

    /**
     * Dials the endpoint and starts reading from it.
     *
     * @param attempt Which attempt this is, used only for the message when the last one fails
     */
    private suspend fun connect(attempt: Int) {
        val resolved = try {
            resolve()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw SessionException("Could not resolve the session endpoint", e)
        }

        val agreed = versions.firstOrNull { it in resolved.versions }
            ?: throw SessionException(
                "No shared version of protocol '${resolved.protocol}': this side speaks " +
                        "${versions.joinToString(", ")}, the plugin speaks " +
                        "${resolved.versions.joinToString(", ")}"
            )

        val opened = CompletableDeferred<Unit>()
        val reader = scope.launch {
            try {
                client.webSocket(
                    urlString = resolved.url + "?v=" + versions.joinToString(","),
                    request = {
                        header(HttpHeaders.Authorization, "Bearer ${resolved.token}")
                    }
                ) {
                    session = this
                    connectionNumber++
                    negotiatedVersion = agreed
                    connection = resolved
                    opened.complete(Unit)
                    for (frame in incoming) {
                        if (frame is Frame.Binary || frame is Frame.Text) {
                            onMessage(frame.data)
                        }
                    }
                }
                session = null
                if (!closing) {
                    logger.info("Session to ${resolved.url} closed by the peer")
                    onClosed("Closed by the peer")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                session = null
                if (!opened.isCompleted) {
                    opened.completeExceptionally(
                        SessionException("Could not open a session at ${resolved.url}", e)
                    )
                } else if (!closing) {
                    logger.warn("Session to ${resolved.url} dropped: ${e.message}")
                    onClosed(e.message ?: "Connection dropped")
                }
            }
        }

        try {
            opened.await()
        } catch (e: SessionException) {
            reader.cancel()
            if (attempt >= maxReconnectAttempts) {
                throw e
            }
            delay(RECONNECT_BASE_DELAY_MILLIS shl attempt)
            connect(attempt + 1)
        }
    }

    /**
     * Brings the transport back after it dropped, and lets the owner restore its own state.
     *
     * @return The reopened session
     */
    private suspend fun reconnect(): DefaultClientWebSocketSession {
        if (closing) {
            throw SessionException("Session is closed")
        }
        connect(attempt = 0)
        val live = session ?: throw SessionException("Session did not come back")
        onReconnect(this)
        return live
    }

    /**
     * Ends the session and releases everything it holds.
     */
    override fun close() {
        closing = true
        val live = session
        session = null
        runBlocking {
            runCatching { live?.close(CloseReason(CloseReason.Codes.NORMAL, "Done")) }
        }
        scope.cancel()
        if (ownsHttpClient) {
            client.close()
        }
    }
}

/**
 * Raised when a session cannot be opened, cannot be kept, or cannot be written to.
 *
 * @param message What went wrong
 * @param cause The underlying failure, when there was one
 */
class SessionException(message: String, cause: Throwable? = null) : Exception(message, cause)
