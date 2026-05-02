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
 * Comprehensive tests for SetImpl.
 */
class SetImplTest {

    // ==================== size() tests ====================
    @Test
    fun `size returns 0 for empty set`() {
        val set = SetImpl<Int>()
        assertEquals(0, set.size())
    }

    @Test
    fun `size returns correct count`() {
        val set = SetImpl.of(1, 2, 3)
        assertEquals(3, set.size())
    }

    @Test
    fun `size does not count duplicates`() {
        val set = SetImpl.of(1, 1, 2, 2, 3)
        assertEquals(3, set.size())
    }

    @Test
    fun `size updates after adding elements`() {
        val set = SetImpl<Int>()
        set.add(1)
        set.add(2)
        assertEquals(2, set.size())
    }

    @Test
    fun `size updates after removing elements`() {
        val set = SetImpl.of(1, 2, 3)
        set.remove(2)
        assertEquals(2, set.size())
    }

    // ==================== isEmpty() / notEmpty() tests ====================
    @Test
    fun `isEmpty returns true for empty set`() {
        val set = SetImpl<Int>()
        assertTrue(set.isEmpty())
    }

    @Test
    fun `isEmpty returns false for non-empty set`() {
        val set = SetImpl.of(1)
        assertFalse(set.isEmpty())
    }

    @Test
    fun `notEmpty returns false for empty set`() {
        val set = SetImpl<Int>()
        assertFalse(set.notEmpty())
    }

    @Test
    fun `notEmpty returns true for non-empty set`() {
        val set = SetImpl.of(1)
        assertTrue(set.notEmpty())
    }

    @Test
    fun `isEmpty returns true after clearing`() {
        val set = SetImpl.of(1, 2, 3)
        set.clear()
        assertTrue(set.isEmpty())
    }

    // ==================== includes() / excludes() tests ====================
    @Test
    fun `includes returns true when element exists`() {
        val set = SetImpl.of(1, 2, 3)
        assertTrue(set.includes(2))
    }

    @Test
    fun `includes returns false when element does not exist`() {
        val set = SetImpl.of(1, 2, 3)
        assertFalse(set.includes(5))
    }

    @Test
    fun `excludes returns true when element does not exist`() {
        val set = SetImpl.of(1, 2, 3)
        assertTrue(set.excludes(5))
    }

    @Test
    fun `excludes returns false when element exists`() {
        val set = SetImpl.of(1, 2, 3)
        assertFalse(set.excludes(2))
    }

    @Test
    fun `includes handles null`() {
        val set = SetImpl<String?>()
        set.add(null)
        assertTrue(set.includes(null))
    }

    // ==================== add() tests ====================
    @Test
    fun `add adds element to set`() {
        val set = SetImpl<Int>()
        set.add(1)
        assertTrue(set.includes(1))
    }

    @Test
    fun `add duplicate does not increase size`() {
        val set = SetImpl.of(1)
        set.add(1)
        assertEquals(1, set.size())
    }

    @Test
    fun `add null works`() {
        val set = SetImpl<String?>()
        set.add(null)
        assertTrue(set.includes(null))
    }

    @Test
    fun `add returns true for new element`() {
        val set = SetImpl<Int>()
        assertTrue(set.add(1))
    }

    @Test
    fun `add returns false for duplicate`() {
        val set = SetImpl.of(1)
        assertFalse(set.add(1))
    }

    // ==================== remove() tests ====================
    @Test
    fun `remove removes element`() {
        val set = SetImpl.of(1, 2, 3)
        set.remove(2)
        assertFalse(set.includes(2))
    }

    @Test
    fun `remove returns true when element found`() {
        val set = SetImpl.of(1, 2, 3)
        assertTrue(set.remove(2))
    }

    @Test
    fun `remove returns false when element not found`() {
        val set = SetImpl.of(1, 2, 3)
        assertFalse(set.remove(5))
    }

    @Test
    fun `remove null works`() {
        val set = SetImpl<String?>()
        set.add(null)
        assertTrue(set.remove(null))
    }

    @Test
    fun `remove decreases size`() {
        val set = SetImpl.of(1, 2, 3)
        set.remove(2)
        assertEquals(2, set.size())
    }

    // ==================== including() / excluding() tests ====================
    @Test
    fun `excluding removes element in new set`() {
        val set = SetImpl.of(1, 2, 3)
        val result = set.excluding(2)
        assertFalse(result.includes(2))
        assertTrue(set.includes(2)) // original unchanged
    }

    @Test
    fun `including adds element in new set`() {
        val set = SetImpl.of(1, 2)
        val result = set.including(3)
        assertTrue(result.includes(3))
        assertFalse(set.includes(3)) // original unchanged
    }

    @Test
    fun `excludingAll removes all in new set`() {
        val set = SetImpl.of(1, 2, 3, 4)
        val result = set.excludingAll(ListImpl.of(2, 4))
        assertEquals(2, result.size())
        assertTrue(result.includes(1))
        assertTrue(result.includes(3))
    }

    @Test
    fun `includingAll adds all in new set`() {
        val set = SetImpl.of(1, 2)
        val result = set.includingAll(ListImpl.of(3, 4))
        assertEquals(4, result.size())
    }

    @Test
    fun `including duplicate does not change size`() {
        val set = SetImpl.of(1, 2)
        val result = set.including(1)
        assertEquals(2, result.size())
    }

    // ==================== map() tests ====================
    @Test
    fun `map transforms elements`() {
        val set = SetImpl.of(1, 2, 3)
        val mapped = set.map(Func1 { it * 2 })
        assertTrue(mapped.includes(2))
        assertTrue(mapped.includes(4))
        assertTrue(mapped.includes(6))
    }

    @Test
    fun `map returns list type`() {
        val set = SetImpl.of(1, 2, 3)
        val mapped = set.map(Func1 { it * 2 })
        @Suppress("USELESS_IS_CHECK")
        assertTrue(mapped is ScriptList)
    }

    @Test
    fun `map on empty set returns empty`() {
        val set = SetImpl<Int>()
        val mapped = set.map(Func1 { it * 2 })
        assertTrue(mapped.isEmpty())
    }

    @Test
    fun `map preserves all elements`() {
        val set = SetImpl.of(1, 2, 3, 4)
        val mapped = set.map(Func1 { it % 2 })
        assertEquals(4, mapped.size())
    }

    @Test
    fun `map can change type`() {
        val set = SetImpl.of(1, 2, 3)
        val mapped = set.map(Func1 { it.toString() })
        assertTrue(mapped.includes("1"))
    }

    // ==================== filter() / reject() tests ====================
    @Test
    fun `filter keeps matching elements`() {
        val set = SetImpl.of(1, 2, 3, 4, 5)
        val result = set.filter(Predicate1 { it > 3 })
        assertEquals(2, result.size())
        assertTrue(result.includes(4))
        assertTrue(result.includes(5))
    }

    @Test
    fun `reject removes matching elements`() {
        val set = SetImpl.of(1, 2, 3, 4, 5)
        val result = set.reject(Predicate1 { it > 3 })
        assertEquals(3, result.size())
    }

    @Test
    fun `filter returns set type`() {
        val set = SetImpl.of(1, 2, 3)
        val result = set.filter(Predicate1 { it > 1 })
        assertTrue(result is ScriptSet)
    }

    @Test
    fun `reject returns set type`() {
        val set = SetImpl.of(1, 2, 3)
        val result = set.reject(Predicate1 { it > 1 })
        assertTrue(result is ScriptSet)
    }

    @Test
    fun `rejectOne removes first matching only`() {
        val set = SetImpl.of(1, 2, 3, 4)
        val result = set.rejectOne(Predicate1 { it > 2 })
        assertEquals(3, result.size())
    }

    // ==================== conversion tests ====================
    @Test
    fun `asList creates list with all elements`() {
        val set = SetImpl.of(1, 2, 3)
        val list = set.toList()
        assertEquals(3, list.size())
    }

    @Test
    fun `asBag creates bag with all elements`() {
        val set = SetImpl.of(1, 2, 3)
        val bag = set.toBag()
        assertEquals(3, bag.size())
    }

    @Test
    fun `asOrderedSet creates ordered set`() {
        val set = SetImpl.of(1, 2, 3)
        val orderedSet = set.toOrderedSet()
        assertEquals(3, orderedSet.size())
    }

    @Test
    fun `asSet creates new set`() {
        val set = SetImpl.of(1, 2, 3)
        val newSet = set.toSet()
        assertEquals(3, newSet.size())
    }

    @Test
    fun `clone creates independent copy`() {
        val set = SetImpl.of(1, 2, 3)
        val cloned = set.clone()
        set.add(4)
        assertEquals(3, cloned.size())
    }

    // ==================== collection operations tests ====================
    @Test
    fun `includesAll returns true when all present`() {
        val set = SetImpl.of(1, 2, 3, 4, 5)
        assertTrue(set.includesAll(ListImpl.of(2, 3, 4)))
    }

    @Test
    fun `excludesAll returns true when none present`() {
        val set = SetImpl.of(1, 2, 3)
        assertTrue(set.excludesAll(ListImpl.of(4, 5, 6)))
    }

    @Test
    fun `count returns 0 or 1 for sets`() {
        val set = SetImpl.of(1, 2, 3)
        assertEquals(1, set.count(2))
        assertEquals(0, set.count(5))
    }

    @Test
    fun `sum returns sum of elements`() {
        val set = SetImpl.of(1, 2, 3, 4)
        assertEquals(10.0, set.sum())
    }

    @Test
    fun `concat joins elements`() {
        val set = SetImpl.of("a", "b", "c")
        val result = set.concat("-")
        assertEquals(5, result.length) // "a-b-c" but order may vary
    }

    // ==================== predicate operations tests ====================
    @Test
    fun `exists returns true when element matches`() {
        val set = SetImpl.of(1, 2, 3)
        assertTrue(set.exists(Predicate1 { it > 2 }))
    }

    @Test
    fun `forEach executes for all elements`() {
        val set = SetImpl.of(2, 4, 6)
        var count = 0
        set.forEach(Action1 { count++ })
        assertEquals(3, count)
    }

    @Test
    fun `none returns true when no matches`() {
        val set = SetImpl.of(1, 2, 3)
        assertTrue(set.none(Predicate1 { it > 10 }))
    }

    @Test
    fun `all returns true when all elements match`() {
        val set = SetImpl.of(2, 4, 6)
        assertTrue(set.all(Predicate1 { it % 2 == 0 }))
    }

    @Test
    fun `all returns false when at least one element does not match`() {
        val set = SetImpl.of(2, 4, 5)
        assertFalse(set.all(Predicate1 { it % 2 == 0 }))
    }

    @Test
    fun `all returns true for empty set`() {
        val set = SetImpl<Int>()
        assertTrue(set.all(Predicate1 { it < 0 }))
    }

    @Test
    fun `one returns true for exactly one match`() {
        val set = SetImpl.of(1, 2, 3)
        assertTrue(set.one(Predicate1 { it == 2 }))
    }

    @Test
    fun `find returns matching element`() {
        val set = SetImpl.of(1, 2, 3)
        assertNotNull(set.find(Predicate1 { it > 2 }))
    }
}
