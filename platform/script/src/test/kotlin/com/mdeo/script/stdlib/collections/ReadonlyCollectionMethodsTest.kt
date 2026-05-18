package com.mdeo.script.stdlib.impl.collections

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import com.mdeo.script.runtime.interfaces.Action1
import com.mdeo.script.runtime.interfaces.Func1
import com.mdeo.script.runtime.interfaces.Predicate1
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Comprehensive tests for ReadonlyCollection methods that were added
 * from the TypeScript ReadonlyCollection.ts implementation.
 */
class ReadonlyCollectionMethodsTest {

    // ==================== includesAll() tests ====================
    @Test
    fun `includesAll returns true when all elements exist`() {
        val list = ListImpl.of(1, 2, 3, 4, 5)
        val subset = ListImpl.of(2, 3, 4)
        assertTrue(list.includesAll(subset))
    }

    @Test
    fun `includesAll returns false when some elements missing`() {
        val list = ListImpl.of(1, 2, 3)
        val other = ListImpl.of(2, 3, 4)
        assertFalse(list.includesAll(other))
    }

    @Test
    fun `includesAll returns true for empty collection`() {
        val list = ListImpl.of(1, 2, 3)
        val empty = ListImpl<Int>()
        assertTrue(list.includesAll(empty))
    }

    @Test
    fun `includesAll returns true for same collection`() {
        val list = ListImpl.of(1, 2, 3)
        assertTrue(list.includesAll(list))
    }

    @Test
    fun `includesAll returns false when list is empty`() {
        val list = ListImpl<Int>()
        val other = ListImpl.of(1)
        assertFalse(list.includesAll(other))
    }

    // ==================== excludesAll() tests ====================
    @Test
    fun `excludesAll returns true when no elements exist`() {
        val list = ListImpl.of(1, 2, 3)
        val other = ListImpl.of(4, 5, 6)
        assertTrue(list.excludesAll(other))
    }

    @Test
    fun `excludesAll returns false when some elements exist`() {
        val list = ListImpl.of(1, 2, 3)
        val other = ListImpl.of(3, 4, 5)
        assertFalse(list.excludesAll(other))
    }

    @Test
    fun `excludesAll returns true for empty collection`() {
        val list = ListImpl.of(1, 2, 3)
        val empty = ListImpl<Int>()
        assertTrue(list.excludesAll(empty))
    }

    @Test
    fun `excludesAll returns true for empty list`() {
        val list = ListImpl<Int>()
        val other = ListImpl.of(1, 2, 3)
        assertTrue(list.excludesAll(other))
    }

    @Test
    fun `excludesAll returns false for same collection`() {
        val list = ListImpl.of(1, 2, 3)
        assertFalse(list.excludesAll(list))
    }

    // ==================== includingAll() tests ====================
    @Test
    fun `includingAll adds all elements from collection`() {
        val list = ListImpl.of(1, 2, 3)
        val other = ListImpl.of(4, 5, 6)
        val result = list.includingAll(other)
        assertEquals(6, result.size())
        assertTrue(result.includesAll(list))
        assertTrue(result.includesAll(other))
    }

    @Test
    fun `includingAll returns new collection`() {
        val list = ListImpl.of(1, 2, 3)
        val other = ListImpl.of(4, 5)
        val result = list.includingAll(other)
        assertTrue(result !== list)
        assertEquals(3, list.size())
    }

    @Test
    fun `includingAll handles empty collection`() {
        val list = ListImpl.of(1, 2, 3)
        val empty = ListImpl<Int>()
        val result = list.includingAll(empty)
        assertEquals(3, result.size())
    }

    @Test
    fun `includingAll handles duplicates`() {
        val list = ListImpl.of(1, 2, 3)
        val other = ListImpl.of(2, 3, 4)
        val result = list.includingAll(other)
        assertEquals(6, result.size())
    }

    @Test
    fun `includingAll works on empty list`() {
        val list = ListImpl<Int>()
        val other = ListImpl.of(1, 2, 3)
        val result = list.includingAll(other)
        assertEquals(3, result.size())
    }

    // ==================== excludingAll() tests ====================
    @Test
    fun `excludingAll removes all matching elements`() {
        val list = ListImpl.of(1, 2, 3, 4, 5)
        val toRemove = ListImpl.of(2, 4)
        val result = list.excludingAll(toRemove)
        assertEquals(3, result.size())
        assertTrue(result.includes(1))
        assertTrue(result.includes(3))
        assertTrue(result.includes(5))
    }

    @Test
    fun `excludingAll returns new collection`() {
        val list = ListImpl.of(1, 2, 3)
        val toRemove = ListImpl.of(2)
        val result = list.excludingAll(toRemove)
        assertTrue(result !== list)
        assertEquals(3, list.size())
    }

    @Test
    fun `excludingAll handles empty collection`() {
        val list = ListImpl.of(1, 2, 3)
        val empty = ListImpl<Int>()
        val result = list.excludingAll(empty)
        assertEquals(3, result.size())
    }

    @Test
    fun `excludingAll handles no matches`() {
        val list = ListImpl.of(1, 2, 3)
        val other = ListImpl.of(4, 5, 6)
        val result = list.excludingAll(other)
        assertEquals(3, result.size())
    }

    @Test
    fun `excludingAll removes duplicates`() {
        val list = ListImpl.of(1, 2, 2, 3, 2)
        val toRemove = ListImpl.of(2)
        val result = list.excludingAll(toRemove)
        assertEquals(2, result.size())
        assertFalse(result.includes(2))
    }

    // ==================== flatten() tests ====================
    @Test
    fun `flatten flattens nested lists`() {
        val inner1 = ListImpl.of(1, 2)
        val inner2 = ListImpl.of(3, 4)
        val outer = ListImpl.of(inner1, inner2)
        val result = outer.flatten()
        assertEquals(4, result.size())
    }

    @Test
    fun `flatten handles deeply nested lists`() {
        val inner1 = ListImpl.of(1, 2)
        val inner2 = ListImpl.of(inner1, 3)
        val outer = ListImpl.of(inner2, 4)
        val result = outer.flatten()
        assertEquals(4, result.size())
    }

    @Test
    fun `flatten handles empty nested lists`() {
        val inner1 = ListImpl<Int>()
        val inner2 = ListImpl.of(1, 2)
        val outer = ListImpl.of(inner1, inner2)
        val result = outer.flatten()
        assertEquals(2, result.size())
    }

    @Test
    fun `flatten handles non-iterable elements`() {
        val list = ListImpl.of(1, 2, 3)
        val result = list.flatten()
        assertEquals(3, result.size())
    }

    @Test
    fun `flatten handles mixed content`() {
        val inner = ListImpl.of(2, 3)
        val outer = ListImpl.of(1, inner, 4)
        val result = outer.flatten()
        assertEquals(4, result.size())
    }

    // ==================== random() tests ====================
    @Test
    fun `random returns element from collection`() {
        val list = ListImpl.of(1, 2, 3, 4, 5)
        val element = list.random()
        assertTrue(list.includes(element))
    }

    @Test
    fun `random returns single element from single-element list`() {
        val list = ListImpl.of(42)
        assertEquals(42, list.random())
    }

    @Test
    fun `random throws on empty collection`() {
        val list = ListImpl<Int>()
        assertThrows<NoSuchElementException> {
            list.random()
        }
    }

    @Test
    fun `random can return any element`() {
        val list = ListImpl.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
        val seen = mutableSetOf<Int>()
        // Try multiple times to get different elements
        repeat(50) {
            seen.add(list.random())
        }
        // Should see at least some variety
        assertTrue(seen.size > 1, "Expected to see multiple different elements")
    }

    @Test
    fun `random works with nullable elements`() {
        val list = ListImpl.of<Int?>(1, 2, null, 3)
        val element = list.random()
        assertTrue(element == null || element in 1..3)
    }

    // ==================== clone() tests ====================
    @Test
    fun `clone creates independent copy`() {
        val list = ListImpl.of(1, 2, 3)
        val clone = list.clone()
        assertTrue(clone !== list)
        assertEquals(list.size(), clone.size())
    }

    @Test
    fun `clone modifications don't affect original`() {
        val list = ListImpl.of(1, 2, 3)
        val clone = list.clone()
        clone.add(4)
        assertEquals(3, list.size())
        assertEquals(4, clone.size())
    }

    @Test
    fun `clone copies all elements`() {
        val list = ListImpl.of(1, 2, 3, 4, 5)
        val clone = list.clone()
        for (element in list) {
            assertTrue(clone.includes(element))
        }
    }

    @Test
    fun `clone works on empty collection`() {
        val list = ListImpl<Int>()
        val clone = list.clone()
        assertEquals(0, clone.size())
    }

    @Test
    fun `clone preserves order for ordered collections`() {
        val list = ListImpl.of(1, 2, 3, 4, 5)
        val clone = list.clone() as ScriptList<Int>
        for (i in 0 until list.size()) {
            assertEquals(list.at(i), clone.at(i))
        }
    }

    // ==================== atLeastNMatch() tests ====================
    @Test
    fun `atLeastNMatch returns true when exactly n match`() {
        val list = ListImpl.of(1, 2, 3, 4, 5)
        assertTrue(list.atLeastNMatch(Predicate1 { it > 3 }, 2))
    }

    @Test
    fun `atLeastNMatch returns true when more than n match`() {
        val list = ListImpl.of(1, 2, 3, 4, 5)
        assertTrue(list.atLeastNMatch(Predicate1 { it > 2 }, 2))
    }

    @Test
    fun `atLeastNMatch returns false when fewer than n match`() {
        val list = ListImpl.of(1, 2, 3, 4, 5)
        assertFalse(list.atLeastNMatch(Predicate1 { it > 4 }, 2))
    }

    @Test
    fun `atLeastNMatch returns true for n=0`() {
        val list = ListImpl.of(1, 2, 3)
        assertTrue(list.atLeastNMatch(Predicate1 { it > 10 }, 0))
    }

    @Test
    fun `atLeastNMatch handles empty collection`() {
        val list = ListImpl<Int>()
        assertFalse(list.atLeastNMatch(Predicate1 { true }, 1))
    }

    // ==================== atMostNMatch() tests ====================
    @Test
    fun `atMostNMatch returns true when exactly n match`() {
        val list = ListImpl.of(1, 2, 3, 4, 5)
        assertTrue(list.atMostNMatch(Predicate1 { it > 3 }, 2))
    }

    @Test
    fun `atMostNMatch returns true when fewer than n match`() {
        val list = ListImpl.of(1, 2, 3, 4, 5)
        assertTrue(list.atMostNMatch(Predicate1 { it > 4 }, 2))
    }

    @Test
    fun `atMostNMatch returns false when more than n match`() {
        val list = ListImpl.of(1, 2, 3, 4, 5)
        assertFalse(list.atMostNMatch(Predicate1 { it > 2 }, 2))
    }

    @Test
    fun `atMostNMatch returns true for empty collection`() {
        val list = ListImpl<Int>()
        assertTrue(list.atMostNMatch(Predicate1 { true }, 5))
    }

    @Test
    fun `atMostNMatch returns false when all match and n is too small`() {
        val list = ListImpl.of(1, 2, 3)
        assertFalse(list.atMostNMatch(Predicate1 { it > 0 }, 2))
    }

    // ==================== aggregate() tests ====================
    @Test
    fun `aggregate groups elements by key`() {
        val list = ListImpl.of(1, 2, 3, 4, 5, 6)
        val result = list.aggregate(Func1 { it % 2 })
        assertEquals(2, result.size())
        assertEquals(3, result.get(0)?.size())
        assertEquals(3, result.get(1)?.size())
    }

    @Test
    fun `aggregate handles single group`() {
        val list = ListImpl.of(1, 2, 3)
        val result = list.aggregate(Func1 { "same" })
        assertEquals(1, result.size())
        assertEquals(3, result.get("same")?.size())
    }

    @Test
    fun `aggregate handles empty collection`() {
        val list = ListImpl<Int>()
        val result = list.aggregate(Func1 { it })
        assertEquals(0, result.size())
    }

    @Test
    fun `aggregate preserves all elements`() {
        val list = ListImpl.of("apple", "banana", "apricot", "cherry")
        val result = list.aggregate(Func1 { it[0] })
        var totalSize = 0
        for (group in result.values()) {
            totalSize += group.size()
        }
        assertEquals(4, totalSize)
    }

    @Test
    fun `aggregate with complex key function`() {
        val list = ListImpl.of(1, 2, 3, 4, 5, 6, 7, 8, 9)
        val result = list.aggregate(Func1 { it / 3 })
        assertTrue(result.size() >= 3)
    }

    // ==================== map() tests ====================
    @Test
    fun `map transforms all elements`() {
        val list = ListImpl.of(1, 2, 3)
        val result = list.map(Func1 { it * 2 })
        assertEquals(3, result.size())
        assertTrue(result.includes(2))
        assertTrue(result.includes(4))
        assertTrue(result.includes(6))
    }

    @Test
    fun `map handles empty collection`() {
        val list = ListImpl<Int>()
        val result = list.map(Func1 { it * 2 })
        assertEquals(0, result.size())
    }

    @Test
    fun `map can change type`() {
        val list = ListImpl.of(1, 2, 3)
        val result = list.map(Func1 { it.toString() })
        assertEquals(3, result.size())
        assertTrue(result.includes("1"))
    }

    @Test
    fun `map preserves order`() {
        val list = ListImpl.of(1, 2, 3, 4, 5)
        val result = list.map(Func1 { it * 2 })
        assertEquals(2, result.at(0))
        assertEquals(4, result.at(1))
        assertEquals(6, result.at(2))
    }

    @Test
    fun `map with complex transformation`() {
        val list = ListImpl.of("a", "bb", "ccc")
        val result = list.map(Func1 { it.length })
        assertTrue(result.includes(1))
        assertTrue(result.includes(2))
        assertTrue(result.includes(3))
    }

    // ==================== exists() tests ====================
    @Test
    fun `exists returns true when element matches`() {
        val list = ListImpl.of(1, 2, 3, 4, 5)
        assertTrue(list.exists(Predicate1 { it > 3 }))
    }

    @Test
    fun `exists returns false when no element matches`() {
        val list = ListImpl.of(1, 2, 3)
        assertFalse(list.exists(Predicate1 { it > 10 }))
    }

    @Test
    fun `exists returns false for empty collection`() {
        val list = ListImpl<Int>()
        assertFalse(list.exists(Predicate1 { true }))
    }

    @Test
    fun `exists returns true when first element matches`() {
        val list = ListImpl.of(5, 1, 2, 3)
        assertTrue(list.exists(Predicate1 { it > 4 }))
    }

    @Test
    fun `exists with complex predicate`() {
        val list = ListImpl.of("apple", "banana", "cherry")
        assertTrue(list.exists(Predicate1 { it.startsWith("b") }))
    }

    // ==================== forEach() tests ====================
    @Test
    fun `forEach executes action for all elements`() {
        val list = ListImpl.of(1, 2, 3)
        val sum = arrayOf(0)
        list.forEach(Action1 { sum[0] += it })
        assertEquals(6, sum[0])
    }

    @Test
    fun `forEach handles empty collection`() {
        val list = ListImpl<Int>()
        var called = false
        list.forEach(Action1 { called = true })
        assertFalse(called)
    }

    @Test
    fun `forEach executes in order for ordered collections`() {
        val list = ListImpl.of(1, 2, 3, 4, 5)
        val result = mutableListOf<Int>()
        list.forEach(Action1 { result.add(it) })
        assertEquals(listOf(1, 2, 3, 4, 5), result)
    }

    @Test
    fun `forEach with side effects`() {
        val list = ListImpl.of("a", "b", "c")
        val builder = StringBuilder()
        list.forEach(Action1 { builder.append(it) })
        assertEquals("abc", builder.toString())
    }

    @Test
    fun `forEach processes all elements exactly once`() {
        val list = ListImpl.of(1, 2, 3, 4, 5)
        var count = 0
        list.forEach(Action1 { count++ })
        assertEquals(5, count)
    }

    // ==================== associate() tests ====================
    @Test
    fun `associate creates map with computed values`() {
        val list = ListImpl.of(1, 2, 3)
        val result = list.associate(Func1 { it * 2 })
        assertEquals(3, result.size())
        assertEquals(2, result.get(1))
        assertEquals(4, result.get(2))
        assertEquals(6, result.get(3))
    }

    @Test
    fun `associate handles empty collection`() {
        val list = ListImpl<Int>()
        val result = list.associate(Func1 { it * 2 })
        assertEquals(0, result.size())
    }

    @Test
    fun `associate uses elements as keys`() {
        val list = ListImpl.of("a", "bb", "ccc")
        val result = list.associate(Func1 { it.length })
        assertEquals(3, result.size())
        assertTrue(result.containsKey("a"))
        assertEquals(1, result.get("a"))
    }

    @Test
    fun `associate handles duplicate keys`() {
        val list = ListImpl.of(1, 2, 3, 4)
        val result = list.associate(Func1 { it % 2 })
        // Each element becomes its own key, so we get 4 entries
        // 1->1, 2->0, 3->1, 4->0 (last value wins)
        assertEquals(4, result.size())
    }

    @Test
    fun `associate with complex value function`() {
        val list = ListImpl.of(1, 2, 3)
        val result = list.associate(Func1 { "value$it" })
        assertEquals("value1", result.get(1))
        assertEquals("value2", result.get(2))
    }

    // ==================== nMatch() tests ====================
    @Test
    fun `nMatch returns true when exactly n match`() {
        val list = ListImpl.of(1, 2, 3, 4, 5)
        assertTrue(list.nMatch(Predicate1 { it > 3 }, 2))
    }

    @Test
    fun `nMatch returns false when more than n match`() {
        val list = ListImpl.of(1, 2, 3, 4, 5)
        assertFalse(list.nMatch(Predicate1 { it > 2 }, 2))
    }

    @Test
    fun `nMatch returns false when fewer than n match`() {
        val list = ListImpl.of(1, 2, 3)
        assertFalse(list.nMatch(Predicate1 { it > 2 }, 2))
    }

    @Test
    fun `nMatch returns true for n=0 when no matches`() {
        val list = ListImpl.of(1, 2, 3)
        assertTrue(list.nMatch(Predicate1 { it > 10 }, 0))
    }

    @Test
    fun `nMatch handles all matching`() {
        val list = ListImpl.of(1, 2, 3)
        assertTrue(list.nMatch(Predicate1 { it > 0 }, 3))
    }

    // ==================== none() tests ====================
    @Test
    fun `none returns true when no elements match`() {
        val list = ListImpl.of(1, 2, 3)
        assertTrue(list.none(Predicate1 { it > 10 }))
    }

    @Test
    fun `none returns false when one element matches`() {
        val list = ListImpl.of(1, 2, 3)
        assertFalse(list.none(Predicate1 { it == 2 }))
    }

    @Test
    fun `none returns false when all elements match`() {
        val list = ListImpl.of(1, 2, 3)
        assertFalse(list.none(Predicate1 { it > 0 }))
    }

    @Test
    fun `none returns true for empty collection`() {
        val list = ListImpl<Int>()
        assertTrue(list.none(Predicate1 { true }))
    }

    @Test
    fun `none with complex predicate`() {
        val list = ListImpl.of("apple", "banana", "cherry")
        assertTrue(list.none(Predicate1 { it.startsWith("z") }))
    }

    // ==================== one() tests ====================
    @Test
    fun `one returns true when exactly one matches`() {
        val list = ListImpl.of(1, 2, 3, 4, 5)
        assertTrue(list.one(Predicate1 { it == 3 }))
    }

    @Test
    fun `one returns false when no elements match`() {
        val list = ListImpl.of(1, 2, 3)
        assertFalse(list.one(Predicate1 { it > 10 }))
    }

    @Test
    fun `one returns false when multiple elements match`() {
        val list = ListImpl.of(1, 2, 3, 4)
        assertFalse(list.one(Predicate1 { it > 2 }))
    }

    @Test
    fun `one returns false for empty collection`() {
        val list = ListImpl<Int>()
        assertFalse(list.one(Predicate1 { true }))
    }

    @Test
    fun `one with complex predicate`() {
        val list = ListImpl.of("apple", "banana", "cherry", "apricot")
        assertTrue(list.one(Predicate1 { it.startsWith("b") }))
    }

    // ==================== reject() tests ====================
    @Test
    fun `reject removes matching elements`() {
        val list = ListImpl.of(1, 2, 3, 4, 5)
        val result = list.reject(Predicate1 { it > 3 })
        assertEquals(3, result.size())
        assertTrue(result.includes(1))
        assertTrue(result.includes(2))
        assertTrue(result.includes(3))
    }

    @Test
    fun `reject returns all elements when none match`() {
        val list = ListImpl.of(1, 2, 3)
        val result = list.reject(Predicate1 { it > 10 })
        assertEquals(3, result.size())
    }

    @Test
    fun `reject returns empty when all match`() {
        val list = ListImpl.of(1, 2, 3)
        val result = list.reject(Predicate1 { it > 0 })
        assertEquals(0, result.size())
    }

    @Test
    fun `reject handles empty collection`() {
        val list = ListImpl<Int>()
        val result = list.reject(Predicate1 { true })
        assertEquals(0, result.size())
    }

    @Test
    fun `reject with complex predicate`() {
        val list = ListImpl.of("apple", "banana", "apricot", "cherry")
        val result = list.reject(Predicate1 { it.startsWith("a") })
        assertEquals(2, result.size())
        assertTrue(result.includes("banana"))
        assertTrue(result.includes("cherry"))
    }

    // ==================== rejectOne() tests ====================
    @Test
    fun `rejectOne removes first matching element`() {
        val list = ListImpl.of(1, 2, 3, 2, 4)
        val result = list.rejectOne(Predicate1 { it == 2 })
        assertEquals(4, result.size())
        assertEquals(1, result.count(2))
    }

    @Test
    fun `rejectOne returns all elements when no match`() {
        val list = ListImpl.of(1, 2, 3)
        val result = list.rejectOne(Predicate1 { it > 10 })
        assertEquals(3, result.size())
    }

    @Test
    fun `rejectOne removes only one element`() {
        val list = ListImpl.of(1, 1, 1)
        val result = list.rejectOne(Predicate1 { it == 1 })
        assertEquals(2, result.size())
    }

    @Test
    fun `rejectOne handles empty collection`() {
        val list = ListImpl<Int>()
        val result = list.rejectOne(Predicate1 { true })
        assertEquals(0, result.size())
    }

    @Test
    fun `rejectOne preserves order`() {
        val list = ListImpl.of(1, 2, 3, 4, 5)
        val result = list.rejectOne(Predicate1 { it == 3 }) as ScriptList<Int>
        assertEquals(1, result.at(0))
        assertEquals(2, result.at(1))
        assertEquals(4, result.at(2))
    }

    // ==================== filter() tests ====================
    @Test
    fun `filter keeps matching elements`() {
        val list = ListImpl.of(1, 2, 3, 4, 5)
        val result = list.filter(Predicate1 { it > 3 })
        assertEquals(2, result.size())
        assertTrue(result.includes(4))
        assertTrue(result.includes(5))
    }

    @Test
    fun `filter returns empty when no match`() {
        val list = ListImpl.of(1, 2, 3)
        val result = list.filter(Predicate1 { it > 10 })
        assertEquals(0, result.size())
    }

    @Test
    fun `filter returns all elements when all match`() {
        val list = ListImpl.of(1, 2, 3)
        val result = list.filter(Predicate1 { it > 0 })
        assertEquals(3, result.size())
    }

    @Test
    fun `filter handles empty collection`() {
        val list = ListImpl<Int>()
        val result = list.filter(Predicate1 { true })
        assertEquals(0, result.size())
    }

    @Test
    fun `filter with complex predicate`() {
        val list = ListImpl.of("apple", "banana", "apricot", "cherry")
        val result = list.filter(Predicate1 { it.startsWith("a") })
        assertEquals(2, result.size())
        assertTrue(result.includes("apple"))
        assertTrue(result.includes("apricot"))
    }

    // ==================== all() tests ====================
    @Test
    fun `all returns true when all elements match`() {
        val list = ListImpl.of(2, 4, 6, 8)
        assertTrue(list.all(Predicate1 { it % 2 == 0 }))
    }

    @Test
    fun `all returns false when one element doesn't match`() {
        val list = ListImpl.of(2, 4, 5, 8)
        assertFalse(list.all(Predicate1 { it % 2 == 0 }))
    }

    @Test
    fun `all returns false when no elements match`() {
        val list = ListImpl.of(1, 3, 5)
        assertFalse(list.all(Predicate1 { it % 2 == 0 }))
    }

    @Test
    fun `all returns true for empty collection`() {
        val list = ListImpl<Int>()
        assertTrue(list.all(Predicate1 { false }))
    }

    @Test
    fun `all with complex predicate`() {
        val list = ListImpl.of("apple", "apricot", "avocado")
        assertTrue(list.all(Predicate1 { it.startsWith("a") }))
    }

    // ==================== find() tests ====================
    @Test
    fun `find returns first matching element`() {
        val list = ListImpl.of(1, 2, 3, 4, 5)
        val result = list.find(Predicate1 { it > 2 })
        assertEquals(3, result)
    }

    @Test
    fun `find returns null when no match`() {
        val list = ListImpl.of(1, 2, 3)
        val result = list.find(Predicate1 { it > 10 })
        assertNull(result)
    }

    @Test
    fun `find returns null for empty collection`() {
        val list = ListImpl<Int>()
        val result = list.find(Predicate1 { true })
        assertNull(result)
    }

    @Test
    fun `find returns first element if it matches`() {
        val list = ListImpl.of(5, 1, 2, 3)
        val result = list.find(Predicate1 { it > 4 })
        assertEquals(5, result)
    }

    @Test
    fun `find with complex predicate`() {
        val list = ListImpl.of("apple", "banana", "cherry")
        val result = list.find(Predicate1 { it.length > 5 })
        assertEquals("banana", result)
    }

    // ==================== sortedBy() tests ====================
    @Test
    fun `sortedBy sorts elements by key`() {
        val list = ListImpl.of(3, 1, 4, 1, 5, 9, 2, 6)
        val result = list.sortedBy(Func1 { it }) as ReadonlyList<Int>
        assertEquals(1, result.at(0))
        assertEquals(1, result.at(1))
        assertEquals(2, result.at(2))
    }

    @Test
    fun `sortedBy handles empty collection`() {
        val list = ListImpl<Int>()
        val result = list.sortedBy(Func1 { it })
        assertEquals(0, result.size())
    }

    @Test
    fun `sortedBy with complex key extractor`() {
        val list = ListImpl.of("apple", "pie", "banana", "split")
        val result = list.sortedBy(Func1 { it.length }) as ReadonlyList<String>
        assertEquals("pie", result.at(0))
        assertEquals("apple", result.at(1))
    }

    @Test
    fun `sortedBy does not modify original`() {
        val list = ListImpl.of(3, 1, 2)
        list.sortedBy(Func1 { it })
        assertEquals(3, list.at(0))
    }

    @Test
    fun `sortedBy sorts in ascending order`() {
        val list = ListImpl.of(5, 4, 3, 2, 1)
        val result = list.sortedBy(Func1 { it }) as ReadonlyList<Int>
        assertEquals(1, result.at(0))
        assertEquals(5, result.at(4))
    }

    // ==================== flatMap() tests ====================

    @Test
    fun `flatMap flattens elements into a single collection`() {
        val list = ListImpl.of(1, 2, 3)
        val result = list.flatMap(Func1 { ListImpl.of(it, it * 10) })
        assertEquals(6, result.size())
    }

    @Test
    fun `flatMap handles empty collection`() {
        val list = ListImpl.of<Int>()
        val result = list.flatMap(Func1 { ListImpl.of(it) })
        assertEquals(0, result.size())
    }

    @Test
    fun `flatMap preserves order`() {
        val list = ListImpl.of(1, 2, 3)
        @Suppress("UNCHECKED_CAST")
        val result = list.flatMap(Func1 { ListImpl.of(it) }) as ReadonlyList<Int>
        assertEquals(1, result.at(0))
        assertEquals(2, result.at(1))
        assertEquals(3, result.at(2))
    }

    // ==================== first() tests ====================

    @Test
    fun `first returns first element`() {
        val list = ListImpl.of(10, 20, 30)
        assertEquals(10, list.first())
    }

    @Test
    fun `first throws when empty`() {
        val list = ListImpl.of<Int>()
        try {
            list.first()
            error("Expected NoSuchElementException")
        } catch (e: NoSuchElementException) {
            // expected
        }
    }

    // ==================== firstOrNull() tests ====================

    @Test
    fun `firstOrNull returns first element of non-empty collection`() {
        val list = ListImpl.of(42, 2, 3)
        assertEquals(42, list.firstOrNull())
    }

    @Test
    fun `firstOrNull returns null for empty collection`() {
        val list = ListImpl.of<Int>()
        assertEquals(null, list.firstOrNull())
    }

    // ==================== sorted() tests ====================

    @Test
    fun `sorted returns elements in natural ascending order`() {
        val list = ListImpl.of(3, 1, 4, 1, 5, 9, 2, 6)
        val result = list.sorted()
        val elements = result.toList()
        assertEquals(listOf(1, 1, 2, 3, 4, 5, 6, 9), (0 until elements.size()).map { elements.at(it) })
    }

    @Test
    fun `sorted does not modify original collection`() {
        val list = ListImpl.of(3, 1, 2)
        list.sorted()
        assertEquals(3, list.at(0))
        assertEquals(1, list.at(1))
        assertEquals(2, list.at(2))
    }

    @Test
    fun `sorted returns ReadonlyOrderedCollection`() {
        val list = ListImpl.of(3, 1, 2)
        val result = list.sorted()
        assertNotNull(result)
        assertEquals(3, result.size())
    }

    @Test
    fun `sorted works on empty collection`() {
        val list = ListImpl.of<Int>()
        val result = list.sorted()
        assertEquals(0, result.size())
    }

    @Test
    fun `sorted works on single element collection`() {
        val list = ListImpl.of(42)
        val result = list.sorted()
        assertEquals(1, result.size())
        assertEquals(42, result.first())
    }

    @Test
    fun `sorted with comparator sorts ascending`() {
        val list = ListImpl.of(3, 1, 4, 1, 5)
        val result = list.sorted(com.mdeo.script.runtime.interfaces.Func2 { a, b -> a.compareTo(b) })
        val elements = result.toList()
        assertEquals(1, elements.at(0))
        assertEquals(5, elements.at(4))
    }

    @Test
    fun `sorted with comparator sorts descending`() {
        val list = ListImpl.of(3, 1, 4, 1, 5)
        val result = list.sorted(com.mdeo.script.runtime.interfaces.Func2 { a, b -> b.compareTo(a) })
        val elements = result.toList()
        assertEquals(5, elements.at(0))
        assertEquals(1, elements.at(4))
    }

    @Test
    fun `sorted with comparator does not modify original`() {
        val list = ListImpl.of(3, 1, 2)
        list.sorted(com.mdeo.script.runtime.interfaces.Func2 { a, b -> a.compareTo(b) })
        assertEquals(3, list.at(0))
    }

    @Test
    fun `sorted with comparator works on empty collection`() {
        val list = ListImpl.of<Int>()
        val result = list.sorted(com.mdeo.script.runtime.interfaces.Func2 { a, b -> a.compareTo(b) })
        assertEquals(0, result.size())
    }
}
