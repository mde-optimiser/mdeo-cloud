package com.mdeo.script.stdlib.impl.collections

import org.apache.commons.collections4.multiset.HashMultiSet
import com.mdeo.script.runtime.interfaces.Action1
import com.mdeo.script.runtime.interfaces.Func1
import com.mdeo.script.runtime.interfaces.Func2
import com.mdeo.script.runtime.interfaces.Predicate1
import java.util.concurrent.ThreadLocalRandom

/**
 * Implementation of [Bag] backed by Apache Commons HashMultiSet.
 * A mutable bag (multiset) that allows duplicate elements with count tracking.
 *
 * @param T the type of elements in this bag
 */
class BagImpl<T> : Bag<T>, DeltaTarget {

    private val backing: HashMultiSet<T>

    /**
     * Mutation counter, see [DeltaTarget.deltaVersion].
     */
    private var version: Long = 0

    override val deltaVersion: Long get() = version

    override fun deltaSnapshot(): List<Any?> = ArrayList<Any?>(backing)

    override fun deltaSplice(index: Int, deleteCount: Int, insert: List<Any?>) {
        throw UnsupportedOperationException("A bag has no order to splice into")
    }

    @Suppress("UNCHECKED_CAST")
    override fun deltaAdd(values: List<Any?>) {
        version++
        for (value in values) backing.add(value as T)
    }

    @Suppress("UNCHECKED_CAST")
    override fun deltaRemove(values: List<Any?>) {
        version++
        for (value in values) backing.remove(value as T, 1)
    }

    @Suppress("UNCHECKED_CAST")
    override fun deltaSetCount(value: Any?, count: Int) {
        version++
        backing.setCount(value as T, count)
    }

    @Suppress("UNCHECKED_CAST")
    override fun deltaReplace(elements: List<Any?>) {
        version++
        backing.clear()
        for (element in elements) backing.add(element as T)
    }

    /**
     * Creates an empty bag.
     */
    constructor() {
        backing = HashMultiSet()
    }

    /**
     * Creates a bag containing all elements from the given collection.
     *
     * @param elements the elements to add to the bag
     */
    constructor(elements: kotlin.collections.Collection<T>) {
        backing = HashMultiSet(elements)
    }

    /**
     * Creates a bag containing all elements from the given iterable.
     *
     * @param elements the elements to add to the bag
     */
    constructor(elements: Iterable<T>) {
        backing = HashMultiSet()
        for (element in elements) {
            backing.add(element)
        }
    }

    /**
     * Creates a bag that takes ownership of [owned] rather than copying it.
     *
     * The derivation operators below (`filter`, `reject`, `including`, …) each build a
     * fresh [HashMultiSet] that is not reachable from anywhere else. Handing it to the
     * [kotlin.collections.Collection] constructor would copy every element into a second
     * [HashMultiSet]; adopting it directly halves the work. Callers must not retain a reference
     * to [owned] afterwards.
     *
     * @param owned the backing bag to adopt.
     * @param marker distinguishes this constructor from the [kotlin.collections.Collection]
     *   one, which [HashMultiSet] would otherwise also match.
     */
    private constructor(owned: HashMultiSet<T>, @Suppress("UNUSED_PARAMETER") marker: Unit) {
        backing = owned
    }

    /** Wraps a freshly built [bag] without copying it. See the adopting constructor. */
    private fun adopt(bag: HashMultiSet<T>): BagImpl<T> = BagImpl(bag, Unit)

    override fun iterator(): Iterator<T> = backing.iterator()

    override fun size(): Int = backing.size

    override fun isEmpty(): Boolean = backing.isEmpty()

    override fun notEmpty(): Boolean = !backing.isEmpty()

    override fun includes(item: Any?): Boolean = backing.contains(item)

    override fun excludes(item: Any?): Boolean = !backing.contains(item)

    override fun includesAll(col: ReadonlyCollection<T>): Boolean {
        for (element in col) {
            if (!backing.contains(element)) {
                return false
            }
        }
        return true
    }

    override fun excludesAll(col: ReadonlyCollection<T>): Boolean {
        for (element in col) {
            if (backing.contains(element)) {
                return false
            }
        }
        return true
    }

    override fun count(item: Any?): Int = backing.getCount(item)

    override fun count(predicate: Predicate1<T>): Int {
        var count = 0
        for (element in backing.uniqueSet()) {
            if (predicate.call(element)) {
                count += backing.getCount(element)
            }
        }
        return count
    }

    override fun excluding(item: Any?): ReadonlyCollection<T> {
        val result = HashMultiSet(backing)
        result.remove(item, 1)
        return adopt(result)
    }

    override fun excludingAll(col: ReadonlyCollection<T>): ReadonlyCollection<T> {
        val result = HashMultiSet(backing)
        for (element in col) {
            result.remove(element, 1)
        }
        return adopt(result)
    }

    override fun including(item: T): ReadonlyCollection<T> {
        val result = HashMultiSet(backing)
        result.add(item)
        return adopt(result)
    }

    override fun includingAll(col: ReadonlyCollection<T>): ReadonlyCollection<T> {
        val result = HashMultiSet(backing)
        for (element in col) {
            result.add(element)
        }
        return adopt(result)
    }

    override fun random(): T {
        if (backing.isEmpty()) {
            throw NoSuchElementException("Cannot get random element from empty collection")
        }
        val index = ThreadLocalRandom.current().nextInt(backing.size)
        var i = 0
        for (element in backing) {
            if (i == index) {
                return element
            }
            i++
        }
        throw IllegalStateException("Should not reach here")
    }

    override fun sum(): Double {
        var total = 0.0
        for (element in backing) {
            when (element) {
                is Number -> total += element.toDouble()
                else -> throw IllegalArgumentException("Cannot sum non-numeric element: $element")
            }
        }
        return total
    }

    override fun concat(): String {
        val sb = StringBuilder()
        for (element in backing) {
            sb.append(element?.toString() ?: "null")
        }
        return sb.toString()
    }

    override fun concat(separator: String): String {
        val sb = StringBuilder()
        var first = true
        for (element in backing) {
            if (!first) {
                sb.append(separator)
            }
            sb.append(element?.toString() ?: "null")
            first = false
        }
        return sb.toString()
    }

    override fun flatten(): ReadonlyCollection<Any?> {
        val result = ArrayList<Any?>()
        flattenInto(result, backing)
        return ListImpl(result)
    }

    private fun flattenInto(result: MutableList<Any?>, source: Iterable<*>) {
        for (element in source) {
            when (element) {
                is Iterable<*> -> flattenInto(result, element)
                else -> result.add(element)
            }
        }
    }

    override fun toBag(): Bag<T> = BagImpl(backing)

    override fun toOrderedSet(): OrderedSet<T> = OrderedSetImpl(backing.uniqueSet())

    override fun toList(): ScriptList<T> {
        val list = ArrayList<T>()
        for (element in backing) {
            list.add(element)
        }
        return ListImpl(list)
    }

    override fun toSet(): ScriptSet<T> = SetImpl(backing.uniqueSet())

    override fun clone(): Bag<T> = BagImpl(backing)

    override fun atLeastNMatch(predicate: Predicate1<T>, n: Int): Boolean {
        var count = 0
        for (element in backing) {
            if (predicate.call(element)) {
                count++
                if (count >= n) {
                    return true
                }
            }
        }
        return count >= n
    }

    override fun atMostNMatch(predicate: Predicate1<T>, n: Int): Boolean {
        var count = 0
        for (element in backing) {
            if (predicate.call(element)) {
                count++
                if (count > n) {
                    return false
                }
            }
        }
        return true
    }

    override fun aggregate(keyMapper: Func1<T, Any?>): ScriptMap<Any?, ScriptList<T>> {
        val groups = LinkedHashMap<Any?, MutableList<T>>()
        for (element in backing) {
            val key = keyMapper.call(element)
            groups.computeIfAbsent(key) { ArrayList() }.add(element)
        }
        val result = MapImpl<Any?, ScriptList<T>>()
        for ((key, value) in groups) {
            result.put(key, ListImpl(value))
        }
        return result
    }

    override fun <U> map(mapper: Func1<T, U>): ScriptList<U> {
        val result = ArrayList<U>()
        for (element in backing) {
            result.add(mapper.call(element))
        }
        return ListImpl(result)
    }

    override fun exists(predicate: Predicate1<T>): Boolean {
        for (element in backing) {
            if (predicate.call(element)) {
                return true
            }
        }
        return false
    }

    override fun forEach(action: Action1<T>) {
        for (element in backing) {
            action.call(element)
        }
    }

    override fun all(predicate: Predicate1<T>): Boolean {
        for (element in backing) {
            if (!predicate.call(element)) {
                return false
            }
        }
        return true
    }

    override fun <U> associate(valueMapper: Func1<T, U>): ReadonlyMap<T, U> {
        val result = MapImpl<T, U>()
        for (element in backing.uniqueSet()) {
            result.put(element, valueMapper.call(element))
        }
        return result
    }

    override fun nMatch(predicate: Predicate1<T>, n: Int): Boolean {
        var count = 0
        for (element in backing) {
            if (predicate.call(element)) {
                count++
                if (count > n) {
                    return false
                }
            }
        }
        return count == n
    }

    override fun none(predicate: Predicate1<T>): Boolean {
        for (element in backing) {
            if (predicate.call(element)) {
                return false
            }
        }
        return true
    }

    override fun one(predicate: Predicate1<T>): Boolean {
        var found = false
        for (element in backing) {
            if (predicate.call(element)) {
                if (found) {
                    return false
                }
                found = true
            }
        }
        return found
    }

    override fun reject(predicate: Predicate1<T>): Bag<T> {
        val result = HashMultiSet<T>()
        for (element in backing) {
            if (!predicate.call(element)) {
                result.add(element)
            }
        }
        return adopt(result)
    }

    override fun rejectOne(predicate: Predicate1<T>): Bag<T> {
        val result = HashMultiSet<T>()
        var removed = false
        for (element in backing) {
            if (!removed && predicate.call(element)) {
                removed = true
            } else {
                result.add(element)
            }
        }
        return adopt(result)
    }

    override fun filter(predicate: Predicate1<T>): Bag<T> {
        val result = HashMultiSet<T>()
        for (element in backing) {
            if (predicate.call(element)) {
                result.add(element)
            }
        }
        return adopt(result)
    }

    override fun find(predicate: Predicate1<T>): T? {
        for (element in backing) {
            if (predicate.call(element)) {
                return element
            }
        }
        return null
    }

    override fun <U : Comparable<U>> sortedBy(keyExtractor: Func1<T, U>): ReadonlyOrderedCollection<T> {
        val sorted = ArrayList<T>()
        for (element in backing) {
            sorted.add(element)
        }
        sorted.sortWith { a, b -> keyExtractor.call(a).compareTo(keyExtractor.call(b)) }
        return ListImpl(sorted)
    }

    @Suppress("UNCHECKED_CAST")
    override fun sorted(): ReadonlyOrderedCollection<T> {
        val sorted = ArrayList<T>()
        for (element in backing) {
            sorted.add(element)
        }
        sorted.sortWith { a, b -> (a as Comparable<Any>).compareTo(b as Any) }
        return ListImpl(sorted)
    }

    override fun sorted(comparator: Func2<T, T, Int>): ReadonlyOrderedCollection<T> {
        val sorted = ArrayList<T>()
        for (element in backing) {
            sorted.add(element)
        }
        sorted.sortWith { a, b -> comparator.call(a, b) }
        return ListImpl(sorted)
    }

    override fun <U> flatMap(mapper: Func1<T, ReadonlyCollection<U>>): Collection<U> {
        val result = ArrayList<U>()
        for (element in backing) {
            for (mapped in mapper.call(element)) {
                result.add(mapped)
            }
        }
        return ListImpl(result)
    }

    override fun first(): T = backing.iterator().next()

    override fun firstOrNull(): T? = backing.firstOrNull()

    override fun add(item: T): Boolean {
        version++
        backing.add(item)
        return true
    }

    override fun addAll(col: ReadonlyCollection<T>): Boolean {
        version++
        var modified = false
        for (element in col) {
            backing.add(element)
            modified = true
        }
        return modified
    }

    override fun clear() {
        version++
        backing.clear()
    }

    // MultiSet.remove(item, n) reports the count the item had *before* the removal, so a
    // non-zero result is what signals that something was actually removed.
    override fun remove(item: T): Boolean {
        version++
        return backing.remove(item, 1) > 0
    }

    override fun removeAll(col: ReadonlyCollection<T>): Boolean {
        version++
        var modified = false
        for (element in col) {
            if (backing.remove(element, 1) > 0) {
                modified = true
            }
        }
        return modified
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Bag<*>) return false
        if (size() != other.size()) return false
        return backing == (other as? BagImpl<*>)?.backing
    }

    override fun hashCode(): Int = backing.hashCode()

    override fun toString(): String = backing.toString()

    companion object {
        /**
         * Creates a bag containing the specified elements.
         *
         * @param elements the elements to add to the bag
         * @return a new bag containing the elements
         */
        @JvmStatic
        fun <T> of(vararg elements: T): Bag<T> {
            val bag = BagImpl<T>()
            for (element in elements) {
                bag.add(element)
            }
            return bag
        }

        /**
         * Creates an empty bag.
         *
         * @return a new empty bag
         */
        @JvmStatic
        fun <T> empty(): Bag<T> = BagImpl()
    }
}
