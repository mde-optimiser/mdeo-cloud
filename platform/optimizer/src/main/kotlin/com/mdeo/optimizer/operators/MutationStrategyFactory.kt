package com.mdeo.optimizer.operators

import com.mdeo.modeltransformation.ast.TypedAst
import com.mdeo.optimizer.config.MutationParameters
import com.mdeo.optimizer.config.MutationStepConfig
import com.mdeo.optimizer.config.MutationStrategy as MutationStrategyEnum

/**
 * Factory for creating mutation-related strategy instances from config.
 */
object MutationStrategyFactory {

    /**
     * Creates a fully-configured [MutationStrategy] from the optimizer config.
     *
     * Operator paths are **sorted alphabetically** before being assigned numerical
     * indices. This guarantees that every node in a federated setup — regardless of
     * the insertion order of the [transformations] map — assigns identical indices to
     * identical operators, keeping [com.mdeo.optimizer.solution.Solution.failedDeterministicOperators]
     * consistent across the cluster.
     *
     * @param params Mutation parameters from config.
     * @param transformations Map of path to compiled TypedAst for all mutation operators.
     * @return A configured MutationStrategy.
     */
    fun create(
        params: MutationParameters,
        transformations: Map<String, TypedAst>
    ): com.mdeo.optimizer.operators.MutationStrategy {
        // Sort paths alphabetically so numerical indices are consistent across nodes.
        val sortedPaths = transformations.keys.sorted()
        val stepSizeStrategy = createStepSizeStrategy(params.step)
        val selectionFactory: () -> OperatorSelectionStrategy = { RandomOperatorSelection(sortedPaths) }

        return when (params.strategy) {
            MutationStrategyEnum.RANDOM -> RandomOperatorMutationStrategy(
                transformations = transformations,
                operatorPaths = sortedPaths,
                stepSizeStrategy = stepSizeStrategy,
                operatorSelectionStrategyFactory = selectionFactory
            )
            MutationStrategyEnum.REPETITIVE -> {
                RepetitiveOperatorMutationStrategy(
                    transformations = transformations,
                    operatorPaths = sortedPaths,
                    stepSizeStrategy = stepSizeStrategy,
                    operatorSelectionStrategyFactory = selectionFactory
                )
            }
        }
    }

    /**
     * Creates a [MutationStepSizeStrategy] from the given step configuration.
     *
     * @param config The step size configuration (fixed or interval).
     * @return A configured [MutationStepSizeStrategy].
     */
    private fun createStepSizeStrategy(config: MutationStepConfig): MutationStepSizeStrategy {
        return when (config) {
            is MutationStepConfig.Fixed -> FixedMutationStepSizeStrategy(config.n)
            is MutationStepConfig.Interval -> IntervalMutationStepSizeStrategy(config.lower, config.upper)
        }
    }
}
