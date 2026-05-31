package com.mdeo.optimizer.evaluation

import com.mdeo.metamodel.SerializedModel
import com.mdeo.optimizer.moea.RebalanceTransfer

/**
 * Work to be executed on a single worker node in one generation.
 *
 * Combines mutation tasks, evaluation-only tasks, and discards into a single per-node
 * request so that the orchestrator can communicate with every worker using exactly one
 * message per generation. Solution relocation between nodes is handled separately via
 * [MutationEvaluator.pushRebalanceSolutions] before batches are dispatched.
 *
 * @param nodeId The worker node that should process this batch.
 * @param tasks Parent solutions on this node to mutate and evaluate.
 * @param evaluationTasks Solutions on this node to evaluate without mutation
 *   (e.g. newly initialized solutions that only need fitness computation).
 * @param discards Solution IDs on this node to drop after evaluation.
 */
data class NodeBatch(
    val nodeId: String,
    val tasks: List<MutationTask>,
    val evaluationTasks: List<EvaluationTask> = emptyList(),
    val discards: List<String>
)

/**
 * Abstracts the mutation and evaluation pipeline so that both single-node (in-process)
 * and federated (multi-node) execution can be driven through the same contract.
 *
 * The orchestrator interacts exclusively with this interface, remaining agnostic
 * to whether work is performed locally or distributed across remote worker nodes.
 */
interface MutationEvaluator {

    /**
     * Creates and evaluates [count] initial solutions for the starting population.
     *
     * Each solution is generated, optionally mutated, and evaluated against all
     * configured objectives and constraints.
     *
     * @param count The number of initial solutions to create.
     * @return A list of [InitialSolutionResult] entries, one per created solution.
     */
    suspend fun initialize(count: Int): List<InitialSolutionResult>

    /**
     * Executes one generation of work across all nodes in a single coordinated call.
     *
     * Each [NodeBatch] carries mutation tasks and discards for one worker node.
     * Solution relocation is handled separately via [pushRebalanceSolutions] before
     * this method is called; any relocated solutions arrive asynchronously in worker
     * subprocesses and are awaited on demand.
     *
     * @param batches One [NodeBatch] per node (nodes with nothing to do receive an empty batch).
     * @return A flat list of [EvaluationResult] entries, one per task across all nodes.
     */
    suspend fun executeNodeBatches(batches: List<NodeBatch>): List<EvaluationResult>

    /**
     * Actively pushes solutions between nodes according to the rebalance plan, so that
     * destination nodes receive the model data before their next batch is dispatched.
     *
     * In a single-node setup this is a no-op. In federated setups the orchestrator
     * fetches each solution from the source node and injects it into the destination
     * node's subprocess, which can then start mutating it without any cross-node fetch.
     *
     * @param transfers The list of transfers computed by [com.mdeo.optimizer.moea.PopulationRebalancer].
     * @return A map from source node ID to the list of solution IDs that were pushed away
     *   (and should therefore be discarded from the source in the next batch).
     */
    suspend fun pushRebalanceSolutions(transfers: List<RebalanceTransfer>): Map<String, List<String>> = emptyMap()

    /**
     * Fetches the serialized model for a solution stored on a worker node.
     *
     * @param ref A reference identifying the worker node and solution.
     * @return The [SerializedModel] representation of the solution's model graph.
     */
    suspend fun getSolutionData(ref: WorkerSolutionRef): SerializedModel

    /**
     * Fetches serialized models for multiple solutions in a single batched operation.
     *
     * Implementations that communicate with remote workers should send a single batched
     * request rather than one request per solution. The default implementation falls back
     * to sequential [getSolutionData] calls.
     *
     * @param refs The solution references to fetch.
     * @return A map from solution ID to its [SerializedModel].
     */
    suspend fun getSolutionDataBatch(refs: List<WorkerSolutionRef>): Map<String, SerializedModel> {
        return refs.associate { it.solutionId to getSolutionData(it) }
    }

    /**
     * Resets all per-batch state so that this evaluator is ready to generate and
     * evaluate a completely fresh initial population for the next batch.
     *
     * For local evaluators this discards all currently stored solutions.
     * For federated evaluators this terminates the worker subprocesses so that
     * the next [initialize] call starts them from scratch without stale solution IDs.
     *
     * The default implementation is a no-op (suitable for evaluators that are
     * stateless between batches).
     */
    suspend fun resetBatch() {}

    /**
     * Releases all resources held by this evaluator.
     *
     * After this call the evaluator must not be used again.
     */
    suspend fun cleanup()

    /**
     * Returns the identifiers of all worker nodes managed by this evaluator.
     *
     * Single-node evaluators return a set with one entry; federated evaluators
     * return one entry per worker.
     */
    fun getNodeIds(): Set<String>
}
