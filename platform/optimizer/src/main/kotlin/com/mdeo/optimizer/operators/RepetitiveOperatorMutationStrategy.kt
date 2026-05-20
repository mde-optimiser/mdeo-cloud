package com.mdeo.optimizer.operators

import com.mdeo.modeltransformation.ast.TransformationOperator
import com.mdeo.optimizer.solution.FailedOperatorsMetadata
import com.mdeo.optimizer.solution.Solution
import org.slf4j.LoggerFactory

/**
 * Mutation strategy that reuses the same operator as long as it succeeds.
 *
 * Unlike [RandomOperatorMutationStrategy], which selects a new operator for every
 * step, this strategy keeps applying the same operator across all steps until it
 * fails. When the current operator fails, it is discarded and a new one is
 * requested from the [OperatorSelectionStrategy].
 *
 * On each mutation:
 * 1. Determines the step size (how many operators to apply).
 * 2. For each step, reuses the current operator if one exists, otherwise fetches a new one.
 * 3. If the operator succeeds, it is retained for the next step.
 * 4. If the operator fails, it is cleared and a new one is selected.
 * 5. Returns the mutated solution.
 *
 * **Model-graph copying between attempts:**
 * Same as [RandomOperatorMutationStrategy]: the caller provides a deep-copied solution
 * for the first attempt; subsequent attempts deep-copy the model graph unless the
 * previous failure is known to have made no changes.
 *
 * **Deterministic-failure tracking via metadata:**
 * Same semantics as [RandomOperatorMutationStrategy]: failures are recorded in
 * [FailedOperatorsMetadata] on the model graph before the first successful mutation.
 * A fresh empty metadata is installed after the first success.
 *
 * **Skip counting:**
 * Known-failed operators are selected normally, then intercepted and counted as skips
 * rather than executed.  The exact count is stored in [Solution.lastSkipCount].
 *
 * @param operators Sorted list of available [TransformationOperator]s.
 * @param stepSizeStrategy Strategy for determining how many operators to apply per mutation.
 * @param operatorSelectionStrategyFactory Factory that creates a fresh [OperatorSelectionStrategy]
 *   per [mutate] call.
 */
class RepetitiveOperatorMutationStrategy(
    private val operators: List<TransformationOperator>,
    private val stepSizeStrategy: MutationStepSizeStrategy,
    private val operatorSelectionStrategyFactory: () -> OperatorSelectionStrategy
) : MutationStrategy {

    private val logger = LoggerFactory.getLogger(RepetitiveOperatorMutationStrategy::class.java)
    private val attemptRunner = TransformationAttemptRunner()

    override fun mutate(solution: Solution): Solution {
        val operatorSelectionStrategy = operatorSelectionStrategyFactory()
        val stepSize = stepSizeStrategy.getNextStepSize(solution)
        var currentOperatorIdx: Int? = null
        val stepTransformations = mutableListOf<Int>()
        var anyOperatorApplied = false
        var attemptCount = 0
        var skipCount = 0
        var copyNeededBeforeNextAttempt = false
        var cleanGraph = solution.modelGraph.deepCopy()

        for (step in 1..stepSize) {
            do {
                if (currentOperatorIdx == null) {
                    currentOperatorIdx = operatorSelectionStrategy.getNextOperator() ?: break
                }

                val failedOps = (solution.modelGraph.metadata as? FailedOperatorsMetadata)
                    ?.failedDeterministicOperators
                if (failedOps != null && currentOperatorIdx in failedOps) {
                    skipCount++
                    currentOperatorIdx = null
                    continue
                }

                if (copyNeededBeforeNextAttempt) {
                    val oldGraph = solution.modelGraph
                    solution.modelGraph = cleanGraph.deepCopy()
                    if (oldGraph !== cleanGraph) {
                        oldGraph.close()
                    }
                }

                val operator = operators[currentOperatorIdx]
                attemptCount++
                when (val result = attemptRunner.tryApply(solution, operator)) {
                    TransformationAttemptResult.Applied -> {
                        stepTransformations.add(currentOperatorIdx)
                        cleanGraph = solution.modelGraph
                        copyNeededBeforeNextAttempt = true
                        if (!anyOperatorApplied) {
                            anyOperatorApplied = true
                            solution.modelGraph.metadata = FailedOperatorsMetadata()
                        }
                    }
                    is TransformationAttemptResult.Failure -> {
                        if (result.isDeterministic && !anyOperatorApplied) {
                            getOrCreateFailedMetadata(solution).failedDeterministicOperators.add(currentOperatorIdx)
                        }
                        copyNeededBeforeNextAttempt = result.changesWereMade
                        currentOperatorIdx = null
                    }
                }
            } while (currentOperatorIdx == null && operatorSelectionStrategy.hasUntriedOperators())

            operatorSelectionStrategy.flushTriedOperators()
        }

        solution.lastMutationAttempts = attemptCount
        solution.lastSkipCount = skipCount
        solution.recordTransformationStep(stepTransformations)
        return solution
    }

    private fun getOrCreateFailedMetadata(solution: Solution): FailedOperatorsMetadata {
        val existing = solution.modelGraph.metadata
        if (existing is FailedOperatorsMetadata) return existing
        val newMetadata = FailedOperatorsMetadata()
        solution.modelGraph.metadata = newMetadata
        return newMetadata
    }
}
