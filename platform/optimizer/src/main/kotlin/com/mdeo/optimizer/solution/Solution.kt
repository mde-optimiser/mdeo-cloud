package com.mdeo.optimizer.solution

import com.mdeo.modeltransformation.graph.ModelGraph
import java.util.Collections

/**
 * Represents a candidate solution in the optimization process.
 *
 * Wraps a [ModelGraph] containing the model state and tracks
 * the chain of transformations applied to reach this state.
 *
 * @param modelGraph The model graph holding the current model state.
 * @param transformationsChain History of applied transformation step names.
 * @param failedDeterministicOperators Numerical IDs (sorted-index into the operator list) of
 *   operators that have been confirmed to deterministically fail on the current model state.
 *   An operator is recorded here when it fails at the very first match opportunity without
 *   any prior graph modification — meaning re-running it on the same state will always fail.
 *   The set accumulates across mutation rounds for each parent: each time a parent is selected
 *   for mutation its deep copy inherits the set (skipping known-failed operators), and any
 *   new deterministic failures found before the first model change are propagated back to the
 *   parent by [LocalMutationEvaluator]. The child's copy is cleared after propagation, since
 *   the child's model state has changed and its failures must be re-discovered independently.
 *   Thread-safe: backed by [Collections.synchronizedSet].
 */
class Solution(
    var modelGraph: ModelGraph,
    val transformationsChain: MutableList<List<String>> = mutableListOf(),
    val failedDeterministicOperators: MutableSet<Int> = Collections.synchronizedSet(HashSet())
) : AutoCloseable {

    /**
     * Total number of [com.mdeo.optimizer.operators.TransformationAttemptRunner.tryApply] calls
     * made during the most recent [com.mdeo.optimizer.operators.MutationStrategy.mutate] invocation.
     *
     * Counts all actual execution attempts — both successful applications and failures
     * (deterministic or not) — but does NOT include operators that were pre-skipped
     * because they were already in [failedDeterministicOperators].
     * Reset to 0 at the start of each [mutate] call; not copied into [deepCopy] or [copy].
     */
    var lastMutationAttempts: Int = 0

    /**
     * Creates a deep copy of this solution.
     *
     * The graph is fully cloned (with shuffled vertex order for nondeterminism)
     * so mutations on the copy do not affect the original.
     * The [failedDeterministicOperators] set is also copied so the copy inherits
     * knowledge of which operators are known to fail on the current model state.
     */
    fun deepCopy(): Solution {
        val copiedGraph = modelGraph.deepCopy()
        val copiedChain = transformationsChain.map { it.toList() }.toMutableList()
        val copiedFailedOps = Collections.synchronizedSet(HashSet(failedDeterministicOperators))
        return Solution(copiedGraph, copiedChain, copiedFailedOps)
    }

    /**
     * Creates a shallow copy of this solution (shares the same [ModelGraph]).
     *
     * Use this for bookkeeping (e.g. tracking Pareto-front entries)
     * where the graph state does not need to be isolated.
     * The [failedDeterministicOperators] set is also copied (not shared).
     */
    fun copy(): Solution {
        val copiedChain = transformationsChain.map { it.toList() }.toMutableList()
        val copiedFailedOps = Collections.synchronizedSet(HashSet(failedDeterministicOperators))
        return Solution(modelGraph, copiedChain, copiedFailedOps)
    }

    /**
     * Records a step of transformations applied to this solution.
     */
    fun recordTransformationStep(transformationNames: List<String>) {
        transformationsChain.add(transformationNames)
    }

    override fun close() {
        modelGraph.close()
    }
}
