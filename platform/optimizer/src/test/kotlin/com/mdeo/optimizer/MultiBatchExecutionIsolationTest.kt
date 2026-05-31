package com.mdeo.optimizer

import com.mdeo.metamodel.SerializedModel
import com.mdeo.optimizer.config.AlgorithmParameters
import com.mdeo.optimizer.config.AlgorithmType
import com.mdeo.optimizer.config.GoalConfig
import com.mdeo.optimizer.config.MutationParameters
import com.mdeo.optimizer.config.MutationStepConfig
import com.mdeo.optimizer.config.MutationStrategy
import com.mdeo.optimizer.config.ObjectiveConfig
import com.mdeo.optimizer.config.ObjectiveTendency
import com.mdeo.optimizer.config.OptimizationConfig
import com.mdeo.optimizer.config.ProblemConfig
import com.mdeo.optimizer.config.RuntimeConfig
import com.mdeo.optimizer.config.SearchConfig
import com.mdeo.optimizer.config.SolverConfig
import com.mdeo.optimizer.config.SolverProvider
import com.mdeo.optimizer.config.TerminationConfig
import com.mdeo.optimizer.config.VariationType
import com.mdeo.optimizer.evaluation.EvaluationResult
import com.mdeo.optimizer.evaluation.EvaluationTask
import com.mdeo.optimizer.evaluation.InitialSolutionResult
import com.mdeo.optimizer.evaluation.MutationEvaluator
import com.mdeo.optimizer.evaluation.MutationTask
import com.mdeo.optimizer.evaluation.NodeBatch
import com.mdeo.optimizer.evaluation.ResultStatus
import com.mdeo.optimizer.evaluation.WorkerSolutionRef
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/**
 * End-to-end regression test for multi-batch execution isolation.
 *
 * The custom evaluator enforces strict discard semantics:
 * discarding or mutating a non-existing solution throws an error with the same
 * shape as the production failure ("solution ... not found on node ...").
 *
 * This verifies that the orchestrator resets both evaluator and coordinator state
 * between batches and does not send stale solution IDs from a previous batch.
 */
class MultiBatchExecutionIsolationTest {

    private class StrictStatefulEvaluator(
        private val nodeId: String = "0"
    ) : MutationEvaluator {
        private val solutions = linkedMapOf<String, Double>()
        private var idCounter = 0
        var resetInvocations: Int = 0
            private set

        override suspend fun initialize(count: Int): List<InitialSolutionResult> {
            return (0 until count).map {
                val id = nextId()
                solutions[id] = 0.0
                InitialSolutionResult(solutionId = id, workerNodeId = nodeId)
            }
        }

        override suspend fun executeNodeBatches(batches: List<NodeBatch>): List<EvaluationResult> {
            val batch = batches.firstOrNull { it.nodeId == nodeId } ?: return emptyList()
            val results = mutableListOf<EvaluationResult>()

            for (task in batch.tasks) {
                val parentValue = solutions[task.solutionId]
                    ?: throw IllegalStateException("solution ${task.solutionId} not found on node $nodeId")
                val newId = nextId()
                val childValue = parentValue + 1.0
                solutions[newId] = childValue
                results += EvaluationResult(
                    parentSolutionId = task.solutionId,
                    newSolutionId = newId,
                    workerNodeId = nodeId,
                    objectives = listOf(childValue),
                    constraints = emptyList(),
                    status = ResultStatus.SUCCESS
                )
            }

            for (task in batch.evaluationTasks) {
                val value = solutions[task.solutionId]
                    ?: throw IllegalStateException("solution ${task.solutionId} not found on node $nodeId")
                results += EvaluationResult(
                    parentSolutionId = task.solutionId,
                    newSolutionId = task.solutionId,
                    workerNodeId = nodeId,
                    objectives = listOf(value),
                    constraints = emptyList(),
                    status = ResultStatus.SUCCESS
                )
            }

            for (solutionId in batch.discards) {
                val removed = solutions.remove(solutionId)
                if (removed == null) {
                    throw IllegalStateException("solution $solutionId not found on node $nodeId")
                }
            }

            return results
        }

        override suspend fun getSolutionData(ref: WorkerSolutionRef): SerializedModel {
            error("getSolutionData is not used by this test")
        }

        override suspend fun resetBatch() {
            resetInvocations++
            solutions.clear()
            idCounter = 0
        }

        override suspend fun cleanup() {
            solutions.clear()
        }

        override fun getNodeIds(): Set<String> = setOf(nodeId)

        private fun nextId(): String {
            val next = "s$idCounter"
            idCounter++
            return next
        }
    }

    @Test
    fun `multi-batch run resets evaluator and coordinator state`() {
        val config = OptimizationConfig(
            problem = ProblemConfig(
                metamodelPath = "/dummy/mm",
                modelPath = "/dummy/model"
            ),
            goal = GoalConfig(
                objectives = listOf(
                    ObjectiveConfig(
                        type = ObjectiveTendency.MINIMIZE,
                        path = "/dummy/goal",
                        functionName = "objective"
                    )
                ),
                constraints = emptyList()
            ),
            search = SearchConfig(
                mutations = com.mdeo.optimizer.config.MutationsConfig(
                    usingPaths = listOf("/dummy/mutation")
                )
            ),
            solver = SolverConfig(
                provider = SolverProvider.MOEA,
                algorithm = AlgorithmType.NSGAII,
                parameters = AlgorithmParameters(
                    population = 10,
                    variation = VariationType.MUTATION,
                    mutation = MutationParameters(
                        step = MutationStepConfig.Fixed(1),
                        strategy = MutationStrategy.RANDOM
                    )
                ),
                termination = TerminationConfig(evolutions = 20),
                batches = 2
            ),
            runtime = RuntimeConfig()
        )

        val evaluator = StrictStatefulEvaluator()
        val orchestrator = OptimizationOrchestrator(config = config, evaluator = evaluator)

        val results = runBlocking { orchestrator.run() }

        assertEquals(2, results.size, "Expected one SearchResult per batch")
        assertEquals(1, evaluator.resetInvocations, "Expected resetBatch between the 2 configured batches")
        assertFalse(results.any { it.getFinalSolutions().isEmpty() }, "Each batch should produce solutions")

        runBlocking { evaluator.cleanup() }
    }
}
