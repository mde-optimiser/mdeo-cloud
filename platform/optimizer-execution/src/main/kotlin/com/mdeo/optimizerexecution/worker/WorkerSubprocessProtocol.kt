package com.mdeo.optimizerexecution.worker

import com.mdeo.metamodel.data.MetamodelData
import com.mdeo.metamodel.data.ModelData
import com.mdeo.optimizer.config.GoalConfig
import com.mdeo.optimizer.config.GraphBackendType
import com.mdeo.optimizer.config.SolverConfig
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Commands sent by the parent process to the worker subprocess.
 *
 * Each instance is CBOR-encoded and sent as a [com.mdeo.execution.common.subprocess.SubprocessMessage.Command]
 * payload. The subprocess decodes and dispatches based on the concrete type.
 */
@Serializable
sealed class WorkerSubprocessRequest {

    /**
     * One-time setup: compile metamodel + scripts, build mutation strategy and evaluator,
     * and optionally generate the initial population.
     *
     * When [orchestratorWsUrl] is provided the subprocess opens a WebSocket connection
     * back to the orchestrator and handles generation traffic (mutations, imports, discards)
     * directly in-process, dramatically reducing cross-process communication overhead.
     *
     * When [useLocalChannel] is `true`, the subprocess communicates with the orchestrator
     * over the existing stdin/stdout pipe instead of opening a WebSocket, which is more
     * efficient when the subprocess and orchestrator share a host. Incoming
     * [SubprocessChannelMessage.OrchestratorRequest] messages are dispatched by the same
     * logic as WebSocket frames; responses are returned as
     * [SubprocessChannelMessage.OrchestratorResponses].
     *
     * @param metamodelData The metamodel that governs model structure.
     * @param initialModelData Seed model for constructing initial solutions.
     * @param transformationAstJsons JSON-serialized transformation TypedAst per path.
     * @param scriptAstJsons JSON-serialized script TypedAst per path.
     * @param goalConfig Objective and constraint definitions.
     * @param solverConfig Solver / mutation parameters.
     * @param initialSolutionCount How many initial solutions to generate (ignored when [skipInitialization] is true).
     * @param skipInitialization When true, skip `evaluator.initialize()` — used when restoring a subprocess after a crash.
     * @param orchestratorWsUrl WebSocket URL to connect back to the orchestrator (e.g. `ws://localhost:8080/ws/subprocess/executions/{id}/{nodeId}`).
     *        When `null` and [useLocalChannel] is `false`, the subprocess operates without an active orchestrator channel.
     * @param useLocalChannel When `true`, the subprocess uses [SubprocessChannelMessage.OrchestratorRequest] /
     *        [SubprocessChannelMessage.OrchestratorResponses] channel messages for orchestrator communication
     *        rather than a WebSocket. Mutually exclusive with [orchestratorWsUrl].
     * @param scriptTimeoutMs Per-script evaluation timeout in milliseconds. Used for internal watchdog timing.
     * @param transformationTimeoutMs Per-transformation execution timeout in milliseconds. Used for internal watchdog timing.
     * @param workerThreads Number of threads to use for parallel mutation within this subprocess.
     *        When greater than 1, tasks in a [NodeWorkBatchRequest] are evaluated in parallel.
     */
    @Serializable
    @SerialName("setup")
    data class Setup(
        val metamodelData: MetamodelData,
        val initialModelData: ModelData,
        val transformationAstJsons: Map<String, String>,
        val scriptAstJsons: Map<String, String>,
        val goalConfig: GoalConfig,
        val solverConfig: SolverConfig,
        val initialSolutionCount: Int,
        val skipInitialization: Boolean = false,
        val orchestratorWsUrl: String? = null,
        val useLocalChannel: Boolean = false,
        val scriptTimeoutMs: Long = 0L,
        val transformationTimeoutMs: Long = 0L,
        val workerThreads: Int = 1,
        val graphBackendType: GraphBackendType = GraphBackendType.MDEO
    ) : WorkerSubprocessRequest()

    /**
     * Resets all subprocess state so it can be reused for a new execution.
     *
     * The subprocess must close any open WebSocket connections, discard the evaluator
     * and all stored solutions, and return to a clean post-construction state. After
     * processing this command, the subprocess is ready to receive a new [Setup].
     */
    @Serializable
    @SerialName("reset")
    data object Reset : WorkerSubprocessRequest()
}

/**
 * Responses sent by the worker subprocess back to the parent process.
 *
 * Each instance is CBOR-encoded and returned as the result payload of a
 * [com.mdeo.execution.common.subprocess.SubprocessMessage.Result].
 */
@Serializable
sealed class WorkerSubprocessResponse {

    /**
     * Setup completed successfully.
     *
     * @param solutions Initial solutions generated during setup (empty when [WorkerSubprocessRequest.Setup.skipInitialization] is true).
     */
    @Serializable
    @SerialName("setup_ok")
    data class SetupOk(
        val solutions: List<WorkerInitialSolution>
    ) : WorkerSubprocessResponse()

    /**
     * Reset acknowledged; the subprocess is ready for a new [WorkerSubprocessRequest.Setup]. 
     */
    @Serializable
    @SerialName("reset_ok")
    data object ResetOk : WorkerSubprocessResponse()
}

/**
 * Compact data for a single initial solution returned as part of [WorkerSubprocessResponse.SetupOk].
 *
 * Contains only the solution identity and serialized model; fitness evaluation is
 * performed separately via evaluation tasks dispatched through the batch mechanism.
 *
 * @param solutionId ID assigned to this solution within the subprocess.
 * @param modelBytes CBOR-encoded [com.mdeo.metamodel.SerializedModel].
 */
@Serializable
data class WorkerInitialSolution(
    val solutionId: String,
    val modelBytes: ByteArray
) {
    override fun equals(other: Any?) =
        other is WorkerInitialSolution && solutionId == other.solutionId && modelBytes.contentEquals(other.modelBytes)
    override fun hashCode() = 31 * solutionId.hashCode() + modelBytes.contentHashCode()
}

/**
 * Channel messages sent by the subprocess to the parent process while the subprocess
 * handles WebSocket or local-channel traffic. These flow over the [SubprocessMessage.Channel]
 * mechanism (stdin/stdout) and are used for:
 *
 * - **Model byte sync**: the parent keeps a copy of every solution's serialized bytes
 *   for cache purposes (e.g. responding to solution fetch requests).
 * - **Discard sync**: the parent removes discarded solution bytes from its map.
 * - **Orchestrator I/O** (local-channel mode): when the subprocess uses the stdio pipe
 *   for orchestrator communication, requests arrive as [OrchestratorRequest] and
 *   responses are sent back as [OrchestratorResponses].
 */
@Serializable
sealed class SubprocessChannelMessage {

    /**
     * Parent → Subprocess: a solution model pushed by a peer subprocess during rebalancing.
     *
     * Sent by [com.mdeo.optimizerexecution.worker.WorkerService] when a peer subprocess
     * delivers a [com.mdeo.optimizer.worker.SolutionPushRequest] to the peer WS endpoint.
     * The subprocess stores the model and signals any mutation task waiting on this ID.
     *
     * @param solutionId The identifier under which the solution should be registered.
     * @param modelBytes CBOR-encoded [com.mdeo.metamodel.SerializedModel].
     * @param failedOperators Numerical operator indices known to deterministically fail on
     *   this solution's model state. Forwarded to [LocalMutationEvaluator.receiveSolution]
     *   so the destination node avoids reattempting those operators.
     */
    @Serializable
    @SerialName("solution_injected")
    data class SolutionInjected(
        val solutionId: String,
        val modelBytes: ByteArray,
        val failedOperators: List<Int> = emptyList()
    ) : SubprocessChannelMessage() {
        override fun equals(other: Any?) =
            other is SolutionInjected && solutionId == other.solutionId && modelBytes.contentEquals(other.modelBytes)
        override fun hashCode() = 31 * solutionId.hashCode() + modelBytes.contentHashCode()
    }

    /**
     * Parent → Subprocess (local-channel mode): a CBOR-encoded [WorkerWsMessage] request
     * forwarded from the orchestrator via the stdin/stdout pipe instead of a WebSocket.
     *
     * The subprocess dispatches this through the same handler as a real WebSocket frame
     * and replies with [OrchestratorResponses].
     *
     * @param payload CBOR-encoded [com.mdeo.optimizer.worker.WorkerWsMessage].
     */
    @Serializable
    @SerialName("orchestrator_request")
    data class OrchestratorRequest(val payload: ByteArray) : SubprocessChannelMessage() {
        override fun equals(other: Any?) =
            other is OrchestratorRequest && payload.contentEquals(other.payload)
        override fun hashCode() = payload.contentHashCode()
    }

    /**
     * Subprocess → Parent (local-channel mode): all CBOR-encoded [WorkerWsMessage] responses
     * produced for the matching [OrchestratorRequest], packed in a single channel message.
     *
     * The parent unpacks each entry and delivers them to the waiting orchestrator caller.
     *
     * @param payloads List of CBOR-encoded [com.mdeo.optimizer.worker.WorkerWsMessage] values.
     */
    @Serializable
    @SerialName("orchestrator_responses")
    data class OrchestratorResponses(val payloads: List<ByteArray>) : SubprocessChannelMessage()

    /**
     * Subprocess → Parent (local-channel mode): an unsolicited CBOR-encoded
     * [com.mdeo.optimizer.worker.WorkerWsMessage] that the subprocess wants to push to the
     * orchestrator without an associated incoming request.
     *
     * Used for graceful-shutdown handshakes where the subprocess sends a
     * [com.mdeo.optimizer.worker.WorkerShutdownNotice] before halting its JVM.
     * The parent forwards the message to the orchestrator WebSocket session and
     * delivers any reply back as an [OrchestratorRequest].
     *
     * @param payload CBOR-encoded [com.mdeo.optimizer.worker.WorkerWsMessage].
     */
    @Serializable
    @SerialName("orchestrator_notice")
    data class OrchestratorNotice(val payload: ByteArray) : SubprocessChannelMessage() {
        override fun equals(other: Any?) =
            other is OrchestratorNotice && payload.contentEquals(other.payload)
        override fun hashCode() = payload.contentHashCode()
    }
}
