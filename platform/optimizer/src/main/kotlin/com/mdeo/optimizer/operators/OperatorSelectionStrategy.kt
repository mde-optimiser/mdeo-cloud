package com.mdeo.optimizer.operators

import com.mdeo.optimizer.solution.Solution

/**
 * Strategy for selecting which transformation operator to apply next.
 *
 * Operators are identified by their **numerical index** in the sorted operator list
 * maintained by [com.mdeo.optimizer.operators.MutationStrategyFactory]. Using indices
 * instead of string paths allows compact, consistent serialisation of
 * [Solution.failedDeterministicOperators] across federated nodes (every node sorts the
 * same operator list and thus assigns identical indices).
 */
interface OperatorSelectionStrategy {
    /**
     * Returns the index of the next transformation operator to try,
     * or null if all available operators have been tried in this step.
     *
     * Implementations must skip any operator whose index appears in
     * [Solution.failedDeterministicOperators] on the provided [solution].
     */
    fun getNextOperator(solution: Solution): Int?

    /**
     * Whether there are untried operators remaining in this step
     * (ignoring the [Solution.failedDeterministicOperators] filter — that is handled
     * inside [getNextOperator]).
     */
    fun hasUntriedOperators(): Boolean

    /**
     * Resets the tried operators for the next step.
     */
    fun flushTriedOperators()
}

/**
 * Randomly selects operators by index, trying each at most once per step before
 * declaring exhaustion. Operators listed in [Solution.failedDeterministicOperators]
 * are skipped entirely.
 *
 * @param operatorPaths The sorted list of available transformation operator paths.
 *   The index of each path in this list is the numerical operator ID.
 */
class RandomOperatorSelection(
    val operatorPaths: List<String>
) : OperatorSelectionStrategy {

    private val triedOperators = mutableSetOf<Int>()

    override fun getNextOperator(solution: Solution): Int? {
        val skipped = solution.failedDeterministicOperators
        val remaining = operatorPaths.indices.filter { it !in triedOperators && it !in skipped }
        if (remaining.isEmpty()) return null

        val selected = remaining.random()
        triedOperators.add(selected)
        return selected
    }

    override fun hasUntriedOperators(): Boolean {
        return triedOperators.size < operatorPaths.size
    }

    override fun flushTriedOperators() {
        triedOperators.clear()
    }
}
