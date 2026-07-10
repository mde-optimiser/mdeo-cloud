package com.mdeo.optimizerexecution.worker

import com.mdeo.optimizer.worker.*
import com.mdeo.optimizerexecution.service.OrchestratorRegistry
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * [WorkerClient] implementation for remote worker nodes.
 *
 * After HTTP allocation, the worker's subprocess opens a WebSocket connection back to
 * the orchestrator. This client registers with the [OrchestratorRegistry] and waits for
 * the subprocess to connect. All ongoing traffic (work batches, solution fetches) flows
 * over this reverse-connected session.
 *
 * The WebSocket reading loop is launched as a child coroutine of [scope]. When that scope
 * is cancelled, the loop terminates cleanly.
 *
 * @param nodeId Unique identifier for the worker node.
 * @param baseUrl Base HTTP URL of the worker node (e.g. `http://worker-1:8080`).
 * @param scope Coroutine scope that owns the background WebSocket reading loop.
 * @param registry The registry that routes incoming subprocess WS connections.
 * @param wsBaseUrl The base WS URL that subprocesses should connect back to
 *        (e.g. `ws://orchestrator-host:8080`).
 */
@OptIn(ExperimentalSerializationApi::class)
class RemoteWorkerClient(
    nodeId: String,
    baseUrl: String,
    private val scope: CoroutineScope,
    private val registry: OrchestratorRegistry,
    private val wsBaseUrl: String
) : WorkerClient(nodeId, baseUrl) {

    private companion object {
        const val SESSION_READY_TIMEOUT_MS = 30_000L
    }

    private val httpClient: HttpClient = HttpClient(CIO) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(HttpTimeout) {
            requestTimeoutMillis = 600_000
            socketTimeoutMillis = 600_000
        }
        install(WebSockets)
    }

    private val logger = LoggerFactory.getLogger(RemoteWorkerClient::class.java)

    /** Pending request correlation: requestId → deferred result. */
    private val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<WorkerWsMessage>>()

    /**
     * Holds the [CompletableDeferred] for the current batch's subprocess WS session.
     *
     * Replaced with a fresh deferred at the start of each [allocate] call so that
     * re-used [RemoteWorkerClient] instances (across batches) do not accidentally
     * resolve to the stale, already-closed session from a previous batch.
     */
    private val wsSessionDeferredRef = AtomicReference(CompletableDeferred<DefaultWebSocketSession>())

    /** Background WS read loop job. */
    private var wsJob: Job? = null

    override val isAlive: Boolean
        get() = wsJob?.isActive ?: false

    /**
     * Registers a slot in the [OrchestratorRegistry], sends the HTTP allocation request
     * embedding the orchestrator WS URL, then waits for the subprocess to connect back
     * before starting the WS read loop.
     *
     * A fresh [CompletableDeferred] is installed in [wsSessionDeferredRef] at the start
     * of every call so that a reused [RemoteWorkerClient] instance (across batches) always
     * hands [sendAndReceive] the session that belongs to the *current* batch, not a stale
     * closed session from a previous one.
     */
    override suspend fun allocate(request: WorkerAllocationRequest): WorkerAllocationResponse {
        wsSessionDeferredRef.set(CompletableDeferred())

        val registryKey = OrchestratorRegistry.key(request.executionId, nodeId)
        val orchestratorWsUrl = "$wsBaseUrl/ws/subprocess/executions/${request.executionId}/$nodeId"
        logger.info(
            "Registering subprocess WS slot for node {} execution {} (key={})",
            nodeId, request.executionId, registryKey
        )
        val wsDeferred = registry.register(registryKey)
        val response = try {
            val httpResp = httpClient.post("$baseUrl/api/worker/executions") {
                contentType(ContentType.Application.Json)
                setBody(request.copy(orchestratorWsUrl = orchestratorWsUrl, useLocalChannel = false))
            }
            if (!httpResp.status.isSuccess()) {
                val errorBody = httpResp.bodyAsText()
                throw IllegalStateException("Worker allocation failed (${httpResp.status.value}): $errorBody")
            }
            httpResp.body<WorkerAllocationResponse>()
        } catch (e: Exception) {
            registry.remove(registryKey)
            throw e
        }
        try {
            logger.info(
                "Waiting up to {}ms for subprocess WS connect-back (node {} execution {})",
                SESSION_READY_TIMEOUT_MS, nodeId, request.executionId
            )
            val session = withTimeout(SESSION_READY_TIMEOUT_MS) { wsDeferred.await() }
            logger.info(
                "Subprocess WS session established for node {} execution {}",
                nodeId, request.executionId
            )
            startRegistryWsReadLoop(request.executionId, session)
        } catch (e: Exception) {
            registry.remove(registryKey)
            throw e
        }
        return response
    }

    /**
     * Fetches metadata via `GET /api/worker/metadata`.
     */
    override suspend fun getMetadata(): WorkerMetadata {
        return httpClient.get("$baseUrl/api/worker/metadata").body<WorkerMetadata>()
    }

    /**
     * Sends `DELETE /api/worker/executions/{id}`, removes the registry entry, and
     * cancels the WS read loop.
     */
    override suspend fun cleanup(executionId: String) {
        httpClient.delete("$baseUrl/api/worker/executions/$executionId")
        registry.remove(OrchestratorRegistry.key(executionId, nodeId))
        wsJob?.cancel()
        wsJob = null
    }

    override fun close() {
        wsJob?.cancel()
        httpClient.close()
    }

    override suspend fun sendAndReceive(msg: WorkerWsMessage, timeoutMs: Long): WorkerWsMessage {
        val session = withTimeout(SESSION_READY_TIMEOUT_MS) { wsSessionDeferredRef.get().await() }
        val deferred = CompletableDeferred<WorkerWsMessage>()
        pendingRequests[msg.requestId] = deferred
        try {
            session.send(Frame.Binary(true, cbor.encodeToByteArray<WorkerWsMessage>(msg)))
        } catch (e: Exception) {
            pendingRequests.remove(msg.requestId)
            throw e
        }
        return withTimeout(timeoutMs) { deferred.await() }
    }

    private fun startRegistryWsReadLoop(executionId: String, session: DefaultWebSocketSession) {
        wsSessionDeferredRef.get().complete(session)
        wsJob = scope.launch {
            try {
                logger.info("Subprocess WS connected for node {} (execution {})", nodeId, executionId)
                for (frame in session.incoming) {
                    if (frame !is Frame.Binary) continue
                    val msg = cbor.decodeFromByteArray<WorkerWsMessage>(frame.readBytes())
                    if (handleUnsolicitedMessage(msg, session)) continue
                    pendingRequests.remove(msg.requestId)?.complete(msg)
                }
                logger.info("Subprocess WS disconnected for node {} (execution {})", nodeId, executionId)
                drainPendingRequests(IllegalStateException("WS session closed (node $nodeId, execution $executionId)"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(
                    "Subprocess WS read loop failed for node {} (execution {}): {}",
                    nodeId, executionId, e.message
                )
                drainPendingRequests(e)
            }
        }
    }

    private suspend fun handleUnsolicitedMessage(
        msg: WorkerWsMessage,
        session: DefaultWebSocketSession
    ): Boolean {
        if (msg is WorkerShutdownNotice) {
            logger.warn("[node={}] Received WorkerShutdownNotice: {}", nodeId, msg.reason)
            shutdownReason = msg.reason
            drainPendingRequests(WorkerShutdownException(msg.reason))
            val ack = WorkerShutdownAck(requestId = msg.requestId)
            try {
                session.send(Frame.Binary(true, cbor.encodeToByteArray<WorkerWsMessage>(ack)))
            } catch (e: Exception) {
                logger.warn("[node={}] Failed to send WorkerShutdownAck: {}", nodeId, e.message)
            }
            return true
        }
        return false
    }

    private fun drainPendingRequests(cause: Exception) {
        val keys = pendingRequests.keys.toList()
        if (keys.isNotEmpty()) {
            logger.warn(
                "[node={}] Draining {} pending request(s) due to WS close: {}",
                nodeId, keys.size, cause.message
            )
        }
        for (key in keys) {
            pendingRequests.remove(key)?.completeExceptionally(cause)
        }
    }
}
