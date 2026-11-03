package com.mdeo.optimizer.operators

import com.mdeo.optimizer.config.CrossoverParameters
import com.mdeo.optimizer.solution.FailedOperatorsMetadata
import com.mdeo.optimizer.solution.Solution
import com.mdeo.optimizer.config.CrossoverStrategy as CrossoverStrategyEnum

/**
 * What a crossover did to a child.
 *
 * @param appliedOperator The id (crossover id space) of the operator that applied, or `null` when
 *   every operator failed and the child is a copy of its primary parent.
 * @param attempts The number of operators run.
 */
data class CrossoverOutcome(val appliedOperator: Int?, val attempts: Int)

/**
 * Strategy for applying a crossover operator to a child.
 */
interface CrossoverStrategy {

    /**
     * Applies a crossover to [child], a deep copy of [primary] whose graph the strategy may
     * replace, reading [donors]. On return [child] is either the result of an operator that
     * applied or, when none did, an unchanged copy of [primary]. Neither [primary] nor a donor is
     * changed.
     *
     * @param child The child: a deep copy of [primary], changed in place.
     * @param primary The primary parent, used to restore the child after a failed attempt that
     *   changed it.
     * @param donors The other parents of the group, in cyclic order after the primary.
     * @return What happened.
     */
    fun crossover(child: Solution, primary: Solution, donors: List<Solution>): CrossoverOutcome
}

/**
 * Tries the crossover operators that accept the group's parent count in a uniformly random order
 * until one applies.
 *
 * A failed attempt that changed the child's graph is undone by replacing it with a fresh deep
 * copy of the primary. Crossover failures are never recorded in [FailedOperatorsMetadata]: a
 * crossover's outcome depends on the donors as much as on the child, so a failure does not
 * predict the next one on the same model. When an operator applies, the child gets a fresh
 * [FailedOperatorsMetadata]: its model no longer equals the primary's, so it must stop sharing
 * the primary's record of deterministic mutation failures.
 *
 * @param operators The crossover operators, in id order.
 */
class RandomCrossoverStrategy(
    private val operators: List<CrossoverOperator>
) : CrossoverStrategy {

    private val attemptRunner = TransformationAttemptRunner()

    override fun crossover(child: Solution, primary: Solution, donors: List<Solution>): CrossoverOutcome {
        val parents = donors.size + 1
        val donorGraphs = donors.map { it.modelGraph }
        var attempts = 0
        for (operator in operators.filter { it.accepts(parents) }.shuffled()) {
            attempts++
            val result = attemptRunner.tryApply(child, operator.operator, operator.modelArguments(donorGraphs))
            when (result) {
                TransformationAttemptResult.Applied -> {
                    child.modelGraph.metadata = FailedOperatorsMetadata()
                    return CrossoverOutcome(operator.id, attempts)
                }
                is TransformationAttemptResult.Failure -> if (result.changesWereMade) {
                    val dirty = child.modelGraph
                    child.modelGraph = primary.modelGraph.deepCopy()
                    dirty.close()
                }
            }
        }
        return CrossoverOutcome(null, attempts)
    }
}

/**
 * Factory for [CrossoverStrategy] instances from config.
 */
object CrossoverStrategyFactory {

    /**
     * Creates the crossover strategy of [params].
     *
     * @param params Crossover parameters from config.
     * @param operators The crossover operators with their ids ([OperatorCatalog.crossoverOperators]).
     * @return The strategy.
     */
    fun create(params: CrossoverParameters, operators: List<CrossoverOperator>): CrossoverStrategy =
        when (params.strategy) {
            CrossoverStrategyEnum.RANDOM -> RandomCrossoverStrategy(operators)
        }
}
