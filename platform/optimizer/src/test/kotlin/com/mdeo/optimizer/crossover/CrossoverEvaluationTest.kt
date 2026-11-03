package com.mdeo.optimizer.crossover

import com.mdeo.metamodel.data.ModelData
import com.mdeo.optimizer.config.MutationParameters
import com.mdeo.optimizer.config.MutationStepConfig
import com.mdeo.optimizer.config.OperatorRef
import com.mdeo.optimizer.crossover.CrossoverFixtures.COPY_NAMES
import com.mdeo.optimizer.crossover.CrossoverFixtures.MOVE
import com.mdeo.optimizer.crossover.CrossoverFixtures.NEVER
import com.mdeo.optimizer.crossover.CrossoverFixtures.TAKE_HOSTING
import com.mdeo.optimizer.crossover.CrossoverFixtures.VOTE
import com.mdeo.optimizer.crossover.CrossoverFixtures.asts
import com.mdeo.optimizer.crossover.CrossoverFixtures.containers
import com.mdeo.optimizer.crossover.CrossoverFixtures.hosting
import com.mdeo.optimizer.crossover.CrossoverFixtures.model
import com.mdeo.optimizer.evaluation.CrossoverTask
import com.mdeo.optimizer.evaluation.EvaluationResult
import com.mdeo.optimizer.evaluation.LocalMutationEvaluator
import com.mdeo.optimizer.evaluation.NodeBatch
import com.mdeo.optimizer.evaluation.ResultStatus
import com.mdeo.optimizer.evaluation.WorkerSolutionRef
import com.mdeo.modeltransformation.ast.TransformationOperator
import com.mdeo.optimizer.operators.CrossoverOperator
import com.mdeo.optimizer.operators.CrossoverOutcome
import com.mdeo.optimizer.operators.MutationStrategyFactory
import com.mdeo.optimizer.operators.OperatorCatalog
import com.mdeo.optimizer.operators.RandomCrossoverStrategy
import com.mdeo.optimizer.solution.FailedOperatorsMetadata
import com.mdeo.optimizer.solution.Solution
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Crossover tasks on a [LocalMutationEvaluator] (spec D.3, D.5): the child starts as a copy of
 * its primary parent, takes from its donors through the crossover operator, falls back to a copy
 * when no operator applies, and is then mutated and evaluated like any offspring. Crossover
 * operators never enter the deterministic-failure record.
 *
 * Mutation runs with `step = fixed(0)` unless stated, so that a child shows the crossover alone.
 */
class CrossoverEvaluationTest {

    private val evaluators = mutableListOf<LocalMutationEvaluator>()

    @AfterEach
    fun cleanup() = runBlocking { evaluators.forEach { it.cleanup() } }

    private fun evaluator(
        crossovers: List<String>,
        step: MutationStepConfig = MutationStepConfig.Fixed(0),
        parents: Int = 2
    ): LocalMutationEvaluator {
        val mutations = OperatorCatalog.mutationOperators(listOf(OperatorRef(MOVE)), asts)
        return LocalMutationEvaluator(
            initialSolutionProvider = { CrossoverFixtures.solution() },
            mutationStrategy = MutationStrategyFactory.create(MutationParameters(step = step), mutations),
            objectives = listOf(CrossoverFixtures.ComponentsOnA()),
            constraints = emptyList(),
            metamodel = CrossoverFixtures.metamodel,
            crossoverStrategy = RandomCrossoverStrategy(
                OperatorCatalog.crossoverOperators(crossovers.map { OperatorRef(it) }, asts, parents)
            )
        ).also { evaluators += it }
    }

    private fun LocalMutationEvaluator.store(id: String, data: ModelData) =
        receiveSolution(id, CrossoverFixtures.graph(data).use { it.toSerializedModel() })

    private fun LocalMutationEvaluator.run(task: CrossoverTask): EvaluationResult = runBlocking {
        executeNodeBatches(listOf(NodeBatch(LocalMutationEvaluator.DEFAULT_NODE_ID, emptyList(), discards = emptyList(), crossoverTasks = listOf(task))))
    }.single()

    private fun ref(id: String) = WorkerSolutionRef(LocalMutationEvaluator.DEFAULT_NODE_ID, id)

    private fun task(primary: String, vararg donors: String, attempt: Boolean = true) =
        CrossoverTask("task-$primary", ref(primary), donors.map { ref(it) }, attempt)

    private val allOnA = model(listOf("a", "b"), (1..4).associate { "c$it" to "a" })
    private val c1OnB = model(listOf("a", "b"), (1..4).associate { "c$it" to if (it == 1) "b" else "a" })

    @Test
    fun `a child takes from its donor and neither parent changes`() {
        val ev = evaluator(listOf(TAKE_HOSTING))
        ev.store("p", allOnA)
        ev.store("d", c1OnB)

        val result = ev.run(task("p", "d"))

        assertEquals(ResultStatus.SUCCESS, result.status)
        assertEquals("task-p", result.taskId)
        assertTrue(result.crossoverAttempted)
        assertEquals(0, result.crossoverOperator)
        assertEquals("b", hosting(ev.solutionGraph(result.newSolutionId))["c1"], "c1 took the donor's hosting")
        assertEquals(hosting(CrossoverFixtures.graph(allOnA)), hosting(ev.solutionGraph("p")), "the primary is unchanged")
        assertEquals(hosting(CrossoverFixtures.graph(c1OnB)), hosting(ev.solutionGraph("d")), "the donor is unchanged")
        assertEquals(listOf("a", "b"), containers(ev.solutionGraph(result.newSolutionId)), "no donor object entered the child")
    }

    @Test
    fun `a task that attempts no crossover is a mutation of its primary`() {
        val ev = evaluator(listOf(COPY_NAMES), step = MutationStepConfig.Fixed(1))
        ev.store("p", allOnA)
        ev.store("d", c1OnB)

        val result = ev.run(task("p", "d", attempt = false))

        assertEquals(ResultStatus.SUCCESS, result.status)
        assertFalse(result.crossoverAttempted)
        assertNull(result.crossoverOperator)
        assertEquals(1, result.executedTransformations, "the move mutation ran")
        assertEquals(listOf("a", "b"), containers(ev.solutionGraph(result.newSolutionId)), "copy-names did not run")
        assertEquals(1, hosting(ev.solutionGraph(result.newSolutionId)).values.count { it == "b" }, "one component moved")
    }

    @Test
    fun `when no crossover operator applies the child is a copy of its primary`() {
        val ev = evaluator(listOf(NEVER))
        ev.store("p", c1OnB)
        ev.store("d", allOnA)

        val result = ev.run(task("p", "d"))

        assertEquals(ResultStatus.SUCCESS, result.status)
        assertTrue(result.crossoverAttempted)
        assertNull(result.crossoverOperator, "fell back to a copy")
        assertEquals(hosting(CrossoverFixtures.graph(c1OnB)), hosting(ev.solutionGraph(result.newSolutionId)))
        assertEquals(listOf("a", "b"), containers(ev.solutionGraph(result.newSolutionId)))
    }

    @Test
    fun `a failing crossover operator is followed by an untried one`() {
        val ev = evaluator(listOf(NEVER, COPY_NAMES))
        ev.store("p", allOnA)
        ev.store("d", c1OnB)

        repeat(12) {
            val result = ev.run(task("p", "d").copy(taskId = "t$it"))
            assertEquals(0, result.crossoverOperator, "copy-names (id 0) applied whatever was tried first")
            assertEquals(3, containers(ev.solutionGraph(result.newSolutionId)).size)
        }
    }

    @Test
    fun `crossover operators never enter the failed-operator record`() {
        val ev = evaluator(listOf(NEVER, TAKE_HOSTING))
        ev.store("p", allOnA)
        ev.store("d", allOnA)

        // take-hosting finds nothing to take from an identical donor, never.mt never applies:
        // both fail deterministically, and neither may be recorded as a failure of the parent.
        repeat(4) {
            val result = ev.run(task("p", "d").copy(taskId = "t$it"))
            assertNull(result.crossoverOperator)
            assertTrue(ev.getSolutionFailedOperators(result.newSolutionId).isEmpty())
        }
        assertTrue(ev.getSolutionFailedOperators("p").isEmpty(), "no crossover id in the primary's record")
        assertTrue(ev.getSolutionFailedOperators("d").isEmpty())
    }

    @Test
    fun `a child to which a crossover applied stops sharing its primary's failed-operator record`() {
        val strategy = RandomCrossoverStrategy(OperatorCatalog.crossoverOperators(listOf(OperatorRef(COPY_NAMES)), asts, 2))
        CrossoverFixtures.solution(allOnA).use { primary ->
            CrossoverFixtures.solution(c1OnB).use { donor ->
                val record = FailedOperatorsMetadata().also { it.failedDeterministicOperators.add(0) }
                primary.modelGraph.metadata = record
                val child = Solution(primary.modelGraph.deepCopy())
                assertSame(record, child.modelGraph.metadata, "a copy shares its parent's record")

                val outcome = strategy.crossover(child, primary, listOf(donor))

                assertEquals(0, outcome.appliedOperator)
                assertNotSame(record, child.modelGraph.metadata, "the changed child tracks its own failures")
                assertEquals(setOf(0), record.failedDeterministicOperators, "the primary's record is untouched")
                child.close()
            }
        }
    }

    @Test
    fun `a variadic operator receives all donors in order`() {
        val ev = evaluator(listOf(VOTE), parents = 3)
        ev.store("p", allOnA)
        ev.store("d1", model(listOf("a"), mapOf("x1" to "a")))
        ev.store("d2", model(listOf("a"), mapOf("x2" to "a")))

        val result = ev.run(task("p", "d1", "d2"))

        assertEquals(0, result.crossoverOperator)
        assertEquals(listOf("a", "b", "x1", "x2"), containers(ev.solutionGraph(result.newSolutionId)))
    }

    @Test
    fun `operators that do not take the group's parent count are not tried`() {
        val operators = listOf(TAKE_HOSTING, VOTE).mapIndexed { id, path ->
            CrossoverOperator(TransformationOperator(id, asts.getValue(path)))
        }
        val strategy = RandomCrossoverStrategy(operators)
        CrossoverFixtures.solution(allOnA).use { primary ->
            val donors = listOf(
                CrossoverFixtures.solution(model(listOf("b"), mapOf("c1" to "b"))),
                CrossoverFixtures.solution(model(listOf("a"), mapOf("x2" to "a")))
            )
            repeat(5) {
                val child = Solution(primary.modelGraph.deepCopy())
                val outcome = strategy.crossover(child, primary, donors)
                assertEquals(CrossoverOutcome(1, 1), outcome, "only vote.mt takes three parents, and only it was run")
                child.close()
            }
            donors.forEach { it.close() }
        }
    }

    private fun LocalMutationEvaluator.solutionGraph(id: String) = CrossoverFixtures.graph(solutionModelData(id))

    private fun LocalMutationEvaluator.solutionModelData(id: String): ModelData =
        runBlocking { getSolutionData(ref(id)) }.toModelData(CrossoverFixtures.metamodel)
}
