package com.mdeo.script.stdlib.impl.primitives

import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.tan
import kotlin.math.asin
import kotlin.math.acos
import kotlin.math.atan
import kotlin.math.sinh
import kotlin.math.cosh
import kotlin.math.tanh

/**
 * Helper methods for the Float type in the script language.
 * These static methods are called with the instance as the first parameter.
 */
object FloatHelper {

    /**
     * Returns the absolute value of a float.
     *
     * @param value the float value
     * @return the absolute value
     */
    @JvmStatic
    fun abs(value: Float): Float = kotlin.math.abs(value)

    /**
     * Returns the ceiling of a float as an int.
     *
     * @param value the float value
     * @return the smallest integer greater than or equal to the value
     */
    @JvmStatic
    fun ceiling(value: Float): Int = ceil(value.toDouble()).toInt()

    /**
     * Returns the floor of a float as an int.
     *
     * @param value the float value
     * @return the largest integer less than or equal to the value
     */
    @JvmStatic
    fun floor(value: Float): Int = kotlin.math.floor(value.toDouble()).toInt()

    /**
     * Returns the natural logarithm of a float.
     *
     * @param value the float value
     * @return the natural logarithm
     */
    @JvmStatic
    fun log(value: Float): Float = ln(value.toDouble()).toFloat()

    /**
     * Returns the base-10 logarithm of a float.
     *
     * @param value the float value
     * @return the base-10 logarithm
     */
    @JvmStatic
    fun log10(value: Float): Float = kotlin.math.log10(value.toDouble()).toFloat()

    /**
     * Returns the maximum of a float and an int.
     *
     * @param value the float
     * @param other the int
     * @return the larger value as a float
     */
    @JvmStatic
    fun max(value: Float, other: Int): Float = maxOf(value, other.toFloat())

    /**
     * Returns the maximum of a float and a long.
     *
     * @param value the float
     * @param other the long
     * @return the larger value as a float
     */
    @JvmStatic
    fun max(value: Float, other: Long): Float = maxOf(value, other.toFloat())

    /**
     * Returns the maximum of two floats.
     *
     * @param value the first float
     * @param other the second float
     * @return the larger value
     */
    @JvmStatic
    fun max(value: Float, other: Float): Float = maxOf(value, other)

    /**
     * Returns the maximum of a float and a double.
     *
     * @param value the float
     * @param other the double
     * @return the larger value as a double
     */
    @JvmStatic
    fun max(value: Float, other: Double): Double = maxOf(value.toDouble(), other)

    /**
     * Returns the minimum of a float and an int.
     *
     * @param value the float
     * @param other the int
     * @return the smaller value as a float
     */
    @JvmStatic
    fun min(value: Float, other: Int): Float = minOf(value, other.toFloat())

    /**
     * Returns the minimum of a float and a long.
     *
     * @param value the float
     * @param other the long
     * @return the smaller value as a float
     */
    @JvmStatic
    fun min(value: Float, other: Long): Float = minOf(value, other.toFloat())

    /**
     * Returns the minimum of two floats.
     *
     * @param value the first float
     * @param other the second float
     * @return the smaller value
     */
    @JvmStatic
    fun min(value: Float, other: Float): Float = minOf(value, other)

    /**
     * Returns the minimum of a float and a double.
     *
     * @param value the float
     * @param other the double
     * @return the smaller value as a double
     */
    @JvmStatic
    fun min(value: Float, other: Double): Double = minOf(value.toDouble(), other)

    /**
     * Raises a float to the given power.
     *
     * @param value the base
     * @param exponent the exponent
     * @return the result as a double
     */
    @JvmStatic
    fun pow(value: Float, exponent: Double): Double = value.toDouble().pow(exponent)

    /**
     * Returns the rounded value of a float as an int.
     *
     * @param value the float value
     * @return the rounded integer value
     */
    @JvmStatic
    fun round(value: Float): Int = kotlin.math.round(value).toInt()

    /**
     * Converts a float to a string.
     *
     * @param value the float value
     * @return the string representation
     */
    @JvmStatic
    fun toString(value: Float): String = value.toString()

    /**
     * Checks if the float is NaN.
     *
     * @param value the float value
     * @return true if NaN
     */
    @JvmStatic
    fun isNaN(value: Float): Boolean = value.isNaN()

    /**
     * Checks if the float is infinite.
     *
     * @param value the float value
     * @return true if infinite
     */
    @JvmStatic
    fun isInfinite(value: Float): Boolean = value.isInfinite()

    /**
     * Returns the square root of a float.
     *
     * @param value the float value
     * @return the square root
     */
    @JvmStatic
    fun sqrt(value: Float): Float = kotlin.math.sqrt(value)

    /**
     * Returns the sine of a float (in radians).
     *
     * @param value the angle in radians
     * @return the sine
     */
    @JvmStatic
    fun sin(value: Float): Float = kotlin.math.sin(value.toDouble()).toFloat()

    /**
     * Returns the cosine of a float (in radians).
     *
     * @param value the angle in radians
     * @return the cosine
     */
    @JvmStatic
    fun cos(value: Float): Float = kotlin.math.cos(value.toDouble()).toFloat()

    /**
     * Returns the tangent of a float (in radians).
     *
     * @param value the angle in radians
     * @return the tangent
     */
    @JvmStatic
    fun tan(value: Float): Float = kotlin.math.tan(value.toDouble()).toFloat()

    /**
     * Returns the arc sine of a float.
     *
     * @param value the value (must be between -1 and 1)
     * @return the arc sine in radians
     */
    @JvmStatic
    fun asin(value: Float): Float = kotlin.math.asin(value.toDouble()).toFloat()

    /**
     * Returns the arc cosine of a float.
     *
     * @param value the value (must be between -1 and 1)
     * @return the arc cosine in radians
     */
    @JvmStatic
    fun acos(value: Float): Float = kotlin.math.acos(value.toDouble()).toFloat()

    /**
     * Returns the arc tangent of a float.
     *
     * @param value the float value
     * @return the arc tangent in radians
     */
    @JvmStatic
    fun atan(value: Float): Float = kotlin.math.atan(value.toDouble()).toFloat()

    /**
     * Returns the hyperbolic sine of a float.
     *
     * @param value the float value
     * @return the hyperbolic sine
     */
    @JvmStatic
    fun sinh(value: Float): Float = kotlin.math.sinh(value.toDouble()).toFloat()

    /**
     * Returns the hyperbolic cosine of a float.
     *
     * @param value the float value
     * @return the hyperbolic cosine
     */
    @JvmStatic
    fun cosh(value: Float): Float = kotlin.math.cosh(value.toDouble()).toFloat()

    /**
     * Returns the hyperbolic tangent of a float.
     *
     * @param value the float value
     * @return the hyperbolic tangent
     */
    @JvmStatic
    fun tanh(value: Float): Float = kotlin.math.tanh(value.toDouble()).toFloat()
}
