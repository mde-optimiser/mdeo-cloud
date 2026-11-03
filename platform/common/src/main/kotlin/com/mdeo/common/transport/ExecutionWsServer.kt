package com.mdeo.common.transport

import com.mdeo.common.model.ApiError
import com.mdeo.common.model.ErrorCodes
import io.ktor.websocket.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import org.slf4j.LoggerFactory

/**
 * Serves [ExecutionWsProtocol] on a WebSocket session.
 *
 * Requests on one session are handled concurrently and answered out of order — each is
 * correlated by its own request id — so a bulk load streaming hundreds of files does not
 * hold up a small request that arrives behind it.
 */
object ExecutionWsServer {
    private val logger = LoggerFactory.getLogger(ExecutionWsServer::class.java)

    /**
     * Reads and dispatches requests until the session closes.
     *
     * Failures raised by [handler] are turned into [ExecutionWsError] responses rather than
     * closing the session, so one bad request does not cost every other caller sharing the
     * connection their work.
     *
     * @param session The WebSocket session to serve
     * @param handler Handles each decoded request
     */
    suspend fun serve(session: WebSocketSession, handler: ExecutionWsRequestHandler) {
        val responder = ExecutionWsResponder(session)
        coroutineScope {
            for (frame in session.incoming) {
                if (frame !is Frame.Text) continue

                val request = try {
                    ExecutionWsProtocol.decode(frame.readText())
                } catch (e: Exception) {
                    logger.warn("Discarding undecodable execution WS frame: ${e.message}")
                    continue
                }

                if (request is ExecutionWsResponse || request is ExecutionWsError ||
                    request is ExecutionFileDataMessage
                ) {
                    logger.warn("Ignoring response-shaped message ${request.requestId} received by a server")
                    continue
                }

                launch {
                    try {
                        handler.handle(request, responder.scopedTo(request.requestId))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: ExecutionWsException) {
                        responder.fail(request.requestId, e.code, e.message ?: "Request failed")
                    } catch (e: Exception) {
                        logger.error("Execution WS request ${request.requestId} failed", e)
                        responder.fail(
                            request.requestId,
                            ErrorCodes.INTERNAL,
                            e.message ?: "Internal error"
                        )
                    }
                }
            }
        }
    }
}

/**
 * Handles decoded execution WebSocket requests.
 */
fun interface ExecutionWsRequestHandler {
    /**
     * Handles one request. Implementations must terminate it by calling
     * [ExecutionWsRequestResponder.respond] or by throwing.
     *
     * @param request The decoded request
     * @param responder Sends this request's responses
     */
    suspend fun handle(request: ExecutionWsMessage, responder: ExecutionWsRequestResponder)
}

/**
 * Sends responses for a single request over a shared session.
 */
interface ExecutionWsRequestResponder {
    /**
     * The request id every message sent through this responder is correlated with.
     */
    val requestId: String

    /**
     * Sends one file of a streamed bulk load.
     *
     * @param path Path of the file within the execution results
     * @param content The file's text content
     */
    suspend fun sendFile(path: String, content: String)

    /**
     * Terminates the request successfully.
     *
     * @param data The payload, or null for requests that return nothing
     */
    suspend fun respond(data: JsonElement? = null)
}

/**
 * Serializes writes of every concurrently handled request onto one session.
 *
 * A WebSocket session tolerates only one writer at a time; without this, two requests
 * streaming files at once would interleave halves of their frames.
 *
 * @param session The session to write to
 */
class ExecutionWsResponder(private val session: WebSocketSession) {
    private val sendMutex = Mutex()

    /**
     * Returns a responder that tags everything it sends with [requestId].
     *
     * @param requestId The request to correlate messages with
     * @return A responder bound to that request
     */
    fun scopedTo(requestId: String): ExecutionWsRequestResponder = Scoped(requestId)

    /**
     * Sends an error response.
     *
     * @param requestId The request that failed
     * @param code One of [ErrorCodes]
     * @param message Human-readable description
     */
    suspend fun fail(requestId: String, code: String, message: String) {
        send(ExecutionWsError(requestId, ApiError(code, message)))
    }

    private suspend fun send(message: ExecutionWsMessage) {
        sendMutex.withLock { session.send(Frame.Text(ExecutionWsProtocol.encode(message))) }
    }

    private inner class Scoped(override val requestId: String) : ExecutionWsRequestResponder {
        override suspend fun sendFile(path: String, content: String) {
            send(ExecutionFileDataMessage(requestId, path, content))
        }

        override suspend fun respond(data: JsonElement?) {
            send(ExecutionWsResponse(requestId, data))
        }
    }
}
