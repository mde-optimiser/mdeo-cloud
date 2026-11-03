package com.mdeo.modeltransformation.runtime.match.plan

import com.mdeo.metamodel.data.MetamodelData
import java.util.Collections
import java.util.WeakHashMap

/**
 * Memoises a value that depends only on a [MetamodelData], for the planner's metamodel-derived
 * tables, which every match would otherwise rebuild.
 *
 * [MetamodelData] is an immutable value (read-only properties and lists all the way down), so a
 * value computed from it can never go stale: the cache needs no invalidation. Lookups compare
 * keys by value, so two equal metamodels (e.g. the same file loaded twice) share an entry and a
 * changed metamodel is a different key. Entries are held weakly, so a metamodel that is no longer
 * used is not kept alive by its derived value.
 *
 * A search runs one metamodel for its whole duration, so the last key is also remembered by
 * identity: that check is a volatile read and a reference comparison, without hashing the whole
 * metamodel. The remembered pair is one immutable object, so a thread can never see the key of
 * one entry with the value of another.
 *
 * @param compute Builds the value; must be a pure function of its argument.
 */
internal class MetamodelMemo<T : Any>(private val compute: (MetamodelData) -> T) {

    private class Entry<T>(val key: MetamodelData, val value: T)

    @Volatile private var last: Entry<T>? = null

    private val byValue: MutableMap<MetamodelData, T> = Collections.synchronizedMap(WeakHashMap())

    /** Returns the value for [metamodelData], computing it on first use. */
    operator fun get(metamodelData: MetamodelData): T {
        val entry = last
        if (entry != null && entry.key === metamodelData) return entry.value
        val value = byValue[metamodelData] ?: compute(metamodelData).also { byValue[metamodelData] = it }
        last = Entry(metamodelData, value)
        return value
    }
}
