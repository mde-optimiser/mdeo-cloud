package com.mdeo.optimizer.crossover

import com.mdeo.metamodel.SerializedModel
import com.mdeo.metamodel.data.ModelData
import com.mdeo.optimizer.config.AlgorithmParameters
import com.mdeo.optimizer.config.AlgorithmType
import com.mdeo.optimizer.config.CrossoverParameters
import com.mdeo.optimizer.config.MutationParameters
import com.mdeo.optimizer.config.MutationStepConfig
import com.mdeo.optimizer.config.OperatorRef
import com.mdeo.optimizer.config.SolverConfig
import com.mdeo.optimizer.config.TerminationConfig
import com.mdeo.optimizer.config.VariationType
import com.mdeo.optimizer.crossover.CrossoverFixtures.COPY_NAMES
import com.mdeo.optimizer.crossover.CrossoverFixtures.MOVE
import com.mdeo.optimizer.crossover.CrossoverFixtures.asts
import com.mdeo.optimizer.crossover.CrossoverFixtures.model
import com.mdeo.optimizer.evaluation.EvaluationResult
import com.mdeo.optimizer.evaluation.InitialSolutionResult
import com.mdeo.optimizer.evaluation.LocalMutationEvaluator
import com.mdeo.optimizer.evaluation.MutationEvaluator
import com.mdeo.optimizer.evaluation.NodeBatch
import com.mdeo.optimizer.evaluation.ResultStatus
import com.mdeo.optimizer.evaluation.WorkerSolutionRef
import com.mdeo.optimizer.moea.DelegatingAlgorithmProvider
import com.mdeo.optimizer.moea.DelegatingCrossoverVariation
import com.mdeo.optimizer.moea.DelegatingProblem
import com.mdeo.optimizer.moea.EvaluationCoordinator
import com.mdeo.optimizer.moea.RebalanceTransfer
import com.mdeo.optimizer.moea.TerminationConditionAdapter
import com.mdeo.optimizer.moea.getWorkerRef
import com.mdeo.optimizer.operators.MutationStrategyFactory
import com.mdeo.optimizer.operators.OperatorCatalog
import com.mdeo.optimizer.operators.RandomCrossoverStrategy
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.moeaframework.core.Solution as MoeaSolution
import org.moeaframework.core.population.Population

/**
 * Crossover across nodes (spec D.5) with in-process [LocalMutationEvaluator]s standing in for
 * worker nodes: a donor on another node is replicated to the primary's node for the batch, used
 * there, and dropped after it, and replicas never become tracked population members, so
 * rebalancing never sees them.
 */
class DistributedCrossoverTest {

    /**
     * Routes work to one [LocalMutationEvaluator] per node, as [com.mdeo.optimizer.evaluation.MutationEvaluator]
     * implementations of several nodes do, and records the batches and replications.
     */
    private class MultiNodeEvaluator(val nodes: Map<String, LocalMutationEvaluator>) : MutationEvaluator {
        val batches = mutableListOf<NodeBatch>()
        val replications = mutableListOf<RebalanceTransfer>()
        val results = mutableListOf<EvaluationResult>()

        override suspend fun initialize(count: Int): List<InitialSolutionResult> {
            val base = count / nodes.size
            val extra = count % nodes.size
            return nodes.values.withIndex().flatMap { (i, node) -> node.initialize(base + if (i < extra) 1 else 0) }
        }

        override suspend fun executeNodeBatches(batches: List<NodeBatch>): List<EvaluationResult> {
            this.batches += batches
            return batches.flatMap { nodes.getValue(it.nodeId).executeNodeBatches(listOf(it)) }.also { results += it }
        }

        override suspend fun pushRebalanceSolutions(transfers: List<RebalanceTransfer>): Map<String, List<String>> {
            copy(transfers)
            return transfers.groupBy({ it.sourceNodeId }, { it.solutionIds }).mapValues { it.value.flatten() }
        }

        override suspend fun replicateSolutions(transfers: List<RebalanceTransfer>) {
            replications += transfers
            copy(transfers)
        }

        private suspend fun copy(transfers: List<RebalanceTransfer>) {
            for (transfer in transfers) {
                val source = nodes.getValue(transfer.sourceNodeId)
                for (id in transfer.solutionIds) {
                    nodes.getValue(transfer.destinationNodeId)
                        .receiveSolution(id, source.getSolutionData(WorkerSolutionRef(transfer.sourceNodeId, id)))
                }
            }
        }

        override suspend fun getSolutionData(ref: WorkerSolutionRef): SerializedModel = nodes.getValue(ref.nodeId).getSolutionData(ref)

        override suspend fun cleanup() = nodes.values.forEach { it.cleanup() }

        override fun getNodeIds(): Set<String> = nodes.keys
    }

    private val evaluators = mutableListOf<MultiNodeEvaluator>()

    @AfterEach
    fun cleanup() = runBlocking { evaluators.forEach { it.cleanup() } }

    /** One node per start model; node `n<i>` starts from `starts[i]`. */
    private fun evaluator(starts: List<ModelData>, step: MutationStepConfig = MutationStepConfig.Fixed(0)): MultiNodeEvaluator {
        val mutations = OperatorCatalog.mutationOperators(listOf(OperatorRef(MOVE)), asts)
        val crossovers = OperatorCatalog.crossoverOperators(listOf(OperatorRef(COPY_NAMES)), asts, 2)
        val nodes = starts.withIndex().associate { (i, start) ->
            "n$i" to LocalMutationEvaluator(
                initialSolutionProvider = { CrossoverFixtures.solution(start) },
                mutationStrategy = MutationStrategyFactory.create(MutationParameters(step = step), mutations),
                objectives = listOf(CrossoverFixtures.ComponentsOnA()),
                constraints = emptyList(),
                metamodel = CrossoverFixtures.metamodel,
                nodeId = "n$i",
                crossoverStrategy = RandomCrossoverStrategy(crossovers)
            )
        }
        return MultiNodeEvaluator(nodes).also { evaluators += it }
    }

    private fun MultiNodeEvaluator.containersOf(ref: WorkerSolutionRef): List<String> = runBlocking {
        CrossoverFixtures.graph(getSolutionData(ref).toModelData(CrossoverFixtures.metamodel)).use { CrossoverFixtures.containers(it) }
    }

    @Test
    fun `a donor on another node is replicated, used and dropped`() {
        val evaluator = evaluator(listOf(
            model(listOf("a"), mapOf("on-n0" to "a")),
            model(listOf("a"), mapOf("on-n1" to "a"))
        ))
        val coordinator = EvaluationCoordinator(evaluator)
        val parents = List(2) { MoeaSolution(0, 1, 0) }
        coordinator.batchEvaluateAndUpdate(parents)
        val (p0, p1) = parents.map { it.getWorkerRef()!! }
        assertEquals(listOf("n0", "n1"), listOf(p0.nodeId, p1.nodeId))

        coordinator.prepareIteration(Population(parents))
        val children = DelegatingCrossoverVariation(2, rate = 1.0).evolve(parents.toTypedArray())
        coordinator.batchEvaluateAndUpdate(children.toList())

        assertEquals(
            setOf(RebalanceTransfer(listOf(p1.solutionId), "n1", "n0"), RebalanceTransfer(listOf(p0.solutionId), "n0", "n1")),
            evaluator.replications.toSet(), "each donor was copied to the other parent's node"
        )
        val offspringBatches = evaluator.batches.filter { it.crossoverTasks.isNotEmpty() }
        assertEquals(mapOf("n0" to listOf(p1.solutionId), "n1" to listOf(p0.solutionId)), offspringBatches.associate { it.nodeId to it.replicas })
        assertTrue(offspringBatches.all { it.discards.isEmpty() }, "replicating discards nothing at the source")

        val (c0, c1) = children.map { it.getWorkerRef()!! }
        assertEquals(listOf("n0", "n1"), listOf(c0.nodeId, c1.nodeId), "a child lives on its primary's node")
        assertEquals(listOf("a", "on-n1"), evaluator.containersOf(c0), "the child on n0 read its donor from n1")
        assertEquals(listOf("a", "on-n0"), evaluator.containersOf(c1), "the child on n1 read its donor from n0")
        assertTrue(evaluator.results.filter { it.crossoverAttempted }.all { it.status == ResultStatus.SUCCESS && it.crossoverOperator == 0 })

        assertFalse(evaluator.nodes.getValue("n0").hasSolution(p1.solutionId), "n0 dropped its replica")
        assertFalse(evaluator.nodes.getValue("n1").hasSolution(p0.solutionId), "n1 dropped its replica")
        assertTrue(evaluator.nodes.getValue("n0").hasSolution(p0.solutionId), "n0 kept its own parent")
        assertTrue(evaluator.nodes.getValue("n1").hasSolution(p1.solutionId), "n1 kept its own parent")

        assertEquals(setOf(p0, p1, c0, c1), coordinator.getAllKnownRefs(), "replicas are never tracked")
        assertEquals(2, coordinator.getLastReplicatedCount())
        assertEquals(mapOf("n0" to 2, "n1" to 2), coordinator.getMetricsSnapshot().second, "rebalancing counts population members only")
    }

    @Test
    fun `a donor on its primary's node is not replicated`() {
        val evaluator = evaluator(listOf(model(listOf("a"), mapOf("c" to "a"))))
        val coordinator = EvaluationCoordinator(evaluator)
        val parents = List(2) { MoeaSolution(0, 1, 0) }
        coordinator.batchEvaluateAndUpdate(parents)
        coordinator.prepareIteration(Population(parents))
        coordinator.batchEvaluateAndUpdate(DelegatingCrossoverVariation(2, rate = 1.0).evolve(parents.toTypedArray()).toList())

        assertTrue(evaluator.replications.isEmpty())
        assertTrue(evaluator.batches.all { it.replicas.isEmpty() })
    }

    @Test
    fun `a genetic run across nodes replicates, rebalances and leaves no replica behind`() {
        val start = CrossoverFixtures.startModel
        val evaluator = evaluator(listOf(start, start, start), step = MutationStepConfig.Fixed(1))
        val coordinator = EvaluationCoordinator(evaluator)
        val solver = SolverConfig(
            algorithm = AlgorithmType.NSGAII,
            parameters = AlgorithmParameters(
                population = 12,
                variation = VariationType.GENETIC,
                mutation = MutationParameters(step = MutationStepConfig.Fixed(1)),
                crossover = CrossoverParameters(rate = 0.8, parents = 2)
            ),
            termination = TerminationConfig(evolutions = 8)
        )
        val provider = DelegatingAlgorithmProvider(coordinator)
        val algorithm = provider.getAlgorithm(solver.algorithm.name, provider.buildProperties(solver), DelegatingProblem(1, 0))
        algorithm.run(TerminationConditionAdapter(solver).create())

        assertTrue(evaluator.replications.isNotEmpty(), "donors were replicated")
        assertTrue(evaluator.results.filter { it.taskId.isNotEmpty() }.all { it.status == ResultStatus.SUCCESS })
        val known = coordinator.getAllKnownRefs()
        for (batch in evaluator.batches) {
            for (replica in batch.replicas) {
                val ref = WorkerSolutionRef(batch.nodeId, replica)
                if (ref !in known) {
                    assertFalse(evaluator.nodes.getValue(batch.nodeId).hasSolution(replica), "replica $ref was dropped")
                }
            }
        }
        for (ref in known) {
            assertTrue(evaluator.nodes.getValue(ref.nodeId).hasSolution(ref.solutionId), "$ref is where the coordinator says")
        }
    }
}
