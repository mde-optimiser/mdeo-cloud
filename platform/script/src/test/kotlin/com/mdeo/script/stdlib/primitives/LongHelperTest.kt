package com.mdeo.script.stdlib.impl.primitives

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Comprehensive tests for LongHelper.
 */
class LongHelperTest {

    // ==================== abs() tests ====================
    @Test
    fun `abs of positive number returns same`() {
        assertEquals(5L, LongHelper.abs(5L))
    }

    @Test
    fun `abs of negative number returns positive`() {
        assertEquals(5L, LongHelper.abs(-5L))
    }

    @Test
    fun `abs of zero returns zero`() {
        assertEquals(0L, LongHelper.abs(0L))
    }

    @Test
    fun `abs of large positive returns same`() {
        assertEquals(Long.MAX_VALUE, LongHelper.abs(Long.MAX_VALUE))
    }

    @Test
    fun `abs of large negative handles overflow`() {
        // Edge case: abs(Long.MIN_VALUE) overflows
        assertEquals(Long.MIN_VALUE, LongHelper.abs(Long.MIN_VALUE))
    }

    // ==================== ceil() tests ====================
    @Test
    fun `ceil of long returns same`() {
        assertEquals(5L, LongHelper.ceil(5L))
    }

    @Test
    fun `ceil of negative returns same`() {
        assertEquals(-5L, LongHelper.ceil(-5L))
    }

    @Test
    fun `ceil of zero returns zero`() {
        assertEquals(0L, LongHelper.ceil(0L))
    }

    @Test
    fun `ceil of large number returns same`() {
        assertEquals(1000000000000L, LongHelper.ceil(1000000000000L))
    }

    @Test
    fun `ceil is identity for long`() {
        assertEquals(42L, LongHelper.ceil(42L))
    }

    // ==================== floor() tests ====================
    @Test
    fun `floor of long returns same`() {
        assertEquals(5L, LongHelper.floor(5L))
    }

    @Test
    fun `floor of negative returns same`() {
        assertEquals(-5L, LongHelper.floor(-5L))
    }

    @Test
    fun `floor of zero returns zero`() {
        assertEquals(0L, LongHelper.floor(0L))
    }

    @Test
    fun `floor of large number returns same`() {
        assertEquals(1000000000000L, LongHelper.floor(1000000000000L))
    }

    @Test
    fun `floor is identity for long`() {
        assertEquals(42L, LongHelper.floor(42L))
    }

    // ==================== log() tests ====================
    @Test
    fun `log of 1 returns 0`() {
        assertEquals(0.0, LongHelper.log(1L))
    }

    @Test
    fun `log of 10 is approximately 2_3`() {
        val result = LongHelper.log(10L)
        assertTrue(result > 2.0 && result < 2.5)
    }

    @Test
    fun `log of 100 is approximately 4_6`() {
        val result = LongHelper.log(100L)
        assertTrue(result > 4.0 && result < 5.0)
    }

    @Test
    fun `log increases with larger numbers`() {
        assertTrue(LongHelper.log(100L) > LongHelper.log(10L))
    }

    @Test
    fun `log of large number works`() {
        assertTrue(LongHelper.log(1000000000000L) > 0)
    }

    // ==================== log10() tests ====================
    @Test
    fun `log10 of 10 returns 1`() {
        assertEquals(1.0, LongHelper.log10(10L))
    }

    @Test
    fun `log10 of 100 returns 2`() {
        assertEquals(2.0, LongHelper.log10(100L))
    }

    @Test
    fun `log10 of 1000 returns 3`() {
        assertEquals(3.0, LongHelper.log10(1000L))
    }

    @Test
    fun `log10 of 1 returns 0`() {
        assertEquals(0.0, LongHelper.log10(1L))
    }

    @Test
    fun `log10 of 1000000000000 returns 12`() {
        assertEquals(12.0, LongHelper.log10(1000000000000L))
    }

    // ==================== max() tests ====================
    @Test
    fun `max of two longs returns larger`() {
        assertEquals(10L, LongHelper.max(5L, 10L))
    }

    @Test
    fun `max with equal values returns value`() {
        assertEquals(5L, LongHelper.max(5L, 5L))
    }

    @Test
    fun `max with negative returns larger`() {
        assertEquals(-5L, LongHelper.max(-10L, -5L))
    }

    @Test
    fun `max with zero and positive returns positive`() {
        assertEquals(5L, LongHelper.max(0L, 5L))
    }

    @Test
    fun `max with large numbers works`() {
        assertEquals(Long.MAX_VALUE, LongHelper.max(Long.MAX_VALUE - 1, Long.MAX_VALUE))
    }

    // ==================== min() tests ====================
    @Test
    fun `min of two longs returns smaller`() {
        assertEquals(5L, LongHelper.min(5L, 10L))
    }

    @Test
    fun `min with equal values returns value`() {
        assertEquals(5L, LongHelper.min(5L, 5L))
    }

    @Test
    fun `min with negative returns smaller`() {
        assertEquals(-10L, LongHelper.min(-10L, -5L))
    }

    @Test
    fun `min with zero and negative returns negative`() {
        assertEquals(-5L, LongHelper.min(0L, -5L))
    }

    @Test
    fun `min with large numbers works`() {
        assertEquals(Long.MIN_VALUE, LongHelper.min(Long.MIN_VALUE, Long.MIN_VALUE + 1))
    }

    // ==================== pow() tests ====================
    @Test
    fun `pow of 2 to 3 is 8`() {
        assertEquals(8.0, LongHelper.pow(2L, 3.0))
    }

    @Test
    fun `pow of any number to 0 is 1`() {
        assertEquals(1.0, LongHelper.pow(5L, 0.0))
    }

    @Test
    fun `pow of any number to 1 is same`() {
        assertEquals(5.0, LongHelper.pow(5L, 1.0))
    }

    @Test
    fun `pow of 0 to positive is 0`() {
        assertEquals(0.0, LongHelper.pow(0L, 5.0))
    }

    @Test
    fun `pow of 10 to 6 is 1000000`() {
        assertEquals(1000000.0, LongHelper.pow(10L, 6.0))
    }

    // ==================== round() tests ====================
    @Test
    fun `round of long returns same`() {
        assertEquals(5L, LongHelper.round(5L))
    }

    @Test
    fun `round of negative returns same`() {
        assertEquals(-5L, LongHelper.round(-5L))
    }

    @Test
    fun `round of zero returns zero`() {
        assertEquals(0L, LongHelper.round(0L))
    }

    @Test
    fun `round is identity for long`() {
        assertEquals(42L, LongHelper.round(42L))
    }

    @Test
    fun `round of large number returns same`() {
        assertEquals(1000000000000L, LongHelper.round(1000000000000L))
    }

    // ==================== iota() tests ====================
    @Test
    fun `iota 5 returns 0 to 4`() {
        val result = LongHelper.iota(0L, 5L, 1L)
        assertEquals(5, result.size())
        assertEquals(0, result.at(0))
        assertEquals(4, result.at(4))
    }

    @Test
    fun `iota 0 returns empty`() {
        val result = LongHelper.iota(0L, 0L, 1L)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `iota 1 returns single element 0`() {
        val result = LongHelper.iota(0L, 1L, 1L)
        assertEquals(1, result.size())
        assertEquals(0, result.at(0))
    }

    @Test
    fun `iota with negative step decrements`() {
        val result = LongHelper.iota(5L, 0L, -1L)
        assertEquals(5, result.size())
        assertEquals(5, result.at(0))
    }

    @Test
    fun `iota 10 contains all values`() {
        val result = LongHelper.iota(0L, 10L, 1L)
        for (i in 0 until 10) {
            assertTrue(result.includes(i))
        }
    }

    @Test
    fun `iota with step 0 throws IllegalArgumentException`() {
        assertThrows<IllegalArgumentException> { LongHelper.iota(0L, 10L, 0L) }
    }

    // ==================== mod() tests ====================
    @Test
    fun `mod 10 by 3 is 1`() {
        assertEquals(1L, LongHelper.mod(10L, 3L))
    }

    @Test
    fun `mod 9 by 3 is 0`() {
        assertEquals(0L, LongHelper.mod(9L, 3L))
    }

    @Test
    fun `mod negative by positive`() {
        // Kotlin % returns -1 (preserves sign of dividend)
        assertEquals(-1L, LongHelper.mod(-10L, 3L))
    }

    @Test
    fun `mod 0 by any is 0`() {
        assertEquals(0L, LongHelper.mod(0L, 5L))
    }

    @Test
    fun `mod by 1 is always 0`() {
        assertEquals(0L, LongHelper.mod(42L, 1L))
    }

    // ==================== to() tests ====================
    @Test
    fun `to 5 from 1 returns 1 to 5`() {
        val result = LongHelper.to(1L, 5L)
        assertEquals(5, result.size())
        assertEquals(1, result.at(0))
        assertEquals(5, result.at(4))
    }

    @Test
    fun `to same number returns single element`() {
        val result = LongHelper.to(5L, 5L)
        assertEquals(1, result.size())
        assertEquals(5, result.at(0))
    }

    @Test
    fun `to with reverse range works`() {
        val result = LongHelper.to(5L, 1L)
        assertEquals(5, result.size())
        assertEquals(5, result.at(0))
        assertEquals(1, result.at(4))
    }

    @Test
    fun `to includes both endpoints`() {
        val result = LongHelper.to(0L, 3L)
        assertTrue(result.includes(0))
        assertTrue(result.includes(3))
    }

    @Test
    fun `to with negative range`() {
        val result = LongHelper.to(-2L, 2L)
        assertEquals(5, result.size())
        assertTrue(result.includes(-2))
        assertTrue(result.includes(2))
    }

    // ==================== toBinary() tests ====================
    @Test
    fun `toBinary of 0 is 0`() {
        assertEquals("0", LongHelper.toBinary(0L))
    }

    @Test
    fun `toBinary of 1 is 1`() {
        assertEquals("1", LongHelper.toBinary(1L))
    }

    @Test
    fun `toBinary of 2 is 10`() {
        assertEquals("10", LongHelper.toBinary(2L))
    }

    @Test
    fun `toBinary of 8 is 1000`() {
        assertEquals("1000", LongHelper.toBinary(8L))
    }

    @Test
    fun `toBinary of 255 is 11111111`() {
        assertEquals("11111111", LongHelper.toBinary(255L))
    }

    // ==================== toHex() tests ====================
    @Test
    fun `toHex of 0 is 0`() {
        assertEquals("0", LongHelper.toHex(0L))
    }

    @Test
    fun `toHex of 15 is f`() {
        assertEquals("f", LongHelper.toHex(15L))
    }

    @Test
    fun `toHex of 16 is 10`() {
        assertEquals("10", LongHelper.toHex(16L))
    }

    @Test
    fun `toHex of 255 is ff`() {
        assertEquals("ff", LongHelper.toHex(255L))
    }

    @Test
    fun `toHex of 256 is 100`() {
        assertEquals("100", LongHelper.toHex(256L))
    }
}
