package com.mdeo.optimizer.moea

import com.mdeo.metamodel.Metamodel
import com.mdeo.metamodel.data.MetamodelData
import com.mdeo.metamodel.data.ModelData
import com.mdeo.optimizer.config.*
import com.mdeo.modeltransformation.graph.tinker.TinkerModelGraph
import com.mdeo.optimizer.evaluation.LocalMutationEvaluator
import com.mdeo.optimizer.guidance.GuidanceFunction
import com.mdeo.optimizer.operators.MutationStrategy
import com.mdeo.optimizer.solution.Solution
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/**
 * Tests for the unified delegating optimization path
 * (EvaluationCoordinator + DelegatingAlgorithmProvider + LocalMutationEvaluator).
 *
 * These tests verify that the evolutionary search executes correctly and produces valid results
 * using the same code path as both local and federated execution.
 */
class MoeaOptimizationTest {

    private val metamodel = Metamodel.compile(MetamodelData())

    private class VertexCountObjective : GuidanceFunction {
        override val name = "VertexCount"
        override fun computeFitness(solution: Solution): Double {
            return solution.modelGraph.traversal().V().count().next().toDouble()
        }
    }

    private class ConstantObjective(private val value: Double) : GuidanceFunction {
        override val name = "Constant"
        override fun computeFitness(solution: Solution): Double = value
    }

    private class IdentityMutationStrategy : MutationStrategy {
        override fun mutate(solution: Solution): Solution = solution.deepCopy()
    }

    private class AddVertexMutationStrategy : MutationStrategy {
        override fun mutate(solution: Solution): Solution {
            val copy = solution.deepCopy()
            copy.modelGraph.traversal().addV("node").next()
            return copy
        }
    }

    private fun createInitialSolution(): Solution {
        val modelGraph = TinkerModelGraph.create(
            ModelData(metamodelPath = "", instances = emptyList(), links = emptyList()),
            metamodel
        )
        modelGraph.traversal().addV("root").property("name", "initial").next()
        return Solution(modelGraph)
    }

    private fun runOptimization(
        algorithmType: AlgorithmType = AlgorithmType.NSGAII,
        objectives: List<GuidanceFunction> = listOf(VertexCountObjective()),
        constraints: List<GuidanceFunction> = emptyList(),
        mutationStrategy: MutationStrategy = AddVertexMutationStrategy(),
        populationSize: Int = 10,
        evolutions: Int = 5
    ): SearchResult {
        val evaluator = LocalMutationEvaluator(
            initialSolutionProvider = { createInitialSolution() },
            mutationStrategy = mutationStrategy,
            objectives = objectives,
            constraints = constraints,
            metamodel = metamodel
        )

        val coordinator = EvaluationCoordinator(evaluator)
        val problem = DelegatingProblem(objectives.size, constraints.size)
        val provider = DelegatingAlgorithmProvider(coordinator)

        val solverConfig = SolverConfig(
            provider = SolverProvider.MOEA,
            algorithm = algorithmType,
            parameters = AlgorithmParameters(population = populationSize),
            termination = TerminationConfig(evolutions = evolutions),
            batches = 1
        )
        val properties = provider.buildProperties(solverConfig)
        val algorithm = provider.getAlgorithm(algorithmType.name, properties, problem)
        val terminationCondition = TerminationConditionAdapter(solverConfig).create()

        try {
            runBlocking {
                withContext(Dispatchers.IO) {
                    algorithm.run(terminationCondition)
                }
            }
            return SearchResult(
                org.moeaframework.analysis.series.ResultSeries(org.moeaframework.analysis.series.IndexType.NFE),
                algorithm.result
            )
        } finally {
            runBlocking { evaluator.cleanup() }
        }
    }

    @Test
    fun `execute completes without errors`() {
        val result = runOptimization()
        assertNotNull(result)
        assertFalse(result.getFinalSolutions().isEmpty())
    }

    @Test
    fun `execute returns final solutions`() {
        val result = runOptimization()
        val solutions = result.getFinalSolutions()

        assertFalse(solutions.isEmpty(), "Expected at least one solution in the result")
        solutions.forEach { sol ->
            assertEquals(1, sol.objectives.size, "Expected one objective value per solution")
        }
    }

    @Test
    fun `execute with identity mutation does not crash`() {
        val result = runOptimization(mutationStrategy = IdentityMutationStrategy())
        assertNotNull(result)
    }

    @Test
    fun `execute with multiple objectives works`() {
        val objectives = listOf(
            VertexCountObjective(),
            ConstantObjective(42.0)
        )
        val result = runOptimization(objectives = objectives)
        val solutions = result.getFinalSolutions()

        assertFalse(solutions.isEmpty())
        solutions.forEach { sol ->
            assertEquals(2, sol.objectives.size, "Expected two objective values per solution")
        }
    }

    @Test
    fun `execute with constraints works`() {
        val constraints = listOf(ConstantObjective(0.0))
        val result = runOptimization(constraints = constraints)
        val solutions = result.getFinalSolutions()

        assertFalse(solutions.isEmpty())
        solutions.forEach { sol ->
            assertEquals(1, sol.constraints.size, "Expected one constraint value")
        }
    }

    @ParameterizedTest
    @EnumSource(value = AlgorithmType::class, names = ["NSGAII", "SPEA2"])
    fun `execute works with different algorithms`(algorithmType: AlgorithmType) {
        val result = runOptimization(
            algorithmType = algorithmType,
            populationSize = 10,
            evolutions = 3
        )
        assertNotNull(result)
        assertFalse(result.getFinalSolutions().isEmpty())
    }
}
