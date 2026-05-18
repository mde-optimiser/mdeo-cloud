package com.mdeo.script.stdlib.impl.collections

import com.mdeo.script.runtime.interfaces.Func1
import com.mdeo.script.runtime.interfaces.Func2

/**
 * A mutable ordered collection that maintains element order and provides modification operations.
 * This interface follows OCL/EOL collection semantics.
 *
 * @param T the type of elements contained in this collection
 */
interface OrderedCollection<T> : ReadonlyOrderedCollection<T>, Collection<T> {

    /**
     * Removes and returns the element at the specified index.
     *
     * @param index the zero-based index
     * @return the removed element
     * @throws IndexOutOfBoundsException if the index is out of range
     */
    fun removeAt(index: Int): T

    /**
     * Sorts this collection in-place in natural order.
     * Elements must implement [Comparable].
     *
     * @return this collection after sorting
     */
    fun sort(): OrderedCollection<T>

    /**
     * Sorts this collection in-place using the given comparator.
     *
     * @param comparator a function that returns a negative integer, zero, or a positive integer
     *   when the first argument is less than, equal to, or greater than the second
     * @return this collection after sorting
     */
    fun sort(comparator: Func2<T, T, Int>): OrderedCollection<T>

    /**
     * Sorts this collection in-place by the given key extractor.
     *
     * @param keyExtractor the function to extract the sort key
     * @return this collection after sorting
     */
    fun <U : Comparable<U>> sortBy(keyExtractor: Func1<T, U>): OrderedCollection<T>
}
