package com.mdeo.optimizer.operators

import com.mdeo.modeltransformation.ast.TransformationOperator
import com.mdeo.optimizer.solution.FailedOperatorsMetadata
import com.mdeo.optimizer.solution.Solution
import org.slf4j.LoggerFactory

/**
 * Mutation strategy that randomly selects and applies transformation operators.
 *
 * On each mutation:
 * 1. Determines the step size (how many operators to apply).
 * 2. For each step, randomly selects an operator and attempts to apply it.
 * 3. Returns the mutated solution.
 *
 * **Model-graph copying between attempts:**
 * The caller (e.g. [com.mdeo.optimizer.evaluation.LocalMutationEvaluator]) provides a
 * deep-copied solution, so the very first operator application is safe.  For every
 * subsequent attempt the strategy deep-copies [Solution.modelGraph] before applying the
 * next operator.  The copy is skipped when the previous failed attempt is known to have
 * made no graph changes ([TransformationAttemptResult.Failure] with
 * `changesWereMade = false`), avoiding unnecessary allocations.
 *
 * **Deterministic-failure tracking via metadata:**
 * Failed operators are stored in [FailedOperatorsMetadata] on [Solution.modelGraph].
 * Because [FailedOperatorsMetadata.deepCopy] is a no-op, the parent solution and the
 * child deep-copy share the same metadata object: failures discovered on the child before
 * its first successful mutation are automatically visible on the parent.
 * Once any operator succeeds, a fresh empty [FailedOperatorsMetadata] is installed on the
 * child's graph so that future failures are tracked independently of the parent.
 *
 * **Skip counting:**
 * Known-failed operators are selected normally by [OperatorSelectionStrategy]; the
 * strategy itself intercepts them, increments [Solution.lastSkipCount], and tries the
 * next operator.  This gives an exact skip count rather than an upper bound.
 *
 * @param operators Sorted list of available [TransformationOperator]s; the list index is
 *   the stable numerical operator ID used in [FailedOperatorsMetadata].
 * @param stepSizeStrategy Strategy for determining how many operators to apply per mutation.
 * @param operatorSelectionStrategyFactory Factory that creates a fresh [OperatorSelectionStrategy]
 *   per [mutate] call so that concurrent invocations share no state.
 */
class RandomOperatorMutationStrategy(
    private val operators: List<TransformationOperator>,
    private val stepSizeStrategy: MutationStepSizeStrategy,
    private val operatorSelectionStrategyFactory: () -> OperatorSelectionStrategy
) : MutationStrategy {

    private val logger = LoggerFactory.getLogger(RandomOperatorMutationStrategy::class.java)
    private val attemptRunner = TransformationAttemptRunner()

    override fun mutate(solution: Solution): Solution {
        val operatorSelectionStrategy = operatorSelectionStrategyFactory()
        val stepSize = stepSizeStrategy.getNextStepSize(solution)
        val stepTransformations = mutableListOf<Int>()
        var anyOperatorApplied = false
        var attemptCount = 0
        var skipCount = 0
        var copyNeededBeforeNextAttempt = false
        var cleanGraph = solution.modelGraph

        for (step in 1..stepSize) {
            var operatorApplied = false

            do {
                val operatorIdx = operatorSelectionStrategy.getNextOperator() ?: break

                val failedOps = (solution.modelGraph.metadata as? FailedOperatorsMetadata)
                    ?.failedDeterministicOperators
                if (failedOps != null && operatorIdx in failedOps) {
                    skipCount++
                    continue
                }

                if (copyNeededBeforeNextAttempt) {
                    val oldGraph = solution.modelGraph
                    solution.modelGraph = cleanGraph.deepCopy()
                    if (oldGraph !== cleanGraph) {
                        oldGraph.close()
                    }
                }

                val operator = operators[operatorIdx]
                attemptCount++
                when (val result = attemptRunner.tryApply(solution, operator)) {
                    TransformationAttemptResult.Applied -> {
                        stepTransformations.add(operatorIdx)
                        operatorApplied = true
                        cleanGraph = solution.modelGraph
                        copyNeededBeforeNextAttempt = true
                        if (!anyOperatorApplied) {
                            anyOperatorApplied = true
                            solution.modelGraph.metadata = FailedOperatorsMetadata()
                        }
                    }
                    is TransformationAttemptResult.Failure -> {
                        if (result.isDeterministic && !anyOperatorApplied) {
                            getOrCreateFailedMetadata(solution).failedDeterministicOperators.add(operatorIdx)
                        }
                        copyNeededBeforeNextAttempt = result.changesWereMade
                    }
                }
            } while (!operatorApplied && operatorSelectionStrategy.hasUntriedOperators())

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
