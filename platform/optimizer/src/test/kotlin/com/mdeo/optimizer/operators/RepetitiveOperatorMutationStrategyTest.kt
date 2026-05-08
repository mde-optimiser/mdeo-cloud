package com.mdeo.optimizer.operators

import com.mdeo.metamodel.Metamodel
import com.mdeo.metamodel.data.MetamodelData
import com.mdeo.metamodel.data.ModelData
import com.mdeo.modeltransformation.ast.TransformationOperator
import com.mdeo.modeltransformation.ast.TypedAst
import com.mdeo.modeltransformation.ast.statements.TypedTransformationStatement
import com.mdeo.modeltransformation.graph.tinker.TinkerModelGraph
import com.mdeo.optimizer.solution.Solution
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Tests for [RepetitiveOperatorMutationStrategy].
 *
 * Uses a no-op [TypedAst] (empty statements) for operators that should succeed,
 * and an AST containing an unrecognised statement kind for operators that should
 * fail. The key property under test is that the repetitive strategy reuses a
 * successful operator and only fetches a new one on failure.
 */
class RepetitiveOperatorMutationStrategyTest {

    private val metamodel = Metamodel.compile(MetamodelData())

    /** A TypedAst with no statements — executes as a successful no-op. */
    private val noOpAst = TypedAst(types = emptyList(), metamodelPath = "", statements = emptyList())

    /** A TypedAst whose statement has an unregistered kind, causing a Failure result. */
    private val failAst = TypedAst(
        types = emptyList(),
        metamodelPath = "",
        statements = listOf(object : TypedTransformationStatement {
            override val kind: String = "__test_unknown_kind__"
        })
    )

    private class FixedStepSize(private val size: Int) : MutationStepSizeStrategy {
        override fun getNextStepSize(solution: Solution): Int = size
    }

    /**
     * Controllable operator selection that returns operators from a predefined list of indices.
     * Tracks how many times [getNextOperator] is called and how many flushes occur.
     */
    private class FakeOperatorSelection(
        private val operatorIndices: List<Int>
    ) : OperatorSelectionStrategy {
        var getNextOperatorCalls = 0
            private set
        var flushCalls = 0
            private set

        private var index = 0

        override fun getNextOperator(): Int? {
            getNextOperatorCalls++
            if (index >= operatorIndices.size) return null
            return operatorIndices[index++]
        }

        override fun hasUntriedOperators(): Boolean = index < operatorIndices.size

        override fun flushTriedOperators() {
            flushCalls++
        }
    }

    private fun createSolution(): Solution {
        return Solution(
            TinkerModelGraph.create(
                ModelData(metamodelPath = "", instances = emptyList(), links = emptyList()),
                metamodel
            )
        )
    }

    @Test
    fun `reuses same operator when it keeps succeeding`() {
        val opA = TransformationOperator(id = 0, ast = noOpAst)
        val selection = FakeOperatorSelection(listOf(0))
        val strategy = RepetitiveOperatorMutationStrategy(
            operators = listOf(opA),
            stepSizeStrategy = FixedStepSize(3),
            operatorSelectionStrategyFactory = { selection }
        )

        val solution = createSolution()
        strategy.mutate(solution)

        // Operator was fetched once then reused for all 3 steps
        assertEquals(1, selection.getNextOperatorCalls)
        assertEquals(listOf(0, 0, 0), solution.transformationsChain.last())
    }

    @Test
    fun `fetches new operator when current one fails`() {
        val opA = TransformationOperator(id = 0, ast = failAst)
        val opB = TransformationOperator(id = 1, ast = noOpAst)
        // Return index 0 (opA) first, then index 1 (opB).
        val selection = FakeOperatorSelection(listOf(0, 1))
        val strategy = RepetitiveOperatorMutationStrategy(
            operators = listOf(opA, opB),
            stepSizeStrategy = FixedStepSize(1),
            operatorSelectionStrategyFactory = { selection }
        )

        val solution = createSolution()
        strategy.mutate(solution)

        // opA tried and failed (unregistered statement kind), then opB tried and succeeded
        assertEquals(2, selection.getNextOperatorCalls)
        assertEquals(listOf(1), solution.transformationsChain.last())
    }

    @Test
    fun `operator persists across multiple steps`() {
        val opA = TransformationOperator(id = 0, ast = noOpAst)
        val opB = TransformationOperator(id = 1, ast = noOpAst)
        val selection = FakeOperatorSelection(listOf(0, 1))
        val strategy = RepetitiveOperatorMutationStrategy(
            operators = listOf(opA, opB),
            stepSizeStrategy = FixedStepSize(5),
            operatorSelectionStrategyFactory = { selection }
        )

        val solution = createSolution()
        strategy.mutate(solution)

        // opA succeeds on step 1 and is reused for all 5 steps
        assertEquals(1, selection.getNextOperatorCalls)
        val recorded = solution.transformationsChain.last()
        assertEquals(5, recorded.size)
        assertTrue(recorded.all { it == 0 })
    }

    @Test
    fun `flushTriedOperators called once per step`() {
        val opA = TransformationOperator(id = 0, ast = noOpAst)
        val selection = FakeOperatorSelection(listOf(0))
        val strategy = RepetitiveOperatorMutationStrategy(
            operators = listOf(opA),
            stepSizeStrategy = FixedStepSize(3),
            operatorSelectionStrategyFactory = { selection }
        )

        val solution = createSolution()
        strategy.mutate(solution)

        assertEquals(3, selection.flushCalls)
    }

    @Test
    fun `handles no operators available`() {
        val selection = FakeOperatorSelection(emptyList())
        val strategy = RepetitiveOperatorMutationStrategy(
            operators = emptyList(),
            stepSizeStrategy = FixedStepSize(2),
            operatorSelectionStrategyFactory = { selection }
        )

        val solution = createSolution()
        val result = strategy.mutate(solution)

        assertSame(solution, result)
        assertEquals(listOf(emptyList<Int>()), result.transformationsChain)
    }

    @Test
    fun `step size of zero produces empty transformation step`() {
        val opA = TransformationOperator(id = 0, ast = noOpAst)
        val selection = FakeOperatorSelection(listOf(0))
        val strategy = RepetitiveOperatorMutationStrategy(
            operators = listOf(opA),
            stepSizeStrategy = FixedStepSize(0),
            operatorSelectionStrategyFactory = { selection }
        )

        val solution = createSolution()
        strategy.mutate(solution)

        assertEquals(0, selection.getNextOperatorCalls)
        assertEquals(listOf(emptyList<Int>()), solution.transformationsChain)
    }
}
