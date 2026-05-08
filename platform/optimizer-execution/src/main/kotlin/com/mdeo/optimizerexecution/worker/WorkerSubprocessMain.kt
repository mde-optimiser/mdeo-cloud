package com.mdeo.optimizerexecution.worker

import com.mdeo.execution.common.subprocess.SubprocessMain
import com.mdeo.expression.ast.expressions.TypedExpression
import com.mdeo.expression.ast.statements.TypedStatement
import com.mdeo.metamodel.Metamodel
import com.mdeo.metamodel.SerializedModel
import com.mdeo.modeltransformation.ast.TypedAst as TransformationTypedAst
import com.mdeo.modeltransformation.ast.TransformationOperator
import com.mdeo.modeltransformation.ast.expressions.TypedExpressionSerializer as TransformationExpressionSerializer
import com.mdeo.modeltransformation.ast.patterns.TypedPatternElement
import com.mdeo.modeltransformation.ast.patterns.TypedPatternElementSerializer
import com.mdeo.modeltransformation.ast.statements.TypedTransformationStatement
import com.mdeo.modeltransformation.ast.statements.TypedTransformationStatementSerializer
import com.mdeo.optimizer.evaluation.EvaluationTask
import com.mdeo.optimizer.evaluation.LocalMutationEvaluator
import com.mdeo.optimizer.evaluation.MutationTask
import com.mdeo.optimizer.evaluation.NodeBatch
import com.mdeo.optimizer.guidance.ScriptGuidanceFunction
import com.mdeo.optimizer.operators.MutationStrategyFactory
import com.mdeo.optimizer.solution.Solution
import com.mdeo.optimizer.worker.*
import com.mdeo.modeltransformation.graph.mdeo.MdeoModelGraph
import com.mdeo.modeltransformation.graph.tinker.TinkerModelGraph
import com.mdeo.optimizer.config.GraphBackendType
import com.mdeo.optimizer.config.ObjectiveTendency
import com.mdeo.script.ast.TypedAst as ScriptTypedAst
import com.mdeo.script.ast.expressions.TypedExpressionSerializer as ScriptExpressionSerializer
import com.mdeo.script.ast.statements.TypedStatementSerializer
import com.mdeo.script.compiler.CompilationInput
import com.mdeo.script.compiler.ScriptCompiler
import com.mdeo.script.runtime.ExecutionEnvironment
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Subprocess entry point for per-execution mutation and evaluation work.
 *
 * Supports two operating modes selected during [WorkerSubprocessRequest.Setup]:
 *
 * **WebSocket mode** (when [WorkerSubprocessRequest.Setup.orchestratorWsUrl] is set):
 * The subprocess opens a WebSocket connection directly to the orchestrator and handles
 * all generation traffic (imports, mutations, discards) entirely in-process via a
 * [WebSocketOrchestratorChannel]. Solution bytes and discards are synced to the parent
 * via [SubprocessChannelMessage]s.
 *
 * **Local-channel mode** (when [WorkerSubprocessRequest.Setup.useLocalChannel] is `true`):
 * Instead of opening a WebSocket, the subprocess receives orchestrator requests through the
 * existing stdin/stdout pipe as [SubprocessChannelMessage.OrchestratorRequest] messages and
 * sends responses back as [SubprocessChannelMessage.OrchestratorResponses]. The parent process
 * (WorkerService) acts as a forwarder between the orchestrator WebSocket session and the
 * subprocess channel. This avoids establishing a new network connection when both sides
 * run on the same host, making it more efficient for the local node in federated setups.
 *
 * Heavy setup work (compilation, class loading) happens once during Setup so that
 * subsequent operations pay only the cost of mutation and evaluation.
 *
 * The [OrchestratorChannel] abstraction unifies WebSocket and local-channel communication
 * so that all message dispatching (“Receive request → call [handleWsMessage] → send responses”)
 * runs through a single code path regardless of the underlying transport.
 */
@OptIn(ExperimentalSerializationApi::class)
class WorkerSubprocessMain : SubprocessMain() {

    /**
     * CBOR codec for encoding/decoding subprocess and WebSocket messages. 
     */
    private val cbor = Cbor { ignoreUnknownKeys = true }

    /**
     * JSON codec for transformation typed ASTs with contextual serializers. 
     */
    private val transformationJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
        serializersModule = SerializersModule {
            contextual(TypedExpression::class, TransformationExpressionSerializer)
            contextual(TypedTransformationStatement::class, TypedTransformationStatementSerializer)
            contextual(TypedPatternElement::class, TypedPatternElementSerializer)
        }
    }

    /**
     * JSON codec for script typed ASTs with contextual serializers. 
     */
    private val scriptJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
        serializersModule = SerializersModule {
            contextual(TypedExpression::class, ScriptExpressionSerializer)
            contextual(TypedStatement::class, TypedStatementSerializer)
        }
    }

    /**
     * Abstraction over the transport used to exchange [WorkerWsMessage] frames with
     * the orchestrator.
     *
     * Two implementations:
     * - [WebSocketOrchestratorChannel] — opens a real WebSocket connection (remote nodes).
     * - [StdioOrchestratorChannel] — forwards messages over the subprocess stdin/stdout
     *   pipe via [SubprocessChannelMessage.OrchestratorRequest] /
     *   [SubprocessChannelMessage.OrchestratorResponses] (local node).
     */
    private interface OrchestratorChannel {
        /**
         * Runs the message-dispatch loop, blocking until the channel is closed or an
         * irrecoverable error occurs.
         *
         * [handler] is called for each incoming [WorkerWsMessage] and must return the
         * list of response messages to deliver back to the orchestrator.
         */
        fun runLoop(handler: (WorkerWsMessage) -> List<WorkerWsMessage>)

        /**
         * Sends an unsolicited [WorkerWsMessage] to the orchestrator outside of the
         * normal request-response loop.
         *
         * Used for graceful-shutdown handshakes where the subprocess needs to push a
         * [WorkerShutdownNotice] before halting. Thread-safe.
         */
        fun sendNotice(msg: WorkerWsMessage)

        /**
         * Signals the loop to stop and releases any held resources. 
         */
        fun close()
    }

    /**
     * [OrchestratorChannel] backed by a WebSocket connection to [wsUrl].
     *
     * Opens the connection eagerly in [runLoop] and processes one frame at a time.
     * [close] tears down the underlying [HttpClient], which cancels the pending coroutine
     * and causes [runLoop] to return.
     *
     * The active WebSocket session reference is stored in [activeSession] so that
     * [sendNotice] can push unsolicited frames from the watchdog thread.
     */
    private inner class WebSocketOrchestratorChannel(private val wsUrl: String) : OrchestratorChannel {
        /**
         * Ktor HTTP client used to open the WebSocket connection to the orchestrator. 
         */
        private val client = HttpClient(CIO) { install(WebSockets) }

        /**
         * Reference to the active WebSocket session, set once the connection is established.
         * Used by [sendNotice] to push unsolicited frames from outside the dispatch loop.
         */
        @Volatile
        private var activeSession: DefaultWebSocketSession? = null

        override fun runLoop(handler: (WorkerWsMessage) -> List<WorkerWsMessage>) {
            runBlocking {
                try {
                    client.webSocket(wsUrl) {
                        activeSession = this
                        for (frame in incoming) {
                            if (frame !is Frame.Binary) {
                                continue
                            }
                            val msg = cbor.decodeFromByteArray<WorkerWsMessage>(frame.readBytes())
                            val responses = handler(msg)
                            for (response in responses) {
                                send(Frame.Binary(true, cbor.encodeToByteArray<WorkerWsMessage>(response)))
                            }
                        }
                    }
                } catch (_: CancellationException) {
                    // Normal shutdown triggered by close()
                } finally {
                    activeSession = null
                }
            }
        }

        override fun sendNotice(msg: WorkerWsMessage) {
            val session = activeSession ?: return
            val bytes = cbor.encodeToByteArray<WorkerWsMessage>(msg)
            try {
                runBlocking { session.send(Frame.Binary(true, bytes)) }
            } catch (_: Exception) {
                // Session may have already closed — nothing we can do on the watchdog thread
            }
        }

        override fun close() {
            client.close()
        }
    }

    /**
     * [OrchestratorChannel] backed by the subprocess stdin/stdout pipe.
     *
     * The parent process (WorkerService) forwards orchestrator messages as
     * [SubprocessChannelMessage.OrchestratorRequest] channel messages; this class
     * enqueues them via [enqueue] (called from [handleChannelMessage]) and dispatches
     * them to the handler in [runLoop]. Responses are sent back as
     * [SubprocessChannelMessage.OrchestratorResponses] using [sendChannelMessage].
     *
     * Unsolicited outgoing notices (e.g. [WorkerShutdownNotice]) are sent via
     * [sendNotice], which uses [SubprocessChannelMessage.OrchestratorNotice] so that the
     * parent can forward them without a matching pending request.
     */
    private inner class StdioOrchestratorChannel : OrchestratorChannel {
        /**
         * Queue of raw CBOR payloads forwarded from the parent via [enqueue], consumed by [runLoop]. 
         */
        private val queue = LinkedBlockingQueue<ByteArray>()

        /**
         * Set to `true` by [close] to stop the [runLoop] poll loop. 
         */
        @Volatile
        private var closed = false

        /**
         * Delivers an incoming orchestrator request payload to the dispatch loop. 
         */
        fun enqueue(payload: ByteArray) {
            if (!closed) queue.offer(payload)
        }

        override fun runLoop(handler: (WorkerWsMessage) -> List<WorkerWsMessage>) {
            while (!closed) {
                val payload = queue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                val msg = cbor.decodeFromByteArray<WorkerWsMessage>(payload)
                val responses = handler(msg)
                val responsePayloads = responses.map { cbor.encodeToByteArray<WorkerWsMessage>(it) }
                sendChannelMessage(
                    cbor.encodeToByteArray<SubprocessChannelMessage>(
                        SubprocessChannelMessage.OrchestratorResponses(responsePayloads)
                    )
                )
            }
        }

        override fun sendNotice(msg: WorkerWsMessage) {
            try {
                sendChannelMessage(
                    cbor.encodeToByteArray<SubprocessChannelMessage>(
                        SubprocessChannelMessage.OrchestratorNotice(cbor.encodeToByteArray<WorkerWsMessage>(msg))
                    )
                )
            } catch (_: Exception) {
                // Pipe may be broken — nothing we can do on the watchdog thread
            }
        }

        override fun close() {
            closed = true
        }
    }

    private val logger by lazy { LoggerFactory.getLogger(WorkerSubprocessMain::class.java) }

    /**
     * Coroutine scope used for outgoing peer WebSocket connections.
     *
     * Lives for the lifetime of this subprocess instance. All [PeerPushChannel] jobs are
     * children of this scope; cancelling the scope (on shutdown) closes all peer connections.
     */
    private val peerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Cached outgoing peer connections keyed by `"$destBaseUrl/$executionId"`.
     *
     * Connections are created on demand in [getOrCreatePeerChannel] and reused for all
     * subsequent relocations within the same optimization run.
     */
    private val peerChannels = ConcurrentHashMap<String, PeerPushChannel>()

    /**
     * Returns an existing [PeerPushChannel] to [destBaseUrl]/[executionId], or creates and
     * caches a new one. The channel's WS connection is established lazily in a background
     * coroutine of [peerScope].
     */
    private fun getOrCreatePeerChannel(destBaseUrl: String, executionId: String): PeerPushChannel {
        val key = "$destBaseUrl/$executionId"
        return peerChannels.getOrPut(key) { PeerPushChannel(destBaseUrl, executionId) }
    }

    /**
     * Closes all open peer push channels and cancels their background coroutines.
     */
    private fun closePeerChannels() {
        for ((_, ch) in peerChannels) ch.close()
        peerChannels.clear()
    }

    /**
     * Persistent outgoing WebSocket connection from this subprocess to a destination
     * worker's `/ws/peer/executions/{id}` endpoint.
     *
     * A single background coroutine (launched in [peerScope]) keeps the connection alive
     * and processes queued [SolutionPushRequest] frames sequentially. Each [push] call
     * enqueues a request and suspends until the matching [SolutionPushAck] is received.
     *
     * @param destBaseUrl HTTP base URL of the destination worker (e.g. `http://worker-2:8080`).
     * @param destExecutionId The execution identifier on the destination worker.
     */
    private inner class PeerPushChannel(
        destBaseUrl: String,
        destExecutionId: String
    ) {
        private val wsUrl = destBaseUrl
            .replace("https://", "wss://")
            .replace("http://", "ws://") +
            "/ws/peer/executions/$destExecutionId"

        /** Pending acks keyed by requestId. */
        private val pendingAcks = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

        /** Queue of encoded frames to send. */
        private val sendQueue = kotlinx.coroutines.channels.Channel<ByteArray>(kotlinx.coroutines.channels.Channel.UNLIMITED)

        @Volatile
        private var failed = false

        private val peerClient = HttpClient(CIO) { install(WebSockets) }

        val job: Job = peerScope.launch {
            try {
                peerClient.webSocket(wsUrl) {
                    val readerJob = launch {
                            for (frame in incoming) {
                            if (frame !is Frame.Binary) continue
                            val msg = runCatching {
                                cbor.decodeFromByteArray<WorkerWsMessage>(frame.readBytes())
                            }.getOrNull() ?: continue
                            if (msg is SolutionPushAck) {
                                pendingAcks.remove(msg.requestId)?.complete(Unit)
                            }
                        }
                    }
                    for (payload in sendQueue) {
        
                        send(Frame.Binary(true, payload))
                    }

                    readerJob.cancelAndJoin()
                }
            } catch (_: CancellationException) {
                // Expected when shutting down
            } catch (e: Exception) {
                logger.error("[PeerPushChannel] FAILED to {}: {}", wsUrl, e.message, e)
                failed = true
                pendingAcks.values.forEach { it.completeExceptionally(e) }
            } finally {
                peerClient.close()
            }
        }

        /**
         * Pushes [solutions] to the destination worker and suspends until the ack arrives.
         *
         * @return `true` on success, `false` if the channel has already failed.
         */
        suspend fun push(solutions: List<SolutionData>): Boolean {

            if (failed) {
                logger.warn("[PeerPushChannel.push] FAILED channel already failed to {}", wsUrl)
                return false
            }
            val requestId = UUID.randomUUID().toString()
            val payload = cbor.encodeToByteArray<WorkerWsMessage>(SolutionPushRequest(requestId, solutions))
            val ack = CompletableDeferred<Unit>()
            pendingAcks[requestId] = ack
            sendQueue.send(payload)
            return try {
                withTimeout(PEER_PUSH_TIMEOUT_MS) { ack.await() }
                true
            } catch (e: Exception) {
                pendingAcks.remove(requestId)
                false
            }
        }

        fun close() {
            sendQueue.close()
            job.cancel()
        }
    }

    /**
     * The active [LocalMutationEvaluator] for this subprocess, or `null` before [handleSetup] runs. 
     */
    @Volatile
    private var evaluator: LocalMutationEvaluator? = null

    /**
     * Active orchestrator channel (WebSocket or stdio), or `null` in legacy mode.
     * Assigned by [startOrchestratorChannel] and cleared by [closeOrchestratorChannel].
     */
    @Volatile
    private var activeOrchestratorChannel: OrchestratorChannel? = null

    /**
     * Completed with `true` when a [WorkerShutdownAck] is received from the orchestrator.
     * Used by [notifyShutdownAndWait] to block until the orchestrator has recorded the
     * shutdown reason before this subprocess halts.
     */
    private val shutdownAckDeferred = AtomicReference<kotlinx.coroutines.CompletableDeferred<Boolean>?>(null)

    /**
     * Background thread running the orchestrator channel dispatch loop.
     * Stored so that [closeOrchestratorChannel] can join it before evaluator cleanup,
     * preventing a race between in-progress mutation work and evaluator teardown.
     */
    @Volatile
    private var wsThread: Thread? = null

    /**
     * Thread-pool dispatcher used to evaluate mutation tasks in parallel when [workerThreads] > 1.
     * `null` in single-threaded mode. Initialised by [handleSetup] and closed by [handleReset] /
     * [cleanup].
     */
    @Volatile
    private var taskDispatcher: ExecutorCoroutineDispatcher? = null

    /**
     * Monotonically increasing counter used to correlate watchdog timeout registrations. 
     */
    private val evalIdCounter = AtomicInteger(0)

    /**
     * Per-script evaluation timeout budget in milliseconds (0 means no timeout). 
     */
    private var scriptTimeoutMs: Long = 0L
    /**
     * Per-transformation execution timeout budget in milliseconds (0 means no timeout). 
     */
    private var transformationTimeoutMs: Long = 0L
    /**
     * Total number of guidance functions (objectives + constraints) used to compute composite timeout. 
     */
    private var numGuidanceFunctions: Int = 0

    /**
     * Per-solution arrival signals for push-rebalanced solutions. When a peer subprocess
     * delivers a [SolutionPushRequest] to this node's [WorkerService], the parent injects
     * the model via [SubprocessChannelMessage.SolutionInjected]; this map lets mutation tasks
     * block on their specific signal until the data arrives. Registered by
     * [awaitSolutionAvailable] before checking [LocalMutationEvaluator.hasSolution] to
     * avoid the lost-update race condition.
     */
    private val incomingSolutionSignals = ConcurrentHashMap<String, LinkedBlockingQueue<Unit>>()

    override fun handleCommand(payload: ByteArray): ByteArray {
        return when (val request = cbor.decodeFromByteArray<WorkerSubprocessRequest>(payload)) {
            is WorkerSubprocessRequest.Setup -> { handleSetup(request) }
            is WorkerSubprocessRequest.Reset -> { handleReset() }
        }
    }

    /**
     * Handles a [WorkerSubprocessRequest.Setup] command: compiles resources, generates the
     * initial population, and starts the orchestrator channel loop if required.
     *
     * @param request The setup request containing all compilation inputs and configuration.
     * @return CBOR-encoded [WorkerSubprocessResponse.SetupOk] with the initial solutions.
     */
    private fun handleSetup(request: WorkerSubprocessRequest.Setup): ByteArray {
        scriptTimeoutMs = request.scriptTimeoutMs
        transformationTimeoutMs = request.transformationTimeoutMs
        numGuidanceFunctions = request.goalConfig.objectives.size + request.goalConfig.constraints.size
        if (request.workerThreads > 1) {
            taskDispatcher = Executors.newFixedThreadPool(request.workerThreads).asCoroutineDispatcher()
        }

        val (localEvaluator, initialSolutions) = buildEvaluatorAndInitialize(request)
        evaluator = localEvaluator
        
        localEvaluator.onSolutionMaterialized = { solutionId ->
            incomingSolutionSignals[solutionId]?.offer(Unit)
        }

        val channel: OrchestratorChannel? = when {
            request.useLocalChannel && !request.skipInitialization -> {
                StdioOrchestratorChannel()
            }
            request.orchestratorWsUrl != null && !request.skipInitialization -> {
                WebSocketOrchestratorChannel(request.orchestratorWsUrl)
            }
            else -> {
                null
            }
        }

        if (channel != null) {
            startOrchestratorChannel(channel)
        }

        return cbor.encodeToByteArray<WorkerSubprocessResponse>(WorkerSubprocessResponse.SetupOk(initialSolutions))
    }

    /**
     * Compiles all resources and optionally generates initial solutions.
     *
     * @return Pair of the evaluator and the list of initial solutions.
     */
    private fun buildEvaluatorAndInitialize(
        request: WorkerSubprocessRequest.Setup
    ): Pair<LocalMutationEvaluator, List<WorkerInitialSolution>> {
        val transformations: Map<String, TransformationTypedAst> = request.transformationAstJsons
            .mapValues { (_, json) -> transformationJson.decodeFromString<TransformationTypedAst>(json) }

        // Sort paths alphabetically and assign stable numerical IDs so that every node
        // in a federated setup assigns the same id to the same operator.
        val operators: List<TransformationOperator> = transformations.keys.sorted()
            .mapIndexed { idx, path -> TransformationOperator(id = idx, ast = transformations[path]!!) }

        val scriptAsts: Map<String, ScriptTypedAst> = request.scriptAstJsons
            .mapValues { (_, json) -> scriptJson.decodeFromString<ScriptTypedAst>(json) }

        val compiledProgram = ScriptCompiler().compile(CompilationInput(scriptAsts), request.metamodelData)
        val metamodel = compiledProgram.metamodel ?: Metamodel.compile(request.metamodelData)
        val clazz = ExecutionEnvironment(compiledProgram).scriptProgramClass

        val objectives = request.goalConfig.objectives.map { obj ->
            val jvmName = compiledProgram.functionLookup[obj.path]?.get(obj.functionName)
                ?: error("Objective '${obj.functionName}' not found in '${obj.path}'")
            ScriptGuidanceFunction(clazz, jvmName, System.out, "${obj.path}::${obj.functionName}", obj.type)
        }

        val constraints = request.goalConfig.constraints.map { con ->
            val jvmName = compiledProgram.functionLookup[con.path]?.get(con.functionName)
                ?: error("Constraint '${con.functionName}' not found in '${con.path}'")
            ScriptGuidanceFunction(clazz, jvmName, System.out, "${con.path}::${con.functionName}")
        }

        val mutationStrategy = MutationStrategyFactory.create(request.solverConfig.parameters.mutation, operators)

        val graphBackendType = request.graphBackendType
        val localEvaluator = LocalMutationEvaluator(
            initialSolutionProvider = {
                val modelGraph = when (graphBackendType) {
                    GraphBackendType.MDEO -> MdeoModelGraph.create(request.initialModelData, metamodel)
                    GraphBackendType.Tinker -> TinkerModelGraph.create(request.initialModelData, metamodel)
                }
                Solution(modelGraph)
            },
            mutationStrategy = mutationStrategy,
            objectives = objectives,
            constraints = constraints,
            metamodel = metamodel,
            graphBackendType = graphBackendType
        )

        val initialSolutions: List<WorkerInitialSolution> = if (!request.skipInitialization) {
            runBlocking { localEvaluator.initialize(request.initialSolutionCount) }.map { result ->
                val ref = com.mdeo.optimizer.evaluation.WorkerSolutionRef(LocalMutationEvaluator.DEFAULT_NODE_ID, result.solutionId)
                val serializedModel = runBlocking { localEvaluator.getSolutionData(ref) }
                WorkerInitialSolution(
                    solutionId = result.solutionId,
                    modelBytes = cbor.encodeToByteArray<SerializedModel>(serializedModel)
                )
            }
        } else {
            emptyList()
        }

        return Pair(localEvaluator, initialSolutions)
    }

    /**
     * Starts the [channel]'s message-dispatch loop in a background daemon thread.
     *
     * The loop calls [handleWsMessage] for every incoming [WorkerWsMessage], so both
     * WebSocket and local-channel transports share the same dispatch logic.
     */
    private fun startOrchestratorChannel(channel: OrchestratorChannel) {
        activeOrchestratorChannel = channel
        val thread = Thread {
            channel.runLoop { msg -> handleWsMessage(msg) }
        }
        thread.isDaemon = true
        thread.name = "subprocess-orchestrator"
        wsThread = thread
        thread.start()
    }

    /**
     * Routes an [OrchestratorRequest] channel message to the active [StdioOrchestratorChannel].
     *
     * Called by the parent's message-read loop whenever it sends a request down the pipe.
     * Silently ignored when not in local-channel mode (i.e. when
     * [activeOrchestratorChannel] is a [WebSocketOrchestratorChannel] or `null`).
     */
    override fun handleChannelMessage(payload: ByteArray) {
        try {
            when (val msg = cbor.decodeFromByteArray<SubprocessChannelMessage>(payload)) {
                is SubprocessChannelMessage.OrchestratorRequest -> {
                    (activeOrchestratorChannel as? StdioOrchestratorChannel)?.enqueue(msg.payload)
                }
                is SubprocessChannelMessage.SolutionInjected -> {
                    val ev = evaluator ?: return
                    val model = cbor.decodeFromByteArray<SerializedModel>(msg.modelBytes)
                    ev.receiveSolution(msg.solutionId, model, msg.failedOperators.toSet())
                    incomingSolutionSignals[msg.solutionId]?.offer(Unit)
                }
                else -> {
                    // Ignore messages not directed at the subprocess
                }
            }
        } catch (e: Exception) {
            logger.error("[handleChannelMessage] EXCEPTION: {}", e.message, e)
        }
    }

    /**
     * Intercepts the watchdog timeout before the subprocess halts its JVM.
     *
     * Sends a [WorkerShutdownNotice] to the orchestrator via the active channel and
     * waits up to [SHUTDOWN_NOTICE_TIMEOUT_MS] for a [WorkerShutdownAck]. This ensures
     * the orchestrator records the correct shutdown reason before the WebSocket connection
     * is torn down, preventing an unhelpful "worker no longer reachable" error message.
     *
     * @param timeoutId The identifier of the expired watchdog timeout.
     */
    override fun onWatchdogTimeout(timeoutId: Int) {
        val chan = activeOrchestratorChannel ?: return
        val reason = "Worker timed out during script or transformation evaluation (timeout id $timeoutId)"
        notifyShutdownAndWait(chan, reason)
    }

    /**
     * Sends a [WorkerShutdownNotice] over [channel] and blocks until [WorkerShutdownAck]
     * is received or [SHUTDOWN_NOTICE_TIMEOUT_MS] elapses.
     *
     * Registers a [CompletableDeferred] in [shutdownAckDeferred] so that the dispatch
     * loop can complete it when the ack arrives.
     *
     * @param channel The active orchestrator channel to send the notice on.
     * @param reason Human-readable reason for the shutdown.
     */
    private fun notifyShutdownAndWait(channel: OrchestratorChannel, reason: String) {
        val ackDeferred = kotlinx.coroutines.CompletableDeferred<Boolean>()
        shutdownAckDeferred.set(ackDeferred)
        val requestId = UUID.randomUUID().toString()
        try {
            channel.sendNotice(WorkerShutdownNotice(requestId = requestId, reason = reason))
            runBlocking {
                withTimeoutOrNull(SHUTDOWN_NOTICE_TIMEOUT_MS) { ackDeferred.await() }
            }
        } catch (_: Exception) {
            // Best-effort: if sending or waiting fails, proceed to halt anyway
        } finally {
            shutdownAckDeferred.set(null)
        }
    }

    /**
     * Handles a WebSocket message from the orchestrator, performing all mutation
     * and evaluation work in-process.
     */
    private fun handleWsMessage(msg: WorkerWsMessage): List<WorkerWsMessage> {
        return when (msg) {
            is NodeWorkBatchRequest -> { listOf(handleWsNodeWorkBatch(msg)) }
            is SolutionBatchFetchRequest -> { listOf(handleWsBatchFetch(msg)) }
            is WorkerShutdownAck -> {
                // Complete the pending deferred so notifyShutdownAndWait can unblock
                shutdownAckDeferred.get()?.complete(true)
                emptyList()
            }
            else -> { emptyList() }
        }
    }

    /**
     * Blocks until the solution for [solutionId] arrives in the evaluator, or [timeoutMs] elapses.
     *
     * Registers a [LinkedBlockingQueue] signal BEFORE checking [LocalMutationEvaluator.hasSolution]
     * to avoid the lost-update race: if the parent delivers a
     * [SubprocessChannelMessage.SolutionInjected] between the check and the register,
     * the offer will complete the queue and the poll returns immediately.
     *
     * @param solutionId The solution to wait for.
     * @param timeoutMs Maximum wait in milliseconds.
     * @return `true` if the solution arrived within the timeout, `false` otherwise.
     */
    private fun awaitSolutionAvailable(solutionId: String, timeoutMs: Long): Boolean {
        val signal = LinkedBlockingQueue<Unit>(1)
        incomingSolutionSignals[solutionId] = signal
        
        // Re-check after registering — injection may have arrived between the caller's first check and now
        if (evaluator?.hasSolution(solutionId) == true) {
            incomingSolutionSignals.remove(solutionId)
            return true
        }
        
        val arrived = signal.poll(timeoutMs, TimeUnit.MILLISECONDS) != null
        incomingSolutionSignals.remove(solutionId)
        return arrived
    }

    /**
     * Processes a unified work batch entirely in-process: mutations with per-mutation
     * watchdog timeouts, evaluation-only tasks with timeouts, discards, and outgoing
     * solution relocations.
     *
     * Relocations are launched concurrently in [peerScope] as soon as model bytes are
     * captured from the local evaluator, so that the destination receives them while
     * mutations are still running. Incoming solutions (transferred here from another node)
     * are awaited on demand inside [processSingleMutationTask]: a task blocks until the
     * matching [SubprocessChannelMessage.SolutionInjected] has been processed by the
     * parent and forwarded to this subprocess, or until [INCOMING_SOLUTION_TIMEOUT_MS].
     *
     * When a multi-thread [taskDispatcher] is configured, mutation and evaluation tasks are
     * dispatched in parallel — each task runs on its own thread, independently of the others.
     * Each task registers its own timeout on the child-process watchdog.
     */
    private fun handleWsNodeWorkBatch(msg: NodeWorkBatchRequest): NodeWorkBatchResponse {
        val ev = requireEvaluator()
        val nodeId = LocalMutationEvaluator.DEFAULT_NODE_ID
        val dispatcher = taskDispatcher
        val allTasks = msg.tasks.size + msg.evaluationTasks.size

        val pushJobs = mutableListOf<Deferred<Boolean>>()
        for (relocation in msg.relocations) {
            val solutions = relocation.solutionIds.mapNotNull { solId ->
                try {
                    val ref = com.mdeo.optimizer.evaluation.WorkerSolutionRef(
                        nodeId = LocalMutationEvaluator.DEFAULT_NODE_ID,
                        solutionId = solId
                    )
                    val model = runBlocking { ev.getSolutionData(ref) }
                    val failedOperators = ev.getSolutionFailedOperators(solId)
                    SolutionData(
                        solutionId = solId,
                        serializedModel = model,
                        failedOperators = failedOperators.toList()
                    )
                } catch (e: Exception) {
                    logger.warn("[handleWsNodeWorkBatch] RELOCATION_FAIL solId={}: {}", solId, e.message)
                    null
                }
            }
            if (solutions.isNotEmpty()) {
                val channel = getOrCreatePeerChannel(relocation.destinationWorkerBaseUrl, relocation.executionId)
                val job = peerScope.async {
                    try {
                        val success = channel.push(solutions)
                        if (!success) {
                            logger.error("[handleWsNodeWorkBatch] RELOCATION_PUSH_FAILED for {} (channel timed out or failed)", relocation.destinationWorkerBaseUrl)
                        }
                        success
                    } catch (e: Exception) {
                        logger.error("[handleWsNodeWorkBatch] RELOCATION_PUSH_ERROR: {}", e.message, e)
                        false
                    }
                }
                pushJobs.add(job)
            }
        }

        val mutationResults: List<BatchResult> = if (dispatcher != null && allTasks > 1) {
            runBlocking {
                msg.tasks.map { task ->
                    async(dispatcher) { processSingleMutationTask(task, ev, nodeId) }
                }.awaitAll()
            }
        } else {
            msg.tasks.map { task -> processSingleMutationTask(task, ev, nodeId) }
        }

        val evaluationResults: List<BatchResult> = if (dispatcher != null && allTasks > 1) {
            runBlocking {
                msg.evaluationTasks.map { task ->
                    async(dispatcher) { processSingleEvaluationTask(task, ev, nodeId) }
                }.awaitAll()
            }
        } else {
            msg.evaluationTasks.map { task -> processSingleEvaluationTask(task, ev, nodeId) }
        }

        if (pushJobs.isNotEmpty()) {
            runBlocking {
                try {
                    pushJobs.awaitAll()
                } catch (e: Exception) {
                    logger.error("[handleWsNodeWorkBatch] PUSH_AWAIT_ERROR: {}", e.message)
                }
            }
        }

        if (msg.discards.isNotEmpty()) {
            val batch = NodeBatch(
                nodeId = nodeId,
                tasks = emptyList(),
                discards = msg.discards
            )
            runBlocking { ev.executeNodeBatches(listOf(batch)) }
        }


        return NodeWorkBatchResponse(requestId = msg.requestId, results = mutationResults + evaluationResults)
    }

    /**
     * Evaluates a single [BatchTask] with a per-mutation watchdog timeout.
     *
     * If the solution is not yet present in the evaluator (incoming from another node
     * via push rebalancing), blocks until [SubprocessChannelMessage.SolutionInjected]
     * has been processed and the signal offered, or [INCOMING_SOLUTION_TIMEOUT_MS] elapses.
     *
     * Thread-safe: relies on the synchronized [sendChannelMessage] for all parent-process
     * communication, and the [ChildProcessWatchdog] for concurrent timeout tracking.
     */
    private fun processSingleMutationTask(
        task: BatchTask,
        ev: LocalMutationEvaluator,
        nodeId: String
    ): BatchResult {
        if (!ev.hasSolution(task.solutionId)) {
            val arrived = awaitSolutionAvailable(task.solutionId, INCOMING_SOLUTION_TIMEOUT_MS)
            if (!arrived) {
                logger.warn(
                    "[processSingleMutationTask] TIMEOUT solId={} did not arrive within {}ms — failing task (penalty)",
                    task.solutionId, INCOMING_SOLUTION_TIMEOUT_MS
                )
                return failedResult(task.solutionId)
            }
        }
        val evalId = evalIdCounter.incrementAndGet()
        val mutationTimeoutMs = numGuidanceFunctions.toLong() * scriptTimeoutMs + transformationTimeoutMs
        if (mutationTimeoutMs > 0) {
            registerTimeout(evalId, mutationTimeoutMs)
        }
        return try {
            val batch = NodeBatch(
                nodeId = nodeId,
                tasks = listOf(MutationTask(task.solutionId, nodeId)),
                discards = emptyList()
            )
            val evalResults = runBlocking { ev.executeNodeBatches(listOf(batch)) }
            val result = evalResults.firstOrNull()

            if (result != null && result.succeeded) {
                BatchResult(
                    parentSolutionId = task.solutionId,
                    newSolutionId = result.newSolutionId,
                    objectives = result.objectives,
                    constraints = result.constraints,
                    succeeded = true,
                    executedTransformations = result.executedTransformations,
                    skippedOperatorSlots = result.skippedOperatorSlots
                )
            } else {
                failedResult(task.solutionId, result?.errorMessage)
            }
        } catch (e: Throwable) {
            failedResult(task.solutionId)
        } finally {
            cancelTimeout(evalId)
        }
    }

    /**
     * Evaluates a single [BatchEvaluationTask] with a per-evaluation watchdog timeout.
     *
     * Unlike [processSingleMutationTask], this does not copy or mutate the solution —
     * it only runs the guidance functions against the existing solution. Used for
     * initial population fitness evaluation where solutions have already been created
     * during setup.
     *
     * Registers a timeout covering all guidance function evaluations. If the timeout
     * fires, the child-process watchdog sends a [SubprocessMessage.Timeout] to the
     * parent and halts the JVM.
     */
    private fun processSingleEvaluationTask(
        task: BatchEvaluationTask,
        ev: LocalMutationEvaluator,
        nodeId: String
    ): BatchResult {
        val evalId = evalIdCounter.incrementAndGet()
        val evaluationTimeoutMs = numGuidanceFunctions.toLong() * scriptTimeoutMs
        if (evaluationTimeoutMs > 0) {
            registerTimeout(evalId, evaluationTimeoutMs)
        }
        return try {
            val batch = NodeBatch(
                nodeId = nodeId,
                tasks = emptyList(),
                evaluationTasks = listOf(EvaluationTask(task.solutionId, nodeId)),
                discards = emptyList()
            )
            val evalResults = runBlocking { ev.executeNodeBatches(listOf(batch)) }
            val result = evalResults.firstOrNull()

            if (result != null && result.succeeded) {
                BatchResult(
                    parentSolutionId = task.solutionId,
                    newSolutionId = result.newSolutionId,
                    objectives = result.objectives,
                    constraints = result.constraints,
                    succeeded = true
                )
            } else {
                failedResult(task.solutionId, result?.errorMessage)
            }
        } catch (_: Throwable) {
            failedResult(task.solutionId)
        } finally {
            cancelTimeout(evalId)
        }
    }

    /**
     * Handles a [SolutionBatchFetchRequest] by retrieving each solution's serialized model
     * and returning a single [SolutionBatchFetchResponse] carrying all results at once.
     *
     * @param msg The batch fetch request identifying all solutions to retrieve.
     * @return A [SolutionBatchFetchResponse] with all found solutions and any not-found IDs.
     */
    private fun handleWsBatchFetch(msg: SolutionBatchFetchRequest): SolutionBatchFetchResponse {
        val ev = requireEvaluator()
        val solutions = mutableListOf<SolutionData>()
        val notFoundIds = mutableListOf<String>()
        for (solutionId in msg.solutionIds) {
            try {
                val ref = com.mdeo.optimizer.evaluation.WorkerSolutionRef(LocalMutationEvaluator.DEFAULT_NODE_ID, solutionId)
                val serializedModel = runBlocking { ev.getSolutionData(ref) }
                val failedOperators = ev.getSolutionFailedOperators(solutionId)
                solutions.add(
                    SolutionData(
                        solutionId = solutionId,
                        serializedModel = serializedModel,
                        failedOperators = failedOperators.toList()
                    )
                )
            } catch (_: Exception) {
                notFoundIds.add(solutionId)
            }
        }
        return SolutionBatchFetchResponse(msg.requestId, solutions, notFoundIds)
    }

    /**
     * Constructs a failed [BatchResult] for [parentSolutionId] with empty objectives and constraints.
     *
     * @param parentSolutionId The ID of the solution whose mutation or evaluation failed.
     * @param errorMessage When non-null, indicates a guidance function failure (not a mutation failure).
     * @return A [BatchResult] with [BatchResult.succeeded] set to `false`.
     */
    private fun failedResult(parentSolutionId: String, errorMessage: String? = null) = BatchResult(
        parentSolutionId = parentSolutionId,
        newSolutionId = "",
        objectives = emptyList(),
        constraints = emptyList(),
        succeeded = false,
        errorMessage = errorMessage
    )

    /**
     * Resets all mutable state so the subprocess can be reused for a new execution.
     *
     * Closes the orchestrator channel first (before returning to pool), then drops the
     * evaluator and clears all counters.
     */
    private fun handleReset(): ByteArray {
        closeOrchestratorChannel()
        closePeerChannels()
        incomingSolutionSignals.clear()
        taskDispatcher?.close()
        taskDispatcher = null
        evaluator?.let { runBlocking { it.cleanup() } }
        evaluator = null
        evalIdCounter.set(0)
        scriptTimeoutMs = 0L
        transformationTimeoutMs = 0L
        numGuidanceFunctions = 0
        return cbor.encodeToByteArray<WorkerSubprocessResponse>(WorkerSubprocessResponse.ResetOk)
    }

    /**
     * Returns the active [LocalMutationEvaluator], throwing if not yet initialised.
     *
     * @return The active evaluator.
     */
    private fun requireEvaluator(): LocalMutationEvaluator =
        evaluator ?: throw IllegalStateException("Evaluator not initialised — send Setup first")

    /**
     * Closes the active [OrchestratorChannel] (WebSocket or stdio) and waits for the
     * background dispatch thread to finish.
     *
     * Closing the channel signals the dispatch loop to exit; joining here prevents a race
     * between in-progress mutation work on the dispatch thread and evaluator cleanup on
     * the command thread.
     */
    fun closeOrchestratorChannel() {
        activeOrchestratorChannel?.close()
        activeOrchestratorChannel = null
        val t = wsThread
        wsThread = null
        t?.join(1000L)
    }

    override fun cleanup() {
        closeOrchestratorChannel()
        closePeerChannels()
        peerScope.cancel()
        incomingSolutionSignals.clear()
        taskDispatcher?.close()
        taskDispatcher = null
        evaluator?.let { runBlocking { it.cleanup() } }
        evaluator = null
    }

    /**
     * Subprocess JVM entry point. 
     */
    companion object {
        /**
         * Maximum time to wait for a [WorkerShutdownAck] from the orchestrator before
         * proceeding to halt the JVM regardless.
         */
        private const val SHUTDOWN_NOTICE_TIMEOUT_MS = 5_000L

        /**
         * Maximum time to wait for an incoming (push-rebalanced) solution to arrive in the
         * subprocess before treating the mutation task as failed.
         */
        private const val INCOMING_SOLUTION_TIMEOUT_MS = 60_000L

        /** Maximum time to wait for a [SolutionPushAck] from a peer worker. */
        private const val PEER_PUSH_TIMEOUT_MS = 60_000L

        /** Maximum time to wait for a batch fetch response. */
        private const val OPERATION_TIMEOUT_MS = 600_000L

        /**
         * JVM entry point for the worker subprocess.
         *
         * @param args Command-line arguments passed by the parent process launcher.
         */
        @JvmStatic
        fun main(args: Array<String>) {
            WorkerSubprocessMain().run(args)
        }
    }
}
