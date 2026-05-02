package com.mdeo.script.stdlib.impl.collections

import com.mdeo.script.runtime.interfaces.Func1
import com.mdeo.script.runtime.interfaces.Predicate1

/**
 * Implementation of [OrderedSet] backed by a LinkedHashSet.
 * A mutable ordered set that maintains insertion order and contains no duplicates.
 *
 * @param T the type of elements in this ordered set
 */
class OrderedSetImpl<T> : AbstractCollection<T, LinkedHashSet<T>>, OrderedSet<T> {

    /**
     * Creates an empty ordered set.
     */
    constructor() : super(LinkedHashSet())

    /**
     * Creates an ordered set containing all elements from the given collection.
     *
     * @param elements the elements to add to the ordered set
     */
    constructor(elements: kotlin.collections.Collection<T>) : super(LinkedHashSet(elements))

    /**
     * Creates an ordered set containing all elements from the given iterable.
     *
     * @param elements the elements to add to the ordered set
     */
    constructor(elements: Iterable<T>) : super(LinkedHashSet<T>().apply {
        for (element in elements) {
            add(element)
        }
    })

    override fun at(index: Int): T {
        if (index < 0 || index >= backing.size) {
            throw IndexOutOfBoundsException("Index: $index, Size: ${backing.size}")
        }
        var i = 0
        for (element in backing) {
            if (i == index) {
                return element
            }
            i++
        }
        throw IndexOutOfBoundsException("Index: $index, Size: ${backing.size}")
    }

    override fun first(): T {
        if (backing.isEmpty()) {
            throw NoSuchElementException("Collection is empty")
        }
        return backing.iterator().next()
    }

    override fun last(): T {
        if (backing.isEmpty()) {
            throw NoSuchElementException("Collection is empty")
        }
        var last: T? = null
        for (element in backing) {
            last = element
        }
        @Suppress("UNCHECKED_CAST")
        return last as T
    }

    override fun indexOf(item: T): Int {
        var index = 0
        for (element in backing) {
            if (element == item) {
                return index
            }
            index++
        }
        return -1
    }

    override fun invert(): ReadonlyOrderedCollection<T> {
        val list = ArrayList(backing)
        list.reverse()
        return OrderedSetImpl(list)
    }

    override fun removeAt(index: Int): T {
        if (index < 0 || index >= backing.size) {
            throw IndexOutOfBoundsException("Index: $index, Size: ${backing.size}")
        }
        var i = 0
        val iterator = backing.iterator()
        while (iterator.hasNext()) {
            val element = iterator.next()
            if (i == index) {
                iterator.remove()
                return element
            }
            i++
        }
        throw IndexOutOfBoundsException("Index: $index, Size: ${backing.size}")
    }

    override fun <U : Comparable<U>> sortBy(keyExtractor: Func1<T, U>): OrderedCollection<T> {
        val sorted = ArrayList(backing)
        sorted.sortWith { a, b -> keyExtractor.call(a).compareTo(keyExtractor.call(b)) }
        backing.clear()
        backing.addAll(sorted)
        return this
    }

    override fun <U : Comparable<U>> sortedBy(keyExtractor: Func1<T, U>): ReadonlyOrderedCollection<T> {
        val sorted = ArrayList(backing)
        sorted.sortWith { a, b -> keyExtractor.call(a).compareTo(keyExtractor.call(b)) }
        return OrderedSetImpl(sorted)
    }

    override fun toOrderedSet(): OrderedSet<T> = OrderedSetImpl(backing)

    override fun clone(): OrderedSet<T> = OrderedSetImpl(backing)

    override fun excluding(item: Any?): ReadonlyOrderedSet<T> {
        val result = LinkedHashSet(backing)
        result.remove(item)
        return OrderedSetImpl(result)
    }

    override fun excludingAll(col: ReadonlyCollection<T>): ReadonlyOrderedSet<T> {
        val result = LinkedHashSet(backing)
        for (element in col) {
            result.remove(element)
        }
        return OrderedSetImpl(result)
    }

    override fun including(item: T): ReadonlyOrderedSet<T> {
        val result = LinkedHashSet(backing)
        result.add(item)
        return OrderedSetImpl(result)
    }

    override fun includingAll(col: ReadonlyCollection<T>): ReadonlyOrderedSet<T> {
        val result = LinkedHashSet(backing)
        for (element in col) {
            result.add(element)
        }
        return OrderedSetImpl(result)
    }

    override fun <U> map(mapper: Func1<T, U>): ScriptList<U> {
        val result = ArrayList<U>()
        for (element in backing) {
            result.add(mapper.call(element))
        }
        return ListImpl(result)
    }

    override fun reject(predicate: Predicate1<T>): OrderedSet<T> {
        val result = LinkedHashSet<T>()
        for (element in backing) {
            if (!predicate.call(element)) {
                result.add(element)
            }
        }
        return OrderedSetImpl(result)
    }

    override fun rejectOne(predicate: Predicate1<T>): OrderedSet<T> {
        val result = LinkedHashSet<T>()
        var removed = false
        for (element in backing) {
            if (!removed && predicate.call(element)) {
                removed = true
            } else {
                result.add(element)
            }
        }
        return OrderedSetImpl(result)
    }

    override fun filter(predicate: Predicate1<T>): OrderedSet<T> {
        val result = LinkedHashSet<T>()
        for (element in backing) {
            if (predicate.call(element)) {
                result.add(element)
            }
        }
        return OrderedSetImpl(result)
    }

    companion object {
        /**
         * Creates an ordered set containing the specified elements.
         *
         * @param elements the elements to add to the ordered set
         * @return a new ordered set containing the elements
         */
        @JvmStatic
        fun <T> of(vararg elements: T): OrderedSet<T> {
            val set = OrderedSetImpl<T>()
            for (element in elements) {
                set.add(element)
            }
            return set
        }

        /**
         * Creates an empty ordered set.
         *
         * @return a new empty ordered set
         */
        @JvmStatic
        fun <T> empty(): OrderedSet<T> = OrderedSetImpl()
    }
}
