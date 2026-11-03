package com.mdeo.optimizer.crossover

import com.mdeo.metamodel.SerializedModel
import com.mdeo.optimizer.OptimizationOrchestrator
import com.mdeo.optimizer.config.AlgorithmParameters
import com.mdeo.optimizer.config.AlgorithmType
import com.mdeo.optimizer.config.CrossoverParameters
import com.mdeo.optimizer.config.CrossoversConfig
import com.mdeo.optimizer.config.GoalConfig
import com.mdeo.optimizer.config.MutationParameters
import com.mdeo.optimizer.config.MutationStepConfig
import com.mdeo.optimizer.config.MutationsConfig
import com.mdeo.optimizer.config.ObjectiveConfig
import com.mdeo.optimizer.config.ObjectiveTendency
import com.mdeo.optimizer.config.OperatorRef
import com.mdeo.optimizer.config.OptimizationConfig
import com.mdeo.optimizer.config.ProblemConfig
import com.mdeo.optimizer.config.SearchConfig
import com.mdeo.optimizer.config.SolverConfig
import com.mdeo.optimizer.config.TerminationConfig
import com.mdeo.optimizer.config.VariationType
import com.mdeo.optimizer.crossover.CrossoverFixtures.COPY_NAMES
import com.mdeo.optimizer.crossover.CrossoverFixtures.MOVE
import com.mdeo.optimizer.crossover.CrossoverFixtures.NEVER
import com.mdeo.optimizer.crossover.CrossoverFixtures.VOTE
import com.mdeo.optimizer.crossover.CrossoverFixtures.asts
import com.mdeo.optimizer.evaluation.EvaluationResult
import com.mdeo.optimizer.evaluation.InitialSolutionResult
import com.mdeo.optimizer.evaluation.LocalMutationEvaluator
import com.mdeo.optimizer.evaluation.MutationEvaluator
import com.mdeo.optimizer.evaluation.NodeBatch
import com.mdeo.optimizer.evaluation.ResultStatus
import com.mdeo.optimizer.evaluation.WorkerSolutionRef
import com.mdeo.optimizer.guidance.GuidanceFunction
import com.mdeo.optimizer.moea.DelegatingAlgorithmProvider
import com.mdeo.optimizer.moea.DelegatingCrossoverVariation
import com.mdeo.optimizer.moea.DelegatingProblem
import com.mdeo.optimizer.moea.EvaluationCoordinator
import com.mdeo.optimizer.moea.QueuedChildrenVariation
import com.mdeo.optimizer.moea.TerminationConditionAdapter
import com.mdeo.optimizer.operators.CrossoverStrategyFactory
import com.mdeo.optimizer.operators.MutationStrategyFactory
import com.mdeo.optimizer.operators.OperatorCatalog
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.moeaframework.algorithm.AbstractEvolutionaryAlgorithm
import org.moeaframework.algorithm.Algorithm
import org.moeaframework.core.Solution as MoeaSolution

/**
 * `variation = genetic` end to end on one node (spec D.3, D.4, D.6): the delegating crossover
 * variation, its wiring per algorithm, the tasks the coordinator dispatches, and the metrics.
 */
class GeneticVariationTest {

    /**
     * Records every batch and its results, and checks during the batch (while parents are still
     * stored) whether each crossover child's model differs from its primary's.
     */
    private class RecordingEvaluator(val inner: LocalMutationEvaluator) : MutationEvaluator by inner {
        val batches = mutableListOf<NodeBatch>()
        val results = mutableListOf<EvaluationResult>()
        val childDiffersFromPrimary = mutableListOf<Boolean>()

        override suspend fun executeNodeBatches(batches: List<NodeBatch>): List<EvaluationResult> {
            this.batches += batches
            val primaries = batches.flatMap { it.crossoverTasks }.associate { it.taskId to modelOf(it.primary.solutionId) }
            val results = inner.executeNodeBatches(batches)
            this.results += results
            for (result in results) {
                val primary = primaries[result.taskId] ?: continue
                if (result.status == ResultStatus.SUCCESS) {
                    childDiffersFromPrimary += modelOf(result.newSolutionId) != primary
                }
            }
            return results
        }

        private suspend fun modelOf(id: String): Pair<List<String>, Map<String, String>> {
            val graph = CrossoverFixtures.graph(inner.getSolutionData(WorkerSolutionRef(LocalMutationEvaluator.DEFAULT_NODE_ID, id)).toModelData(CrossoverFixtures.metamodel))
            return graph.use { CrossoverFixtures.containers(it) to CrossoverFixtures.hosting(it) }
        }

        override suspend fun initialize(count: Int): List<InitialSolutionResult> = inner.initialize(count)
        override suspend fun getSolutionData(ref: WorkerSolutionRef): SerializedModel = inner.getSolutionData(ref)
    }

    private val evaluators = mutableListOf<RecordingEvaluator>()

    /** Two conflicting objectives, see [CrossoverFixtures.TradeOff]. */
    private val noise = listOf(CrossoverFixtures.TradeOff(false), CrossoverFixtures.TradeOff(true))

    @AfterEach
    fun cleanup() = runBlocking { evaluators.forEach { it.inner.cleanup() } }

    private fun solverConfig(
        algorithm: AlgorithmType = AlgorithmType.NSGAII,
        variation: VariationType = VariationType.GENETIC,
        rate: Double = 1.0,
        parents: Int = 2,
        population: Int = 6,
        evolutions: Int = 4,
        step: MutationStepConfig = MutationStepConfig.Fixed(0)
    ) = SolverConfig(
        algorithm = algorithm,
        parameters = AlgorithmParameters(
            population = population,
            variation = variation,
            mutation = MutationParameters(step = step),
            crossover = CrossoverParameters(rate = rate, parents = parents, children = parents)
        ),
        termination = TerminationConfig(evolutions = evolutions)
    )

    private fun evaluator(
        solver: SolverConfig,
        crossovers: List<String> = listOf(if (solver.parameters.crossover.parents == 2) COPY_NAMES else VOTE),
        objectives: List<GuidanceFunction> = listOf(CrossoverFixtures.ComponentsOnA())
    ): RecordingEvaluator {
        val mutations = OperatorCatalog.mutationOperators(listOf(OperatorRef(MOVE)), asts)
        val crossover = solver.parameters.crossover
        val local = LocalMutationEvaluator(
            initialSolutionProvider = { CrossoverFixtures.solution() },
            mutationStrategy = MutationStrategyFactory.create(solver.parameters.mutation, mutations),
            objectives = objectives,
            constraints = emptyList(),
            metamodel = CrossoverFixtures.metamodel,
            crossoverStrategy = CrossoverStrategyFactory.create(
                crossover, OperatorCatalog.crossoverOperators(crossovers.map { OperatorRef(it) }, asts, crossover.parents)
            )
        )
        return RecordingEvaluator(local).also { evaluators += it }
    }

    private fun algorithm(solver: SolverConfig, evaluator: MutationEvaluator): Algorithm {
        val provider = DelegatingAlgorithmProvider(EvaluationCoordinator(evaluator))
        return provider.getAlgorithm(solver.algorithm.name, provider.buildProperties(solver), DelegatingProblem(1, 0))
    }

    private fun run(solver: SolverConfig, evaluator: MutationEvaluator): Algorithm =
        algorithm(solver, evaluator).also { it.run(TerminationConditionAdapter(solver).create()) }

    /** The offspring batches (those with mutation or crossover tasks), each as (mutation tasks, crossover tasks). */
    private fun RecordingEvaluator.generations() = batches
        .filter { it.tasks.isNotEmpty() || it.crossoverTasks.isNotEmpty() }
        .map { it.tasks.size to it.crossoverTasks.size }

    @Test
    fun `mutation dispatches one mutation task per offspring as before`() {
        val solver = solverConfig(variation = VariationType.MUTATION, step = MutationStepConfig.Fixed(1))
        val evaluator = evaluator(solver)
        run(solver, evaluator)

        assertTrue(evaluator.generations().isNotEmpty())
        assertTrue(evaluator.generations().all { it == 6 to 0 }, "${evaluator.generations()}")
        assertTrue(evaluator.batches.all { it.replicas.isEmpty() })
        assertTrue(evaluator.results.none { it.crossoverAttempted })
        assertTrue(evaluator.results.filter { it.taskId.isNotEmpty() }.all { it.status == ResultStatus.SUCCESS })
    }

    @Test
    fun `genetic with rate 0 dispatches exactly what mutation dispatches`() {
        val mutationSolver = solverConfig(variation = VariationType.MUTATION, step = MutationStepConfig.Fixed(1))
        val mutation = evaluator(mutationSolver)
        run(mutationSolver, mutation)

        val geneticSolver = solverConfig(rate = 0.0, step = MutationStepConfig.Fixed(1))
        val genetic = evaluator(geneticSolver)
        run(geneticSolver, genetic)

        assertEquals(mutation.generations(), genetic.generations(), "the same mutation tasks per generation, no crossover task")
        assertTrue(genetic.batches.all { it.replicas.isEmpty() })
        assertTrue(genetic.results.none { it.crossoverAttempted })
        assertEquals(
            mutation.results.count { it.taskId.isNotEmpty() && it.status == ResultStatus.SUCCESS },
            genetic.results.count { it.taskId.isNotEmpty() && it.status == ResultStatus.SUCCESS }
        )
    }

    @Test
    fun `rate 1 makes every child by crossover, one per parent with cyclic donors`() {
        val solver = solverConfig(parents = 3, population = 6)
        val evaluator = evaluator(solver)
        run(solver, evaluator)

        val offspringBatches = evaluator.batches.filter { it.crossoverTasks.isNotEmpty() }
        assertTrue(offspringBatches.isNotEmpty())
        for (batch in offspringBatches) {
            assertTrue(batch.tasks.isEmpty(), "no child of a crossover group is a plain mutation task")
            assertEquals(6, batch.crossoverTasks.size)
            for (group in batch.crossoverTasks.chunked(3)) {
                val primaries = group.map { it.primary }
                for ((i, task) in group.withIndex()) {
                    assertEquals(listOf(primaries[(i + 1) % 3], primaries[(i + 2) % 3]), task.donors,
                        "child $i: the other parents, cyclically from ${i + 1}")
                }
            }
        }
        val crossoverResults = evaluator.results.filter { it.crossoverAttempted }
        assertTrue(crossoverResults.all { it.status == ResultStatus.SUCCESS && it.crossoverOperator == 0 })
        assertTrue(evaluator.childDiffersFromPrimary.isNotEmpty() && evaluator.childDiffersFromPrimary.all { it },
            "vote applied: every child differs from its primary")
    }

    @Test
    fun `with no applicable crossover every child falls back to a copy`() {
        val solver = solverConfig()
        val evaluator = evaluator(solver, crossovers = listOf(NEVER))
        run(solver, evaluator)

        val crossoverResults = evaluator.results.filter { it.crossoverAttempted }
        assertTrue(crossoverResults.isNotEmpty())
        assertTrue(crossoverResults.all { it.status == ResultStatus.SUCCESS && it.crossoverOperator == null })
        assertTrue(evaluator.childDiffersFromPrimary.none { it }, "with step 0 every child equals its primary")
    }

    /**
     * Every algorithm but PAES takes `parents` children per group without crashing or changing
     * its population size, and evaluates every child it inserts.
     */
    @ParameterizedTest
    @CsvSource(
        "NSGAII,2", "NSGAII,3", "SPEA2,2", "SPEA2,3", "IBEA,2", "IBEA,3",
        "SMSMOEA,2", "SMSMOEA,3", "VEGA,2", "VEGA,3", "PESA2,2", "PESA2,3"
    )
    fun `every algorithm takes the children of every group`(algorithm: AlgorithmType, parents: Int) {
        val population = 7
        val solver = solverConfig(algorithm = algorithm, parents = parents, population = population, evolutions = 4)
            .let { if (algorithm == AlgorithmType.PESA2) it.copy(parameters = it.parameters.copy(bisections = 4, archiveSize = 20)) else it }
        val evaluator = evaluator(solver, objectives = noise)
        val provider = DelegatingAlgorithmProvider(EvaluationCoordinator(evaluator))
        val moea = provider.getAlgorithm(algorithm.name, provider.buildProperties(solver), DelegatingProblem(2, 0)) as AbstractEvolutionaryAlgorithm
        moea.run(TerminationConditionAdapter(solver).create())

        val offspring = evaluator.batches.flatMap { it.crossoverTasks }
        assertTrue(offspring.isNotEmpty())
        assertTrue(evaluator.results.filter { it.crossoverAttempted }.all { it.status == ResultStatus.SUCCESS })
        when (algorithm) {
            AlgorithmType.PESA2 -> assertTrue(moea.population.size() >= population)
            else -> assertEquals(population, moea.population.size(), "the population size is unchanged")
        }
        when (algorithm) {
            // VEGA fills its population child by child and drops the rest of the last group unevaluated.
            AlgorithmType.VEGA -> assertTrue(evaluator.batches.filter { it.crossoverTasks.isNotEmpty() }.all { it.crossoverTasks.size == population })
            // SMS-EMOA evaluates a whole group at once and inserts one child per step.
            AlgorithmType.SMSMOEA -> assertTrue(evaluator.batches.filter { it.crossoverTasks.isNotEmpty() }.all { it.crossoverTasks.size == parents })
            else -> assertTrue(evaluator.batches.filter { it.crossoverTasks.isNotEmpty() }.all { it.crossoverTasks.size % parents == 0 },
                "every group's children are all evaluated")
        }
    }

    @Test
    fun `SMS-EMOA inserts every child of a group, one per step`() {
        val solver = solverConfig(algorithm = AlgorithmType.SMSMOEA, parents = 3, population = 5)
        val evaluator = evaluator(solver, objectives = noise)
        val provider = DelegatingAlgorithmProvider(EvaluationCoordinator(evaluator))
        val moea = provider.getAlgorithm(AlgorithmType.SMSMOEA.name, provider.buildProperties(solver), DelegatingProblem(2, 0)) as AbstractEvolutionaryAlgorithm
        val variation = moea.variation as QueuedChildrenVariation

        moea.step() // initialization
        val inserted = mutableListOf<MoeaSolution>()
        repeat(6) {
            val before = moea.population.toList()
            moea.step()
            assertEquals(5, moea.population.size())
            inserted += moea.population.filter { candidate -> before.none { it === candidate } }
        }

        val evaluatedChildren = evaluator.batches.sumOf { it.crossoverTasks.size }
        assertEquals(6, evaluatedChildren, "two groups of three children were evaluated, one batch each")
        assertEquals(2, evaluator.batches.count { it.crossoverTasks.isNotEmpty() })
        assertTrue(variation.pending.isEmpty(), "every child of both groups was handed to a step")
        assertEquals(6, moea.numberOfEvaluations - 5, "each child counted once")
        assertTrue(inserted.size <= 6)
    }

    @Test
    fun `PAES rejects genetic`() {
        val solver = solverConfig(algorithm = AlgorithmType.PAES).let {
            it.copy(parameters = it.parameters.copy(bisections = 4, archiveSize = 20))
        }
        val error = assertThrows<IllegalArgumentException> { algorithm(solver, evaluator(solver)) }
        assertTrue("PAES" in error.message!! && "mutation" in error.message!!, error.message)
    }

    @Test
    fun `the variation yields one child per parent with its group's decision`() {
        val parents = (0 until 4).map { i ->
            MoeaSolution(0, 1, 0).also { it.setAttribute(WorkerSolutionRef.ATTRIBUTE_KEY, WorkerSolutionRef("n", "p$i")) }
        }
        val attempting = DelegatingCrossoverVariation(4, rate = 0.5, random = { 0.25 }).evolve(parents.toTypedArray())
        val copying = DelegatingCrossoverVariation(4, rate = 0.5, random = { 0.75 }).evolve(parents.toTypedArray())

        assertEquals(4, attempting.size)
        assertTrue(attempting.all { it.getAttribute(DelegatingCrossoverVariation.ATTEMPT_CROSSOVER_KEY) == true })
        assertTrue(copying.all { it.getAttribute(DelegatingCrossoverVariation.ATTEMPT_CROSSOVER_KEY) == false })
        assertEquals(WorkerSolutionRef("n", "p2"), attempting[2].getAttribute("parentSolutionRef"))
        assertEquals(listOf("p3", "p0", "p1"), (attempting[2].getAttribute(DelegatingCrossoverVariation.DONOR_REFS_KEY) as List<*>).map { (it as WorkerSolutionRef).solutionId })
        assertTrue(attempting.all { it.getAttribute("needsMutation") == true })
        assertTrue(DelegatingCrossoverVariation(2, rate = 0.0, random = { 0.0 }).evolve(parents.take(2).toTypedArray())
            .none { it.getAttribute(DelegatingCrossoverVariation.ATTEMPT_CROSSOVER_KEY) == true }, "rate 0 never attempts")
    }

    @Test
    fun `crossover metrics are recorded per generation`() {
        val solver = solverConfig(population = 4, evolutions = 3)
        val config = OptimizationConfig(
            problem = ProblemConfig("/mm", "/model"),
            goal = GoalConfig(listOf(ObjectiveConfig(ObjectiveTendency.MINIMIZE, "/f", "f")), emptyList()),
            search = SearchConfig(
                mutations = MutationsConfig(listOf(OperatorRef(MOVE))),
                crossovers = CrossoversConfig(listOf(OperatorRef(COPY_NAMES)))
            ),
            solver = solver
        )
        val evaluator = evaluator(solver)
        val result = runBlocking { OptimizationOrchestrator(config, evaluator).run() }.single()

        val generations = result.getMetrics().generations
        val attempted = generations.sumOf { it.crossover.attempted }
        assertEquals(evaluator.results.count { it.crossoverAttempted }, attempted)
        assertTrue(attempted > 0)
        assertEquals(attempted, generations.sumOf { it.crossover.succeeded })
        assertEquals(0, generations.sumOf { it.crossover.fellBack })
        assertEquals(attempted, generations.sumOf { it.crossover.perOperator[0] ?: 0 })
        assertEquals(0, generations.first().crossover.attempted, "the initial population is not made by crossover")
    }
}
