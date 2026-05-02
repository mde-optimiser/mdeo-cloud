package com.mdeo.script.stdlib.impl.collections

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import com.mdeo.script.runtime.interfaces.Predicate1
import com.mdeo.script.runtime.interfaces.Func1
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertNotNull

/**
 * Comprehensive tests for OrderedSetImpl.
 */
class OrderedSetImplTest {

    // ==================== size() tests ====================
    @Test
    fun `size returns 0 for empty ordered set`() {
        val set = OrderedSetImpl<Int>()
        assertEquals(0, set.size())
    }

    @Test
    fun `size returns correct count`() {
        val set = OrderedSetImpl.of(1, 2, 3)
        assertEquals(3, set.size())
    }

    @Test
    fun `size does not count duplicates`() {
        val set = OrderedSetImpl.of(1, 1, 2, 2, 3)
        assertEquals(3, set.size())
    }

    @Test
    fun `size updates after adding elements`() {
        val set = OrderedSetImpl<Int>()
        set.add(1)
        set.add(2)
        assertEquals(2, set.size())
    }

    @Test
    fun `size updates after removing elements`() {
        val set = OrderedSetImpl.of(1, 2, 3)
        set.remove(2)
        assertEquals(2, set.size())
    }

    // ==================== order preservation tests ====================
    @Test
    fun `maintains insertion order`() {
        val set = OrderedSetImpl.of(3, 1, 2)
        assertEquals(3, set.first())
        assertEquals(2, set.last())
    }

    @Test
    fun `at returns element at index`() {
        val set = OrderedSetImpl.of(10, 20, 30)
        assertEquals(10, set.at(0))
        assertEquals(20, set.at(1))
        assertEquals(30, set.at(2))
    }

    @Test
    fun `indexOf returns correct index`() {
        val set = OrderedSetImpl.of(10, 20, 30)
        assertEquals(0, set.indexOf(10))
        assertEquals(1, set.indexOf(20))
        assertEquals(2, set.indexOf(30))
    }

    @Test
    fun `indexOf returns -1 for missing element`() {
        val set = OrderedSetImpl.of(1, 2, 3)
        assertEquals(-1, set.indexOf(5))
    }

    @Test
    fun `duplicate add does not change order`() {
        val set = OrderedSetImpl.of(1, 2, 3)
        set.add(1) // duplicate
        assertEquals(1, set.first())
        assertEquals(3, set.size())
    }

    // ==================== first() / last() tests ====================
    @Test
    fun `first returns first element`() {
        val set = OrderedSetImpl.of(1, 2, 3)
        assertEquals(1, set.first())
    }

    @Test
    fun `last returns last element`() {
        val set = OrderedSetImpl.of(1, 2, 3)
        assertEquals(3, set.last())
    }

    @Test
    fun `first throws for empty set`() {
        val set = OrderedSetImpl<Int>()
        assertThrows<NoSuchElementException> { set.first() }
    }

    @Test
    fun `last throws for empty set`() {
        val set = OrderedSetImpl<Int>()
        assertThrows<NoSuchElementException> { set.last() }
    }

    @Test
    fun `single element is both first and last`() {
        val set = OrderedSetImpl.of(42)
        assertEquals(42, set.first())
        assertEquals(42, set.last())
    }

    // ==================== at() tests ====================
    @Test
    fun `at throws for negative index`() {
        val set = OrderedSetImpl.of(1, 2, 3)
        assertThrows<IndexOutOfBoundsException> { set.at(-1) }
    }

    @Test
    fun `at throws for index out of bounds`() {
        val set = OrderedSetImpl.of(1, 2, 3)
        assertThrows<IndexOutOfBoundsException> { set.at(3) }
    }

    @Test
    fun `at throws for empty set`() {
        val set = OrderedSetImpl<Int>()
        assertThrows<IndexOutOfBoundsException> { set.at(0) }
    }

    @Test
    fun `at returns first at index 0`() {
        val set = OrderedSetImpl.of(10, 20, 30)
        assertEquals(10, set.at(0))
    }

    @Test
    fun `at returns last at last index`() {
        val set = OrderedSetImpl.of(10, 20, 30)
        assertEquals(30, set.at(2))
    }

    // ==================== invert() tests ====================
    @Test
    fun `invert reverses order`() {
        val set = OrderedSetImpl.of(1, 2, 3)
        val inverted = set.invert()
        assertEquals(3, inverted.at(0))
        assertEquals(2, inverted.at(1))
        assertEquals(1, inverted.at(2))
    }

    @Test
    fun `invert returns empty for empty set`() {
        val set = OrderedSetImpl<Int>()
        assertTrue(set.invert().isEmpty())
    }

    @Test
    fun `invert creates new collection`() {
        val set = OrderedSetImpl.of(1, 2, 3)
        val inverted = set.invert()
        set.add(4)
        assertEquals(3, inverted.size())
    }

    @Test
    fun `invert single element returns same`() {
        val set = OrderedSetImpl.of(1)
        val inverted = set.invert()
        assertEquals(1, inverted.first())
    }

    @Test
    fun `invert preserves all elements`() {
        val set = OrderedSetImpl.of(1, 2, 3)
        val inverted = set.invert()
        assertTrue(inverted.includes(1))
        assertTrue(inverted.includes(2))
        assertTrue(inverted.includes(3))
    }

    // ==================== removeAt() tests ====================
    @Test
    fun `removeAt removes element at index`() {
        val set = OrderedSetImpl.of(1, 2, 3)
        val removed = set.removeAt(1)
        assertEquals(2, removed)
        assertEquals(2, set.size())
    }

    @Test
    fun `removeAt throws for negative index`() {
        val set = OrderedSetImpl.of(1, 2, 3)
        assertThrows<IndexOutOfBoundsException> { set.removeAt(-1) }
    }

    @Test
    fun `removeAt throws for index out of bounds`() {
        val set = OrderedSetImpl.of(1, 2, 3)
        assertThrows<IndexOutOfBoundsException> { set.removeAt(3) }
    }

    @Test
    fun `removeAt first element works`() {
        val set = OrderedSetImpl.of(1, 2, 3)
        val removed = set.removeAt(0)
        assertEquals(1, removed)
        assertEquals(2, set.first())
    }

    @Test
    fun `removeAt last element works`() {
        val set = OrderedSetImpl.of(1, 2, 3)
        val removed = set.removeAt(2)
        assertEquals(3, removed)
        assertEquals(2, set.last())
    }

    // ==================== sortBy() tests ====================
    @Test
    fun `sortBy sorts in place`() {
        val set = OrderedSetImpl.of(3, 1, 2)
        set.sortBy(Func1 { it })
        assertEquals(1, set.at(0))
        assertEquals(2, set.at(1))
        assertEquals(3, set.at(2))
    }

    @Test
    fun `sortBy with custom key`() {
        val set = OrderedSetImpl.of("apple", "cat", "banana")
        set.sortBy(Func1 { it.length })
        assertEquals("cat", set.first())
    }

    @Test
    fun `sortBy returns same set`() {
        val set = OrderedSetImpl.of(3, 1, 2)
        val result = set.sortBy(Func1 { it })
        assertTrue(result === set)
    }

    @Test
    fun `sortBy handles empty set`() {
        val set = OrderedSetImpl<Int>()
        set.sortBy(Func1 { it })
        assertTrue(set.isEmpty())
    }

    @Test
    fun `sortBy handles single element`() {
        val set = OrderedSetImpl.of(1)
        set.sortBy(Func1 { it })
        assertEquals(1, set.first())
    }

    // ==================== sortedBy() tests ====================
    @Test
    fun `sortedBy returns new sorted collection`() {
        val set = OrderedSetImpl.of(3, 1, 2)
        val sorted = set.sortedBy(Func1 { it })
        assertEquals(1, sorted.first())
        assertEquals(3, set.first()) // Original unchanged
    }

    @Test
    fun `sortedBy with custom key`() {
        val set = OrderedSetImpl.of("apple", "cat", "banana")
        val sorted = set.sortedBy(Func1 { it.length })
        assertEquals("cat", sorted.first())
    }

    @Test
    fun `sortedBy does not modify original`() {
        val set = OrderedSetImpl.of(3, 1, 2)
        set.sortedBy(Func1 { it })
        assertEquals(3, set.first())
    }

    @Test
    fun `sortedBy handles empty set`() {
        val set = OrderedSetImpl<Int>()
        val sorted = set.sortedBy(Func1 { it })
        assertTrue(sorted.isEmpty())
    }

    @Test
    fun `sortedBy preserves all elements`() {
        val set = OrderedSetImpl.of(3, 1, 2)
        val sorted = set.sortedBy(Func1 { it })
        assertEquals(3, sorted.size())
    }

    // ==================== including() / excluding() tests ====================
    @Test
    fun `excluding returns ordered set`() {
        val set = OrderedSetImpl.of(1, 2, 3)
        val result = set.excluding(2)
        assertTrue(result is ReadonlyOrderedSet)
        assertEquals(2, result.size())
    }

    @Test
    fun `including returns ordered set`() {
        val set = OrderedSetImpl.of(1, 2)
        val result = set.including(3)
        assertTrue(result is ReadonlyOrderedSet)
        assertEquals(3, result.size())
    }

    @Test
    fun `excludingAll returns ordered set`() {
        val set = OrderedSetImpl.of(1, 2, 3, 4)
        val result = set.excludingAll(ListImpl.of(2, 4))
        assertTrue(result is ReadonlyOrderedSet)
        assertEquals(2, result.size())
    }

    @Test
    fun `includingAll returns ordered set`() {
        val set = OrderedSetImpl.of(1, 2)
        val result = set.includingAll(ListImpl.of(3, 4))
        assertTrue(result is ReadonlyOrderedSet)
        assertEquals(4, result.size())
    }

    @Test
    fun `including duplicate does not add`() {
        val set = OrderedSetImpl.of(1, 2)
        val result = set.including(1)
        assertEquals(2, result.size())
    }

    // ==================== map() tests ====================
    @Test
    fun `map returns list type`() {
        val set = OrderedSetImpl.of(1, 2, 3)
        val mapped = set.map(Func1 { it * 2 })
        @Suppress("USELESS_IS_CHECK")
        assertTrue(mapped is ScriptList)
    }

    @Test
    fun `map preserves order`() {
        val set = OrderedSetImpl.of(1, 2, 3)
        val mapped = set.map(Func1 { it * 10 })
        assertEquals(10, mapped.first())
        assertEquals(30, mapped.last())
    }

    @Test
    fun `map preserves all elements`() {
        val set = OrderedSetImpl.of(1, 2, 3, 4)
        val mapped = set.map(Func1 { it % 2 })
        assertEquals(4, mapped.size())
    }

    @Test
    fun `map handles empty set`() {
        val set = OrderedSetImpl<Int>()
        val mapped = set.map(Func1 { it * 2 })
        assertTrue(mapped.isEmpty())
    }

    @Test
    fun `map can change type`() {
        val set = OrderedSetImpl.of(1, 2, 3)
        val mapped = set.map(Func1 { it.toString() })
        assertEquals("1", mapped.first())
    }

    // ==================== filter() / reject() tests ====================
    @Test
    fun `filter returns ordered set`() {
        val set = OrderedSetImpl.of(1, 2, 3, 4, 5)
        val result = set.filter(Predicate1 { it > 3 })
        assertTrue(result is OrderedSet)
    }

    @Test
    fun `filter preserves order`() {
        val set = OrderedSetImpl.of(5, 4, 3, 2, 1)
        val result = set.filter(Predicate1 { it > 2 })
        assertEquals(5, result.first())
        assertEquals(3, result.last())
    }

    @Test
    fun `reject returns ordered set`() {
        val set = OrderedSetImpl.of(1, 2, 3, 4, 5)
        val result = set.reject(Predicate1 { it > 3 })
        assertTrue(result is OrderedSet)
    }

    @Test
    fun `rejectOne returns ordered set`() {
        val set = OrderedSetImpl.of(1, 2, 3, 4)
        val result = set.rejectOne(Predicate1 { it > 2 })
        assertTrue(result is OrderedSet)
        assertEquals(3, result.size())
    }

    @Test
    fun `filter on empty returns empty`() {
        val set = OrderedSetImpl<Int>()
        val result = set.filter(Predicate1 { it > 0 })
        assertTrue(result.isEmpty())
    }

    // ==================== conversion tests ====================
    @Test
    fun `asOrderedSet creates copy`() {
        val set = OrderedSetImpl.of(1, 2, 3)
        val copy = set.toOrderedSet()
        set.add(4)
        assertEquals(3, copy.size())
    }

    @Test
    fun `asList preserves order`() {
        val set = OrderedSetImpl.of(3, 1, 2)
        val list = set.toList()
        assertEquals(3, list.first())
        assertEquals(2, list.last())
    }

    @Test
    fun `asSet converts to set`() {
        val set = OrderedSetImpl.of(1, 2, 3)
        val regularSet = set.toSet()
        assertEquals(3, regularSet.size())
    }

    @Test
    fun `clone creates independent copy`() {
        val set = OrderedSetImpl.of(1, 2, 3)
        val cloned = set.clone()
        set.add(4)
        assertEquals(3, cloned.size())
    }

    @Test
    fun `clone preserves order`() {
        val set = OrderedSetImpl.of(3, 1, 2)
        val cloned = set.clone()
        assertEquals(3, cloned.first())
    }

    // ==================== collection operations tests ====================
    @Test
    fun `sum works correctly`() {
        val set = OrderedSetImpl.of(1, 2, 3, 4)
        assertEquals(10.0, set.sum())
    }

    @Test
    fun `concat preserves order`() {
        val set = OrderedSetImpl.of("a", "b", "c")
        assertEquals("a-b-c", set.concat("-"))
    }

    @Test
    fun `count returns 0 or 1`() {
        val set = OrderedSetImpl.of(1, 2, 3)
        assertEquals(1, set.count(2))
        assertEquals(0, set.count(5))
    }

    @Test
    fun `random returns element from set`() {
        val set = OrderedSetImpl.of(1, 2, 3)
        val random = set.random()
        assertTrue(set.includes(random))
    }

    @Test
    fun `flatten works correctly`() {
        val inner = ListImpl.of(1, 2)
        val set = OrderedSetImpl<Any>()
        set.add(inner)
        set.add(3)
        val flattened = set.flatten()
        assertEquals(3, flattened.size())
    }

    // ==================== iterator tests ====================
    @Test
    fun `iterator preserves order`() {
        val set = OrderedSetImpl.of(3, 1, 2)
        val result = mutableListOf<Int>()
        for (element in set) {
            result.add(element)
        }
        assertEquals(listOf(3, 1, 2), result)
    }

    @Test
    fun `iterator on empty produces nothing`() {
        val set = OrderedSetImpl<Int>()
        var count = 0
        for (element in set) {
            count++
        }
        assertEquals(0, count)
    }

    @Test
    fun `iterator handles null`() {
        val set = OrderedSetImpl<String?>()
        set.add(null)
        set.add("a")
        var nullCount = 0
        for (element in set) {
            if (element == null) nullCount++
        }
        assertEquals(1, nullCount)
    }

    @Test
    fun `iterator reflects current state`() {
        val set = OrderedSetImpl.of(1, 2)
        set.add(3)
        var count = 0
        for (element in set) {
            count++
        }
        assertEquals(3, count)
    }

    @Test
    fun `iterator returns all elements`() {
        val set = OrderedSetImpl.of(1, 2, 3, 4, 5)
        val elements = mutableSetOf<Int>()
        for (element in set) {
            elements.add(element)
        }
        assertEquals(5, elements.size)
    }
}
