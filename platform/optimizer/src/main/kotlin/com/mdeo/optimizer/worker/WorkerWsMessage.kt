package com.mdeo.optimizer.worker

import com.mdeo.metamodel.SerializedModel
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Polymorphic message type for WebSocket-based orchestrator-to-worker communication.
 *
 * After the initial HTTP handshake that allocates a worker, all ongoing traffic between
 * the orchestrator and each worker node is carried as binary CBOR frames of this sealed
 * hierarchy. The [requestId] present on every message is echoed back in the reply,
 * enabling many in-flight requests to share one persistent WebSocket connection.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
sealed class WorkerWsMessage {

    /**
     * Correlation identifier echoed verbatim in the corresponding response. 
     */
    abstract val requestId: String
}


/**
 * Identifies a set of solutions that a source worker subprocess should push directly to
 * a destination worker node during population rebalancing.
 *
 * Carried inside [NodeWorkBatchRequest.relocations] so the source subprocess learns at
 * batch dispatch time where to send the models. The subprocess opens (or reuses) a
 * persistent WebSocket to [destinationWorkerBaseUrl] and pushes the solutions via
 * [SolutionPushRequest] without any orchestrator involvement.
 *
 * @param destinationWorkerBaseUrl HTTP base URL of the destination worker (e.g. `http://worker-2:8080`).
 * @param executionId The execution identifier on the destination worker.
 * @param solutionIds Identifiers of the solutions to push to the destination.
 */
@Serializable
data class SolutionRelocation(
    val destinationWorkerBaseUrl: String,
    val executionId: String,
    val solutionIds: List<String>
)

/**
 * Orchestrator → Worker: execute one generation of work atomically.
 *
 * Combines solution import references (for rebalancing), mutation tasks, evaluation-only
 * tasks, and discards into a single message so that the orchestrator requires exactly one
 * round trip per worker per generation. The worker processes these in order: fetch and
 * store imports from peer nodes, execute mutations, execute evaluations, drop discards.
 *
 * Import model data is NOT pre-fetched by the orchestrator. Instead, the source worker
 * subprocess opens (or reuses) a persistent WebSocket to each destination worker and
 * pushes the data directly, keeping the orchestrator off the data path.
 *
 * @param requestId Correlation identifier echoed in [NodeWorkBatchResponse].
 * @param tasks Existing solutions on this node to mutate and evaluate.
 * @param evaluationTasks Solutions on this node to evaluate without mutation
 *   (e.g. newly initialized solutions needing fitness computation).
 * @param discards Solution IDs on this node to release after evaluation.
 * @param relocations Solutions on this node that must be pushed to other nodes before
 *   (or concurrently with) the mutation work. The subprocess handles the direct transfer.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@SerialName("node_work_batch_request")
data class NodeWorkBatchRequest(
    override val requestId: String,
    val tasks: List<BatchTask>,
    val evaluationTasks: List<BatchEvaluationTask> = emptyList(),
    val discards: List<String>,
    val relocations: List<SolutionRelocation> = emptyList()
) : WorkerWsMessage()

/**
 * Worker → Orchestrator: results for all mutation tasks in the batch.
 *
 * @param requestId Matches [NodeWorkBatchRequest.requestId].
 * @param results One entry per task in [NodeWorkBatchRequest.tasks], in request order.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@SerialName("node_work_batch_response")
data class NodeWorkBatchResponse(
    override val requestId: String,
    val results: List<BatchResult>
) : WorkerWsMessage()


/**
 * Orchestrator → Worker: retrieve model data for multiple solutions in one request.
 *
 * Batches several solution IDs into a single message to reduce round-trip overhead.
 * The worker responds with a single [SolutionBatchFetchResponse] carrying all
 * requested models (and any not-found IDs) at once.
 *
 * @param requestId Correlation identifier echoed in [SolutionBatchFetchResponse].
 * @param solutionIds Identifiers of the solutions whose model data is requested.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@SerialName("solution_batch_fetch_request")
data class SolutionBatchFetchRequest(
    override val requestId: String,
    val solutionIds: List<String>
) : WorkerWsMessage()

/**
 * Worker → Orchestrator/Peer: all solution models for a [SolutionBatchFetchRequest].
 *
 * Carries every available serialized model together with a list of IDs that were
 * requested but not found, all in a single frame so no separate completion signal
 * is required.
 *
 * @param requestId Matches [SolutionBatchFetchRequest.requestId].
 * @param solutions The fetched solutions with their serialized models.
 * @param notFoundIds Solution IDs that were requested but not present on this node.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@SerialName("solution_batch_fetch_response")
data class SolutionBatchFetchResponse(
    override val requestId: String,
    val solutions: List<SolutionData>,
    val notFoundIds: List<String> = emptyList()
) : WorkerWsMessage()

/**
 * A single solution's data within a [SolutionBatchFetchResponse].
 *
 * @param solutionId Identifier of the solution.
 * @param serializedModel Serialized model graph for the solution.
 * @param failedOperators Numerical indices (into the sorted operator list) of operators
 *   that are known to deterministically fail on this solution's model state. Transferred
 *   alongside the model so that the receiving node avoids reattempting these operators.
 *   Defaults to empty for solutions where no failures have been recorded.
 */
@Serializable
data class SolutionData(
    val solutionId: String,
    val serializedModel: SerializedModel,
    val failedOperators: List<Int> = emptyList()
)

/**
 * Worker → Orchestrator: the worker subprocess is about to shut down.
 *
 * Sent by a worker subprocess when it needs to terminate for an internal reason
 * (e.g. a script-evaluation or transformation timeout fired by [ChildProcessWatchdog]).
 * The orchestrator must record the [reason] as the execution error, set the execution
 * state to FAILED, and reply with [WorkerShutdownAck] before the worker halts its JVM.
 *
 * Waiting for the acknowledgement ensures that:
 * - The orchestrator learns the real cause of the shutdown **before** the websocket
 *   is torn down, preventing a confusing "worker no longer reachable" message.
 * - At most one error message is recorded per execution, because [WorkerShutdownAck]
 *   is only sent once the orchestrator has gated on the execution already being in a
 *   terminal state.
 *
 * @param requestId Correlation identifier echoed in [WorkerShutdownAck].
 * @param reason Human-readable description of why the worker is shutting down.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@SerialName("worker_shutdown_notice")
data class WorkerShutdownNotice(
    override val requestId: String,
    val reason: String
) : WorkerWsMessage()

/**
 * Orchestrator → Worker: acknowledgement of a [WorkerShutdownNotice].
 *
 * Sent after the orchestrator has recorded the shutdown reason and set the execution
 * state to FAILED.  Upon receiving this the worker subprocess may safely halt its JVM.
 *
 * @param requestId Matches [WorkerShutdownNotice.requestId].
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@SerialName("worker_shutdown_ack")
data class WorkerShutdownAck(
    override val requestId: String
) : WorkerWsMessage()

/**
 * Orchestrator → Worker: push solution model data for rebalanced solutions.
 *
 * Sent by the orchestrator before dispatching a [NodeWorkBatchRequest] that contains
 * tasks referencing solutions transferred from another node. The subprocess stores
 * each solution and signals any waiting tasks.
 *
 * @param requestId Correlation identifier echoed in [SolutionPushAck].
 * @param solutions The solutions being pushed; each carries its ID and model data.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@SerialName("solution_push_request")
data class SolutionPushRequest(
    override val requestId: String,
    val solutions: List<SolutionData>
) : WorkerWsMessage()

/**
 * Worker → Orchestrator: acknowledgement that all solutions in a [SolutionPushRequest]
 * have been stored and are ready for use by incoming mutation tasks.
 *
 * @param requestId Matches [SolutionPushRequest.requestId].
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@SerialName("solution_push_ack")
data class SolutionPushAck(
    override val requestId: String
) : WorkerWsMessage()
