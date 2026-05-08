package com.mdeo.optimizer.solution

import com.mdeo.modeltransformation.graph.ModelGraph

/**
 * Represents a candidate solution in the optimization process.
 *
 * Wraps a [ModelGraph] containing the model state and tracks the chain of
 * transformations applied to reach this state.
 *
 * Deterministic-failure tracking is stored on [modelGraph] as a
 * [FailedOperatorsMetadata] instance rather than directly on this class.
 * Because [FailedOperatorsMetadata.deepCopy] is a no-op, parent and child
 * solutions produced by [deepCopy] share the same metadata object: failures
 * discovered on the child before its first successful mutation are
 * automatically visible on the parent, eliminating the need for an explicit
 * back-propagation step.  Once a mutation succeeds, the mutation strategy
 * replaces the child's [ModelGraph.metadata] with a fresh empty
 * [FailedOperatorsMetadata] so the child tracks its own failures independently.
 *
 * @param modelGraph The model graph holding the current model state.
 * @param transformationsChain History of applied transformation step operator indices.
 */
class Solution(
    var modelGraph: ModelGraph,
    val transformationsChain: MutableList<List<Int>> = mutableListOf()
) : AutoCloseable {

    /**
     * Total number of [com.mdeo.optimizer.operators.TransformationAttemptRunner.tryApply]
     * calls made during the most recent
     * [com.mdeo.optimizer.operators.MutationStrategy.mutate] invocation.
     *
     * Counts actual execution attempts (successes and failures) but does NOT
     * include operators that were skipped because they were already in
     * [FailedOperatorsMetadata.failedDeterministicOperators].
     * Reset to 0 at the start of each [mutate] call; not copied into [deepCopy] or [copy].
     */
    var lastMutationAttempts: Int = 0

    /**
     * Number of times a known-failed operator was selected during the most recent
     * [com.mdeo.optimizer.operators.MutationStrategy.mutate] invocation and
     * then skipped instead of executed.
     *
     * Unlike the old pre-filtering approach this is an exact count of actual
     * skips rather than an upper bound derived from the size of the failed set.
     * Reset to 0 at the start of each [mutate] call; not copied into [deepCopy] or [copy].
     */
    var lastSkipCount: Int = 0

    /**
     * Creates a deep copy of this solution.
     *
     * The graph is fully cloned (with shuffled vertex order for nondeterminism)
     * so mutations on the copy do not affect the original.
     * [ModelGraph.metadata] — including any [FailedOperatorsMetadata] — is
     * propagated via [com.mdeo.modeltransformation.graph.ModelMetadata.deepCopy],
     * which is a no-op by default, making the copy share the same metadata object
     * as the original.
     */
    fun deepCopy(): Solution {
        val copiedGraph = modelGraph.deepCopy()
        val copiedChain = transformationsChain.map { it.toList() }.toMutableList()
        return Solution(copiedGraph, copiedChain)
    }

    /**
     * Creates a shallow copy of this solution (shares the same [ModelGraph]).
     *
     * Use this for bookkeeping (e.g. tracking Pareto-front entries)
     * where the graph state does not need to be isolated.
     */
    fun copy(): Solution {
        val copiedChain = transformationsChain.map { it.toList() }.toMutableList()
        return Solution(modelGraph, copiedChain)
    }

    /**
     * Records a step of transformations applied to this solution.
     */
    fun recordTransformationStep(operatorIds: List<Int>) {
        transformationsChain.add(operatorIds)
    }

    override fun close() {
        modelGraph.close()
    }
}
