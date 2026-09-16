package com.mdeo.script.stdlib.impl.collections

/**
 * Hook through which the external-call machinery reads and edits a collection.
 *
 * A contributed function implemented outside the platform follows copy-restore semantics: its
 * arguments are sent, the service may change the mutable ones, and only what changed comes back
 * as per-element deltas. Applying those deltas needs operations the script-visible API does not
 * offer — inserting at an index, setting a bag count outright — and this interface provides them
 * without widening that API. It is `internal`, so nothing outside the script module, and nothing
 * a script can name, sees it.
 *
 * Every mutation of a collection, through this hook or through the ordinary API, bumps
 * [deltaVersion]. A collection whose version has not moved since it was last sent can be sent
 * again as just its id.
 */
internal interface DeltaTarget {

    /**
     * Counter bumped by every mutation.
     */
    val deltaVersion: Long

    /**
     * The elements in iteration order; a bag lists each element as often as it occurs.
     */
    fun deltaSnapshot(): List<Any?>

    /**
     * Replaces [deleteCount] elements at [index] with [insert]. Ordered collections only.
     */
    fun deltaSplice(index: Int, deleteCount: Int, insert: List<Any?>)

    /**
     * Adds each value once.
     */
    fun deltaAdd(values: List<Any?>)

    /**
     * Removes one occurrence of each value.
     */
    fun deltaRemove(values: List<Any?>)

    /**
     * Sets how often [value] occurs. Bags only.
     */
    fun deltaSetCount(value: Any?, count: Int)

    /**
     * Replaces the whole content.
     */
    fun deltaReplace(elements: List<Any?>)
}

/**
 * The map counterpart of [DeltaTarget].
 */
internal interface MapDeltaTarget {

    /**
     * Counter bumped by every mutation.
     */
    val deltaVersion: Long

    /**
     * The entries in iteration order.
     */
    fun deltaEntries(): List<Pair<Any?, Any?>>

    /**
     * Associates [value] with [key].
     */
    fun deltaPut(key: Any?, value: Any?)

    /**
     * Removes the entry for [key].
     */
    fun deltaRemoveKey(key: Any?)

    /**
     * Replaces the whole content.
     */
    fun deltaReplace(entries: List<Pair<Any?, Any?>>)
}
