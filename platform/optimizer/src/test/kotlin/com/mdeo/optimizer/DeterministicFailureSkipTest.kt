package com.mdeo.optimizer

import com.mdeo.expression.ast.types.ClassTypeRef
import com.mdeo.expression.ast.types.ReturnType
import com.mdeo.expression.ast.types.VoidType
import com.mdeo.metamodel.Metamodel
import com.mdeo.metamodel.data.*
import com.mdeo.modeltransformation.ast.TypedAst as TransformationTypedAst
import com.mdeo.modeltransformation.ast.patterns.TypedPattern
import com.mdeo.modeltransformation.ast.patterns.TypedPatternObjectInstance
import com.mdeo.modeltransformation.ast.patterns.TypedPatternObjectInstanceElement
import com.mdeo.modeltransformation.ast.statements.TypedMatchStatement
import com.mdeo.modeltransformation.graph.mdeo.MdeoModelGraph
import com.mdeo.optimizer.OptimizationOrchestrator
import com.mdeo.optimizer.config.*
import com.mdeo.optimizer.evaluation.LocalMutationEvaluator
import com.mdeo.optimizer.guidance.GuidanceFunction
import com.mdeo.optimizer.operators.MutationStrategyFactory
import com.mdeo.optimizer.operators.TransformationAttemptResult
import com.mdeo.optimizer.operators.TransformationAttemptRunner
import com.mdeo.optimizer.solution.Solution
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Verifies that deterministic failure tracking actually skips operators once the model
 * state makes them impossible.
 *
 * Setup:
 * - Single class "Node" with no associations.
 * - Single transformation "deleteNode": matches any Node and deletes it (no links involved).
 * - Initial model: 10 Node instances.
 * - After 10 successful applications the graph is empty and deleteNode can never succeed again.
 *
 * Expected behaviour:
 * - The first 10 applications succeed, each removing one Node.
 * - The 11th application returns DeterministicFailure (first-match failure, no graph change).
 * - After that failure is recorded in Solution.failedDeterministicOperators, any further
 *   call to the mutation strategy should skip deleteNode entirely rather than attempting it.
 *
 * This test also runs a short end-to-end optimization (50 evolutions) and asserts that:
 * 1. The skip stats show > 0 skips (the optimization actually benefited from the feature).
 * 2. The optimizer completes without error.
 */
class DeterministicFailureSkipTest {

    // ──────────────────────────────────────────────────────────────────────────
    // Metamodel: single class "Node", no associations
    // ──────────────────────────────────────────────────────────────────────────

    private fun buildMetamodelData() = MetamodelData(
        path = "/metamodel.mm",
        classes = listOf(ClassData(name = "Node", isAbstract = false)),
        enums = emptyList(),
        associations = emptyList(),
        importedMetamodelPaths = emptyList()
    )

    // ──────────────────────────────────────────────────────────────────────────
    // Model: 10 Node instances
    // ──────────────────────────────────────────────────────────────────────────

    private fun buildModelData(nodeCount: Int = 10) = ModelData(
        metamodelPath = "../metamodel.mm",
        instances = (0 until nodeCount).map { i ->
            ModelDataInstance(name = "node$i", className = "Node", properties = emptyMap())
        },
        links = emptyList()
    )

    // ──────────────────────────────────────────────────────────────────────────
    // Transformation: match any Node and delete it
    // ──────────────────────────────────────────────────────────────────────────

    private fun buildTypes(): List<ReturnType> = listOf(
        VoidType(),
        ClassTypeRef(`package` = "builtin", type = "string", isNullable = false, typeArgs = emptyMap()),
        ClassTypeRef(`package` = "builtin", type = "double", isNullable = false, typeArgs = emptyMap()),
        ClassTypeRef(`package` = "builtin", type = "boolean", isNullable = false, typeArgs = emptyMap()),
        ClassTypeRef(`package` = "builtin", type = "Any", isNullable = true, typeArgs = emptyMap())
    )

    private fun buildDeleteNodeAst() = TransformationTypedAst(
        types = buildTypes(),
        metamodelPath = "/metamodel.mm",
        statements = listOf(
            TypedMatchStatement(
                pattern = TypedPattern(
                    elements = listOf(
                        TypedPatternObjectInstanceElement(
                            objectInstance = TypedPatternObjectInstance(
                                modifier = "delete",
                                name = "n",
                                className = "Node",
                                properties = emptyList()
                            )
                        )
                    )
                )
            )
        )
    )

    private fun buildTransformations() = mapOf(
        "/transformation/deleteNode.mt" to buildDeleteNodeAst()
    )

    // ──────────────────────────────────────────────────────────────────────────
    // Test 1: TransformationAttemptRunner directly — verify DeterministicFailure
    //         is returned after all nodes are consumed.
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `deleteNode returns DeterministicFailure when graph is empty`() {
        val metamodel = Metamodel.compile(buildMetamodelData())
        val transformations = buildTransformations()
        val runner = TransformationAttemptRunner(transformations)

        val solution = Solution(MdeoModelGraph.create(buildModelData(10), metamodel))

        // Delete all 10 nodes — every application must succeed.
        repeat(10) { i ->
            val result = runner.tryApply(solution, "/transformation/deleteNode.mt")
            assertTrue(result.isApplied, "Expected Applied on iteration ${i + 1}, got $result")
        }

        // Graph is now empty — next attempt must be DeterministicFailure.
        val failResult = runner.tryApply(solution, "/transformation/deleteNode.mt")
        assertEquals(
            TransformationAttemptResult.DeterministicFailure,
            failResult,
            "Expected DeterministicFailure on empty graph, got $failResult"
        )

        solution.close()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 2: Mutation strategy records the failure in failedDeterministicOperators
    //         so that subsequent mutation() calls skip deleteNode immediately.
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `mutation strategy skips deleteNode after deterministic failure recorded`() {
        val metamodel = Metamodel.compile(buildMetamodelData())
        val transformations = buildTransformations()

        // Build a strategy with a single operator.
        val mutationConfig = MutationParameters(
            step = MutationStepConfig.Fixed(n = 1),
            strategy = MutationStrategy.RANDOM
        )
        val strategy = MutationStrategyFactory.create(mutationConfig, transformations)

        // Solution with exactly 1 node — first mutation removes it.
        val solution = Solution(MdeoModelGraph.create(buildModelData(1), metamodel))

        val afterFirst = strategy.mutate(solution)
        // The single node was removed; the chain should record deleteNode was applied.
        assertTrue(
            afterFirst.transformationsChain.last().isNotEmpty(),
            "Expected deleteNode to be applied on first mutation"
        )
        // failedDeterministicOperators should be empty (success clears it).
        assertTrue(
            afterFirst.failedDeterministicOperators.isEmpty(),
            "failedDeterministicOperators must be empty after a successful mutation"
        )

        // Now trigger a failure: mutate again on the empty graph.
        // The deep-copy in evaluateSingle would normally carry forward the empty set,
        // so we mimic that directly here.
        val copy = afterFirst.deepCopy()
        val afterSecond = strategy.mutate(copy)

        // deleteNode should have failed deterministically and been recorded.
        assertTrue(
            afterSecond.failedDeterministicOperators.isNotEmpty(),
            "Expected deleteNode index to be recorded in failedDeterministicOperators after empty-graph failure"
        )

        // A third mutation on the same (empty) solution should produce an empty chain
        // (all operators skipped), meaning no tryApply was attempted.
        val copy2 = afterSecond.deepCopy()
        val afterThird = strategy.mutate(copy2)
        assertTrue(
            afterThird.transformationsChain.last().isEmpty(),
            "Expected empty transformation step when all operators are in failedDeterministicOperators"
        )

        solution.close()
        afterFirst.close()
        afterSecond.close()
        afterThird.close()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 3: End-to-end optimization with 1 node and 30 evolutions.
    //
    // With 1 initial node:
    //   - initialize(): deletes the 1 node → every solution starts at 0 nodes, failedOps={}
    //   - Evolution 1: deleteNode fails deterministically on every 0-node solution,
    //     recording {0} into both the copy (stored as offspring) and the parent (via addAll).
    //   - Evolution 2+: EVERY copy starts with failedOps={0} → preSkipCount=1 → skip counted.
    //
    // With population=5 and 30 evolutions this yields ~5×29=145 counted skips.
    // The assertion checks mutationsWithSkips > 0.
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `end-to-end optimization skips deleteNode after all nodes consumed`() {
        val metamodel = Metamodel.compile(buildMetamodelData())
        val modelData = buildModelData(nodeCount = 1)   // ← 1 node so 0-node state is instant
        val transformations = buildTransformations()

        // Trivial objective: always 0.0 (we only care about skip behaviour, not fitness).
        val trivialObjective = object : GuidanceFunction {
            override val name: String = "trivial"
            override fun computeFitness(solution: Solution): Double = 0.0
        }
        val objectives = listOf(trivialObjective)
        val constraints = emptyList<GuidanceFunction>()

        val mutationConfig = MutationParameters(
            step = MutationStepConfig.Fixed(n = 1),
            strategy = MutationStrategy.RANDOM
        )
        val strategy = MutationStrategyFactory.create(mutationConfig, transformations)

        val evaluator = LocalMutationEvaluator(
            initialSolutionProvider = { Solution(MdeoModelGraph.create(modelData, metamodel)) },
            mutationStrategy = strategy,
            objectives = objectives,
            constraints = constraints,
            metamodel = metamodel
        )

        val config = OptimizationConfig(
            problem = ProblemConfig(
                metamodelPath = "/metamodel.mm",
                modelPath = "/model/model.m"
            ),
            goal = GoalConfig(
                objectives = listOf(
                    ObjectiveConfig(
                        type = ObjectiveTendency.MINIMIZE,
                        path = "/script/objectives.fn",
                        functionName = "trivial"
                    )
                ),
                constraints = emptyList()
            ),
            search = SearchConfig(
                mutations = MutationsConfig(
                    usingPaths = listOf("/transformation/deleteNode.mt")
                )
            ),
            solver = SolverConfig(
                provider = SolverProvider.MOEA,
                algorithm = AlgorithmType.NSGAII,
                parameters = AlgorithmParameters(
                    population = 5,
                    variation = VariationType.MUTATION,
                    mutation = MutationParameters(
                        step = MutationStepConfig.Fixed(n = 1),
                        strategy = MutationStrategy.RANDOM
                    )
                ),
                termination = TerminationConfig(evolutions = 30),
                batches = 1
            )
        )

        val orchestrator = OptimizationOrchestrator(
            config = config,
            evaluator = evaluator
        )

        runBlocking { orchestrator.run() }

        // Capture skip stats before cleanup.
        val totalMutations = evaluator.totalMutations.get()
        val mutationsWithSkips = evaluator.mutationsWithSkips.get()
        val totalSkippedSlots = evaluator.totalSkippedOperatorSlots.get()

        runBlocking { evaluator.cleanup() }

        System.err.println()
        System.err.println("=== DeterministicFailureSkipTest end-to-end results ===")
        System.err.println("  Total mutations        : $totalMutations")
        System.err.println("  Mutations with skips   : $mutationsWithSkips")
        System.err.println("  Total skipped slots    : $totalSkippedSlots")
        System.err.println("=======================================================")
        System.err.println()

        assertTrue(totalMutations > 0, "Expected at least one mutation to have been evaluated")
        assertTrue(
            mutationsWithSkips > 0,
            "Expected at least some mutations to have skipped deleteNode after the graph was emptied. " +
                "mutationsWithSkips=$mutationsWithSkips / totalMutations=$totalMutations"
        )
        assertTrue(
            totalSkippedSlots > 0,
            "Expected totalSkippedOperatorSlots > 0, got $totalSkippedSlots"
        )
    }
}
