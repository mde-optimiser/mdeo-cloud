package com.mdeo.optimizerexecution.worker

import com.mdeo.common.model.ExecutionState
import com.mdeo.execution.common.subprocess.SubprocessPool
import com.mdeo.execution.common.subprocess.SubprocessResult
import com.mdeo.execution.common.subprocess.SubprocessRunner
import com.mdeo.metamodel.SerializedModel
import com.mdeo.optimizer.config.GraphBackendType
import com.mdeo.optimizer.worker.*
import com.mdeo.optimizerexecution.database.OptimizerExecutionsTable
import io.ktor.websocket.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.uuid.toKotlinUuid

/**
 * Service that manages worker-side execution state for multi-node optimization.
 *
 * Each optimization execution runs its mutations and evaluations in a dedicated child JVM
 * ([WorkerSubprocessMain]) started via [SubprocessRunner].
 *
 * **WebSocket mode** (for remote nodes): The subprocess opens a WebSocket connection
 * directly back to the orchestrator and handles all generation traffic in-process.
 *
 * **Local-channel mode** (for the local node in a federated run): The subprocess receives
 * orchestrator requests through the subprocess stdin/stdout pipe as
 * [SubprocessChannelMessage.OrchestratorRequest] channel messages and replies via
 * [SubprocessChannelMessage.OrchestratorResponses]. [WorkerClient] calls [dispatchToSubprocess]
 * directly; no WebSocket is established.
 *
 * @param workerThreads Number of blocking I/O threads per execution for subprocess communication.
 * @param scriptTimeoutMs Per-script-invocation timeout budget in milliseconds.
 * @param transformationTimeoutMs Transformation-step timeout budget in milliseconds.
 * @param serverPort The local HTTP/WS server port, used to build the subprocess WS URL.
 * @param subprocessPool Pool of reusable subprocess runners. Completed executions return
 *        their subprocesses to the pool (after a protocol-level reset) instead of destroying
 *        them, amortising JVM startup and class-loading costs across executions.
 */
@OptIn(ExperimentalSerializationApi::class)
class WorkerService(
    private val workerThreads: Int,
    private val scriptTimeoutMs: Long,
    private val transformationTimeoutMs: Long,
    private val serverPort: Int = 0,
    private val subprocessPool: SubprocessPool = buildDefaultPool()
) {

    /**
     * Logger instance for this service. 
     */
    private val logger = LoggerFactory.getLogger(WorkerService::class.java)
    /**
     * Map of active execution states keyed by execution ID string. 
     */
    private val executions = ConcurrentHashMap<String, WorkerExecutionState>()

    /**
     * Pending local-channel response deferreds, keyed by [WorkerWsMessage.requestId].
     *
     * When [WorkerService] acts as a forwarder in local-channel mode it registers a
     * [CompletableDeferred] here before sending an [OrchestratorRequest] to the subprocess.
     * The channel message handler completes it when the matching [OrchestratorResponses]
     * arrives, unblocking [sendViaLocalChannel].
     */
    private val localChannelPending =
        ConcurrentHashMap<String, CompletableDeferred<List<WorkerWsMessage>>>()

    /**
     * Returns metadata describing this worker node's local thread capacity and
     * the backends it supports.
     *
     * @return A [WorkerMetadata] instance with current configuration values.
     */
    fun getMetadata(): WorkerMetadata = WorkerMetadata(
        threadCount = workerThreads,
        supportedBackends = SUPPORTED_GRAPH_BACKENDS
    )

    /**
     * CBOR codec used to encode/decode subprocess and WebSocket messages. 
     */
    private val cbor = Cbor { ignoreUnknownKeys = true }

    /**
     * Allocates resources for a new optimization execution on this worker.
     *
     * Starts a [WorkerSubprocessMain] subprocess, sends the [WorkerSubprocessRequest.Setup]
     * command carrying all compilation inputs, and stores the returned initial solutions.
     *
     * When [orchestratorWsUrl] is provided, the subprocess will connect back to the
     * orchestrator via WebSocket and handle all generation traffic directly.
     *
     * When [request.useLocalChannel] is `true`, the subprocess uses the stdin/stdout pipe
     * for orchestrator communication and this service acts as a forwarder via
     * [WorkerService.handleOrchestratorSession].
     *
     * @param request Allocation request from the orchestrator.
     * @param orchestratorWsUrl WebSocket URL for the subprocess to connect back to, or `null`.
     * @return Allocation response with initial solution fitness data.
     */
    suspend fun allocate(
        request: WorkerAllocationRequest,
        orchestratorWsUrl: String? = null
    ): WorkerAllocationResponse {
        val effectiveThreads = minOf(request.threadsPerNode, workerThreads)
        val useLocalChannel = request.useLocalChannel
        logger.info(
            "Allocating execution {} with {} initial solutions ({} threads, cap {}, wsMode={}, localChannel={})",
            request.executionId, request.initialSolutionCount, effectiveThreads,
            request.threadsPerNode, orchestratorWsUrl != null, useLocalChannel
        )

        val dispatcher = Executors.newFixedThreadPool(effectiveThreads).asCoroutineDispatcher()
        val needsChannelHandler = orchestratorWsUrl != null || useLocalChannel
        val pooledRunner = subprocessPool.acquire()
        val runner: SubprocessRunner
        if (pooledRunner != null) {
            runner = pooledRunner
            configureChannelHandler(runner, request.executionId, needsChannelHandler)
        } else {
            runner = createSubprocessRunner(request.executionId, needsChannelHandler)
            if (!runner.start()) {
                dispatcher.close()
                throw IllegalStateException("Failed to start worker subprocess for execution ${request.executionId}")
            }
        }

        val setupRequest = WorkerSubprocessRequest.Setup(
            metamodelData = request.metamodelData,
            initialModelData = request.initialModelData,
            transformationAstJsons = request.transformationAstJsons,
            scriptAstJsons = request.scriptAstJsons,
            goalConfig = request.goalConfig,
            solverConfig = request.solverConfig,
            initialSolutionCount = request.initialSolutionCount,
            skipInitialization = false,
            orchestratorWsUrl = orchestratorWsUrl,
            useLocalChannel = useLocalChannel,
            scriptTimeoutMs = scriptTimeoutMs,
            transformationTimeoutMs = transformationTimeoutMs,
            workerThreads = effectiveThreads,
            graphBackendType = request.graphBackendType
        )

        val setupResult = withContext(dispatcher) {
            runner.sendCommand(cbor.encodeToByteArray<WorkerSubprocessRequest>(setupRequest))
        }

        if (setupResult !is SubprocessResult.Success) {
            runner.destroy()
            dispatcher.close()
            throw IllegalStateException("Worker subprocess Setup failed for execution ${request.executionId}: $setupResult")
        }

        val setupOk = cbor.decodeFromByteArray<WorkerSubprocessResponse>(setupResult.data) as? WorkerSubprocessResponse.SetupOk
            ?: throw IllegalStateException("Unexpected subprocess response type for execution ${request.executionId}")

        executions[request.executionId] = WorkerExecutionState(
            executionId = request.executionId,
            runner = runner,
            dispatcher = dispatcher
        )

        logger.info("Execution {} allocated with {} initial solutions", request.executionId, setupOk.solutions.size)

        return WorkerAllocationResponse(
            initialSolutions = setupOk.solutions.map { s ->
                InitialSolutionData(
                    solutionId = s.solutionId
                )
            },
            threadCount = effectiveThreads
        )
    }


    /**
     * Retrieves the serialised model for a specific solution by fetching it from the subprocess.
     *
     * @param executionId The execution identifier.
     * @param solutionId The solution identifier to retrieve.
     * @return The [SerializedModel] for the solution.
     */
    suspend fun getSolutionData(executionId: String, solutionId: String): SerializedModel {
        val state = requireExecution(executionId)
        return fetchSolutionFromSubprocess(state, solutionId)
    }

    /**
     * Services a long-lived WebSocket connection from a peer subprocess delivering
     * relocated solution models.
     *
     * Source worker subprocesses connect here during population rebalancing and send
     * [SolutionPushRequest] frames. Each solution is injected into this node's subprocess
     * via a [SubprocessChannelMessage.SolutionInjected] channel message, signalling any
     * mutation task that is waiting for that solution ID.
     *
     * @param executionId The execution this node is running.
     * @param session The incoming WebSocket session from the peer subprocess.
     */
    suspend fun handlePeerSession(executionId: String, session: DefaultWebSocketSession) {
        val state = executions[executionId]
        if (state == null) {
            logger.error("[handlePeerSession] EXECUTION_NOT_FOUND executionId={}", executionId)
            session.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Execution not found: $executionId"))
            return
        }
        logger.info("[handlePeerSession] CONNECTED executionId={}", executionId)
        try {
            for (frame in session.incoming) {
                if (frame !is Frame.Binary) {
                    throw IllegalStateException("Expected binary frame with solution data, but received: ${frame.frameType}")
                }
                val msg = cbor.decodeFromByteArray<WorkerWsMessage>(frame.readBytes())
                if (msg is SolutionPushRequest) {
                    for ((idx, solution) in msg.solutions.withIndex()) {
                        val modelBytes = cbor.encodeToByteArray<SerializedModel>(solution.serializedModel)
                        val channelMsg = SubprocessChannelMessage.SolutionInjected(
                            solution.solutionId,
                            modelBytes,
                            solution.failedOperators
                        )
                        state.runner.sendChannelMessage(cbor.encodeToByteArray<SubprocessChannelMessage>(channelMsg))
                    }
                    session.send(Frame.Binary(true, cbor.encodeToByteArray<WorkerWsMessage>(SolutionPushAck(msg.requestId))))
                }
            }
        } catch (e: Exception) {
            logger.error("[handlePeerSession] ERROR executionId={}: {}", executionId, e.message, e)
        } finally {
            logger.info("[handlePeerSession] DISCONNECTED executionId={}", executionId)
        }
    }

    /**
     * Fetches a solution model from the subprocess via the local channel.
     */
    private suspend fun fetchSolutionFromSubprocess(state: WorkerExecutionState, solutionId: String): SerializedModel {
        val requestId = java.util.UUID.randomUUID().toString()
        val responses = sendViaLocalChannel(state, SolutionBatchFetchRequest(requestId, listOf(solutionId)))
        val response = responses.firstOrNull()
        if (response is SolutionBatchFetchResponse) {
            val data = response.solutions.firstOrNull { it.solutionId == solutionId }
            if (data != null) {
                return data.serializedModel
            }
        }
        throw IllegalArgumentException("Solution not found: $solutionId (execution ${state.executionId})")
    }


    /**
     * Stops the subprocess and releases all resources for the specified execution.
     *
     * If a [SubprocessPool] is configured and the subprocess is still healthy,
     * it is reset and returned to the pool for reuse. Otherwise, it is destroyed.
     *
     * @param executionId The execution identifier to clean up.
     */
    suspend fun cleanup(executionId: String) {
        val state = executions.remove(executionId)
            ?: throw IllegalArgumentException("Execution not found: $executionId")

        if (state.runner.isRunning) {
            subprocessPool.resetAndRelease(state.runner)
        } else {
            state.runner.destroy()
        }
        state.dispatcher.close()
        logger.info("Execution {} cleaned up", executionId)
    }

    /**
     * Registers a callback to receive unsolicited subprocess notices (e.g. [WorkerShutdownNotice])
     * for the given execution. Used by [WorkerClient] in local-channel mode.
     *
     * @param executionId The execution whose notices should be forwarded.
     * @param callback Invoked on the subprocess runner's reader thread when a notice arrives.
     */
    internal fun registerNoticeCallback(executionId: String, callback: (WorkerWsMessage) -> Unit) {
        executions[executionId]?.noticeCallback = callback
    }

    /**
     * Removes the unsolicited notice callback for the given execution.
     *
     * @param executionId The execution whose callback should be removed.
     */
    internal fun removeNoticeCallback(executionId: String) {
        executions[executionId]?.noticeCallback = null
    }

    /**
     * Forwards a raw CBOR-encoded [WorkerShutdownAck] to the subprocess as a
     * [SubprocessChannelMessage.OrchestratorRequest], without expecting a response.
     *
     * Called by [WorkerClient] in local-channel mode after receiving a [WorkerShutdownNotice].
     *
     * @param executionId The execution whose subprocess should receive the ack.
     * @param rawAckBytes CBOR-encoded [WorkerShutdownAck] payload.
     */
    internal fun forwardShutdownAck(executionId: String, rawAckBytes: ByteArray) {
        val state = executions[executionId] ?: return
        forwardAckToSubprocess(state, rawAckBytes)
    }

    /**
     * Forwards a raw WS frame to the subprocess as a [SubprocessChannelMessage.OrchestratorRequest]
     * without registering a response deferred.
     *
     * Used for one-way acknowledgements such as [WorkerShutdownAck] where the subprocess
     * will halt immediately after receiving the message and no response should be expected.
     *
     * @param state The execution state providing the subprocess runner.
     * @param rawPayload The raw CBOR bytes of the [WorkerWsMessage] to forward.
     */
    private fun forwardAckToSubprocess(state: WorkerExecutionState, rawPayload: ByteArray) {
        try {
            state.runner.sendChannelMessage(
                cbor.encodeToByteArray<SubprocessChannelMessage>(
                    SubprocessChannelMessage.OrchestratorRequest(rawPayload)
                )
            )
        } catch (e: Exception) {
            logger.warn(
                "Failed to forward ack to subprocess for execution {}: {}",
                state.executionId, e.message
            )
        }
    }

    /**
     * Dispatches [msg] to the subprocess for [executionId] via the local channel and returns
     * the responses. Returns an empty list if the subprocess has died. Used by tests.
     *
     * @param executionId The execution identifier to look up.
     * @param msg The [WorkerWsMessage] to forward.
     * @return The list of response messages from the subprocess, or empty on subprocess death.
     */
    internal suspend fun dispatchToSubprocess(executionId: String, msg: WorkerWsMessage): List<WorkerWsMessage> =
        sendViaLocalChannel(requireExecution(executionId), msg)

    /**
     * Sends [msg] to the subprocess via the local-channel mechanism and awaits all
     * response messages. Returns an empty list if the subprocess has died before
     * or during the operation (e.g. due to an internal timeout).
     *
     * Encodes [msg] into a [SubprocessChannelMessage.OrchestratorRequest], registers a
     * pending [CompletableDeferred] in [localChannelPending] keyed by [msg.requestId], and
     * sends the channel message to the subprocess. The channel handler completes the
     * deferred when the matching [SubprocessChannelMessage.OrchestratorResponses] arrives.
     *
     * @param state The execution state (provides the [SubprocessRunner]).
     * @param msg The [WorkerWsMessage] to forward.
     * @return The list of response messages from the subprocess, or empty on subprocess death.
     */
    private suspend fun sendViaLocalChannel(
        state: WorkerExecutionState,
        msg: WorkerWsMessage
    ): List<WorkerWsMessage> {
        val requestPayload = cbor.encodeToByteArray<WorkerWsMessage>(msg)
        val channelMsgBytes = cbor.encodeToByteArray<SubprocessChannelMessage>(
            SubprocessChannelMessage.OrchestratorRequest(requestPayload)
        )
        val deferred = CompletableDeferred<List<WorkerWsMessage>>()
        localChannelPending[msg.requestId] = deferred
        state.runner.sendChannelMessage(channelMsgBytes)
        return try {
            withTimeout(LOCAL_CHANNEL_TIMEOUT_MS) { deferred.await() }
        } catch (_: Exception) {
            logger.warn("Local-channel request {} for execution did not complete (subprocess may have died)", msg.requestId)
            emptyList()
        } finally {
            localChannelPending.remove(msg.requestId)
        }
    }


    /**
     * Builds the channel message callback for a subprocess in WS or local-channel mode.
     *
     * Routes [OrchestratorResponses] back to the pending [CompletableDeferred] registered
     * by [sendViaLocalChannel] and forwards unsolicited [OrchestratorNotice] messages to
     * the registered [WorkerExecutionState.noticeCallback] (if any).
     *
     * @param executionId Execution identifier for logging.
     * @return The channel message callback.
     */
    private fun buildChannelHandler(
        executionId: String
    ): (ByteArray, (ByteArray) -> Unit) -> Unit {
        return { payload, _ ->
            try {
                when (val msg = cbor.decodeFromByteArray<SubprocessChannelMessage>(payload)) {
                    is SubprocessChannelMessage.OrchestratorResponses -> {
                        val responses = msg.payloads.map { cbor.decodeFromByteArray<WorkerWsMessage>(it) }
                        val requestId = responses.firstOrNull()?.requestId
                        if (requestId != null) {
                            localChannelPending.remove(requestId)?.complete(responses)
                        }
                    }
                    is SubprocessChannelMessage.OrchestratorNotice -> {
                        val state = executions[executionId]
                        val callback = state?.noticeCallback
                        if (callback != null) {
                            val notice = cbor.decodeFromByteArray<WorkerWsMessage>(msg.payload)
                            callback(notice)
                        } else {
                            logger.warn(
                                "Received OrchestratorNotice for execution {} but no notice callback registered",
                                executionId
                            )
                        }
                    }
                    else -> {
                        logger.error(
                            "Received unexpected channel message type from subprocess for execution {}: {}",
                            executionId, msg::class.java.name
                        )
                    }
                }
            } catch (e: Exception) {
                logger.warn("Error processing channel message for execution {}: {}", executionId, e.message)
            }
        }
    }

    /**
     * Configures the channel message handler and process-exit callback on an existing [SubprocessRunner].
     *
     * Used when reusing a pooled subprocess for a new execution.
     *
     * @param runner The subprocess runner to configure.
     * @param solutions The parent-side solution byte store to keep in sync.
     * @param executionId Execution identifier for logging.
     * @param wsMode Whether the subprocess operates in WebSocket or local-channel mode.
     */
    private fun configureChannelHandler(
        runner: SubprocessRunner,
        executionId: String,
        wsMode: Boolean
    ) {
        logger.info("Configuring pooled subprocess for execution {} (cancellation check already set)", executionId)
        runner.executionId = executionId
        runner.onChannelMessage = if (wsMode) buildChannelHandler(executionId) else null
        runner.onProcessExited = {
            localChannelPending.values.forEach { it.completeExceptionally(RuntimeException("Subprocess exited")) }
        }
    }

    /**
     * Creates a new [SubprocessRunner] with channel message handling configured.
     *
     * @param executionId Execution identifier for logging.
     * @param wsMode Whether the subprocess operates in WebSocket or local-channel mode.
     */
    private fun createSubprocessRunner(
        executionId: String,
        wsMode: Boolean
    ): SubprocessRunner {
        logger.info("Creating subprocess runner for execution {} with cancellation check (interval={}ms)", executionId, CANCELLATION_CHECK_INTERVAL_MS)
        val runner = SubprocessRunner(
            mainClass = WorkerSubprocessMain::class.java.name,
            cancellationCheck = { id -> isExecutionCancelled(id) },
            cancellationCheckIntervalMs = CANCELLATION_CHECK_INTERVAL_MS,
            onChannelMessage = if (wsMode) buildChannelHandler(executionId) else null
        )
        runner.executionId = executionId
        runner.onProcessExited = {
            localChannelPending.values.forEach { it.completeExceptionally(RuntimeException("Subprocess exited")) }
        }
        return runner
    }

    /**
     * Looks up the execution state for [executionId], throwing if not found.
     *
     * @param executionId The execution identifier to look up.
     * @return The [WorkerExecutionState] for the execution.
     */
    private fun requireExecution(executionId: String): WorkerExecutionState {
        return executions[executionId]
            ?: throw IllegalArgumentException("Execution not found: $executionId")
    }

    /**
     * Releases all active executions, their associated resources, and any pooled subprocesses.
     *
     * Should be called when the application is shutting down.
     */
    suspend fun close() {
        for ((id, state) in executions) {
            try {
                state.suspendClose()
            } catch (e: Exception) {
                logger.warn("Failed to close execution {}: {}", id, e.message)
            }
        }
        executions.clear()
        subprocessPool.close()
    }

    /**
     * Per-execution runtime state held in the parent process.
     *
     * @param executionId The execution identifier.
     * @param runner The subprocess runner.
     * @param dispatcher Thread pool used for blocking subprocess I/O.
     */
    inner class WorkerExecutionState(
        val executionId: String,
        @Volatile var runner: SubprocessRunner,
        val dispatcher: ExecutorCoroutineDispatcher
    ) {
        /**
         * Callback invoked (on the subprocess reader thread) when an unsolicited
         * [SubprocessChannelMessage.OrchestratorNotice] arrives from the subprocess.
         *
         * Registered by [WorkerClient] in local-channel mode during [allocate] and
         * cleared during [cleanup]. `null` when no listener is registered (e.g. in
         * remote-WebSocket mode where the subprocess communicates directly with the
         * orchestrator over its own WS connection).
         */
        @Volatile
        var noticeCallback: ((WorkerWsMessage) -> Unit)? = null

        /**
         * Stops subprocess and releases thread pool. 
         */
        suspend fun suspendClose() {
            runner.stop()
            dispatcher.close()
        }
    }


    /**
     * Checks whether the execution has been cancelled or deleted in the database.
     *
     * This is called from the [SubprocessRunner] cancellation polling thread (not a coroutine),
     * so it uses a blocking [transaction] call.
     *
     * @param executionId The execution identifier (string form).
     * @return `true` if the execution record is missing or its state is [ExecutionState.CANCELLED].
     */
    private fun isExecutionCancelled(executionId: String): Boolean {
        return try {
            val uuid = UUID.fromString(executionId)
            val state = transaction {
                OptimizerExecutionsTable.selectAll()
                    .where { OptimizerExecutionsTable.id eq uuid.toKotlinUuid() }
                    .firstOrNull()
                    ?.get(OptimizerExecutionsTable.state)
            }
            val cancelled = state == null || state == ExecutionState.CANCELLED
            logger.warn("Cancellation check for execution {}: {} (dbState={})", executionId, if (cancelled) { "CANCELLED" } else { "not cancelled" }, state)
            cancelled
        } catch (e: Exception) {
            logger.warn("Cancellation check for execution {} failed: {}", executionId, e.message)
            false
        }
    }

    companion object {
        /**
         * Graph backend types supported by this worker.
         */
        val SUPPORTED_GRAPH_BACKENDS: List<GraphBackendType> = listOf(
            GraphBackendType.MDEO, GraphBackendType.Tinker
        )

        /**
         * Interval between cancellation checks in the subprocess runner's polling thread.
         */
        private const val CANCELLATION_CHECK_INTERVAL_MS = 5000L

        /**
         * Timeout for local-channel request/response round-trips.
         * Matches the WebSocket operation timeout in [WorkerClient].
         */
        const val LOCAL_CHANNEL_TIMEOUT_MS = 600_000L

        /**
         * Builds the default [SubprocessPool] for worker executions.
         *
         * Uses the CBOR-encoded [WorkerSubprocessRequest.Reset] / [WorkerSubprocessResponse.ResetOk]
         * protocol to reset subprocesses before returning them to the pool.
         *
         * @param maxSize Maximum number of idle subprocesses the pool will hold.
         * @return A configured [SubprocessPool] with the CBOR reset protocol.
         */
        @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
        fun buildDefaultPool(maxSize: Int = SubprocessPool.DEFAULT_POOL_SIZE): SubprocessPool {
            val cbor = Cbor { ignoreUnknownKeys = true }
            return SubprocessPool(maxSize = maxSize) { runner ->
                val result = runner.sendCommand(
                    cbor.encodeToByteArray<WorkerSubprocessRequest>(WorkerSubprocessRequest.Reset)
                )
                if (result !is SubprocessResult.Success) return@SubprocessPool false
                runCatching {
                    cbor.decodeFromByteArray<WorkerSubprocessResponse>(result.data) is WorkerSubprocessResponse.ResetOk
                }.getOrDefault(false)
            }
        }
    }
}
