package com.mdeo.script.stdlib.impl.collections

/**
 * Hook through which the external-call machinery reads and fills a collection.
 *
 * A collection passed to an external function is sent with its content, and a collection the
 * function returns is created and filled from what the service sent. Filling needs an operation
 * the script-visible API does not offer, and this interface provides it without widening that API.
 * It is `internal`, so nothing outside the script module, and nothing a script can name, sees it.
 *
 * Every mutation of a collection, through this hook or through the ordinary API, bumps
 * [heapVersion]. A collection whose version has not moved since it was last sent can be sent
 * again as just its id.
 */
internal interface HeapCollection {

    /**
     * Counter bumped by every mutation.
     */
    val heapVersion: Long

    /**
     * The elements in iteration order; a bag lists each element as often as it occurs.
     */
    fun heapSnapshot(): List<Any?>

    /**
     * Replaces the whole content.
     */
    fun heapReplace(elements: List<Any?>)
}

/**
 * The map counterpart of [HeapCollection].
 */
internal interface HeapMap {

    /**
     * Counter bumped by every mutation.
     */
    val heapVersion: Long

    /**
     * The entries in iteration order.
     */
    fun heapEntries(): List<Pair<Any?, Any?>>

    /**
     * Replaces the whole content.
     */
    fun heapReplace(entries: List<Pair<Any?, Any?>>)
}
