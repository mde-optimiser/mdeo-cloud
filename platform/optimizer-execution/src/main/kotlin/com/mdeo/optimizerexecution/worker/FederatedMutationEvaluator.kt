package com.mdeo.optimizerexecution.worker

import com.mdeo.metamodel.SerializedModel
import com.mdeo.optimizer.evaluation.*
import com.mdeo.optimizer.moea.RebalanceTransfer
import com.mdeo.optimizer.worker.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.ConcurrentHashMap

/**
 * Thrown when a worker subprocess shuts down unexpectedly and can no longer serve
 * evaluation requests.
 *
 * The [message] is sourced from the [WorkerShutdownNotice] sent by the subprocess
 * before it halted (if one was received), providing a precise, human-readable reason.
 * Falls back to a generic "not reachable" description when no notice was received
 * (e.g. the subprocess crashed without warning).
 *
 * @param message Human-readable reason for the worker shutdown.
 * @param cause The underlying exception that triggered the worker death detection.
 */
class WorkerShutdownException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Federated implementation of [MutationEvaluator] that distributes mutation and
 * evaluation work across multiple remote worker nodes.
 *
 * The orchestrator uses this evaluator to fan out work to a pool of [WorkerClient]
 * instances, each representing a distinct worker node. All worker communication is
 * done via a single [NodeWorkBatchRequest] per worker per generation — combining imports
 * (rebalancing), mutation tasks, and discards into one message.
 *
 * @param executionId Unique identifier of the optimization execution being coordinated.
 * @param workers The list of worker clients that this evaluator distributes work across.
 * @param allocationRequest Template allocation request; [WorkerAllocationRequest.initialSolutionCount]
 *        and [WorkerAllocationRequest.threadsPerNode] are overridden per worker when distributing
 *        initial solution generation.
 * @param workerThreadBudgets Optional map from node ID to the exact thread budget allocated for
 *        that node (computed from the global threads cap, per-node limit, and node capacity).
 *        When present, overrides [WorkerAllocationRequest.threadsPerNode] per worker so that
 *        the global [ResourcesConfig.threads] cap is honoured correctly.
 */
class FederatedMutationEvaluator(
    private val executionId: String,
    private val workers: List<WorkerClient>,
    private val allocationRequest: WorkerAllocationRequest,
    private val workerThreadBudgets: Map<String, Int> = emptyMap()
) : MutationEvaluator {

    /**
     * Fast lookup from nodeId to WorkerClient, built once at construction time. 
     */
    private val workerByNodeId: Map<String, WorkerClient> = workers.associateBy { it.nodeId }

    /**
     * Number of worker nodes this evaluator distributes work across. 
     */
    val workerCount: Int = workers.size

    /**
     * Thread counts reported by each worker during allocation, keyed by node ID.
     * Populated after [initialize] completes.
     */
    private val workerThreadCounts = ConcurrentHashMap<String, Int>()

    /**
     * Pending [SolutionRelocation] objects to include in each source node's next batch.
     *
     * Populated by [pushRebalanceSolutions] and consumed (then cleared) by [executeOnWorker].
     * Keyed by source nodeId.
     */
    private val pendingRelocations = ConcurrentHashMap<String, MutableList<SolutionRelocation>>()

    override fun getNodeIds(): Set<String> = workerByNodeId.keys

    /**
     * Returns the number of threads allocated on each worker node, keyed by node ID.
     *
     * Only populated after [initialize] has been called.
     */
    fun getWorkerThreadCounts(): Map<String, Int> = workerThreadCounts.toMap()

    /**
     * Distributes initial solution creation across all workers and collects the results.
     *
     * The requested [count] is split roughly evenly across workers using [distributeCount].
     * Each worker receives an allocation request with its assigned portion and returns
     * evaluated initial solutions. All allocations run in parallel.
     */
    override suspend fun initialize(count: Int): List<InitialSolutionResult> {
        val counts = distributeCount(count, workers.size)
        return coroutineScope {
            workers.zip(counts).map { (worker, workerCount) ->
                async { allocateWorker(worker, workerCount) }
            }.awaitAll()
        }.flatten()
    }

    /**
     * Sends one [NodeWorkBatchRequest] per worker node, combining imports, mutation tasks,
     * and discards into a single message. All workers are contacted in parallel.
     *
     * If a worker fails, its tasks produce [EvaluationResult] entries with
     * [EvaluationResult.succeeded] set to `false`.
     */
    override suspend fun executeNodeBatches(batches: List<NodeBatch>): List<EvaluationResult> {
        return coroutineScope {
            batches.map { batch ->
                async { executeOnWorker(batch) }
            }.awaitAll()
        }.flatten()
    }

    /**
     * Fetches the serialized model for a solution stored on a remote worker node.
     */
    override suspend fun getSolutionData(ref: WorkerSolutionRef): SerializedModel {
        val worker = requireWorker(ref.nodeId)
        return worker.getSolutionData(executionId, ref.solutionId)
    }

    /**
     * Fetches serialized models for multiple solutions using batched requests grouped by worker node.
     *
     * All solutions on the same worker are requested in a single [SolutionBatchFetchRequest].
     * Workers for different nodes are contacted in parallel.
     */
    override suspend fun getSolutionDataBatch(refs: List<WorkerSolutionRef>): Map<String, SerializedModel> {
        val byNode = refs.groupBy { it.nodeId }
        return coroutineScope {
            byNode.map { (nodeId, nodeRefs) ->
                async {
                    val worker = requireWorker(nodeId)
                    worker.getSolutionDataBatch(executionId, nodeRefs.map { it.solutionId })
                }
            }.awaitAll()
        }.fold(mutableMapOf()) { acc, map -> acc.apply { putAll(map) } }
    }

    /**
     * Computes the relocation plan and stores it as pending per source node.
     *
     * No model data is fetched here. Instead, each transfer is converted into a
     * [SolutionRelocation] that will be included in the source node's next
     * [NodeWorkBatchRequest]. The source subprocess then reads the models locally
     * and pushes them directly to the destination worker via a persistent WebSocket,
     * keeping the orchestrator off the data path entirely.
     *
     * Returns the per-source-node list of solution IDs that were relocated (i.e. should
     * be discarded from the source in the current batch).
     */
    override suspend fun pushRebalanceSolutions(transfers: List<RebalanceTransfer>): Map<String, List<String>> {
        if (transfers.isEmpty()) return emptyMap()
        val discardsBySource = mutableMapOf<String, MutableList<String>>()
        for (transfer in transfers) {
            val destWorker = requireWorker(transfer.destinationNodeId)
            val relocation = SolutionRelocation(
                destinationWorkerBaseUrl = destWorker.baseUrl,
                executionId = executionId,
                solutionIds = transfer.solutionIds
            )
            pendingRelocations.getOrPut(transfer.sourceNodeId) { mutableListOf() }.add(relocation)
            discardsBySource.getOrPut(transfer.sourceNodeId) { mutableListOf() }.addAll(transfer.solutionIds)
        }
        return discardsBySource
    }

    /**
     * Cleans up the execution on all workers and closes the underlying HTTP clients.
     *
     * After this call the evaluator must not be used again.
     */
    override suspend fun cleanup() {
        coroutineScope {
            workers.map { worker ->
                async { worker.cleanup(executionId) }
            }.awaitAll()
        }
        workers.forEach { it.close() }
    }

    /**
     * Fetches [WorkerMetadata] from all worker nodes in parallel.
     *
     * Useful for orchestrators that need to inspect actual thread capacities or
     * supported backends across the full worker pool after the evaluator is created.
     *
     * @return A map from node ID to the [WorkerMetadata] reported by that node.
     *         Nodes that fail to respond are omitted from the result.
     */
    suspend fun fetchMetadata(): Map<String, WorkerMetadata> {
        return coroutineScope {
            workers.map { worker ->
                async {
                    try {
                        worker.nodeId to worker.getMetadata()
                    } catch (_: Exception) {
                        null
                    }
                }
            }.awaitAll()
        }.filterNotNull().toMap()
    }

    /**
     * Allocates a worker for the given [count] of initial solutions and records its thread count.
     *
     * @param worker The worker client to allocate.
     * @param count The number of initial solutions this worker should generate.
     * @return The list of initial solution results returned by the worker.
     */
    private suspend fun allocateWorker(worker: WorkerClient, count: Int): List<InitialSolutionResult> {
        val threadBudget = workerThreadBudgets[worker.nodeId]
        val request = allocationRequest.copy(
            initialSolutionCount = count,
            threadsPerNode = threadBudget ?: allocationRequest.threadsPerNode
        )
        val response = worker.allocate(request)
        workerThreadCounts[worker.nodeId] = response.threadCount
        return response.initialSolutions.map { solution ->
            InitialSolutionResult(
                solutionId = solution.solutionId,
                workerNodeId = worker.nodeId
            )
        }
    }

    /**
     * Sends a [NodeWorkBatchRequest] to the worker responsible for [batch.nodeId] and
     * maps the results to [EvaluationResult] instances.
     *
     * If the worker dies during or before the request (i.e. [WorkerClient.isAlive] is
     * `false`), a [WorkerShutdownException] is thrown carrying the shutdown reason
     * provided via [WorkerShutdownNotice] (if one was received) or a generic fallback.
     * This ensures callers receive a precise error message rather than a generic
     * "worker no longer reachable" message.
     *
     * @param batch The node batch to execute.
     * @return The list of evaluation results for all tasks in the batch.
     */
    private suspend fun executeOnWorker(batch: NodeBatch): List<EvaluationResult> {
        val worker = requireWorker(batch.nodeId)
        val relocations = pendingRelocations.remove(batch.nodeId) ?: emptyList()
        return try {
            val response = worker.executeNodeBatch(
                executionId,
                tasks = batch.tasks.map { BatchTask(it.solutionId) },
                evaluationTasks = batch.evaluationTasks.map { BatchEvaluationTask(it.solutionId) },
                discards = batch.discards,
                relocations = relocations
            )
            response.results.map { result ->
                EvaluationResult(
                    parentSolutionId = result.parentSolutionId,
                    newSolutionId = result.newSolutionId,
                    workerNodeId = batch.nodeId,
                    objectives = result.objectives,
                    constraints = result.constraints,
                    status = result.status,
                    executedTransformations = result.executedTransformations,
                    skippedOperatorSlots = result.skippedOperatorSlots,
                    errorMessage = result.errorMessage
                )
            }
        } catch (e: Exception) {
            if (!worker.isAlive || worker.shutdownReason != null) {
                val reason = worker.shutdownReason
                    ?: "Worker ${batch.nodeId} is no longer reachable (subprocess may have timed out or crashed)"
                throw WorkerShutdownException(reason, e)
            }
            val mutationFailures = batch.tasks.map { task ->
                EvaluationResult(
                    parentSolutionId = task.solutionId,
                    newSolutionId = "",
                    workerNodeId = batch.nodeId,
                    objectives = emptyList(),
                    constraints = emptyList(),
                    status = ResultStatus.SOFT_FAILURE
                )
            }
            val evaluationFailures = batch.evaluationTasks.map { task ->
                EvaluationResult(
                    parentSolutionId = task.solutionId,
                    newSolutionId = "",
                    workerNodeId = batch.nodeId,
                    objectives = emptyList(),
                    constraints = emptyList(),
                    status = ResultStatus.SOFT_FAILURE
                )
            }
            mutationFailures + evaluationFailures
        }
    }

    /**
     * Returns the [WorkerClient] for [nodeId], throwing if not found.
     *
     * @param nodeId The node identifier to look up.
     * @return The [WorkerClient] for that node.
     */
    private fun requireWorker(nodeId: String): WorkerClient =
        workerByNodeId[nodeId]
            ?: throw IllegalArgumentException("No worker found with nodeId '$nodeId'")

    companion object {
        /**
         * Distributes a total count as evenly as possible across the given number of buckets.
         *
         * The remainder after integer division is distributed one-per-bucket to the first buckets.
         * For example, `distributeCount(10, 3)` returns `[4, 3, 3]`.
         */
        fun distributeCount(total: Int, buckets: Int): List<Int> {
            val base = total / buckets
            val remainder = total % buckets
            return List(buckets) { i -> if (i < remainder) { base + 1 } else { base } }
        }
    }
}
