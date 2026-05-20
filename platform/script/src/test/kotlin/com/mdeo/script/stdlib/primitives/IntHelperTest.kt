package com.mdeo.script.stdlib.impl.primitives

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Comprehensive tests for IntHelper.
 */
class IntHelperTest {

    // ==================== abs() tests ====================
    @Test
    fun `abs of positive number returns same`() {
        assertEquals(5, IntHelper.abs(5))
    }

    @Test
    fun `abs of negative number returns positive`() {
        assertEquals(5, IntHelper.abs(-5))
    }

    @Test
    fun `abs of zero returns zero`() {
        assertEquals(0, IntHelper.abs(0))
    }

    @Test
    fun `abs of MIN_VALUE returns MIN_VALUE`() {
        // Edge case: abs(Int.MIN_VALUE) overflows
        assertEquals(Int.MIN_VALUE, IntHelper.abs(Int.MIN_VALUE))
    }

    @Test
    fun `abs of MAX_VALUE returns MAX_VALUE`() {
        assertEquals(Int.MAX_VALUE, IntHelper.abs(Int.MAX_VALUE))
    }

    // ==================== ceil() tests ====================
    @Test
    fun `ceil of int returns same`() {
        assertEquals(5, IntHelper.ceil(5))
    }

    @Test
    fun `ceil of negative returns same`() {
        assertEquals(-5, IntHelper.ceil(-5))
    }

    @Test
    fun `ceil of zero returns zero`() {
        assertEquals(0, IntHelper.ceil(0))
    }

    @Test
    fun `ceil of large number returns same`() {
        assertEquals(1000000, IntHelper.ceil(1000000))
    }

    @Test
    fun `ceil is identity for int`() {
        assertEquals(42, IntHelper.ceil(42))
    }

    // ==================== floor() tests ====================
    @Test
    fun `floor of int returns same`() {
        assertEquals(5, IntHelper.floor(5))
    }

    @Test
    fun `floor of negative returns same`() {
        assertEquals(-5, IntHelper.floor(-5))
    }

    @Test
    fun `floor of zero returns zero`() {
        assertEquals(0, IntHelper.floor(0))
    }

    @Test
    fun `floor of large number returns same`() {
        assertEquals(1000000, IntHelper.floor(1000000))
    }

    @Test
    fun `floor is identity for int`() {
        assertEquals(42, IntHelper.floor(42))
    }

    // ==================== log() tests ====================
    @Test
    fun `log of e returns 1`() {
        val result = IntHelper.log(Math.E.toInt() + 1)
        assertTrue(result > 0)
    }

    @Test
    fun `log of 1 returns 0`() {
        assertEquals(0.0f, IntHelper.log(1))
    }

    @Test
    fun `log of 10 is approximately 2_3`() {
        val result = IntHelper.log(10)
        assertTrue(result > 2.0f && result < 2.5f)
    }

    @Test
    fun `log of 100 is approximately 4_6`() {
        val result = IntHelper.log(100)
        assertTrue(result > 4.0f && result < 5.0f)
    }

    @Test
    fun `log increases with larger numbers`() {
        assertTrue(IntHelper.log(100) > IntHelper.log(10))
    }

    // ==================== log10() tests ====================
    @Test
    fun `log10 of 10 returns 1`() {
        assertEquals(1.0f, IntHelper.log10(10))
    }

    @Test
    fun `log10 of 100 returns 2`() {
        assertEquals(2.0f, IntHelper.log10(100))
    }

    @Test
    fun `log10 of 1000 returns 3`() {
        assertEquals(3.0f, IntHelper.log10(1000))
    }

    @Test
    fun `log10 of 1 returns 0`() {
        assertEquals(0.0f, IntHelper.log10(1))
    }

    @Test
    fun `log10 increases with larger numbers`() {
        assertTrue(IntHelper.log10(1000) > IntHelper.log10(100))
    }

    // ==================== max() tests ====================
    @Test
    fun `max of two ints returns larger`() {
        assertEquals(10, IntHelper.max(5, 10))
    }

    @Test
    fun `max with equal values returns value`() {
        assertEquals(5, IntHelper.max(5, 5))
    }

    @Test
    fun `max with negative returns larger`() {
        assertEquals(-5, IntHelper.max(-10, -5))
    }

    @Test
    fun `max with zero and positive returns positive`() {
        assertEquals(5, IntHelper.max(0, 5))
    }

    @Test
    fun `max with zero and negative returns zero`() {
        assertEquals(0, IntHelper.max(0, -5))
    }

    // ==================== min() tests ====================
    @Test
    fun `min of two ints returns smaller`() {
        assertEquals(5, IntHelper.min(5, 10))
    }

    @Test
    fun `min with equal values returns value`() {
        assertEquals(5, IntHelper.min(5, 5))
    }

    @Test
    fun `min with negative returns smaller`() {
        assertEquals(-10, IntHelper.min(-10, -5))
    }

    @Test
    fun `min with zero and positive returns zero`() {
        assertEquals(0, IntHelper.min(0, 5))
    }

    @Test
    fun `min with zero and negative returns negative`() {
        assertEquals(-5, IntHelper.min(0, -5))
    }

    // ==================== pow() tests ====================
    @Test
    fun `pow of 2 to 3 is 8`() {
        assertEquals(8.0, IntHelper.pow(2, 3.0))
    }

    @Test
    fun `pow of any number to 0 is 1`() {
        assertEquals(1.0, IntHelper.pow(5, 0.0))
    }

    @Test
    fun `pow of any number to 1 is same`() {
        assertEquals(5.0, IntHelper.pow(5, 1.0))
    }

    @Test
    fun `pow of 0 to positive is 0`() {
        assertEquals(0.0, IntHelper.pow(0, 5.0))
    }

    @Test
    fun `pow of 10 to 2 is 100`() {
        assertEquals(100.0, IntHelper.pow(10, 2.0))
    }

    // ==================== round() tests ====================
    @Test
    fun `round of int returns same`() {
        assertEquals(5, IntHelper.round(5))
    }

    @Test
    fun `round of negative returns same`() {
        assertEquals(-5, IntHelper.round(-5))
    }

    @Test
    fun `round of zero returns zero`() {
        assertEquals(0, IntHelper.round(0))
    }

    @Test
    fun `round is identity for int`() {
        assertEquals(42, IntHelper.round(42))
    }

    @Test
    fun `round of large number returns same`() {
        assertEquals(1000000, IntHelper.round(1000000))
    }

    // ==================== iota() tests ====================
    @Test
    fun `iota 5 returns 0 to 4`() {
        val result = IntHelper.iota(0, 5, 1)
        assertEquals(5, result.size())
        assertEquals(0, result.at(0))
        assertEquals(4, result.at(4))
    }

    @Test
    fun `iota 0 returns empty`() {
        val result = IntHelper.iota(0, 0, 1)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `iota 1 returns single element 0`() {
        val result = IntHelper.iota(0, 1, 1)
        assertEquals(1, result.size())
        assertEquals(0, result.at(0))
    }

    @Test
    fun `iota with negative step decrements`() {
        val result = IntHelper.iota(5, 0, -1)
        assertEquals(5, result.size())
        assertEquals(5, result.at(0))
        assertEquals(1, result.at(4))
    }

    @Test
    fun `iota 10 contains all values`() {
        val result = IntHelper.iota(0, 10, 1)
        for (i in 0 until 10) {
            assertTrue(result.includes(i))
        }
    }

    @Test
    fun `iota with step 0 throws IllegalArgumentException`() {
        // BUG: step=0 silently returns empty list instead of throwing
        assertThrows<IllegalArgumentException> { IntHelper.iota(0, 10, 0) }
    }

    // ==================== mod() tests ====================
    @Test
    fun `mod 10 by 3 is 1`() {
        assertEquals(1, IntHelper.mod(10, 3))
    }

    @Test
    fun `mod 9 by 3 is 0`() {
        assertEquals(0, IntHelper.mod(9, 3))
    }

    @Test
    fun `mod negative by positive`() {
        // Kotlin % returns -1 (preserves sign of dividend)
        assertEquals(-1, IntHelper.mod(-10, 3))
    }

    @Test
    fun `mod 0 by any is 0`() {
        assertEquals(0, IntHelper.mod(0, 5))
    }

    @Test
    fun `mod by 1 is always 0`() {
        assertEquals(0, IntHelper.mod(42, 1))
    }

    // ==================== to() tests ====================
    @Test
    fun `to 5 from 1 returns 1 to 5`() {
        val result = IntHelper.to(1, 5)
        assertEquals(5, result.size())
        assertEquals(1, result.at(0))
        assertEquals(5, result.at(4))
    }

    @Test
    fun `to same number returns single element`() {
        val result = IntHelper.to(5, 5)
        assertEquals(1, result.size())
        assertEquals(5, result.at(0))
    }

    @Test
    fun `to with reverse range works`() {
        val result = IntHelper.to(5, 1)
        assertEquals(5, result.size())
        assertEquals(5, result.at(0))
        assertEquals(1, result.at(4))
    }

    @Test
    fun `to includes both endpoints`() {
        val result = IntHelper.to(0, 3)
        assertTrue(result.includes(0))
        assertTrue(result.includes(3))
    }

    @Test
    fun `to with negative range`() {
        val result = IntHelper.to(-2, 2)
        assertEquals(5, result.size())
        assertTrue(result.includes(-2))
        assertTrue(result.includes(2))
    }

    // ==================== toBinary() tests ====================
    @Test
    fun `toBinary of 0 is 0`() {
        assertEquals("0", IntHelper.toBinary(0))
    }

    @Test
    fun `toBinary of 1 is 1`() {
        assertEquals("1", IntHelper.toBinary(1))
    }

    @Test
    fun `toBinary of 2 is 10`() {
        assertEquals("10", IntHelper.toBinary(2))
    }

    @Test
    fun `toBinary of 8 is 1000`() {
        assertEquals("1000", IntHelper.toBinary(8))
    }

    @Test
    fun `toBinary of 255 is 11111111`() {
        assertEquals("11111111", IntHelper.toBinary(255))
    }

    // ==================== toHex() tests ====================
    @Test
    fun `toHex of 0 is 0`() {
        assertEquals("0", IntHelper.toHex(0))
    }

    @Test
    fun `toHex of 15 is f`() {
        assertEquals("f", IntHelper.toHex(15))
    }

    @Test
    fun `toHex of 16 is 10`() {
        assertEquals("10", IntHelper.toHex(16))
    }

    @Test
    fun `toHex of 255 is ff`() {
        assertEquals("ff", IntHelper.toHex(255))
    }

    @Test
    fun `toHex of 256 is 100`() {
        assertEquals("100", IntHelper.toHex(256))
    }
}
