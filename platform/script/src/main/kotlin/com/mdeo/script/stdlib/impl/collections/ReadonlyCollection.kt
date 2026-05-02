package com.mdeo.script.stdlib.impl.collections

import com.mdeo.script.runtime.interfaces.Action1
import com.mdeo.script.runtime.interfaces.Func1
import com.mdeo.script.runtime.interfaces.Predicate1

/**
 * A readonly collection that provides query operations without modification capabilities.
 * This interface follows OCL/EOL collection semantics.
 *
 * @param T the type of elements contained in this collection
 */
interface ReadonlyCollection<out T> : ScriptIterable<T> {

    /**
     * Returns the number of elements in this collection.
     */
    fun size(): Int

    /**
     * Returns true if this collection contains no elements.
     */
    fun isEmpty(): Boolean

    /**
     * Returns true if this collection contains at least one element.
     */
    fun notEmpty(): Boolean

    /**
     * Returns true if this collection contains the specified element.
     *
     * @param item the element to check for
     */
    fun includes(item: Any?): Boolean

    /**
     * Returns true if this collection does not contain the specified element.
     *
     * @param item the element to check for
     */
    fun excludes(item: Any?): Boolean

    /**
     * Returns true if this collection contains all elements from the given collection.
     *
     * @param col the collection of elements to check for
     */
    fun includesAll(col: ReadonlyCollection<@UnsafeVariance T>): Boolean

    /**
     * Returns true if this collection contains none of the elements from the given collection.
     *
     * @param col the collection of elements to check for
     */
    fun excludesAll(col: ReadonlyCollection<@UnsafeVariance T>): Boolean

    /**
     * Returns the count of occurrences of the specified item in this collection.
     *
     * @param item the element to count
     */
    fun count(item: Any?): Int

    /**
     * Returns the count of elements that match the given predicate.
     *
     * @param predicate the predicate to apply to each element
     */
    fun count(predicate: Predicate1<@UnsafeVariance T>): Int

    /**
     * Returns a new collection containing all elements except the specified item.
     *
     * @param item the element to exclude
     */
    fun excluding(item: Any?): ReadonlyCollection<T>

    /**
     * Returns a new collection containing all elements except those in the given collection.
     *
     * @param col the collection of elements to exclude
     */
    fun excludingAll(col: ReadonlyCollection<@UnsafeVariance T>): ReadonlyCollection<T>

    /**
     * Returns a new collection containing all elements plus the specified item.
     *
     * @param item the element to include
     */
    fun including(item: @UnsafeVariance T): ReadonlyCollection<T>

    /**
     * Returns a new collection containing all elements plus those from the given collection.
     *
     * @param col the collection of elements to include
     */
    fun includingAll(col: ReadonlyCollection<@UnsafeVariance T>): ReadonlyCollection<T>

    /**
     * Returns a random element from this collection.
     *
     * @throws NoSuchElementException if the collection is empty
     */
    fun random(): T

    /**
     * Returns the sum of all elements (elements must be numeric).
     */
    fun sum(): Double

    /**
     * Concatenates all elements to a string without separator.
     */
    fun concat(): String

    /**
     * Concatenates all elements to a string with the given separator.
     *
     * @param separator the separator to use between elements
     */
    fun concat(separator: String): String

    /**
     * Flattens nested collections into a single collection.
     */
    fun flatten(): ReadonlyCollection<Any?>

    /**
     * Converts this collection to a Bag.
     */
    fun toBag(): Bag<@UnsafeVariance T>

    /**
     * Converts this collection to an OrderedSet.
     */
    fun toOrderedSet(): OrderedSet<@UnsafeVariance T>

    /**
     * Converts this collection to a List.
     */
    fun toList(): ScriptList<@UnsafeVariance T>

    /**
     * Converts this collection to a Set.
     */
    fun toSet(): ScriptSet<@UnsafeVariance T>

    /**
     * Creates a clone of this collection.
     */
    fun clone(): Collection<@UnsafeVariance T>

    /**
     * Returns true if at least n elements match the given predicate.
     *
     * @param predicate the predicate to apply
     * @param n the minimum number of matches required
     */
    fun atLeastNMatch(predicate: Predicate1<@UnsafeVariance T>, n: Int): Boolean

    /**
     * Returns true if at most n elements match the given predicate.
     *
     * @param predicate the predicate to apply
     * @param n the maximum number of matches allowed
     */
    fun atMostNMatch(predicate: Predicate1<@UnsafeVariance T>, n: Int): Boolean

    /**
     * Groups elements by a key function.
     *
     * @param keyMapper the function to extract the key from each element
     */
    fun aggregate(keyMapper: Func1<@UnsafeVariance T, Any?>): ScriptMap<Any?, ScriptList<@UnsafeVariance T>>

    /**
     * Maps each element to a new value. Always returns a [ScriptList] regardless of the
     * underlying collection type.
     *
     * @param mapper the mapping function
     */
    fun <U> map(mapper: Func1<@UnsafeVariance T, U>): ScriptList<U>

    /**
     * Returns true if at least one element matches the predicate.
     *
     * @param predicate the predicate to apply
     */
    fun exists(predicate: Predicate1<@UnsafeVariance T>): Boolean

    /**
     * Performs the given action for each element in this collection.
     *
     * @param action the action to be performed for each element
     */
    fun forEach(action: Action1<@UnsafeVariance T>)

    /**
     * Returns true if all elements match the predicate.
     *
     * @param predicate the predicate to apply
     */
    fun all(predicate: Predicate1<@UnsafeVariance T>): Boolean

    /**
     * Creates a map by associating each element with a computed value.
     *
     * @param valueMapper the function to compute the value for each element
     */
    fun <U> associate(valueMapper: Func1<@UnsafeVariance T, U>): ReadonlyMap<@UnsafeVariance T, U>

    /**
     * Returns true if exactly n elements match the predicate.
     *
     * @param predicate the predicate to apply
     * @param n the exact number of matches required
     */
    fun nMatch(predicate: Predicate1<@UnsafeVariance T>, n: Int): Boolean

    /**
     * Returns true if no elements match the predicate.
     *
     * @param predicate the predicate to apply
     */
    fun none(predicate: Predicate1<@UnsafeVariance T>): Boolean

    /**
     * Returns true if exactly one element matches the predicate.
     *
     * @param predicate the predicate to apply
     */
    fun one(predicate: Predicate1<@UnsafeVariance T>): Boolean

    /**
     * Returns a new collection containing only elements that don't match the predicate.
     *
     * @param predicate the predicate to apply
     */
    fun reject(predicate: Predicate1<@UnsafeVariance T>): Collection<@UnsafeVariance T>

    /**
     * Returns a new collection with the first matching element removed.
     *
     * @param predicate the predicate to apply
     */
    fun rejectOne(predicate: Predicate1<@UnsafeVariance T>): Collection<@UnsafeVariance T>

    /**
     * Returns a new collection containing only elements that match the predicate.
     * Alias for select in OCL.
     *
     * @param predicate the predicate to apply
     */
    fun filter(predicate: Predicate1<@UnsafeVariance T>): Collection<@UnsafeVariance T>

    /**
     * Finds the first element matching the predicate.
     *
     * @param predicate the predicate to apply
     * @return the first matching element or null if not found
     */
    fun find(predicate: Predicate1<@UnsafeVariance T>): T?

    /**
     * Returns an ordered collection sorted by the given key extractor.
     *
     * @param keyExtractor the function to extract the sort key
     */
    fun <U : Comparable<U>> sortedBy(keyExtractor: Func1<@UnsafeVariance T, U>): ReadonlyOrderedCollection<@UnsafeVariance T>

    /**
     * Maps each element to a collection and flattens the results into a single collection.
     *
     * @param mapper the function that maps each element to a collection
     */
    fun <U> flatMap(mapper: Func1<@UnsafeVariance T, ReadonlyCollection<U>>): Collection<U>

    /**
     * Returns the first element in this collection.
     *
     * @throws NoSuchElementException if the collection is empty
     */
    fun first(): T

    /**
     * Returns the first element in this collection, or null if the collection is empty.
     */
    fun firstOrNull(): T?
}
