package com.mdeo.optimizerexecution.worker

import com.mdeo.optimizer.worker.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToByteArray
import org.slf4j.LoggerFactory

/**
 * [WorkerClient] implementation for the local node in a federated optimization run.
 *
 * All operations are dispatched directly to [WorkerService] in-process — no HTTP
 * requests or WebSocket connections are established. This is more efficient than the
 * remote path when the subprocess and orchestrator share a host.
 *
 * Unsolicited [WorkerShutdownNotice] messages are delivered via a callback registered
 * with [WorkerService] during [allocate].
 *
 * @param nodeId Unique identifier for this worker node.
 * @param workerService The [WorkerService] managing the local subprocess.
 * @param scope Coroutine scope for forwarding unsolicited [WorkerShutdownAck] messages.
 */
@OptIn(ExperimentalSerializationApi::class)
class LocalWorkerClient(
    nodeId: String,
    baseUrl: String,
    private val workerService: WorkerService,
    private val scope: CoroutineScope
) : WorkerClient(nodeId, baseUrl) {

    private val logger = LoggerFactory.getLogger(LocalWorkerClient::class.java)

    @Volatile
    private var _localAlive: Boolean = false

    @Volatile
    private var currentExecutionId: String? = null

    override val isAlive: Boolean
        get() = _localAlive

    /**
     * Allocates resources directly via [WorkerService.allocate], bypassing HTTP.
     * Forces [WorkerAllocationRequest.useLocalChannel] = `true` so the subprocess
     * uses the stdin/stdout pipe for orchestrator communication.
     * Registers a notice callback for [WorkerShutdownNotice] delivery.
     */
    override suspend fun allocate(request: WorkerAllocationRequest): WorkerAllocationResponse {
        val response = workerService.allocate(request.copy(useLocalChannel = true))
        currentExecutionId = request.executionId
        _localAlive = true
        shutdownReason = null
        workerService.registerNoticeCallback(request.executionId) { msg -> handleLocalNotice(msg) }
        return response
    }

    /**
     * Returns metadata directly from [WorkerService], bypassing HTTP.
     */
    override suspend fun getMetadata(): WorkerMetadata = workerService.getMetadata()

    /**
     * Cleans up the execution directly via [WorkerService], bypassing HTTP.
     */
    override suspend fun cleanup(executionId: String) {
        workerService.removeNoticeCallback(executionId)
        _localAlive = false
        currentExecutionId = null
        workerService.cleanup(executionId)
    }

    override fun close() {
        // No HTTP client or WebSocket connections to release
    }

    override suspend fun sendAndReceive(msg: WorkerWsMessage, timeoutMs: Long): WorkerWsMessage {
        val execId = currentExecutionId
            ?: throw IllegalStateException("No active execution on node $nodeId")
        val responses = workerService.dispatchToSubprocess(execId, msg)
        if (responses.isEmpty()) {
            val reason = shutdownReason
            throw if (reason != null) WorkerShutdownException(reason)
            else IllegalStateException("No response from subprocess (node $nodeId, execution $execId)")
        }
        return responses.first()
    }

    private fun handleLocalNotice(msg: WorkerWsMessage) {
        if (msg is WorkerShutdownNotice) {
            logger.warn("[node={}] Received WorkerShutdownNotice: {}", nodeId, msg.reason)
            shutdownReason = msg.reason
            _localAlive = false
            val ack = WorkerShutdownAck(requestId = msg.requestId)
            val execId = currentExecutionId ?: return
            scope.launch {
                try {
                    workerService.forwardShutdownAck(execId, cbor.encodeToByteArray<WorkerWsMessage>(ack))
                } catch (e: Exception) {
                    logger.warn("[node={}] Failed to forward WorkerShutdownAck: {}", nodeId, e.message)
                }
            }
        }
    }
}
