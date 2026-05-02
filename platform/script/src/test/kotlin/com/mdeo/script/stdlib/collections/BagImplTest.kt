package com.mdeo.script.stdlib.impl.collections

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import com.mdeo.script.runtime.interfaces.Action1
import com.mdeo.script.runtime.interfaces.Predicate1
import com.mdeo.script.runtime.interfaces.Func1
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertNotNull

/**
 * Comprehensive tests for BagImpl.
 */
class BagImplTest {

    // ==================== size() tests ====================
    @Test
    fun `size returns 0 for empty bag`() {
        val bag = BagImpl<Int>()
        assertEquals(0, bag.size())
    }

    @Test
    fun `size counts duplicates`() {
        val bag = BagImpl.of(1, 1, 1, 2, 2, 3)
        assertEquals(6, bag.size())
    }

    @Test
    fun `size updates after adding elements`() {
        val bag = BagImpl<Int>()
        bag.add(1)
        bag.add(1)
        assertEquals(2, bag.size())
    }

    @Test
    fun `size updates after removing elements`() {
        val bag = BagImpl.of(1, 1, 1)
        bag.remove(1)
        assertEquals(2, bag.size())
    }

    @Test
    fun `size returns 0 after clearing`() {
        val bag = BagImpl.of(1, 2, 3)
        bag.clear()
        assertEquals(0, bag.size())
    }

    // ==================== isEmpty() / notEmpty() tests ====================
    @Test
    fun `isEmpty returns true for empty bag`() {
        val bag = BagImpl<Int>()
        assertTrue(bag.isEmpty())
    }

    @Test
    fun `isEmpty returns false for non-empty bag`() {
        val bag = BagImpl.of(1)
        assertFalse(bag.isEmpty())
    }

    @Test
    fun `notEmpty returns false for empty bag`() {
        val bag = BagImpl<Int>()
        assertFalse(bag.notEmpty())
    }

    @Test
    fun `notEmpty returns true for non-empty bag`() {
        val bag = BagImpl.of(1)
        assertTrue(bag.notEmpty())
    }

    @Test
    fun `isEmpty returns true after clearing`() {
        val bag = BagImpl.of(1, 2, 3)
        bag.clear()
        assertTrue(bag.isEmpty())
    }

    // ==================== count(item) tests (bag-specific) ====================
    @Test
    fun `count returns number of occurrences`() {
        val bag = BagImpl.of(1, 1, 1, 2, 2, 3)
        assertEquals(3, bag.count(1))
        assertEquals(2, bag.count(2))
        assertEquals(1, bag.count(3))
    }

    @Test
    fun `count returns 0 for missing element`() {
        val bag = BagImpl.of(1, 2, 3)
        assertEquals(0, bag.count(5))
    }

    @Test
    fun `count returns 0 for empty bag`() {
        val bag = BagImpl<Int>()
        assertEquals(0, bag.count(1))
    }

    @Test
    fun `count handles null`() {
        val bag = BagImpl<String?>()
        bag.add(null)
        bag.add(null)
        assertEquals(2, bag.count(null))
    }

    @Test
    fun `count updates after add`() {
        val bag = BagImpl.of(1)
        bag.add(1)
        assertEquals(2, bag.count(1))
    }

    // ==================== add() tests ====================
    @Test
    fun `add adds element to bag`() {
        val bag = BagImpl<Int>()
        bag.add(1)
        assertTrue(bag.includes(1))
    }

    @Test
    fun `add allows duplicates`() {
        val bag = BagImpl<Int>()
        bag.add(1)
        bag.add(1)
        assertEquals(2, bag.size())
    }

    @Test
    fun `add returns true`() {
        val bag = BagImpl<Int>()
        assertTrue(bag.add(1))
    }

    @Test
    fun `add null works`() {
        val bag = BagImpl<String?>()
        bag.add(null)
        assertTrue(bag.includes(null))
    }

    @Test
    fun `add increases count`() {
        val bag = BagImpl.of(1, 1)
        bag.add(1)
        assertEquals(3, bag.count(1))
    }

    // ==================== remove() tests ====================
    @Test
    fun `remove removes one occurrence`() {
        val bag = BagImpl.of(1, 1, 1)
        bag.remove(1)
        assertEquals(2, bag.count(1))
    }

    @Test
    fun `remove returns true when element found`() {
        val bag = BagImpl.of(1, 2, 3)
        assertTrue(bag.remove(2))
    }

    @Test
    fun `remove returns false when element not found`() {
        val bag = BagImpl.of(1, 2, 3)
        assertFalse(bag.remove(5))
    }

    @Test
    fun `remove null works`() {
        val bag = BagImpl<String?>()
        bag.add(null)
        bag.add(null)
        assertTrue(bag.remove(null))
        assertEquals(1, bag.count(null))
    }

    @Test
    fun `remove last occurrence removes element`() {
        val bag = BagImpl.of(1)
        bag.remove(1)
        assertFalse(bag.includes(1))
    }

    // ==================== including() / excluding() tests ====================
    @Test
    fun `excluding removes one occurrence in new bag`() {
        val bag = BagImpl.of(1, 1, 1)
        val result = bag.excluding(1)
        assertEquals(2, result.size())
        assertEquals(3, bag.size()) // original unchanged
    }

    @Test
    fun `including adds element in new bag`() {
        val bag = BagImpl.of(1, 1)
        val result = bag.including(1)
        assertEquals(3, result.size())
        assertEquals(2, bag.size()) // original unchanged
    }

    @Test
    fun `excludingAll removes one of each in new bag`() {
        val bag = BagImpl.of(1, 1, 2, 2, 3, 3)
        val result = bag.excludingAll(ListImpl.of(1, 2))
        assertEquals(4, result.size())
    }

    @Test
    fun `excludingAll with duplicates in input removes multiple`() {
        // If input has [1, 1], should remove two 1s (one per occurrence in input)
        val bag = BagImpl.of(1, 1, 1, 2, 2)
        val result = bag.excludingAll(ListImpl.of(1, 1))
        assertEquals(3, result.size()) // 1 + 2 + 2 = remaining
        assertEquals(1, result.count(1))
        assertEquals(2, result.count(2))
    }

    @Test
    fun `includingAll adds all in new bag`() {
        val bag = BagImpl.of(1, 1)
        val result = bag.includingAll(ListImpl.of(1, 2))
        assertEquals(4, result.size())
    }

    @Test
    fun `excluding returns bag type`() {
        val bag = BagImpl.of(1, 2, 3)
        val result = bag.excluding(2)
        @Suppress("USELESS_IS_CHECK")
        assertTrue(result is ReadonlyCollection)
    }

    // ==================== map() tests ====================
    @Test
    fun `map transforms elements`() {
        val bag = BagImpl.of(1, 2, 3)
        val mapped = bag.map(Func1 { it * 2 })
        assertTrue(mapped.includes(2))
        assertTrue(mapped.includes(4))
        assertTrue(mapped.includes(6))
    }

    @Test
    fun `map preserves duplicates`() {
        val bag = BagImpl.of(1, 1, 1)
        val mapped = bag.map(Func1 { it * 2 })
        assertEquals(3, mapped.size())
    }

    @Test
    fun `map on empty bag returns empty`() {
        val bag = BagImpl<Int>()
        val mapped = bag.map(Func1 { it * 2 })
        assertTrue(mapped.isEmpty())
    }

    @Test
    fun `map can change type`() {
        val bag = BagImpl.of(1, 2, 3)
        val mapped = bag.map(Func1 { it.toString() })
        assertTrue(mapped.includes("1"))
    }

    @Test
    fun `map returns list type`() {
        val bag = BagImpl.of(1, 2, 3)
        val mapped = bag.map(Func1 { it * 2 })
        @Suppress("USELESS_IS_CHECK")
        assertTrue(mapped is ScriptList)
    }

    // ==================== filter() / reject() tests ====================
    @Test
    fun `filter keeps matching elements including duplicates`() {
        val bag = BagImpl.of(1, 1, 2, 2, 3, 3)
        val result = bag.filter(Predicate1 { it > 1 })
        assertEquals(4, result.size())
    }

    @Test
    fun `reject removes matching elements including duplicates`() {
        val bag = BagImpl.of(1, 1, 2, 2, 3, 3)
        val result = bag.reject(Predicate1 { it > 2 })
        assertEquals(4, result.size())
    }

    @Test
    fun `filter returns bag type`() {
        val bag = BagImpl.of(1, 2, 3)
        val result = bag.filter(Predicate1 { it > 1 })
        assertTrue(result is Bag)
    }

    @Test
    fun `reject returns bag type`() {
        val bag = BagImpl.of(1, 2, 3)
        val result = bag.reject(Predicate1 { it > 1 })
        assertTrue(result is Bag)
    }

    @Test
    fun `rejectOne removes only first matching`() {
        val bag = BagImpl.of(1, 2, 2, 3)
        val result = bag.rejectOne(Predicate1 { it == 2 })
        assertEquals(3, result.size())
    }

    // ==================== conversion tests ====================
    @Test
    fun `asList creates list with all duplicates`() {
        val bag = BagImpl.of(1, 1, 2, 2)
        val list = bag.toList()
        assertEquals(4, list.size())
    }

    @Test
    fun `asSet removes duplicates`() {
        val bag = BagImpl.of(1, 1, 2, 2, 3, 3)
        val set = bag.toSet()
        assertEquals(3, set.size())
    }

    @Test
    fun `asOrderedSet removes duplicates`() {
        val bag = BagImpl.of(1, 1, 2, 2, 3, 3)
        val orderedSet = bag.toOrderedSet()
        assertEquals(3, orderedSet.size())
    }

    @Test
    fun `asBag creates copy`() {
        val bag = BagImpl.of(1, 1, 2)
        val copy = bag.toBag()
        bag.add(3)
        assertEquals(3, copy.size())
    }

    @Test
    fun `clone creates independent copy`() {
        val bag = BagImpl.of(1, 1, 2)
        val cloned = bag.clone()
        bag.add(3)
        assertEquals(3, cloned.size())
    }

    // ==================== sum() tests ====================
    @Test
    fun `sum includes all occurrences`() {
        val bag = BagImpl.of(1, 1, 2, 2, 3, 3)
        assertEquals(12.0, bag.sum())
    }

    @Test
    fun `sum returns 0 for empty bag`() {
        val bag = BagImpl<Int>()
        assertEquals(0.0, bag.sum())
    }

    @Test
    fun `sum works with doubles`() {
        val bag = BagImpl.of(1.5, 1.5, 2.0)
        assertEquals(5.0, bag.sum())
    }

    @Test
    fun `sum handles single element`() {
        val bag = BagImpl.of(42)
        assertEquals(42.0, bag.sum())
    }

    @Test
    fun `sum throws for non-numeric`() {
        val bag = BagImpl.of("a", "b")
        assertThrows<IllegalArgumentException> { bag.sum() }
    }

    // ==================== aggregate() tests ====================
    @Test
    fun `aggregate groups by key including duplicates`() {
        val bag = BagImpl.of(1, 1, 2, 2, 3, 3)
        val grouped = bag.aggregate(Func1 { it % 2 })
        assertEquals(2, grouped.size())
        // Key 0 (even): 2, 2 = 2 elements
        assertEquals(2, grouped.get(0)?.size())
        // Key 1 (odd): 1, 1, 3, 3 = 4 elements
        assertEquals(4, grouped.get(1)?.size())
    }

    @Test
    fun `aggregate on empty bag returns empty map`() {
        val bag = BagImpl<Int>()
        val grouped = bag.aggregate(Func1 { it })
        assertTrue(grouped.isEmpty())
    }

    @Test
    fun `aggregate preserves all occurrences`() {
        val bag = BagImpl.of(1, 1, 1)
        val grouped = bag.aggregate(Func1 { "all" })
        assertEquals(3, grouped.get("all")?.size())
    }

    @Test
    fun `aggregate handles null keys`() {
        val bag = BagImpl<String?>()
        bag.add(null)
        bag.add("a")
        val grouped = bag.aggregate(Func1 { it })
        assertEquals(2, grouped.size())
    }

    @Test
    fun `aggregate with identity returns one group per unique element`() {
        val bag = BagImpl.of(1, 1, 2, 2)
        val grouped = bag.aggregate(Func1 { it })
        assertEquals(2, grouped.size())
    }

    // ==================== predicate operations tests ====================
    @Test
    fun `exists returns true when at least one matches`() {
        val bag = BagImpl.of(1, 1, 2, 2, 3)
        assertTrue(bag.exists(Predicate1 { it == 3 }))
    }

    @Test
    fun `forEach executes for all elements`() {
        val bag = BagImpl.of(2, 2, 4, 4, 6)
        var count = 0
        bag.forEach(Action1 { count++ })
        assertEquals(5, count)
    }

    @Test
    fun `none returns true when no matches`() {
        val bag = BagImpl.of(1, 1, 2, 2, 3)
        assertTrue(bag.none(Predicate1 { it > 10 }))
    }

    @Test
    fun `all returns true when all elements match`() {
        val bag = BagImpl.of(2, 2, 4, 4, 6)
        assertTrue(bag.all(Predicate1 { it % 2 == 0 }))
    }

    @Test
    fun `all returns false when at least one element does not match`() {
        val bag = BagImpl.of(2, 2, 4, 5, 6)
        assertFalse(bag.all(Predicate1 { it % 2 == 0 }))
    }

    @Test
    fun `all returns true for empty bag`() {
        val bag = BagImpl<Int>()
        assertTrue(bag.all(Predicate1 { it < 0 }))
    }

    @Test
    fun `one returns true for exactly one match`() {
        val bag = BagImpl.of(1, 2, 2, 3, 3, 3)
        assertTrue(bag.one(Predicate1 { it == 1 }))
    }

    @Test
    fun `one returns false for multiple matches`() {
        val bag = BagImpl.of(1, 1, 2, 3)
        assertFalse(bag.one(Predicate1 { it == 1 }))
    }

    // ==================== associate() tests ====================
    @Test
    fun `associate creates map with unique keys`() {
        val bag = BagImpl.of("a", "a", "bb", "ccc")
        val map = bag.associate(Func1 { it.length })
        assertEquals(3, map.size()) // Only 3 unique elements: "a", "bb", "ccc"
    }

    @Test
    fun `associate handles duplicates by using unique elements`() {
        val bag = BagImpl.of(1, 1, 2, 2)
        val map = bag.associate(Func1 { it * 10 })
        assertEquals(2, map.size())
    }

    @Test
    fun `associate on empty bag returns empty map`() {
        val bag = BagImpl<String>()
        val map = bag.associate(Func1 { it.length })
        assertTrue(map.isEmpty())
    }

    @Test
    fun `associate with null values works`() {
        val bag = BagImpl.of(1, 2)
        val map = bag.associate(Func1 { if (it == 1) null else "value" })
        assertNull(map.get(1))
    }

    @Test
    fun `associate preserves key from element`() {
        val bag = BagImpl.of("a", "b")
        val map = bag.associate(Func1 { it.uppercase() })
        assertEquals("A", map.get("a"))
    }

    // ==================== iterator tests ====================
    @Test
    fun `iterator includes all occurrences`() {
        val bag = BagImpl.of(1, 1, 2, 2, 3)
        var count = 0
        for (element in bag) {
            count++
        }
        assertEquals(5, count)
    }

    @Test
    fun `iterator on empty bag produces nothing`() {
        val bag = BagImpl<Int>()
        var count = 0
        for (element in bag) {
            count++
        }
        assertEquals(0, count)
    }

    @Test
    fun `iterator handles null elements`() {
        val bag = BagImpl<String?>()
        bag.add(null)
        bag.add(null)
        var nullCount = 0
        for (element in bag) {
            if (element == null) nullCount++
        }
        assertEquals(2, nullCount)
    }

    @Test
    fun `iterator reflects current state`() {
        val bag = BagImpl.of(1, 2)
        bag.add(3)
        var count = 0
        for (element in bag) {
            count++
        }
        assertEquals(3, count)
    }

    @Test
    fun `iterator returns all elements`() {
        val bag = BagImpl.of(1, 1, 2, 2, 3)
        val elements = mutableListOf<Int>()
        for (element in bag) {
            elements.add(element)
        }
        assertEquals(5, elements.size)
    }

    // ==================== sortedBy() tests ====================
    @Test
    fun `sortedBy returns ordered collection`() {
        val bag = BagImpl.of(3, 1, 2)
        val sorted = bag.sortedBy(Func1 { it })
        assertEquals(1, sorted.first())
        assertEquals(3, sorted.last())
    }

    @Test
    fun `sortedBy preserves duplicates`() {
        val bag = BagImpl.of(2, 1, 2, 1)
        val sorted = bag.sortedBy(Func1 { it })
        assertEquals(4, sorted.size())
    }

    @Test
    fun `sortedBy does not modify original`() {
        val bag = BagImpl.of(3, 1, 2)
        bag.sortedBy(Func1 { it })
        // Original order is not guaranteed for bag, just check size
        assertEquals(3, bag.size())
    }

    @Test
    fun `sortedBy with custom key`() {
        val bag = BagImpl.of("apple", "cat", "banana")
        val sorted = bag.sortedBy(Func1 { it.length })
        assertEquals("cat", sorted.first())
    }

    @Test
    fun `sortedBy handles empty bag`() {
        val bag = BagImpl<Int>()
        val sorted = bag.sortedBy(Func1 { it })
        assertTrue(sorted.isEmpty())
    }
}
