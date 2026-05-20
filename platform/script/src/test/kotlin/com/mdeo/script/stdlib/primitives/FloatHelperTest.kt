package com.mdeo.script.stdlib.impl.primitives

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Comprehensive tests for FloatHelper.
 */
class FloatHelperTest {

    // ==================== abs() tests ====================
    @Test
    fun `abs of positive number returns same`() {
        assertEquals(5.5f, FloatHelper.abs(5.5f))
    }

    @Test
    fun `abs of negative number returns positive`() {
        assertEquals(5.5f, FloatHelper.abs(-5.5f))
    }

    @Test
    fun `abs of zero returns zero`() {
        assertEquals(0.0f, FloatHelper.abs(0.0f))
    }

    @Test
    fun `abs of negative zero returns zero`() {
        assertEquals(0.0f, FloatHelper.abs(-0.0f))
    }

    @Test
    fun `abs of very small number works`() {
        assertEquals(0.0001f, FloatHelper.abs(-0.0001f), 0.00001f)
    }

    // ==================== ceil() tests ====================
    @Test
    fun `ceil of 5_3 is 6`() {
        assertEquals(6, FloatHelper.ceil(5.3f))
    }

    @Test
    fun `ceil of 5_0 is 5`() {
        assertEquals(5, FloatHelper.ceil(5.0f))
    }

    @Test
    fun `ceil of negative 5_7 is -5`() {
        assertEquals(-5, FloatHelper.ceil(-5.7f))
    }

    @Test
    fun `ceil of 0_1 is 1`() {
        assertEquals(1, FloatHelper.ceil(0.1f))
    }

    @Test
    fun `ceil of zero is zero`() {
        assertEquals(0, FloatHelper.ceil(0.0f))
    }

    // ==================== floor() tests ====================
    @Test
    fun `floor of 5_7 is 5`() {
        assertEquals(5, FloatHelper.floor(5.7f))
    }

    @Test
    fun `floor of 5_0 is 5`() {
        assertEquals(5, FloatHelper.floor(5.0f))
    }

    @Test
    fun `floor of negative 5_3 is -6`() {
        assertEquals(-6, FloatHelper.floor(-5.3f))
    }

    @Test
    fun `floor of 0_9 is 0`() {
        assertEquals(0, FloatHelper.floor(0.9f))
    }

    @Test
    fun `floor of zero is zero`() {
        assertEquals(0, FloatHelper.floor(0.0f))
    }

    // ==================== log() tests ====================
    @Test
    fun `log of 1 returns 0`() {
        assertEquals(0.0f, FloatHelper.log(1.0f))
    }

    @Test
    fun `log of e returns approximately 1`() {
        assertEquals(1.0f, FloatHelper.log(Math.E.toFloat()), 0.01f)
    }

    @Test
    fun `log of 10 is approximately 2_3`() {
        val result = FloatHelper.log(10.0f)
        assertTrue(result > 2.0f && result < 2.5f)
    }

    @Test
    fun `log increases with larger numbers`() {
        assertTrue(FloatHelper.log(100.0f) > FloatHelper.log(10.0f))
    }

    @Test
    fun `log of very small positive number is negative`() {
        assertTrue(FloatHelper.log(0.001f) < 0)
    }

    // ==================== log10() tests ====================
    @Test
    fun `log10 of 10 returns 1`() {
        assertEquals(1.0f, FloatHelper.log10(10.0f), 0.0001f)
    }

    @Test
    fun `log10 of 100 returns 2`() {
        assertEquals(2.0f, FloatHelper.log10(100.0f), 0.0001f)
    }

    @Test
    fun `log10 of 1 returns 0`() {
        assertEquals(0.0f, FloatHelper.log10(1.0f), 0.0001f)
    }

    @Test
    fun `log10 of 0_1 returns -1`() {
        assertEquals(-1.0f, FloatHelper.log10(0.1f), 0.0001f)
    }

    @Test
    fun `log10 increases with larger numbers`() {
        assertTrue(FloatHelper.log10(1000.0f) > FloatHelper.log10(100.0f))
    }

    // ==================== max() tests ====================
    @Test
    fun `max of two floats returns larger`() {
        assertEquals(10.5f, FloatHelper.max(5.5f, 10.5f))
    }

    @Test
    fun `max with equal values returns value`() {
        assertEquals(5.5f, FloatHelper.max(5.5f, 5.5f))
    }

    @Test
    fun `max with negative returns larger`() {
        assertEquals(-5.5f, FloatHelper.max(-10.5f, -5.5f))
    }

    @Test
    fun `max with zero and positive returns positive`() {
        assertEquals(5.5f, FloatHelper.max(0.0f, 5.5f))
    }

    @Test
    fun `max with NaN returns NaN`() {
        assertTrue(FloatHelper.max(Float.NaN, 5.0f).isNaN())
    }

    // ==================== min() tests ====================
    @Test
    fun `min of two floats returns smaller`() {
        assertEquals(5.5f, FloatHelper.min(5.5f, 10.5f))
    }

    @Test
    fun `min with equal values returns value`() {
        assertEquals(5.5f, FloatHelper.min(5.5f, 5.5f))
    }

    @Test
    fun `min with negative returns smaller`() {
        assertEquals(-10.5f, FloatHelper.min(-10.5f, -5.5f))
    }

    @Test
    fun `min with zero and negative returns negative`() {
        assertEquals(-5.5f, FloatHelper.min(0.0f, -5.5f))
    }

    @Test
    fun `min with NaN returns NaN`() {
        assertTrue(FloatHelper.min(Float.NaN, 5.0f).isNaN())
    }

    // ==================== pow() tests ====================
    @Test
    fun `pow of 2 to 3 is 8`() {
        assertEquals(8.0, FloatHelper.pow(2.0f, 3.0), 0.0001)
    }

    @Test
    fun `pow of any number to 0 is 1`() {
        assertEquals(1.0, FloatHelper.pow(5.5f, 0.0), 0.0001)
    }

    @Test
    fun `pow of any number to 1 is same`() {
        assertEquals(5.5, FloatHelper.pow(5.5f, 1.0), 0.0001)
    }

    @Test
    fun `pow of 0 to positive is 0`() {
        assertEquals(0.0, FloatHelper.pow(0.0f, 5.0), 0.0001)
    }

    @Test
    fun `pow of 2_5 to 2 is 6_25`() {
        assertEquals(6.25, FloatHelper.pow(2.5f, 2.0), 0.0001)
    }

    // ==================== round() tests ====================
    @Test
    fun `round of 5_4 is 5`() {
        assertEquals(5, FloatHelper.round(5.4f))
    }

    @Test
    fun `round of 5_5 is 6`() {
        assertEquals(6, FloatHelper.round(5.5f))
    }

    @Test
    fun `round of 5_6 is 6`() {
        assertEquals(6, FloatHelper.round(5.6f))
    }

    @Test
    fun `round of negative 5_4 is -5`() {
        assertEquals(-5, FloatHelper.round(-5.4f))
    }

    @Test
    fun `round of negative 5_5 is -6`() {
        // Kotlin round() rounds half away from zero
        assertEquals(-6, FloatHelper.round(-5.5f))
    }

    // ==================== isNaN() tests ====================
    @Test
    fun `isNaN returns true for NaN`() {
        assertTrue(FloatHelper.isNaN(Float.NaN))
    }

    @Test
    fun `isNaN returns false for normal number`() {
        assertFalse(FloatHelper.isNaN(5.0f))
    }

    @Test
    fun `isNaN returns false for zero`() {
        assertFalse(FloatHelper.isNaN(0.0f))
    }

    @Test
    fun `isNaN returns false for infinity`() {
        assertFalse(FloatHelper.isNaN(Float.POSITIVE_INFINITY))
    }

    @Test
    fun `isNaN returns false for negative infinity`() {
        assertFalse(FloatHelper.isNaN(Float.NEGATIVE_INFINITY))
    }

    // ==================== isInfinite() tests ====================
    @Test
    fun `isInfinite returns true for positive infinity`() {
        assertTrue(FloatHelper.isInfinite(Float.POSITIVE_INFINITY))
    }

    @Test
    fun `isInfinite returns true for negative infinity`() {
        assertTrue(FloatHelper.isInfinite(Float.NEGATIVE_INFINITY))
    }

    @Test
    fun `isInfinite returns false for normal number`() {
        assertFalse(FloatHelper.isInfinite(5.0f))
    }

    @Test
    fun `isInfinite returns false for zero`() {
        assertFalse(FloatHelper.isInfinite(0.0f))
    }

    @Test
    fun `isInfinite returns false for NaN`() {
        assertFalse(FloatHelper.isInfinite(Float.NaN))
    }

    // ==================== sqrt() tests ====================
    @Test
    fun `sqrt of 4 returns 2`() {
        assertEquals(2.0f, FloatHelper.sqrt(4.0f), 0.0001f)
    }

    @Test
    fun `sqrt of 9 returns 3`() {
        assertEquals(3.0f, FloatHelper.sqrt(9.0f), 0.0001f)
    }

    @Test
    fun `sqrt of 2 returns approximately 1_414`() {
        assertEquals(1.41421f, FloatHelper.sqrt(2.0f), 0.0001f)
    }

    @Test
    fun `sqrt of 0 returns 0`() {
        assertEquals(0.0f, FloatHelper.sqrt(0.0f), 0.0001f)
    }

    @Test
    fun `sqrt of negative returns NaN`() {
        assertTrue(FloatHelper.sqrt(-1.0f).isNaN())
    }

    // ==================== sin() tests ====================
    @Test
    fun `sin of 0 returns 0`() {
        assertEquals(0.0f, FloatHelper.sin(0.0f), 0.0001f)
    }

    @Test
    fun `sin of pi over 2 returns 1`() {
        assertEquals(1.0f, FloatHelper.sin((Math.PI / 2).toFloat()), 0.0001f)
    }

    @Test
    fun `sin of pi returns 0`() {
        assertEquals(0.0f, FloatHelper.sin(Math.PI.toFloat()), 0.001f)
    }

    @Test
    fun `sin of negative pi over 2 returns -1`() {
        assertEquals(-1.0f, FloatHelper.sin((-Math.PI / 2).toFloat()), 0.0001f)
    }

    @Test
    fun `sin result is between -1 and 1`() {
        val result = FloatHelper.sin(1.0f)
        assertTrue(result >= -1.0f && result <= 1.0f)
    }

    // ==================== cos() tests ====================
    @Test
    fun `cos of 0 returns 1`() {
        assertEquals(1.0f, FloatHelper.cos(0.0f), 0.0001f)
    }

    @Test
    fun `cos of pi over 2 returns 0`() {
        assertEquals(0.0f, FloatHelper.cos((Math.PI / 2).toFloat()), 0.001f)
    }

    @Test
    fun `cos of pi returns -1`() {
        assertEquals(-1.0f, FloatHelper.cos(Math.PI.toFloat()), 0.0001f)
    }

    @Test
    fun `cos of 2pi returns 1`() {
        assertEquals(1.0f, FloatHelper.cos((2 * Math.PI).toFloat()), 0.0001f)
    }

    @Test
    fun `cos result is between -1 and 1`() {
        val result = FloatHelper.cos(1.0f)
        assertTrue(result >= -1.0f && result <= 1.0f)
    }

    // ==================== tan() tests ====================
    @Test
    fun `tan of 0 returns 0`() {
        assertEquals(0.0f, FloatHelper.tan(0.0f), 0.0001f)
    }

    @Test
    fun `tan of pi over 4 returns 1`() {
        assertEquals(1.0f, FloatHelper.tan((Math.PI / 4).toFloat()), 0.0001f)
    }

    @Test
    fun `tan of negative pi over 4 returns -1`() {
        assertEquals(-1.0f, FloatHelper.tan((-Math.PI / 4).toFloat()), 0.0001f)
    }

    @Test
    fun `tan of pi returns 0`() {
        assertEquals(0.0f, FloatHelper.tan(Math.PI.toFloat()), 0.001f)
    }

    @Test
    fun `tan is sin over cos`() {
        val angle = 0.5f
        assertEquals(FloatHelper.sin(angle) / FloatHelper.cos(angle), FloatHelper.tan(angle), 0.0001f)
    }

    // ==================== asin() tests ====================
    @Test
    fun `asin of 0 returns 0`() {
        assertEquals(0.0f, FloatHelper.asin(0.0f), 0.0001f)
    }

    @Test
    fun `asin of 1 returns pi over 2`() {
        assertEquals((Math.PI / 2).toFloat(), FloatHelper.asin(1.0f), 0.0001f)
    }

    @Test
    fun `asin of -1 returns negative pi over 2`() {
        assertEquals((-Math.PI / 2).toFloat(), FloatHelper.asin(-1.0f), 0.0001f)
    }

    @Test
    fun `asin inverts sin`() {
        val value = 0.5f
        assertEquals(value, FloatHelper.sin(FloatHelper.asin(value)), 0.0001f)
    }

    @Test
    fun `asin of value outside range returns NaN`() {
        assertTrue(FloatHelper.asin(2.0f).isNaN())
    }

    // ==================== acos() tests ====================
    @Test
    fun `acos of 1 returns 0`() {
        assertEquals(0.0f, FloatHelper.acos(1.0f), 0.0001f)
    }

    @Test
    fun `acos of 0 returns pi over 2`() {
        assertEquals((Math.PI / 2).toFloat(), FloatHelper.acos(0.0f), 0.0001f)
    }

    @Test
    fun `acos of -1 returns pi`() {
        assertEquals(Math.PI.toFloat(), FloatHelper.acos(-1.0f), 0.0001f)
    }

    @Test
    fun `acos inverts cos`() {
        val value = 0.5f
        assertEquals(value, FloatHelper.cos(FloatHelper.acos(value)), 0.0001f)
    }

    @Test
    fun `acos of value outside range returns NaN`() {
        assertTrue(FloatHelper.acos(2.0f).isNaN())
    }

    // ==================== atan() tests ====================
    @Test
    fun `atan of 0 returns 0`() {
        assertEquals(0.0f, FloatHelper.atan(0.0f), 0.0001f)
    }

    @Test
    fun `atan of 1 returns pi over 4`() {
        assertEquals((Math.PI / 4).toFloat(), FloatHelper.atan(1.0f), 0.0001f)
    }

    @Test
    fun `atan of -1 returns negative pi over 4`() {
        assertEquals((-Math.PI / 4).toFloat(), FloatHelper.atan(-1.0f), 0.0001f)
    }

    @Test
    fun `atan result is between negative pi over 2 and pi over 2`() {
        val result = FloatHelper.atan(100.0f)
        assertTrue(result > (-Math.PI / 2).toFloat() && result < (Math.PI / 2).toFloat())
    }

    @Test
    fun `atan inverts tan`() {
        val value = 0.5f
        assertEquals(value, FloatHelper.tan(FloatHelper.atan(value)), 0.0001f)
    }

    // ==================== sinh() tests ====================
    @Test
    fun `sinh of 0 returns 0`() {
        assertEquals(0.0f, FloatHelper.sinh(0.0f), 0.0001f)
    }

    @Test
    fun `sinh of 1 is approximately 1_175`() {
        assertEquals(1.1752012f, FloatHelper.sinh(1.0f), 0.001f)
    }

    @Test
    fun `sinh is odd function`() {
        assertEquals(-FloatHelper.sinh(1.0f), FloatHelper.sinh(-1.0f), 0.0001f)
    }

    @Test
    fun `sinh grows with input`() {
        assertTrue(FloatHelper.sinh(2.0f) > FloatHelper.sinh(1.0f))
    }

    @Test
    fun `sinh of large value is large`() {
        assertTrue(FloatHelper.sinh(5.0f) > 70.0f)
    }

    // ==================== cosh() tests ====================
    @Test
    fun `cosh of 0 returns 1`() {
        assertEquals(1.0f, FloatHelper.cosh(0.0f), 0.0001f)
    }

    @Test
    fun `cosh of 1 is approximately 1_543`() {
        assertEquals(1.5430806f, FloatHelper.cosh(1.0f), 0.001f)
    }

    @Test
    fun `cosh is even function`() {
        assertEquals(FloatHelper.cosh(1.0f), FloatHelper.cosh(-1.0f), 0.0001f)
    }

    @Test
    fun `cosh is always at least 1`() {
        assertTrue(FloatHelper.cosh(5.0f) >= 1.0f)
    }

    @Test
    fun `cosh squared minus sinh squared equals 1`() {
        val x = 2.0f
        assertEquals(1.0f, FloatHelper.cosh(x) * FloatHelper.cosh(x) - FloatHelper.sinh(x) * FloatHelper.sinh(x), 0.001f)
    }

    // ==================== tanh() tests ====================
    @Test
    fun `tanh of 0 returns 0`() {
        assertEquals(0.0f, FloatHelper.tanh(0.0f), 0.0001f)
    }

    @Test
    fun `tanh approaches 1 for large positive values`() {
        assertTrue(FloatHelper.tanh(20.0f) > 0.999f)
    }

    @Test
    fun `tanh approaches -1 for large negative values`() {
        assertTrue(FloatHelper.tanh(-20.0f) < -0.999f)
    }

    @Test
    fun `tanh is odd function`() {
        assertEquals(-FloatHelper.tanh(1.0f), FloatHelper.tanh(-1.0f), 0.0001f)
    }

    @Test
    fun `tanh result is between -1 and 1`() {
        val result = FloatHelper.tanh(2.0f)
        assertTrue(result > -1.0f && result < 1.0f)
    }
}
