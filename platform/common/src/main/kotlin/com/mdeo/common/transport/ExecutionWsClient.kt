package com.mdeo.common.transport

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Request/response client for [ExecutionWsProtocol], over WebSocket connections that are
 * cached per target service and closed again once they go unused.
 *
 * A connection is opened on the first request to a target, shared by every request to that
 * target afterwards, and closed after [idleTimeoutMillis] without traffic. Nothing about a
 * request depends on connection state — each one carries its own authorization — so a
 * connection that was dropped in between is simply reopened, and two unrelated callers can
 * share one without either being able to observe the other.
 *
 * @param idleTimeoutMillis How long a connection may sit without traffic before it is closed
 * @param requestTimeoutMillis How long a single request may wait for its terminating response
 * @param httpClient WebSocket-capable client to dial with; a private one is created if omitted
 */
class ExecutionWsClient(
    private val idleTimeoutMillis: Long = DEFAULT_IDLE_TIMEOUT_MILLIS,
    private val requestTimeoutMillis: Long = DEFAULT_REQUEST_TIMEOUT_MILLIS,
    httpClient: HttpClient? = null
) : AutoCloseable {

    companion object {
        /**
         * Default idle lifetime of a pooled connection. Long enough that the bursts of
         * requests one user interaction produces all share a connection, short enough that
         * an idle service is not holding sockets open for sessions nobody is using.
         */
        const val DEFAULT_IDLE_TIMEOUT_MILLIS = 180_000L

        /**
         * Default per-request timeout. Bulk loads of a large result set are the slow case
         * this has to accommodate.
         */
        const val DEFAULT_REQUEST_TIMEOUT_MILLIS = 300_000L

        /**
         * How often the reaper looks for connections that have gone idle.
         */
        private const val REAPER_INTERVAL_MILLIS = 30_000L

        /**
         * Keepalive ping interval. A peer that vanishes without closing the connection would
         * otherwise leave this side holding a socket that will never answer.
         */
        private const val PING_INTERVAL_MILLIS = 30_000L

        /**
         * How long dialling a peer may take before it counts as unreachable.
         */
        private const val CONNECT_TIMEOUT_MILLIS = 10_000L

        /**
         * Converts an `http(s)` service base URL into the `ws(s)` URL of its execution endpoint.
         *
         * @param baseUrl Base URL of the target service
         * @return The WebSocket URL of that service's execution endpoint
         */
        fun toWebSocketUrl(baseUrl: String): String {
            val trimmed = baseUrl.trimEnd('/')
            val scheme = when {
                trimmed.startsWith("https://") -> "wss://" + trimmed.removePrefix("https://")
                trimmed.startsWith("http://") -> "ws://" + trimmed.removePrefix("http://")
                else -> trimmed
            }
            return scheme + ExecutionWsProtocol.ENDPOINT_PATH
        }
    }

    private val logger = LoggerFactory.getLogger(ExecutionWsClient::class.java)

    private val ownsHttpClient = httpClient == null
    private val client: HttpClient = httpClient ?: HttpClient(CIO) {
        install(WebSockets) {
            pingIntervalMillis = PING_INTERVAL_MILLIS
            extensions { installDeflate() }
        }
        engine {
            // Without this, dialling a peer that accepts the connection but never completes the
            // upgrade blocks the connect mutex indefinitely, and every request to every target
            // queues behind it with nothing logged. A stalled dial has to become a failure.
            endpoint {
                connectTimeout = CONNECT_TIMEOUT_MILLIS
                requestTimeout = CONNECT_TIMEOUT_MILLIS
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connections = ConcurrentHashMap<String, PooledConnection>()
    private val connectMutex = Mutex()

    private val reaper = scope.launch {
        while (isActive) {
            delay(REAPER_INTERVAL_MILLIS)
            reapIdleConnections()
        }
    }

    /**
     * Sends a request to a target service and awaits its terminating response.
     *
     * @param baseUrl Base URL of the target service
     * @param build Builds the request message from the generated request id
     * @param onStream Invoked for each intermediate message, such as the per-file messages
     *        of a bulk load, before the terminating response arrives
     * @return The successful response
     * @throws ExecutionWsException if the peer answered with an error or could not be reached
     */
    suspend fun request(
        baseUrl: String,
        build: (requestId: String) -> ExecutionWsMessage,
        onStream: (suspend (ExecutionWsMessage) -> Unit)? = null
    ): ExecutionWsResponse {
        val url = toWebSocketUrl(baseUrl)
        // A cached connection can be closed by the peer between the moment it is handed out
        // and the moment it is written to, which is indistinguishable from it never having
        // worked. Retrying once on a fresh connection separates the two.
        return try {
            sendOnce(url, build, onStream)
        } catch (e: ExecutionWsException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Execution WS request to {} failed ({}), retrying on a new connection", url, e.toString())
            connections.remove(url)?.close()
            try {
                sendOnce(url, build, onStream)
            } catch (retry: ExecutionWsException) {
                throw retry
            } catch (retry: CancellationException) {
                throw retry
            } catch (retry: Exception) {
                throw ExecutionWsException(
                    ExecutionWsErrorCodes.UNAVAILABLE,
                    "Execution WebSocket request to $url failed: ${retry.message}",
                    retry
                )
            }
        }
    }

    private suspend fun sendOnce(
        url: String,
        build: (requestId: String) -> ExecutionWsMessage,
        onStream: (suspend (ExecutionWsMessage) -> Unit)?
    ): ExecutionWsResponse {
        val connection = obtainConnection(url)
        val requestId = UUID.randomUUID().toString()
        val pending = PendingRequest(onStream)
        connection.pending[requestId] = pending
        try {
            connection.send(ExecutionWsProtocol.encode(build(requestId)))
            return withTimeout(requestTimeoutMillis) { pending.result.await() }
        } finally {
            connection.pending.remove(requestId)
        }
    }

    private suspend fun obtainConnection(url: String): PooledConnection {
        connections[url]?.let { if (it.isAlive) return it.touch() }
        return connectMutex.withLock {
            connections[url]?.let { if (it.isAlive) return@withLock it.touch() }
            connections.remove(url)?.close()
            val session = try {
                client.webSocketSession(url)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("Failed to open execution WS connection to {}: {}", url, e.toString())
                throw e
            }
            val connection = PooledConnection(url, session)
            connection.startReadLoop()
            connections[url] = connection
            connection
        }
    }

    private fun reapIdleConnections() {
        val now = System.currentTimeMillis()
        for ((url, connection) in connections) {
            val idle = now - connection.lastUsedAt
            if (!connection.isAlive) {
                connections.remove(url)?.close()
            } else if (idle >= idleTimeoutMillis && connection.pending.isEmpty()) {
                logger.debug("Closing idle execution WS connection to {} after {}ms", url, idle)
                connections.remove(url)?.close()
            }
        }
    }

    /**
     * Closes every pooled connection and, if this client created it, the underlying HTTP client.
     */
    override fun close() {
        reaper.cancel()
        for (url in connections.keys.toList()) {
            connections.remove(url)?.close()
        }
        scope.cancel()
        if (ownsHttpClient) {
            client.close()
        }
    }

    private inner class PooledConnection(
        private val url: String,
        private val session: DefaultClientWebSocketSession
    ) {
        val pending = ConcurrentHashMap<String, PendingRequest>()

        @Volatile
        var lastUsedAt: Long = System.currentTimeMillis()
            private set

        private var readLoop: Job? = null
        private val sendMutex = Mutex()

        val isAlive: Boolean
            get() = session.isActive && (readLoop?.isActive ?: true)

        fun touch(): PooledConnection {
            lastUsedAt = System.currentTimeMillis()
            return this
        }

        suspend fun send(text: String) {
            touch()
            sendMutex.withLock { session.send(Frame.Text(text)) }
        }

        fun startReadLoop() {
            readLoop = scope.launch {
                try {
                    for (frame in session.incoming) {
                        if (frame !is Frame.Text) continue
                        touch()
                        dispatch(ExecutionWsProtocol.decode(frame.readText()))
                    }
                    drain(ExecutionWsException(ExecutionWsErrorCodes.UNAVAILABLE, "Connection to $url closed"))
                } catch (e: CancellationException) {
                    drain(ExecutionWsException(ExecutionWsErrorCodes.UNAVAILABLE, "Connection to $url cancelled"))
                    throw e
                } catch (e: Exception) {
                    logger.warn("Execution WS read loop for {} ended: {}", url, e.toString())
                    drain(
                        ExecutionWsException(
                            ExecutionWsErrorCodes.UNAVAILABLE,
                            "Connection to $url failed: ${e.message}",
                            e
                        )
                    )
                } finally {
                    // Only evict this connection, never a replacement that was already
                    // opened for the same target after this one started failing.
                    if (connections[url] === this@PooledConnection) {
                        connections.remove(url)
                    }
                }
            }
        }

        private suspend fun dispatch(message: ExecutionWsMessage) {
            when (message) {
                is ExecutionWsResponse -> pending.remove(message.requestId)?.result?.complete(message)
                is ExecutionWsError -> pending.remove(message.requestId)?.result?.completeExceptionally(
                    ExecutionWsException(message.code, message.message)
                )
                else -> pending[message.requestId]?.onStream?.invoke(message)
            }
        }

        private fun drain(cause: Throwable) {
            for (key in pending.keys.toList()) {
                pending.remove(key)?.result?.completeExceptionally(cause)
            }
        }

        fun close() {
            readLoop?.cancel()
            drain(ExecutionWsException(ExecutionWsErrorCodes.UNAVAILABLE, "Connection to $url closed"))
            // Cancelled rather than closed politely: a close handshake would have to be
            // launched on this client's scope, which shutdown cancels out from under it,
            // leaving the socket open exactly when it most needs to go away.
            session.cancel()
        }
    }

    private class PendingRequest(val onStream: (suspend (ExecutionWsMessage) -> Unit)?) {
        val result = CompletableDeferred<ExecutionWsResponse>()
    }
}

/**
 * Failure of an execution WebSocket request, carrying the protocol error code so that the
 * originating failure keeps its meaning as it is relayed back up the chain of hops.
 *
 * @property code One of [ExecutionWsErrorCodes]
 */
class ExecutionWsException(
    val code: String,
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)
