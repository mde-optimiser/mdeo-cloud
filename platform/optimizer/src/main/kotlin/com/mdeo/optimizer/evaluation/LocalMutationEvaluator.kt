package com.mdeo.optimizer.evaluation

import com.mdeo.metamodel.Metamodel
import com.mdeo.metamodel.Model
import com.mdeo.metamodel.SerializedModel
import com.mdeo.metamodel.data.ModelData
import com.mdeo.modeltransformation.graph.mdeo.MdeoModelGraph
import com.mdeo.modeltransformation.graph.tinker.TinkerModelGraph
import com.mdeo.optimizer.config.GraphBackendType
import com.mdeo.optimizer.guidance.GuidanceFunction
import com.mdeo.optimizer.operators.CrossoverOutcome
import com.mdeo.optimizer.operators.CrossoverStrategy
import com.mdeo.optimizer.operators.MutationStrategy
import com.mdeo.optimizer.solution.FailedOperatorsMetadata
import com.mdeo.optimizer.solution.Solution
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Single-node, in-process implementation of [MutationEvaluator].
 *
 * All mutation and evaluation work is performed locally within the current JVM.
 * Solutions are stored in a thread-safe map keyed by generated identifiers.
 *
 * @param initialSolutionProvider Factory that creates a fresh initial [Solution].
 * @param mutationStrategy The mutation strategy applied to solutions.
 * @param objectives The objective guidance functions used for fitness evaluation.
 * @param constraints The constraint guidance functions used for feasibility checks.
 * @param metamodel The compiled metamodel used to reconstitute solutions from [ModelData].
 * @param nodeId Optional identifier for this evaluator node (defaults to [DEFAULT_NODE_ID]).
 * @param graphBackendType The graph backend to use when reconstituting imported solutions.
 * @param crossoverStrategy The crossover strategy for [CrossoverTask]s, or `null` when the run
 *   only mutates (`variation = mutation`); a crossover task then fails.
 */
class LocalMutationEvaluator(
    private val initialSolutionProvider: () -> Solution,
    private val mutationStrategy: MutationStrategy,
    private val objectives: List<GuidanceFunction>,
    private val constraints: List<GuidanceFunction>,
    private val metamodel: Metamodel? = null,
    private val nodeId: String = DEFAULT_NODE_ID,
    private val graphBackendType: GraphBackendType = GraphBackendType.MDEO,
    private val crossoverStrategy: CrossoverStrategy? = null
) : MutationEvaluator {

    private val logger = LoggerFactory.getLogger(LocalMutationEvaluator::class.java)
    private val solutions: ConcurrentHashMap<String, Solution> = ConcurrentHashMap()

    /**
     * Staged incoming [SerializedModel]s pending lazy materialization.
     *
     * When a peer subprocess pushes a solution via [receiveSolution], the raw bytes are
     * stored here rather than immediately creating a full [ModelGraph].  This keeps the
     * subprocess command-loop fast (no blocking graph construction) so that the stdin
     * pipe drains quickly and [SolutionInjected] channel messages are processed without
     * back-pressure.  The graph is built lazily inside [requireSolution], which runs on
     * the task-dispatcher thread when the mutation actually needs the solution.
     */
    private val incomingSerializedModels: ConcurrentHashMap<String, SerializedModel> = ConcurrentHashMap()

    /**
     * Staged [Solution.failedDeterministicOperators] sets for lazily materialised solutions.
     *
     * When a peer sends a solution with known-failed operator indices, those indices are
     * held here and applied to the [Solution] when it is materialised in [requireSolution].
     * This ensures that previously discovered deterministic failures are not re-attempted
     * on the destination node after a rebalancing transfer.
     */
    private val incomingFailedOperators: ConcurrentHashMap<String, Set<Int>> = ConcurrentHashMap()

    /**
     * Optional callback invoked immediately after a solution has been materialised and
     * placed into [solutions].  The subprocess wires this to its [incomingSolutionSignals]
     * so that any task thread that entered [awaitSolutionAvailable] while materialization
     * was in progress (i.e., the solution had already been removed from
     * [incomingSerializedModels] but not yet written to [solutions]) gets unblocked
     * promptly instead of waiting for the full 60-second timeout.
     *
     * Declared `@Volatile` to guarantee that task-pool threads created before setup
     * completes always observe the assigned callback rather than a stale null.
     */
    @Volatile
    var onSolutionMaterialized: ((String) -> Unit)? = null

    override fun getNodeIds(): Set<String> = setOf(nodeId)

    /**
     * Returns `true` when [solutionId] is present in this evaluator's solution store
     * or has been staged for lazy materialization via [receiveSolution].
     * Thread-safe; may be called from any thread.
     */
    fun hasSolution(solutionId: String): Boolean {
        val ready = solutions.containsKey(solutionId)
        val staged = incomingSerializedModels.containsKey(solutionId)
        return ready || staged
    }

    override suspend fun initialize(count: Int): List<InitialSolutionResult> {
        return (0 until count).map {
            val solution = initialSolutionProvider()
            val mutated = mutationStrategy.mutate(solution)
            try {
                val id = generateId()
                solutions[id] = mutated
                InitialSolutionResult(
                    solutionId = id,
                    workerNodeId = nodeId
                )
            } catch (e: Exception) {
                mutated.close()
                throw e
            }
        }
    }

    override suspend fun executeNodeBatches(batches: List<NodeBatch>): List<EvaluationResult> {
        val batch = batches.firstOrNull { it.nodeId == nodeId } ?: return emptyList()

        val mutationResults = batch.tasks.map { task -> evaluateSingle(task) }

        val crossoverResults = batch.crossoverTasks.map { task -> evaluateCrossover(task) }

        val evaluationResults = batch.evaluationTasks.map { task -> evaluateExisting(task) }

        // Replicas were copied here as donors of this batch's crossovers only.
        for (solutionId in batch.discards + batch.replicas) {
            solutions.remove(solutionId)?.close()
            incomingSerializedModels.remove(solutionId)
            incomingFailedOperators.remove(solutionId)
        }

        return mutationResults + crossoverResults + evaluationResults
    }

    override suspend fun getSolutionData(ref: WorkerSolutionRef): SerializedModel {
        val solution = requireSolution(ref.solutionId)
        return solution.modelGraph.toSerializedModel()
    }

    /**
     * The model of a stored solution, read in place (no serialisation), for in-process callers
     * that only inspect it.
     */
    internal fun solutionModel(solutionId: String): Model = requireSolution(solutionId).modelGraph.toModel()

    override suspend fun cleanup() {
        for (solution in solutions.values) {
            solution.close()
        }
        solutions.clear()
        incomingSerializedModels.clear()
        incomingFailedOperators.clear()
    }

    /**
     * Discards all currently stored solutions and clears all staging queues, making
     * this evaluator ready for a fresh initialization in the next batch.
     *
     * Functionally equivalent to [cleanup] but does not signal that the evaluator is
     * permanently finished — it can be used again after this call.
     */
    override suspend fun resetBatch() {
        for (solution in solutions.values) {
            solution.close()
        }
        solutions.clear()
        incomingSerializedModels.clear()
        incomingFailedOperators.clear()
    }

    /**
     * Stages [serializedModel] for lazy [ModelGraph] construction under [solutionId].
     *
     * Rather than building the graph inline (which is expensive and would block the
     * subprocess command-loop thread), the bytes are kept in [incomingSerializedModels]
     * until [requireSolution] materialises them on the first access by a task thread.
     * [hasSolution] returns `true` as soon as this method returns, so waiting tasks can
     * be signalled and unblocked without delay.
     *
     * @param failedOperators Numerical operator indices known to deterministically fail on
     *   this solution's model state. Restored to the materialised [Solution] so that the
     *   destination node does not reattempt operators that are guaranteed to fail.
     */
    fun receiveSolution(solutionId: String, serializedModel: SerializedModel, failedOperators: Set<Int> = emptySet()) {
        if (failedOperators.isNotEmpty()) {
            incomingFailedOperators[solutionId] = failedOperators
        }
        incomingSerializedModels[solutionId] = serializedModel
    }

    /**
     * Returns the set of numerical operator indices known to deterministically fail on the
     * solution identified by [solutionId], or an empty set if the solution is not found.
     *
     * Used when serialising a solution for transfer to another node so the destination
     * can reconstruct the solution's failure history.
     */
    fun getSolutionFailedOperators(solutionId: String): Set<Int> {
        val solution = solutions[solutionId] ?: return emptySet()
        val meta = solution.modelGraph.metadata as? FailedOperatorsMetadata ?: return emptySet()
        return meta.failedDeterministicOperators.toSet()
    }

    /**
     * Deep-copies the parent solution, applies the mutation strategy, stores the offspring,
     * and returns an [EvaluationResult] with fitness values.
     *
     * The child deep-copy shares the parent's [FailedOperatorsMetadata] instance (via
     * the no-op [com.mdeo.modeltransformation.graph.ModelMetadata.deepCopy]). Any
     * deterministic failures discovered on the child before its first successful mutation
     * are therefore automatically visible on the parent — no explicit back-propagation is
     * needed. When the mutation strategy applies its first successful operator it installs
     * a fresh [FailedOperatorsMetadata] on the child's graph, decoupling the child's
     * failure tracking from the parent's.
     *
     * @param task The mutation task identifying the parent solution.
     * @return An [EvaluationResult] with [EvaluationResult.status] set accordingly.
     */
    private fun evaluateSingle(task: MutationTask): EvaluationResult {
        val parent = requireSolution(task.solutionId)
        return mutateAndStore(task.taskId, task.solutionId, parent.copy(), ownGraph = false)
    }

    /**
     * Makes the child of a [CrossoverTask]: copies the primary parent, applies a crossover with
     * the donors as model inputs ([CrossoverStrategy.crossover]), then mutates, stores and
     * evaluates the child exactly as a mutation offspring ([mutateAndStore]).
     *
     * A task that does not attempt a crossover is a mutation task of its primary: the same
     * copy, the same mutation, the same failure sharing with the parent. A crossover that does
     * not apply leaves an unchanged copy of the primary, which keeps sharing the primary's
     * [FailedOperatorsMetadata] (same model state); one that applies gives the child its own.
     *
     * @param task The crossover task.
     * @return An [EvaluationResult] carrying the task id and the crossover's outcome.
     */
    private fun evaluateCrossover(task: CrossoverTask): EvaluationResult {
        val primary = requireSolution(task.primary.solutionId)
        if (!task.attemptCrossover) {
            return mutateAndStore(task.taskId, task.primary.solutionId, primary.copy(), ownGraph = false)
        }
        val strategy = checkNotNull(crossoverStrategy) { "No crossover operators configured on node $nodeId" }
        val donors = task.donors.map { requireSolution(it.solutionId) }
        val child = Solution(primary.modelGraph.deepCopy(), primary.transformationsChain.map { it.toList() }.toMutableList())
        val outcome = try {
            strategy.crossover(child, primary, donors)
        } catch (e: Throwable) {
            child.close()
            throw e
        }
        return mutateAndStore(task.taskId, task.primary.solutionId, child, ownGraph = true, crossover = outcome)
    }

    /**
     * Mutates [child], stores the result as a new solution, evaluates it, and returns the result
     * of task [taskId].
     *
     * @param taskId The task the result answers.
     * @param parentId The (primary) parent's id.
     * @param child The solution to mutate: a shallow copy of the parent ([ownGraph] `false`), or
     *   a solution with a graph of its own ([ownGraph] `true`), which is closed once the mutation
     *   strategy has replaced it.
     * @param crossover The crossover the child went through, or `null` for a mutation offspring.
     */
    private fun mutateAndStore(
        taskId: String,
        parentId: String,
        child: Solution,
        ownGraph: Boolean,
        crossover: CrossoverOutcome? = null
    ): EvaluationResult {
        val unmutatedGraph = child.modelGraph
        fun failure(status: ResultStatus, executed: Int = 0, skipped: Int = 0, message: String? = null) = EvaluationResult(
            parentSolutionId = parentId,
            newSolutionId = "",
            workerNodeId = nodeId,
            objectives = emptyList(),
            constraints = emptyList(),
            status = status,
            executedTransformations = executed,
            skippedOperatorSlots = skipped,
            errorMessage = message,
            taskId = taskId,
            crossoverAttempted = crossover != null,
            crossoverOperator = crossover?.appliedOperator
        )

        val mutated = try {
            mutationStrategy.mutate(child)
        } catch (e: Throwable) {
            if (child.modelGraph !== unmutatedGraph) child.modelGraph.close()
            if (ownGraph) unmutatedGraph.close()
            return failure(ResultStatus.SOFT_FAILURE)
        }
        if (ownGraph && mutated.modelGraph !== unmutatedGraph) {
            unmutatedGraph.close()
        }
        val executedTransformations = mutated.lastMutationAttempts
        val skippedOperatorSlots = mutated.lastSkipCount
        val newId = generateId()
        solutions[newId] = mutated
        val objectives: List<Double>
        val constraints: List<Double>
        try {
            val values = evaluateGuidance(mutated)
            objectives = values.first
            constraints = values.second
        } catch (e: Throwable) {
            solutions.remove(newId)?.close()
            return failure(
                ResultStatus.HARD_FAILURE, executedTransformations, skippedOperatorSlots,
                "Guidance function evaluation failed: ${e.message}"
            )
        }
        return EvaluationResult(
            parentSolutionId = parentId,
            newSolutionId = newId,
            workerNodeId = nodeId,
            objectives = objectives,
            constraints = constraints,
            status = ResultStatus.SUCCESS,
            executedTransformations = executedTransformations,
            skippedOperatorSlots = skippedOperatorSlots,
            taskId = taskId,
            crossoverAttempted = crossover != null,
            crossoverOperator = crossover?.appliedOperator
        )
    }

    /**
     * Evaluates an existing solution without mutation, returning fitness values.
     *
     * Used for initial population evaluation where solutions have already been created
     * and stored but need their objective and constraint values computed.
     *
     * @param task The evaluation task identifying the solution to evaluate.
     * @return An [EvaluationResult] with the same solution ID for both parent and new.
     */
    private fun evaluateExisting(task: EvaluationTask): EvaluationResult {
        val solution = requireSolution(task.solutionId)
        val objectives: List<Double>
        val constraints: List<Double>
        try {
            val values = evaluateGuidance(solution)
            objectives = values.first
            constraints = values.second
        } catch (e: Throwable) {
            return EvaluationResult(
                parentSolutionId = task.solutionId,
                newSolutionId = "",
                workerNodeId = nodeId,
                objectives = emptyList(),
                constraints = emptyList(),
                status = ResultStatus.HARD_FAILURE,
                errorMessage = "Guidance function evaluation failed: ${e.message}"
            )
        }
        return EvaluationResult(
            parentSolutionId = task.solutionId,
            newSolutionId = task.solutionId,
            workerNodeId = nodeId,
            objectives = objectives,
            constraints = constraints,
            status = ResultStatus.SUCCESS
        )
    }

    /**
     * Evaluates all objective and constraint functions against the given solution. They share
     * one [com.mdeo.metamodel.Model] of the solution (built on first use): a model per function
     * would repeat every per-model cost, such as encoding it for external functions.
     *
     * @param solution The candidate solution to evaluate.
     * @return Fitness values in the order the objectives were configured, and constraint
     *   violation values in the order the constraints were configured.
     */
    private fun evaluateGuidance(solution: Solution): Pair<List<Double>, List<Double>> {
        val model = lazy(LazyThreadSafetyMode.NONE) { solution.modelGraph.toModel() }
        return objectives.map { it.computeFitness(solution, model) } to
            constraints.map { it.computeFitness(solution, model) }
    }

    /**
     * Looks up a solution by ID, materialising it from [incomingSerializedModels] if
     * necessary, and throwing if neither store contains it.
     *
     * [ConcurrentHashMap.computeIfAbsent] guarantees that only one thread builds the
     * graph for a given [solutionId]; concurrent callers for the same key wait for the
     * first thread to finish and then return the cached result.
     *
     * @param solutionId The solution identifier to look up.
     * @return The stored (or freshly materialised) [Solution].
     * @throws IllegalArgumentException if no solution with that ID exists.
     */
    private fun requireSolution(solutionId: String): Solution {
        val result = solutions.computeIfAbsent(solutionId) { id ->
            val stagedModel = incomingSerializedModels[id]
            if (stagedModel == null) {
                throw IllegalArgumentException("Solution not found: $id")
            }
            val serializedModel = incomingSerializedModels.remove(id)
            if (serializedModel == null) {
                throw IllegalArgumentException("Solution not found (concurrent removal): $id")
            }
            val mm = checkNotNull(metamodel) { "Cannot materialise solution without a metamodel" }
            val modelGraph = when (graphBackendType) {
                GraphBackendType.MDEO -> MdeoModelGraph.create(serializedModel, mm)
                GraphBackendType.Tinker -> TinkerModelGraph.create(serializedModel.toModelData(mm), mm)
            }
            val solution = Solution(modelGraph)
            // Restore failed operator indices transferred alongside the model data.
            val failedOps = incomingFailedOperators.remove(id)
            if (!failedOps.isNullOrEmpty()) {
                val meta = FailedOperatorsMetadata()
                meta.failedDeterministicOperators.addAll(failedOps)
                solution.modelGraph.metadata = meta
            }
            solution
        }
        onSolutionMaterialized?.invoke(solutionId)
        return result
    }

    /**
     * Generates a unique identifier for a new solution, scoped to this node.
     *
     * @return A string of the form `"<nodeId>-<uuid>"` that is unique across all nodes.
     */
    private fun generateId(): String = "$nodeId-${UUID.randomUUID()}"

    companion object {
        /**
         * Default node identifier used when no [nodeId] is provided. 
         */
        const val DEFAULT_NODE_ID = "local"
    }
}
