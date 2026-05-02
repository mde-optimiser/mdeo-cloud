package com.mdeo.script.stdlib.impl.collections

import com.mdeo.script.runtime.interfaces.Func1
import com.mdeo.script.runtime.interfaces.Predicate1

/**
 * Implementation of [ScriptSet] backed by a HashSet.
 * A mutable set that contains no duplicate elements.
 *
 * @param T the type of elements in this set
 */
class SetImpl<T> : AbstractCollection<T, HashSet<T>>, ScriptSet<T> {

    /**
     * Creates an empty set.
     */
    constructor() : super(HashSet())

    /**
     * Creates a set containing all elements from the given collection.
     *
     * @param elements the elements to add to the set
     */
    constructor(elements: kotlin.collections.Collection<T>) : super(HashSet(elements))

    /**
     * Creates a set containing all elements from the given iterable.
     *
     * @param elements the elements to add to the set
     */
    constructor(elements: Iterable<T>) : super(HashSet<T>().apply {
        for (element in elements) {
            add(element)
        }
    })

    override fun toSet(): ScriptSet<T> = SetImpl(backing)

    override fun clone(): ScriptSet<T> = SetImpl(backing)

    override fun excluding(item: Any?): ReadonlySet<T> {
        val result = HashSet(backing)
        result.remove(item)
        return SetImpl(result)
    }

    override fun excludingAll(col: ReadonlyCollection<T>): ReadonlySet<T> {
        val result = HashSet(backing)
        for (element in col) {
            result.remove(element)
        }
        return SetImpl(result)
    }

    override fun including(item: T): ReadonlySet<T> {
        val result = HashSet(backing)
        result.add(item)
        return SetImpl(result)
    }

    override fun includingAll(col: ReadonlyCollection<T>): ReadonlySet<T> {
        val result = HashSet(backing)
        for (element in col) {
            result.add(element)
        }
        return SetImpl(result)
    }

    override fun <U> map(mapper: Func1<T, U>): ScriptList<U> {
        val result = ArrayList<U>()
        for (element in backing) {
            result.add(mapper.call(element))
        }
        return ListImpl(result)
    }

    override fun reject(predicate: Predicate1<T>): ScriptSet<T> {
        val result = HashSet<T>()
        for (element in backing) {
            if (!predicate.call(element)) {
                result.add(element)
            }
        }
        return SetImpl(result)
    }

    override fun rejectOne(predicate: Predicate1<T>): ScriptSet<T> {
        val result = HashSet<T>()
        var removed = false
        for (element in backing) {
            if (!removed && predicate.call(element)) {
                removed = true
            } else {
                result.add(element)
            }
        }
        return SetImpl(result)
    }

    override fun filter(predicate: Predicate1<T>): ScriptSet<T> {
        val result = HashSet<T>()
        for (element in backing) {
            if (predicate.call(element)) {
                result.add(element)
            }
        }
        return SetImpl(result)
    }

    companion object {
        /**
         * Creates a set containing the specified elements.
         *
         * @param elements the elements to add to the set
         * @return a new set containing the elements
         */
        @JvmStatic
        fun <T> of(vararg elements: T): ScriptSet<T> {
            val set = SetImpl<T>()
            for (element in elements) {
                set.add(element)
            }
            return set
        }

        /**
         * Creates an empty set.
         *
         * @return a new empty set
         */
        @JvmStatic
        fun <T> empty(): ScriptSet<T> = SetImpl()
    }
}
