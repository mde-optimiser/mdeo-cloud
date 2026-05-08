package com.mdeo.optimizer.solution

import com.mdeo.modeltransformation.graph.ModelMetadata
import java.util.Collections

/**
 * [ModelMetadata] implementation that tracks which transformation operators are
 * known to deterministically fail on the current model state.
 *
 * An operator is recorded here when it fails at the very first match opportunity
 * without any prior graph modification — meaning re-running it on the same state
 * will always fail.
 *
 * **Shared-reference design:** [deepCopy] is intentionally a no-op — it returns
 * `this` — so that a parent [com.mdeo.modeltransformation.graph.ModelGraph] and all
 * child graphs produced by
 * [com.mdeo.modeltransformation.graph.ModelGraph.deepCopy] share the same
 * [FailedOperatorsMetadata] instance.  This means that when the mutation strategy
 * discovers a new deterministic failure on a child's model (before any state
 * change), it is automatically visible on the parent too without an explicit
 * propagation step.
 *
 * Once a mutation succeeds and the child's model state diverges, the mutation
 * strategy replaces the child graph's
 * [com.mdeo.modeltransformation.graph.ModelGraph.metadata] with a fresh, empty
 * [FailedOperatorsMetadata] instance so that future failures on the child are
 * tracked independently.
 */
class FailedOperatorsMetadata : ModelMetadata {

    /**
     * Numerical IDs (alphabetically-sorted index into the operator list) of
     * operators that are known to deterministically fail on the current model state.
     * Thread-safe: backed by [Collections.synchronizedSet].
     */
    val failedDeterministicOperators: MutableSet<Int> = Collections.synchronizedSet(HashSet())

    /**
     * No-op: returns the same instance so that parent and child graphs share
     * their failure knowledge without explicit propagation.
     */
    override fun deepCopy(): ModelMetadata = this
}
