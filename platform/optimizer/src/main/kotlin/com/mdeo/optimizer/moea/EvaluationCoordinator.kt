package com.mdeo.optimizer.moea

import com.mdeo.optimizer.evaluation.CrossoverTask
import com.mdeo.optimizer.evaluation.EvaluationFailedException
import com.mdeo.optimizer.evaluation.EvaluationResult
import com.mdeo.optimizer.evaluation.EvaluationTask
import com.mdeo.optimizer.evaluation.MutationEvaluator
import com.mdeo.optimizer.evaluation.MutationTask
import com.mdeo.optimizer.evaluation.NodeBatch
import com.mdeo.optimizer.evaluation.ResultStatus
import com.mdeo.optimizer.evaluation.WorkerSolutionRef
import com.mdeo.optimizer.metrics.CrossoverGenerationMetrics
import kotlinx.coroutines.runBlocking
import org.moeaframework.core.Solution
import org.moeaframework.core.population.Population
import org.slf4j.LoggerFactory

/**
 * Applied and skipped transformation counts for the last completed generation.
 *
 * @param appliedTotal Total transformation operators actually applied across all nodes.
 * @param skippedTotal Total operator slots pre-skipped across all nodes.
 * @param appliedPerNode Per-node applied transformation counts.
 * @param skippedPerNode Per-node skipped operator slot counts.
 */
data class LastTransformationInfo(
    val appliedTotal: Int,
    val skippedTotal: Int,
    val appliedPerNode: Map<String, Int>,
    val skippedPerNode: Map<String, Int>
)

/**
 * Bridges MOEA Framework's synchronous evaluation callbacks into the asynchronous
 * [MutationEvaluator] contract, managing solution references and lifecycle.
 *
 * Each evaluation batch is handled uniformly: solutions marked with
 * [DelegatingVariation.NEEDS_MUTATION_KEY] produce [MutationTask]s, or [CrossoverTask]s when
 * they come from a [DelegatingCrossoverVariation] group that attempts a crossover, while
 * uninitialized solutions (no [WorkerSolutionRef]) are created via
 * [MutationEvaluator.initialize] and then evaluated via [EvaluationTask]s.
 * All task types are bundled into [NodeBatch]es per worker so that all
 * fitness evaluation—including initial population—benefits from the same
 * timeout protection and lifecycle mechanisms. Coroutine calls are bridged
 * with [runBlocking] because MOEA Framework invokes callbacks synchronously.
 *
 * Every offspring task has a task id, and its result is matched to the offspring by that id: one
 * parent can be the parent of several offspring of one batch (selected several times, or the
 * primary of one child and a donor of another), so its id identifies no offspring.
 *
 * A crossover task runs on its primary parent's node. Donors living on other nodes are
 * replicated there for the batch ([MutationEvaluator.replicateSolutions]) and listed in that
 * node's [NodeBatch.replicas], which the node drops after the batch. Replicas are never added to
 * the tracked references, so [PopulationRebalancer] never sees them and they are never discarded
 * through the population lifecycle. A single-node run never replicates.
 *
 * @param evaluator The mutation evaluator that performs the actual work (local or federated).
 */
class EvaluationCoordinator(
    private val evaluator: MutationEvaluator
) {
    private val logger = LoggerFactory.getLogger(EvaluationCoordinator::class.java)

    private val allKnownRefs = mutableSetOf<WorkerSolutionRef>()
    private var pendingDiscards = mutableListOf<WorkerSolutionRef>()
    private var pendingRebalancePlan: List<RebalanceTransfer> = emptyList()
    /**
     * The population passed to [prepareIteration], cached so that [handleEvaluation] can
     * update [WorkerSolutionRef] attributes on surviving parent solutions after rebalancing.
     *
     * Valid because [prepareIteration] is called before `super.iterate()`, and
     * [handleEvaluation] runs inside that same `super.iterate()` call before MOEA merges
     * offspring back into the population.
     */
    private var iterationPopulation: Population? = null
    private var lastBatchSize = 0
    private var lastBatchPerNode: Map<String, Int> = emptyMap()
    private var lastRebalancedCount = 0
    private var lastAppliedTransformations = 0
    private var lastSkippedOperatorSlots = 0
    private var lastAppliedPerNode: Map<String, Int> = emptyMap()
    private var lastSkippedPerNode: Map<String, Int> = emptyMap()
    private var lastCrossover = CrossoverGenerationMetrics()
    private var lastReplicatedCount = 0

    /**
     * The number of batches dispatched so far. A step of an algorithm that dispatched no batch
     * (a steady-state step that inserts an offspring evaluated earlier) leaves it unchanged, so
     * that per-generation metrics do not count the previous batch twice.
     */
    var batchCount: Int = 0
        private set

    /**
     * Identifies solutions removed from the population since the last iteration,
     * queues them for deferred disposal, and pre-computes the rebalancing plan.
     *
     * Call before `super.iterate()` in delegating algorithm subclasses.
     *
     * @param currentPopulation The live population (or archive) to compare against known refs.
     */
    fun prepareIteration(currentPopulation: Population) {
        iterationPopulation = currentPopulation

        val liveRefs = currentPopulation.mapNotNull { it.getWorkerRef() }.toSet()
        val discarded = allKnownRefs - liveRefs
        if (discarded.isNotEmpty()) {
            pendingDiscards.addAll(discarded)
            allKnownRefs.removeAll(discarded)
        }

        pendingRebalancePlan = PopulationRebalancer.computeTransferPlan(
            allKnownRefs = allKnownRefs,
            nodeIds = evaluator.getNodeIds().toList(),
            lastBatchPerNode = lastBatchPerNode
        )
    }

    /**
     * Evaluates a batch of solutions by dispatching unified per-node work batches
     * to the underlying [MutationEvaluator].
     *
     * Each solution is inspected individually:
     * - Solutions with [DelegatingVariation.NEEDS_MUTATION_KEY] set to `true` are treated
     *   as generation offspring: a [MutationTask] is created from the parent ref.
     * - Solutions without that flag (and without a [WorkerSolutionRef]) are treated as
     *   uninitialized: new solutions are created via [MutationEvaluator.initialize] and
     *   then [EvaluationTask]s are dispatched for fitness evaluation.
     *
     * Both task types are bundled into [NodeBatch]es and dispatched in a single call to
     * [MutationEvaluator.executeNodeBatches], so all evaluation work benefits from the
     * same timeout and lifecycle mechanisms.
     *
     * @param solutions The solutions to evaluate.
     * @return The number of solutions processed.
     */
    fun batchEvaluateAndUpdate(solutions: Iterable<Solution>): Int {
        val solutionList = solutions.toList()
        if (solutionList.isEmpty()) {
            return 0
        }

        handleEvaluation(solutionList)
        return solutionList.size
    }

    /**
     * Evaluates a single solution. Delegates to [batchEvaluateAndUpdate] with a singleton list.
     *
     * @param solution The solution to evaluate.
     */
    fun singleEvaluateAndUpdate(solution: Solution) {
        batchEvaluateAndUpdate(listOf(solution))
    }

    /**
     * Returns a snapshot of all [WorkerSolutionRef]s currently tracked as live by this coordinator.
     *
     * @return An immutable copy of the live reference set.
     */
    fun getAllKnownRefs(): Set<WorkerSolutionRef> = allKnownRefs.toSet()

    /**
     * Resets all tracking state so this coordinator is ready to manage a fresh batch.
     *
     * Clears the live-reference set, any queued discards, pending rebalance plans,
     * and all cached per-generation metrics.  After this call [prepareIteration] and
     * [batchEvaluateAndUpdate] behave exactly as they would for a brand-new coordinator.
     *
     * Must be called AFTER [MutationEvaluator.resetBatch] has been invoked (so that
     * stale solution IDs are never sent as discards to the freshly-started workers).
     */
    fun reset() {
        allKnownRefs.clear()
        pendingDiscards.clear()
        pendingRebalancePlan = emptyList()
        iterationPopulation = null
        lastBatchSize = 0
        lastBatchPerNode = emptyMap()
        lastRebalancedCount = 0
        lastAppliedTransformations = 0
        lastSkippedOperatorSlots = 0
        lastAppliedPerNode = emptyMap()
        lastSkippedPerNode = emptyMap()
        lastCrossover = CrossoverGenerationMetrics()
        lastReplicatedCount = 0
    }

    /**
     * Returns the total number of live solutions and a per-node breakdown.
     *
     * @return Pair of (total count, per-nodeId count map).
     */
    fun getMetricsSnapshot(): Pair<Int, Map<String, Int>> {
        val perNode = allKnownRefs.groupBy { it.nodeId }.mapValues { it.value.size }
        return Pair(allKnownRefs.size, perNode)
    }

    /**
     * Returns the size and per-node breakdown of the last batch dispatched to workers.
     *
     * @return Pair of (total batch size, per-nodeId task count map).
     */
    fun getLastBatchInfo(): Pair<Int, Map<String, Int>> = Pair(lastBatchSize, lastBatchPerNode)

    /**
     * Returns applied and skipped transformation counts for the last generation.
     *
     * @return [LastTransformationInfo] with totals and per-node breakdowns.
     */
    fun getLastTransformationInfo(): LastTransformationInfo = LastTransformationInfo(
        appliedTotal = lastAppliedTransformations,
        skippedTotal = lastSkippedOperatorSlots,
        appliedPerNode = lastAppliedPerNode,
        skippedPerNode = lastSkippedPerNode
    )

    /**
     * Returns the number of solutions moved between nodes during the last generation's rebalancing.
     *
     * @return Count of solutions transferred; zero if no rebalancing occurred.
     */
    fun getLastRebalancedCount(): Int = lastRebalancedCount

    /**
     * Returns the crossovers of the last batch.
     *
     * @return Crossovers attempted, succeeded and fallen back to copies, and per-operator successes.
     */
    fun getLastCrossoverInfo(): CrossoverGenerationMetrics = lastCrossover

    /**
     * Returns the number of donor copies made for the last batch ([NodeBatch.replicas]).
     *
     * @return The number of replicas; zero for a single node.
     */
    fun getLastReplicatedCount(): Int = lastReplicatedCount

    /**
     * Unified evaluation handler that processes both initialization and generation
     * solutions in a single dispatch to [MutationEvaluator.executeNodeBatches].
     *
     * Solutions are partitioned into two groups:
     * - **Offspring**: from [DelegatingVariation] or [DelegatingCrossoverVariation], with
     *   [DelegatingVariation.NEEDS_MUTATION_KEY] set — these produce [MutationTask]s, or
     *   [CrossoverTask]s for the children of a group that attempts a crossover.
     * - **Uninitialized solutions**: solutions without a [WorkerSolutionRef] — these are
     *   created via [MutationEvaluator.initialize] and then evaluated via [EvaluationTask]s.
     *
     * All task types are dispatched together, ensuring that all fitness evaluation
     * (including initial population) benefits from the same timeout protection and
     * lifecycle mechanisms as generation mutations.
     *
     * @param solutions All MOEA solutions to evaluate in this batch.
     */
    private fun handleEvaluation(solutions: List<Solution>) {
        val uninitializedSolutions = mutableListOf<Solution>()
        val offspringSolutions = mutableListOf<Solution>()

        for (solution in solutions) {
            val needsMutation = solution.getAttribute(DelegatingVariation.NEEDS_MUTATION_KEY) == true
            if (needsMutation) {
                offspringSolutions.add(solution)
            } else {
                uninitializedSolutions.add(solution)
            }
        }

        val evaluationTasks = mutableListOf<EvaluationTask>()
        if (uninitializedSolutions.isNotEmpty()) {
            if (pendingDiscards.isNotEmpty()) {
                val discards = pendingDiscards.toList()
                pendingDiscards.clear()
                runBlocking {
                    val emptyBatches = evaluator.getNodeIds().map { nodeId ->
                        NodeBatch(nodeId, emptyList(), emptyList(),
                            discards.filter { it.nodeId == nodeId }.map { it.solutionId })
                    }
                    evaluator.executeNodeBatches(emptyBatches)
                }
            }

            val initResults = runBlocking { evaluator.initialize(uninitializedSolutions.size) }
            for ((index, solution) in uninitializedSolutions.withIndex()) {
                if (index >= initResults.size) break
                val result = initResults[index]
                val ref = WorkerSolutionRef(nodeId = result.workerNodeId, solutionId = result.solutionId)
                solution.setAttribute(WorkerSolutionRef.ATTRIBUTE_KEY, ref)
                allKnownRefs.add(ref)
                evaluationTasks.add(EvaluationTask(solutionId = ref.solutionId, workerNodeId = ref.nodeId))
            }
        }

        val offspringTasks = buildOffspringTasks(offspringSolutions)

        val rebalancePlan = pendingRebalancePlan
        pendingRebalancePlan = emptyList()

        // Push solutions between nodes before dispatching the batch so that destination
        // subprocesses receive model data asynchronously while local tasks are already running.
        // The push returns the source-node discard map; those discards are included in the
        // current batch (no deferral needed because the push has already delivered the data).
        val rebalanceDiscardsByNode = runBlocking { evaluator.pushRebalanceSolutions(rebalancePlan) }

        // Donors of crossover tasks on other nodes are copied to the primary's node for this
        // batch; the destination drops them afterwards. Planned against the references of this
        // batch: during it every solution is still where the population says (a rebalanced one
        // leaves its source only after the batch).
        val replication = planReplication(offspringTasks.crossoverTasks, rebalancePlan)
        if (replication.isNotEmpty()) {
            runBlocking { evaluator.replicateSolutions(replication) }
        }
        val replicasByNode = replication.groupBy({ it.destinationNodeId }, { it.solutionIds })
            .mapValues { (_, ids) -> ids.flatten() }

        val discards = pendingDiscards.toList()
        pendingDiscards.clear()

        val allDiscards = discards +
            rebalanceDiscardsByNode.entries.flatMap { (nodeId, ids) -> ids.map { WorkerSolutionRef(nodeId, it) } }

        val batches = buildNodeBatches(
            offspringTasks.mutationTasks, evaluationTasks, allDiscards,
            offspringTasks.crossoverTasks, replicasByNode
        )
        val results = runBlocking { evaluator.executeNodeBatches(batches) }

        val evaluationFailure = results.firstOrNull { it.status == ResultStatus.HARD_FAILURE }
        if (evaluationFailure != null) {
            throw EvaluationFailedException(
                evaluationFailure.errorMessage
                    ?: "Hard failure for solution '${evaluationFailure.parentSolutionId}'"
            )
        }

        val populationBySolutionId = buildPopulationLookup()
        applyRebalanceUpdates(rebalancePlan, populationBySolutionId)

        val offspringResultsByTask = mutableMapOf<String, EvaluationResult>()
        // Results of an evaluator that does not echo task ids, matched by parent as before them.
        val untaggedResultsByParent = mutableMapOf<String, MutableList<EvaluationResult>>()
        val evalResultsBySolution = mutableMapOf<String, EvaluationResult>()
        for (result in results) {
            if (result.taskId.isNotEmpty()) {
                offspringResultsByTask[result.taskId] = result
            } else if (result.parentSolutionId == result.newSolutionId) {
                evalResultsBySolution[result.parentSolutionId] = result
            } else {
                untaggedResultsByParent.getOrPut(result.parentSolutionId) { mutableListOf() }.add(result)
            }
        }

        for (solution in uninitializedSolutions) {
            val ref = solution.getWorkerRef() ?: continue
            val result = evalResultsBySolution[ref.solutionId]
            if (result != null && result.status == ResultStatus.SUCCESS) {
                applyFitness(solution, result.objectives, result.constraints)
            } else {
                applyPenaltyFitness(solution)
            }
        }

        for (solution in offspringSolutions) {
            val result = offspringTasks.taskIdBySolution[solution]?.let { offspringResultsByTask[it] }
                ?: (solution.getAttribute(DelegatingVariation.PARENT_REF_KEY) as? WorkerSolutionRef)
                    ?.let { untaggedResultsByParent[it.solutionId]?.removeFirstOrNull() }
            if (result != null && result.status == ResultStatus.SUCCESS) {
                val newRef = WorkerSolutionRef(nodeId = result.workerNodeId, solutionId = result.newSolutionId)
                applyFitness(solution, result.objectives, result.constraints)
                solution.setAttribute(WorkerSolutionRef.ATTRIBUTE_KEY, newRef)
                allKnownRefs.add(newRef)
            } else {
                applyPenaltyFitness(solution)
            }
        }

        batchCount++
        lastBatchSize = results.size
        lastBatchPerNode = results.filter { it.status == ResultStatus.SUCCESS }.groupBy { it.workerNodeId }.mapValues { it.value.size }
        lastRebalancedCount = rebalancePlan.sumOf { it.solutionIds.size }
        lastReplicatedCount = replication.sumOf { it.solutionIds.size }
        lastAppliedTransformations = results.sumOf { it.executedTransformations }
        lastSkippedOperatorSlots = results.sumOf { it.skippedOperatorSlots }
        lastAppliedPerNode = results.groupBy { it.workerNodeId }.mapValues { entry -> entry.value.sumOf { it.executedTransformations } }
        lastSkippedPerNode = results.groupBy { it.workerNodeId }.mapValues { entry -> entry.value.sumOf { it.skippedOperatorSlots } }
        lastCrossover = crossoverMetrics(results)
    }

    /**
     * The tasks of a batch's offspring and the task id of each offspring.
     *
     * @param mutationTasks Tasks of mutation offspring and of crossover children whose group
     *   attempts no crossover.
     * @param crossoverTasks Tasks of crossover children whose group attempts a crossover.
     * @param taskIdBySolution The task id of every offspring that got a task (by identity).
     */
    private class OffspringTasks(
        val mutationTasks: List<MutationTask>,
        val crossoverTasks: List<CrossoverTask>,
        val taskIdBySolution: java.util.IdentityHashMap<Solution, String>
    )

    /**
     * Builds one task per offspring from its attributes: a [CrossoverTask] for a child of a
     * [DelegatingCrossoverVariation] group that attempts a crossover, a [MutationTask] of the
     * primary (or only) parent otherwise. A child whose group attempts no crossover is thereby
     * exactly a mutation offspring. Offspring without a parent reference are skipped with a
     * warning (and penalised).
     *
     * @param solutions Offspring solutions produced by the variation operator.
     * @return The tasks and the task id of each offspring.
     */
    private fun buildOffspringTasks(solutions: List<Solution>): OffspringTasks {
        val mutationTasks = mutableListOf<MutationTask>()
        val crossoverTasks = mutableListOf<CrossoverTask>()
        val taskIds = java.util.IdentityHashMap<Solution, String>()
        for (solution in solutions) {
            val parentRef = solution.getAttribute(DelegatingVariation.PARENT_REF_KEY) as? WorkerSolutionRef
            if (parentRef == null) {
                logger.warn("Solution missing parent reference during generation phase")
                continue
            }
            @Suppress("UNCHECKED_CAST")
            val donors = solution.getAttribute(DelegatingCrossoverVariation.DONOR_REFS_KEY) as? List<WorkerSolutionRef>
            val attempt = solution.getAttribute(DelegatingCrossoverVariation.ATTEMPT_CROSSOVER_KEY) == true
            if (donors != null && attempt) {
                val task = CrossoverTask(newTaskId(), parentRef, donors.toList(), attemptCrossover = true)
                crossoverTasks.add(task)
                taskIds[solution] = task.taskId
            } else {
                val task = MutationTask(solutionId = parentRef.solutionId, workerNodeId = parentRef.nodeId, taskId = newTaskId())
                mutationTasks.add(task)
                taskIds[solution] = task.taskId
            }
        }
        return OffspringTasks(mutationTasks, crossoverTasks, taskIds)
    }

    /**
     * Plans the donor copies a batch needs: every donor of a [CrossoverTask] that is not on the
     * task's node, once per destination node. A donor the [rebalancePlan] moves to that very node
     * in this batch is not copied: it arrives there anyway and stays.
     *
     * @param tasks The batch's crossover tasks.
     * @param rebalancePlan The batch's rebalancing transfers.
     * @return One transfer per (source, destination) pair, in replicate mode.
     */
    private fun planReplication(
        tasks: List<CrossoverTask>,
        rebalancePlan: List<RebalanceTransfer>
    ): List<RebalanceTransfer> {
        if (tasks.isEmpty()) return emptyList()
        val relocated = rebalancePlan.flatMap { transfer -> transfer.solutionIds.map { it to transfer.destinationNodeId } }.toSet()
        val copies = linkedMapOf<Pair<String, String>, LinkedHashSet<String>>()
        for (task in tasks) {
            val destination = task.workerNodeId
            for (donor in task.donors) {
                if (donor.nodeId == destination || (donor.solutionId to destination) in relocated) continue
                copies.getOrPut(donor.nodeId to destination) { linkedSetOf() }.add(donor.solutionId)
            }
        }
        return copies.map { (nodes, ids) -> RebalanceTransfer(ids.toList(), nodes.first, nodes.second) }
    }

    /**
     * Counts the crossovers of a batch's results, per child.
     */
    private fun crossoverMetrics(results: List<EvaluationResult>): CrossoverGenerationMetrics {
        val attempted = results.filter { it.crossoverAttempted }
        if (attempted.isEmpty()) return CrossoverGenerationMetrics()
        val applied = attempted.mapNotNull { it.crossoverOperator }
        return CrossoverGenerationMetrics(
            attempted = attempted.size,
            succeeded = applied.size,
            fellBack = attempted.size - applied.size,
            perOperator = applied.groupingBy { it }.eachCount().toSortedMap()
        )
    }

    private fun newTaskId(): String = java.util.UUID.randomUUID().toString()

    /**
     * Assembles one [NodeBatch] per worker node, combining mutation, crossover and evaluation
     * tasks, the replicas copied to the node for this batch, and all pending discards.
     *
     * @param mutationTasks Tasks to dispatch this generation.
     * @param evaluationTasks Evaluation-only tasks (e.g. initial population fitness evaluation).
     * @param discards All discards for this generation (regular + rebalance-source).
     * @param crossoverTasks Crossover tasks, each dispatched to its primary's node.
     * @param replicasByNode Donor copies made for this batch, by destination node.
     * @return One [NodeBatch] per node in [MutationEvaluator.getNodeIds].
     */
    private fun buildNodeBatches(
        mutationTasks: List<MutationTask>,
        evaluationTasks: List<EvaluationTask>,
        discards: List<WorkerSolutionRef>,
        crossoverTasks: List<CrossoverTask> = emptyList(),
        replicasByNode: Map<String, List<String>> = emptyMap()
    ): List<NodeBatch> {
        val discardsByNode = discards.groupBy({ it.nodeId }, { it.solutionId })
        return evaluator.getNodeIds().map { nodeId ->
            NodeBatch(
                nodeId = nodeId,
                tasks = mutationTasks.filter { it.workerNodeId == nodeId },
                evaluationTasks = evaluationTasks.filter { it.workerNodeId == nodeId },
                discards = discardsByNode[nodeId] ?: emptyList(),
                crossoverTasks = crossoverTasks.filter { it.workerNodeId == nodeId },
                replicas = replicasByNode[nodeId] ?: emptyList()
            )
        }
    }

    /**
     * Builds a lookup map from solution ID to MOEA [Solution] for the current population.
     *
     * @return Map of solutionId → MOEA solution for all solutions with a [WorkerSolutionRef].
     */
    private fun buildPopulationLookup(): Map<String, Solution> {
        return iterationPopulation
            ?.associateBy { sol -> sol.getWorkerRef()?.solutionId ?: "" }
            ?.filterKeys { it.isNotEmpty() }
            ?: emptyMap()
    }

    /**
     * Updates [WorkerSolutionRef] attributes on population solutions and [allKnownRefs]
     * to reflect the new node assignments after rebalancing.
     *
     * @param rebalancePlan The executed rebalance transfers.
     * @param populationBySolutionId Current population keyed by solution ID.
     */
    private fun applyRebalanceUpdates(
        rebalancePlan: List<RebalanceTransfer>,
        populationBySolutionId: Map<String, Solution>
    ) {
        for (transfer in rebalancePlan) {
            for (solutionId in transfer.solutionIds) {
                val oldRef = WorkerSolutionRef(transfer.sourceNodeId, solutionId)
                val newRef = WorkerSolutionRef(transfer.destinationNodeId, solutionId)
                allKnownRefs.remove(oldRef)
                allKnownRefs.add(newRef)
                populationBySolutionId[solutionId]?.setAttribute(WorkerSolutionRef.ATTRIBUTE_KEY, newRef)
            }
        }
    }

    /**
     * Writes objective and constraint values from the worker result onto the MOEA solution.
     *
     * @param solution The MOEA solution to update.
     * @param objectives Objective values, applied up to [Solution.numberOfObjectives].
     * @param constraints Constraint values, applied up to [Solution.numberOfConstraints].
     */
    private fun applyFitness(solution: Solution, objectives: List<Double>, constraints: List<Double>) {
        for (i in objectives.indices) {
            if (i < solution.numberOfObjectives) {
                solution.setObjectiveValue(i, objectives[i])
            }
        }
        for (i in constraints.indices) {
            if (i < solution.numberOfConstraints) {
                solution.setConstraintValue(i, constraints[i])
            }
        }
    }

    /**
     * Applies [Double.MAX_VALUE] to all objectives and constraints on [solution],
     * effectively making it dominated by any valid solution.
     *
     * @param solution The MOEA solution to penalise.
     */
    private fun applyPenaltyFitness(solution: Solution) {
        for (i in 0 until solution.numberOfObjectives) {
            solution.setObjectiveValue(i, Double.MAX_VALUE)
        }
        for (i in 0 until solution.numberOfConstraints) {
            solution.setConstraintValue(i, Double.MAX_VALUE)
        }
    }
}

/**
 * Returns the [WorkerSolutionRef] stored as an attribute on this MOEA solution, or `null` if absent.
 */
fun Solution.getWorkerRef(): WorkerSolutionRef? =
    getAttribute(WorkerSolutionRef.ATTRIBUTE_KEY) as? WorkerSolutionRef

