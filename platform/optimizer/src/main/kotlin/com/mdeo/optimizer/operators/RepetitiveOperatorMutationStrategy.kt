package com.mdeo.optimizer.operators

import com.mdeo.modeltransformation.ast.TypedAst
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
 * **Deterministic-failure tracking:** Same semantics as [RandomOperatorMutationStrategy]:
 * deterministic failures are recorded into [Solution.failedDeterministicOperators] only
 * before the first successful operator application. Once any operator succeeds and changes
 * the model state, the set is cleared and no further failures are recorded.
 *
 * @param transformations Map of transformation path to its compiled TypedAst.
 * @param operatorPaths Sorted list of all operator paths; indices serve as consistent
 *   numerical operator IDs across federated nodes.
 * @param stepSizeStrategy Strategy for determining how many operators to apply per mutation.
 * @param operatorSelectionStrategyFactory Factory that creates a fresh [OperatorSelectionStrategy] per
 *   [mutate] call. A new instance is created on every invocation so that concurrent calls from
 *   different threads each operate on independent, unshared selection state.
 */
class RepetitiveOperatorMutationStrategy(
    private val transformations: Map<String, TypedAst>,
    private val operatorPaths: List<String>,
    private val stepSizeStrategy: MutationStepSizeStrategy,
    private val operatorSelectionStrategyFactory: () -> OperatorSelectionStrategy
) : MutationStrategy {

    private val logger = LoggerFactory.getLogger(RepetitiveOperatorMutationStrategy::class.java)
    private val attemptRunner = TransformationAttemptRunner(transformations)

    override fun mutate(solution: Solution): Solution {
        val operatorSelectionStrategy = operatorSelectionStrategyFactory()
        val stepSize = stepSizeStrategy.getNextStepSize(solution)
        // Index of the currently retained operator (reused across steps on success).
        var currentOperatorIdx: Int? = null
        val stepTransformations = mutableListOf<String>()
        // True once at least one operator has been successfully applied.
        var anyOperatorApplied = false
        var attemptCount = 0

        for (step in 1..stepSize) {
            do {
                if (currentOperatorIdx == null) {
                    currentOperatorIdx = operatorSelectionStrategy.getNextOperator(solution) ?: break
                }
                // After the null-check + break above, Kotlin smart-casts currentOperatorIdx to Int.
                val operatorPath = operatorPaths[currentOperatorIdx]

                attemptCount++
                when (attemptRunner.tryApply(solution, operatorPath)) {
                    TransformationAttemptResult.Applied -> {
                        stepTransformations.add(operatorPath)
                        if (!anyOperatorApplied) {
                            // First successful application: model state has changed, so stop
                            // recording further failures. Already-recorded failures are kept
                            // so they can be propagated back to the parent.
                            anyOperatorApplied = true
                        }
                        // Retain currentOperatorIdx for the next step (repetitive behaviour).
                    }
                    TransformationAttemptResult.DeterministicFailure -> {
                        if (!anyOperatorApplied) {
                            solution.failedDeterministicOperators.add(currentOperatorIdx)
                        }
                        currentOperatorIdx = null
                    }
                    TransformationAttemptResult.NonDeterministicFailure -> {
                        currentOperatorIdx = null
                    }
                }
            } while (currentOperatorIdx == null && operatorSelectionStrategy.hasUntriedOperators())

            operatorSelectionStrategy.flushTriedOperators()
        }

        solution.lastMutationAttempts = attemptCount
        solution.recordTransformationStep(stepTransformations)
        return solution
    }
}
