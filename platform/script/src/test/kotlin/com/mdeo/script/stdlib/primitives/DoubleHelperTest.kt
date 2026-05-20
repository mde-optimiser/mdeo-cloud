package com.mdeo.script.stdlib.impl.primitives

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Comprehensive tests for DoubleHelper.
 */
class DoubleHelperTest {

    // ==================== abs() tests ====================
    @Test
    fun `abs of positive number returns same`() {
        assertEquals(5.5, DoubleHelper.abs(5.5))
    }

    @Test
    fun `abs of negative number returns positive`() {
        assertEquals(5.5, DoubleHelper.abs(-5.5))
    }

    @Test
    fun `abs of zero returns zero`() {
        assertEquals(0.0, DoubleHelper.abs(0.0))
    }

    @Test
    fun `abs of negative zero returns zero`() {
        assertEquals(0.0, DoubleHelper.abs(-0.0))
    }

    @Test
    fun `abs of very small number works`() {
        assertEquals(0.0001, DoubleHelper.abs(-0.0001), 0.00001)
    }

    // ==================== ceil() tests ====================
    @Test
    fun `ceil of 5_3 is 6`() {
        assertEquals(6L, DoubleHelper.ceil(5.3))
    }

    @Test
    fun `ceil of 5_0 is 5`() {
        assertEquals(5L, DoubleHelper.ceil(5.0))
    }

    @Test
    fun `ceil of negative 5_7 is -5`() {
        assertEquals(-5L, DoubleHelper.ceil(-5.7))
    }

    @Test
    fun `ceil of 0_1 is 1`() {
        assertEquals(1L, DoubleHelper.ceil(0.1))
    }

    @Test
    fun `ceil of zero is zero`() {
        assertEquals(0L, DoubleHelper.ceil(0.0))
    }

    // ==================== floor() tests ====================
    @Test
    fun `floor of 5_7 is 5`() {
        assertEquals(5L, DoubleHelper.floor(5.7))
    }

    @Test
    fun `floor of 5_0 is 5`() {
        assertEquals(5L, DoubleHelper.floor(5.0))
    }

    @Test
    fun `floor of negative 5_3 is -6`() {
        assertEquals(-6L, DoubleHelper.floor(-5.3))
    }

    @Test
    fun `floor of 0_9 is 0`() {
        assertEquals(0L, DoubleHelper.floor(0.9))
    }

    @Test
    fun `floor of zero is zero`() {
        assertEquals(0L, DoubleHelper.floor(0.0))
    }

    // ==================== log() tests ====================
    @Test
    fun `log of 1 returns 0`() {
        assertEquals(0.0, DoubleHelper.log(1.0), 0.0001)
    }

    @Test
    fun `log of e returns 1`() {
        assertEquals(1.0, DoubleHelper.log(Math.E), 0.0001)
    }

    @Test
    fun `log of 10 is approximately 2_3`() {
        val result = DoubleHelper.log(10.0)
        assertTrue(result > 2.0 && result < 2.5)
    }

    @Test
    fun `log increases with larger numbers`() {
        assertTrue(DoubleHelper.log(100.0) > DoubleHelper.log(10.0))
    }

    @Test
    fun `log of very small positive number is negative`() {
        assertTrue(DoubleHelper.log(0.001) < 0)
    }

    // ==================== log10() tests ====================
    @Test
    fun `log10 of 10 returns 1`() {
        assertEquals(1.0, DoubleHelper.log10(10.0), 0.0001)
    }

    @Test
    fun `log10 of 100 returns 2`() {
        assertEquals(2.0, DoubleHelper.log10(100.0), 0.0001)
    }

    @Test
    fun `log10 of 1 returns 0`() {
        assertEquals(0.0, DoubleHelper.log10(1.0), 0.0001)
    }

    @Test
    fun `log10 of 0_1 returns -1`() {
        assertEquals(-1.0, DoubleHelper.log10(0.1), 0.0001)
    }

    @Test
    fun `log10 increases with larger numbers`() {
        assertTrue(DoubleHelper.log10(1000.0) > DoubleHelper.log10(100.0))
    }

    // ==================== max() tests ====================
    @Test
    fun `max of two doubles returns larger`() {
        assertEquals(10.5, DoubleHelper.max(5.5, 10.5))
    }

    @Test
    fun `max with equal values returns value`() {
        assertEquals(5.5, DoubleHelper.max(5.5, 5.5))
    }

    @Test
    fun `max with negative returns larger`() {
        assertEquals(-5.5, DoubleHelper.max(-10.5, -5.5))
    }

    @Test
    fun `max with zero and positive returns positive`() {
        assertEquals(5.5, DoubleHelper.max(0.0, 5.5))
    }

    @Test
    fun `max with NaN returns NaN`() {
        assertTrue(DoubleHelper.max(Double.NaN, 5.0).isNaN())
    }

    // ==================== min() tests ====================
    @Test
    fun `min of two doubles returns smaller`() {
        assertEquals(5.5, DoubleHelper.min(5.5, 10.5))
    }

    @Test
    fun `min with equal values returns value`() {
        assertEquals(5.5, DoubleHelper.min(5.5, 5.5))
    }

    @Test
    fun `min with negative returns smaller`() {
        assertEquals(-10.5, DoubleHelper.min(-10.5, -5.5))
    }

    @Test
    fun `min with zero and negative returns negative`() {
        assertEquals(-5.5, DoubleHelper.min(0.0, -5.5))
    }

    @Test
    fun `min with NaN returns NaN`() {
        assertTrue(DoubleHelper.min(Double.NaN, 5.0).isNaN())
    }

    // ==================== pow() tests ====================
    @Test
    fun `pow of 2 to 3 is 8`() {
        assertEquals(8.0, DoubleHelper.pow(2.0, 3.0), 0.0001)
    }

    @Test
    fun `pow of any number to 0 is 1`() {
        assertEquals(1.0, DoubleHelper.pow(5.5, 0.0), 0.0001)
    }

    @Test
    fun `pow of any number to 1 is same`() {
        assertEquals(5.5, DoubleHelper.pow(5.5, 1.0), 0.0001)
    }

    @Test
    fun `pow of 0 to positive is 0`() {
        assertEquals(0.0, DoubleHelper.pow(0.0, 5.0), 0.0001)
    }

    @Test
    fun `pow of 2_5 to 2 is 6_25`() {
        assertEquals(6.25, DoubleHelper.pow(2.5, 2.0), 0.0001)
    }

    // ==================== round() tests ====================
    @Test
    fun `round of 5_4 is 5`() {
        assertEquals(5L, DoubleHelper.round(5.4))
    }

    @Test
    fun `round of 5_5 is 6`() {
        assertEquals(6L, DoubleHelper.round(5.5))
    }

    @Test
    fun `round of 5_6 is 6`() {
        assertEquals(6L, DoubleHelper.round(5.6))
    }

    @Test
    fun `round of negative 5_4 is -5`() {
        assertEquals(-5L, DoubleHelper.round(-5.4))
    }

    @Test
    fun `round of negative 5_5 is -6`() {
        // Kotlin round() rounds half away from zero
        assertEquals(-6L, DoubleHelper.round(-5.5))
    }

    // ==================== isNaN() tests ====================
    @Test
    fun `isNaN returns true for NaN`() {
        assertTrue(DoubleHelper.isNaN(Double.NaN))
    }

    @Test
    fun `isNaN returns false for normal number`() {
        assertFalse(DoubleHelper.isNaN(5.0))
    }

    @Test
    fun `isNaN returns false for zero`() {
        assertFalse(DoubleHelper.isNaN(0.0))
    }

    @Test
    fun `isNaN returns false for infinity`() {
        assertFalse(DoubleHelper.isNaN(Double.POSITIVE_INFINITY))
    }

    @Test
    fun `isNaN returns false for negative infinity`() {
        assertFalse(DoubleHelper.isNaN(Double.NEGATIVE_INFINITY))
    }

    // ==================== isInfinite() tests ====================
    @Test
    fun `isInfinite returns true for positive infinity`() {
        assertTrue(DoubleHelper.isInfinite(Double.POSITIVE_INFINITY))
    }

    @Test
    fun `isInfinite returns true for negative infinity`() {
        assertTrue(DoubleHelper.isInfinite(Double.NEGATIVE_INFINITY))
    }

    @Test
    fun `isInfinite returns false for normal number`() {
        assertFalse(DoubleHelper.isInfinite(5.0))
    }

    @Test
    fun `isInfinite returns false for zero`() {
        assertFalse(DoubleHelper.isInfinite(0.0))
    }

    @Test
    fun `isInfinite returns false for NaN`() {
        assertFalse(DoubleHelper.isInfinite(Double.NaN))
    }

    // ==================== sqrt() tests ====================
    @Test
    fun `sqrt of 4 returns 2`() {
        assertEquals(2.0, DoubleHelper.sqrt(4.0), 0.0001)
    }

    @Test
    fun `sqrt of 9 returns 3`() {
        assertEquals(3.0, DoubleHelper.sqrt(9.0), 0.0001)
    }

    @Test
    fun `sqrt of 2 returns approximately 1_414`() {
        assertEquals(1.41421356, DoubleHelper.sqrt(2.0), 0.0001)
    }

    @Test
    fun `sqrt of 0 returns 0`() {
        assertEquals(0.0, DoubleHelper.sqrt(0.0), 0.0001)
    }

    @Test
    fun `sqrt of negative returns NaN`() {
        assertTrue(DoubleHelper.sqrt(-1.0).isNaN())
    }

    // ==================== sin() tests ====================
    @Test
    fun `sin of 0 returns 0`() {
        assertEquals(0.0, DoubleHelper.sin(0.0), 0.0001)
    }

    @Test
    fun `sin of pi over 2 returns 1`() {
        assertEquals(1.0, DoubleHelper.sin(Math.PI / 2), 0.0001)
    }

    @Test
    fun `sin of pi returns 0`() {
        assertEquals(0.0, DoubleHelper.sin(Math.PI), 0.0001)
    }

    @Test
    fun `sin of negative pi over 2 returns -1`() {
        assertEquals(-1.0, DoubleHelper.sin(-Math.PI / 2), 0.0001)
    }

    @Test
    fun `sin result is between -1 and 1`() {
        val result = DoubleHelper.sin(1.0)
        assertTrue(result >= -1.0 && result <= 1.0)
    }

    // ==================== cos() tests ====================
    @Test
    fun `cos of 0 returns 1`() {
        assertEquals(1.0, DoubleHelper.cos(0.0), 0.0001)
    }

    @Test
    fun `cos of pi over 2 returns 0`() {
        assertEquals(0.0, DoubleHelper.cos(Math.PI / 2), 0.0001)
    }

    @Test
    fun `cos of pi returns -1`() {
        assertEquals(-1.0, DoubleHelper.cos(Math.PI), 0.0001)
    }

    @Test
    fun `cos of 2pi returns 1`() {
        assertEquals(1.0, DoubleHelper.cos(2 * Math.PI), 0.0001)
    }

    @Test
    fun `cos result is between -1 and 1`() {
        val result = DoubleHelper.cos(1.0)
        assertTrue(result >= -1.0 && result <= 1.0)
    }

    // ==================== tan() tests ====================
    @Test
    fun `tan of 0 returns 0`() {
        assertEquals(0.0, DoubleHelper.tan(0.0), 0.0001)
    }

    @Test
    fun `tan of pi over 4 returns 1`() {
        assertEquals(1.0, DoubleHelper.tan(Math.PI / 4), 0.0001)
    }

    @Test
    fun `tan of negative pi over 4 returns -1`() {
        assertEquals(-1.0, DoubleHelper.tan(-Math.PI / 4), 0.0001)
    }

    @Test
    fun `tan of pi returns 0`() {
        assertEquals(0.0, DoubleHelper.tan(Math.PI), 0.0001)
    }

    @Test
    fun `tan is sin over cos`() {
        val angle = 0.5
        assertEquals(DoubleHelper.sin(angle) / DoubleHelper.cos(angle), DoubleHelper.tan(angle), 0.0001)
    }

    // ==================== asin() tests ====================
    @Test
    fun `asin of 0 returns 0`() {
        assertEquals(0.0, DoubleHelper.asin(0.0), 0.0001)
    }

    @Test
    fun `asin of 1 returns pi over 2`() {
        assertEquals(Math.PI / 2, DoubleHelper.asin(1.0), 0.0001)
    }

    @Test
    fun `asin of -1 returns negative pi over 2`() {
        assertEquals(-Math.PI / 2, DoubleHelper.asin(-1.0), 0.0001)
    }

    @Test
    fun `asin inverts sin`() {
        val value = 0.5
        assertEquals(value, DoubleHelper.sin(DoubleHelper.asin(value)), 0.0001)
    }

    @Test
    fun `asin of value outside range returns NaN`() {
        assertTrue(DoubleHelper.asin(2.0).isNaN())
    }

    // ==================== acos() tests ====================
    @Test
    fun `acos of 1 returns 0`() {
        assertEquals(0.0, DoubleHelper.acos(1.0), 0.0001)
    }

    @Test
    fun `acos of 0 returns pi over 2`() {
        assertEquals(Math.PI / 2, DoubleHelper.acos(0.0), 0.0001)
    }

    @Test
    fun `acos of -1 returns pi`() {
        assertEquals(Math.PI, DoubleHelper.acos(-1.0), 0.0001)
    }

    @Test
    fun `acos inverts cos`() {
        val value = 0.5
        assertEquals(value, DoubleHelper.cos(DoubleHelper.acos(value)), 0.0001)
    }

    @Test
    fun `acos of value outside range returns NaN`() {
        assertTrue(DoubleHelper.acos(2.0).isNaN())
    }

    // ==================== atan() tests ====================
    @Test
    fun `atan of 0 returns 0`() {
        assertEquals(0.0, DoubleHelper.atan(0.0), 0.0001)
    }

    @Test
    fun `atan of 1 returns pi over 4`() {
        assertEquals(Math.PI / 4, DoubleHelper.atan(1.0), 0.0001)
    }

    @Test
    fun `atan of -1 returns negative pi over 4`() {
        assertEquals(-Math.PI / 4, DoubleHelper.atan(-1.0), 0.0001)
    }

    @Test
    fun `atan result is between negative pi over 2 and pi over 2`() {
        val result = DoubleHelper.atan(100.0)
        assertTrue(result > -Math.PI / 2 && result < Math.PI / 2)
    }

    @Test
    fun `atan inverts tan`() {
        val value = 0.5
        assertEquals(value, DoubleHelper.tan(DoubleHelper.atan(value)), 0.0001)
    }

    // ==================== sinh() tests ====================
    @Test
    fun `sinh of 0 returns 0`() {
        assertEquals(0.0, DoubleHelper.sinh(0.0), 0.0001)
    }

    @Test
    fun `sinh of 1 is approximately 1_175`() {
        assertEquals(1.1752012, DoubleHelper.sinh(1.0), 0.0001)
    }

    @Test
    fun `sinh is odd function`() {
        assertEquals(-DoubleHelper.sinh(1.0), DoubleHelper.sinh(-1.0), 0.0001)
    }

    @Test
    fun `sinh grows with input`() {
        assertTrue(DoubleHelper.sinh(2.0) > DoubleHelper.sinh(1.0))
    }

    @Test
    fun `sinh of large value is large`() {
        assertTrue(DoubleHelper.sinh(10.0) > 1000.0)
    }

    // ==================== cosh() tests ====================
    @Test
    fun `cosh of 0 returns 1`() {
        assertEquals(1.0, DoubleHelper.cosh(0.0), 0.0001)
    }

    @Test
    fun `cosh of 1 is approximately 1_543`() {
        assertEquals(1.5430806, DoubleHelper.cosh(1.0), 0.0001)
    }

    @Test
    fun `cosh is even function`() {
        assertEquals(DoubleHelper.cosh(1.0), DoubleHelper.cosh(-1.0), 0.0001)
    }

    @Test
    fun `cosh is always at least 1`() {
        assertTrue(DoubleHelper.cosh(5.0) >= 1.0)
    }

    @Test
    fun `cosh squared minus sinh squared equals 1`() {
        val x = 2.0
        assertEquals(1.0, DoubleHelper.cosh(x) * DoubleHelper.cosh(x) - DoubleHelper.sinh(x) * DoubleHelper.sinh(x), 0.0001)
    }

    // ==================== tanh() tests ====================
    @Test
    fun `tanh of 0 returns 0`() {
        assertEquals(0.0, DoubleHelper.tanh(0.0), 0.0001)
    }

    @Test
    fun `tanh approaches 1 for large positive values`() {
        assertTrue(DoubleHelper.tanh(100.0) > 0.999)
    }

    @Test
    fun `tanh approaches -1 for large negative values`() {
        assertTrue(DoubleHelper.tanh(-100.0) < -0.999)
    }

    @Test
    fun `tanh is odd function`() {
        assertEquals(-DoubleHelper.tanh(1.0), DoubleHelper.tanh(-1.0), 0.0001)
    }

    @Test
    fun `tanh result is between -1 and 1`() {
        val result = DoubleHelper.tanh(2.0)
        assertTrue(result > -1.0 && result < 1.0)
    }
}
