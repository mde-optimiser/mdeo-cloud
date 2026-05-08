package com.mdeo.optimizer.operators

/**
 * Strategy for selecting which transformation operator to try next within a mutation step.
 *
 * Operators are identified by their **numerical index** in the sorted operator list
 * maintained by [com.mdeo.optimizer.operators.MutationStrategyFactory]. Using indices
 * instead of string paths allows compact, consistent serialisation of
 * [com.mdeo.optimizer.solution.FailedOperatorsMetadata.failedDeterministicOperators]
 * across federated nodes (every node sorts the same operator list and thus assigns
 * identical indices).
 *
 * Filtering of known-failed operators is **not** done here.  Each implementation
 * returns any untried operator; the mutation strategy is responsible for checking
 * whether a selected operator should be skipped (and for counting those skips).
 */
interface OperatorSelectionStrategy {
    /**
     * Returns the index of the next transformation operator to try,
     * or `null` if all operators have already been tried in this step.
     */
    fun getNextOperator(): Int?

    /**
     * Whether there are untried operators remaining in this step.
     */
    fun hasUntriedOperators(): Boolean

    /**
     * Resets the tried operators for the next step.
     */
    fun flushTriedOperators()
}

/**
 * Randomly selects operators by index, trying each at most once per step before
 * declaring exhaustion.
 *
 * @param operatorCount The total number of available transformation operators.
 */
class RandomOperatorSelection(
    private val operatorCount: Int
) : OperatorSelectionStrategy {

    private val triedOperators = mutableSetOf<Int>()

    override fun getNextOperator(): Int? {
        val remaining = (0 until operatorCount).filter { it !in triedOperators }
        if (remaining.isEmpty()) return null

        val selected = remaining.random()
        triedOperators.add(selected)
        return selected
    }

    override fun hasUntriedOperators(): Boolean {
        return triedOperators.size < operatorCount
    }

    override fun flushTriedOperators() {
        triedOperators.clear()
    }
}
