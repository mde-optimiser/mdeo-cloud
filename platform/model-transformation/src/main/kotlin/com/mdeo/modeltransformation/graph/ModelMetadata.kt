package com.mdeo.modeltransformation.graph

/**
 * Arbitrary metadata that can be attached to a [ModelGraph].
 *
 * Implementations may store any optimizer-specific state alongside the graph.
 * The [deepCopy] method is intentionally a no-op by default: it returns the
 * same instance so that the parent and child share the same metadata object
 * after a graph deep-copy.  This allows mutations on the copy to propagate
 * side-effects (e.g. newly discovered deterministic failures) back to the
 * parent without any explicit synchronisation step.
 *
 * When a mutation succeeds and the child's model state diverges from the
 * parent's, the mutation strategy replaces the child's [ModelGraph.metadata]
 * with a fresh, independent instance so that future failures on the child are
 * tracked separately.
 */
interface ModelMetadata {
    /**
     * Returns the metadata to attach to a deep-copied [ModelGraph].
     *
     * The default contract is a **no-op**: return `this` so that parent and
     * child share the same instance.  Override only if independent copies are
     * genuinely required.
     */
    fun deepCopy(): ModelMetadata
}
