package com.mdeo.script.stdlib.impl.collections

import com.mdeo.script.runtime.interfaces.Func1
import com.mdeo.script.runtime.interfaces.Func2

/**
 * Abstract base class for ordered collections that maintain element order.
 *
 * @param T the type of elements in the collection
 * @param C the type of the backing JDK list
 */
abstract class AbstractOrderedCollection<T, C : MutableList<T>>(
    backing: C
) : AbstractCollection<T, C>(backing), OrderedCollection<T> {

    override fun at(index: Int): T {
        if (index < 0 || index >= backing.size) {
            throw IndexOutOfBoundsException("Index: $index, Size: ${backing.size}")
        }
        return backing[index]
    }

    override fun first(): T {
        if (backing.isEmpty()) {
            throw NoSuchElementException("Collection is empty")
        }
        return backing[0]
    }

    override fun last(): T {
        if (backing.isEmpty()) {
            throw NoSuchElementException("Collection is empty")
        }
        return backing[backing.size - 1]
    }

    override fun indexOf(item: T): Int = backing.indexOf(item)

    override fun invert(): ReadonlyOrderedCollection<T> {
        val reversed = ArrayList<T>(backing.size)
        for (i in backing.size - 1 downTo 0) {
            reversed.add(backing[i])
        }
        return ListImpl(reversed)
    }

    override fun removeAt(index: Int): T {
        if (index < 0 || index >= backing.size) {
            throw IndexOutOfBoundsException("Index: $index, Size: ${backing.size}")
        }
        return backing.removeAt(index)
    }

    @Suppress("UNCHECKED_CAST")
    override fun sort(): OrderedCollection<T> {
        backing.sortWith { a, b -> (a as Comparable<Any>).compareTo(b as Any) }
        return this
    }

    override fun sort(comparator: Func2<T, T, Int>): OrderedCollection<T> {
        backing.sortWith { a, b -> comparator.call(a, b) }
        return this
    }

    override fun <U : Comparable<U>> sortBy(keyExtractor: Func1<T, U>): OrderedCollection<T> {
        backing.sortWith { a, b -> keyExtractor.call(a).compareTo(keyExtractor.call(b)) }
        return this
    }

    override fun <U : Comparable<U>> sortedBy(keyExtractor: Func1<T, U>): ReadonlyOrderedCollection<T> {
        val sorted = ArrayList(backing)
        sorted.sortWith { a, b -> keyExtractor.call(a).compareTo(keyExtractor.call(b)) }
        return ListImpl(sorted)
    }

    override fun excluding(item: Any?): ReadonlyOrderedCollection<T> {
        val result = ArrayList<T>()
        var removed = false
        for (element in backing) {
            if (!removed && element == item) {
                removed = true
            } else {
                result.add(element)
            }
        }
        return ListImpl(result)
    }

    override fun excludingAll(col: ReadonlyCollection<T>): ReadonlyOrderedCollection<T> {
        val toExclude = HashSet<Any?>()
        for (element in col) {
            toExclude.add(element)
        }
        val result = ArrayList<T>()
        for (element in backing) {
            if (!toExclude.contains(element)) {
                result.add(element)
            }
        }
        return ListImpl(result)
    }

    override fun including(item: T): ReadonlyOrderedCollection<T> {
        val result = ArrayList(backing)
        result.add(item)
        return ListImpl(result)
    }

    override fun includingAll(col: ReadonlyCollection<T>): ReadonlyOrderedCollection<T> {
        val result = ArrayList(backing)
        for (element in col) {
            result.add(element)
        }
        return ListImpl(result)
    }

    override fun clone(): OrderedCollection<T> {
        return ListImpl(ArrayList(backing))
    }
}
