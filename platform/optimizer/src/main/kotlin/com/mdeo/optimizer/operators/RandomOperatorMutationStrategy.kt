package com.mdeo.optimizer.operators

import com.mdeo.modeltransformation.ast.TypedAst
import com.mdeo.optimizer.solution.Solution
import org.slf4j.LoggerFactory

/**
 * Mutation strategy that randomly selects and applies transformation operators.
 *
 * On each mutation:
 * 1. Deep-copies the candidate solution (copy-before-transform).
 * 2. Determines the step size (how many operators to apply).
 * 3. For each step, randomly selects an operator and attempts to apply it.
 * 4. Returns the mutated copy (original is never modified).
 *
 * **Deterministic-failure tracking:** Before the first successful operator application
 * the strategy records every operator that fails deterministically (no match found,
 * same model state) into [Solution.failedDeterministicOperators]. Once an operator
 * is successfully applied (model state changes), the set is cleared — the new state
 * may allow previously failing operators to succeed. Failures on subsequent steps
 * (after the model has already been modified) are never recorded, since re-running
 * a step on a changed model may produce different results.
 *
 * @param transformations Map of transformation path to its compiled TypedAst.
 * @param operatorPaths Sorted list of all operator paths; indices serve as consistent
 *   numerical operator IDs across federated nodes.
 * @param stepSizeStrategy Strategy for determining how many operators to apply per mutation.
 * @param operatorSelectionStrategyFactory Factory that creates a fresh [OperatorSelectionStrategy] per
 *   [mutate] call. A new instance is created on every invocation so that concurrent calls from
 *   different threads each operate on independent, unshared selection state.
 */
class RandomOperatorMutationStrategy(
    private val transformations: Map<String, TypedAst>,
    private val operatorPaths: List<String>,
    private val stepSizeStrategy: MutationStepSizeStrategy,
    private val operatorSelectionStrategyFactory: () -> OperatorSelectionStrategy
) : MutationStrategy {

    private val logger = LoggerFactory.getLogger(RandomOperatorMutationStrategy::class.java)
    private val attemptRunner = TransformationAttemptRunner(transformations)

    override fun mutate(solution: Solution): Solution {
        val operatorSelectionStrategy = operatorSelectionStrategyFactory()
        val stepSize = stepSizeStrategy.getNextStepSize(solution)
        val stepTransformations = mutableListOf<String>()
        // Tracks whether at least one operator has been successfully applied, changing the
        // model state. Before the first success, deterministic failures are recorded.
        var anyOperatorApplied = false
        var attemptCount = 0

        for (step in 1..stepSize) {
            var operatorApplied = false

            do {
                val operatorIdx = operatorSelectionStrategy.getNextOperator(solution) ?: break
                val operatorPath = operatorPaths[operatorIdx]

                attemptCount++
                when (attemptRunner.tryApply(solution, operatorPath)) {
                    TransformationAttemptResult.Applied -> {
                        stepTransformations.add(operatorPath)
                        operatorApplied = true
                        if (!anyOperatorApplied) {
                            anyOperatorApplied = true
                        }
                    }
                    TransformationAttemptResult.DeterministicFailure -> {
                        if (!anyOperatorApplied) {
                            solution.failedDeterministicOperators.add(operatorIdx)
                        }
                    }
                    TransformationAttemptResult.NonDeterministicFailure -> {
                        // Transient failure — do not record.
                    }
                }
            } while (!operatorApplied && operatorSelectionStrategy.hasUntriedOperators())

            operatorSelectionStrategy.flushTriedOperators()
        }

        solution.lastMutationAttempts = attemptCount
        solution.recordTransformationStep(stepTransformations)
        return solution
    }
}
