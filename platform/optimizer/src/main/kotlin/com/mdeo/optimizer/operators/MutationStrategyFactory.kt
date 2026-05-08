package com.mdeo.optimizer.operators

import com.mdeo.modeltransformation.ast.TransformationOperator
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
     * The caller is responsible for assigning stable numerical IDs to operators
     * (e.g. by sorting paths alphabetically and using the zero-based index as the
     * [TransformationOperator.id]) so that every node in a federated setup assigns
     * identical IDs to identical operators.
     *
     * @param params Mutation parameters from config.
     * @param operators List of compiled [TransformationOperator]s with pre-assigned IDs.
     * @return A configured MutationStrategy.
     */
    fun create(
        params: MutationParameters,
        operators: List<TransformationOperator>
    ): com.mdeo.optimizer.operators.MutationStrategy {
        val stepSizeStrategy = createStepSizeStrategy(params.step)
        val selectionFactory: () -> OperatorSelectionStrategy = { RandomOperatorSelection(operators.size) }

        return when (params.strategy) {
            MutationStrategyEnum.RANDOM -> RandomOperatorMutationStrategy(
                operators = operators,
                stepSizeStrategy = stepSizeStrategy,
                operatorSelectionStrategyFactory = selectionFactory
            )
            MutationStrategyEnum.REPETITIVE -> {
                RepetitiveOperatorMutationStrategy(
                    operators = operators,
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
