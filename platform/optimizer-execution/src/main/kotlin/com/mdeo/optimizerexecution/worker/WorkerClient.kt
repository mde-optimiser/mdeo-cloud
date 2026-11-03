package com.mdeo.optimizerexecution.worker

import com.mdeo.metamodel.SerializedModel
import com.mdeo.optimizer.worker.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Abstract client that communicates with a single worker node in a multi-node optimization setup.
 *
 * Two concrete implementations are provided:
 * - [LocalWorkerClient]: dispatches requests directly in-process via [WorkerService],
 *   with no HTTP or WebSocket connections.
 * - [RemoteWorkerClient]: allocates via HTTP and communicates over a reverse WebSocket
 *   connection opened by the worker subprocess.
 *
 * @param nodeId Unique identifier for the worker node (used as a key in solution refs).
 * @param baseUrl Base HTTP URL of this worker node (used by peers for [SolutionRelocation]).
 */
@OptIn(ExperimentalSerializationApi::class)
abstract class WorkerClient(val nodeId: String, val baseUrl: String) : AutoCloseable {

    private val logger = LoggerFactory.getLogger(WorkerClient::class.java)

    /**
     * CBOR codec shared by all subclasses for encoding/decoding [WorkerWsMessage] frames.
     */
    protected val cbor = Cbor { ignoreUnknownKeys = true }

    /**
     * The reason provided in a [WorkerShutdownNotice] received from the subprocess, or
     * `null` if no notice has been received yet.
     */
    @Volatile
    var shutdownReason: String? = null
        protected set

    /**
     * Whether the connection to the worker subprocess is still active.
     */
    abstract val isAlive: Boolean

    /**
     * Allocates resources for a new optimization execution on the worker.
     *
     * @param request The allocation request containing all resources and configuration.
     * @return The allocation response with initial solution fitness data.
     */
    abstract suspend fun allocate(request: WorkerAllocationRequest): WorkerAllocationResponse

    /**
     * Fetches metadata describing the worker node's thread capacity and supported backends.
     *
     * @return A [WorkerMetadata] instance describing this node's capabilities.
     */
    abstract suspend fun getMetadata(): WorkerMetadata

    /**
     * Cleans up the execution on the worker and releases all associated resources.
     *
     * @param executionId The execution identifier to clean up.
     */
    abstract suspend fun cleanup(executionId: String)

    /**
     * Sends [msg] and suspends until the matching response arrives.
     *
     * There is no deadline: how long a request may take is a property of the work in it, which
     * the worker's own watchdog governs. Implementations wait while the worker reports progress
     * and fail once it stops, so that work of any length completes and a worker that has stopped
     * answering is still noticed.
     *
     * @param msg The message to send.
     * @return The response message.
     */
    protected abstract suspend fun sendAndReceive(msg: WorkerWsMessage): WorkerWsMessage

    /**
     * Sends a unified work batch to the worker.
     *
     * @param executionId The execution identifier on the worker (used for logging/tracing only).
     * @param tasks Mutation tasks referencing existing solutions.
     * @param evaluationTasks Evaluation-only tasks for fitness computation without mutation.
     * @param discards Solution IDs to discard after processing the batch.
     * @param relocations Solutions to push to other nodes before or during mutation.
     * @param crossoverTasks Crossover tasks whose primary parent is on this node.
     * @param replicas Donors pushed to this node for this batch only, dropped after it.
     * @return The batch response with evaluation results for each task.
     */
    suspend fun executeNodeBatch(
        executionId: String,
        tasks: List<BatchTask>,
        evaluationTasks: List<BatchEvaluationTask> = emptyList(),
        discards: List<String>,
        relocations: List<SolutionRelocation> = emptyList(),
        crossoverTasks: List<BatchCrossoverTask> = emptyList(),
        replicas: List<String> = emptyList()
    ): NodeWorkBatchResponse {
        val requestId = newRequestId()
        return sendAndReceive(
            NodeWorkBatchRequest(requestId, tasks, evaluationTasks, discards, relocations, crossoverTasks, replicas)
        ) as NodeWorkBatchResponse
    }

    /**
     * Retrieves the serialized model for a specific solution from the worker.
     * Delegates to [getSolutionDataBatch] with a single-element list.
     */
    suspend fun getSolutionData(executionId: String, solutionId: String): SerializedModel {
        logger.info("[node={}] Fetching model for solution {} (execution {})", nodeId, solutionId, executionId)
        return getSolutionDataBatch(executionId, listOf(solutionId))[solutionId]
            ?: throw IllegalStateException("Solution $solutionId not found on node $nodeId (execution $executionId)")
    }

    /**
     * Retrieves serialized models for multiple solutions in a single batched request.
     */
    suspend fun getSolutionDataBatch(
        executionId: String,
        solutionIds: List<String>
    ): Map<String, SerializedModel> {
        if (solutionIds.isEmpty()) return emptyMap()
        logger.info(
            "[node={}] Fetching models for {} solution(s) in batch (execution {})",
            nodeId, solutionIds.size, executionId
        )
        val requestId = newRequestId()
        val response = sendAndReceive(SolutionBatchFetchRequest(requestId, solutionIds)) as SolutionBatchFetchResponse
        return response.solutions.associate { it.solutionId to it.serializedModel }
    }

    private fun newRequestId() = UUID.randomUUID().toString()
}


