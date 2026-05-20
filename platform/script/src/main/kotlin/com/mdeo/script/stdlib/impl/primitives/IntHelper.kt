package com.mdeo.script.stdlib.impl.primitives

import com.mdeo.script.stdlib.impl.collections.ListImpl
import com.mdeo.script.stdlib.impl.collections.ScriptList
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.abs
import kotlin.math.log10 as mathLog10

/**
 * Helper methods for the Int type in the script language.
 * These static methods are called with the instance as the first parameter.
 */
object IntHelper {

    /**
     * Returns the absolute value of an integer.
     *
     * @param value the integer value
     * @return the absolute value
     */
    @JvmStatic
    fun abs(value: Int): Int = kotlin.math.abs(value)

    /**
     * Returns the ceil of an integer (the integer itself).
     *
     * @param value the integer value
     * @return the integer value (ceil of an integer is itself)
     */
    @JvmStatic
    fun ceil(value: Int): Int = value

    /**
     * Returns the floor of an integer (the integer itself).
     *
     * @param value the integer value
     * @return the integer value (floor of an integer is itself)
     */
    @JvmStatic
    fun floor(value: Int): Int = value

    /**
     * Returns the natural logarithm of an integer.
     *
     * @param value the integer value
     * @return the natural logarithm
     */
    @JvmStatic
    fun log(value: Int): Float = ln(value.toDouble()).toFloat()

    /**
     * Returns the base-10 logarithm of an integer.
     *
     * @param value the integer value
     * @return the base-10 logarithm
     */
    @JvmStatic
    fun log10(value: Int): Float = kotlin.math.log10(value.toDouble()).toFloat()

    /**
     * Returns the maximum of two integers.
     *
     * @param value the first integer
     * @param other the second integer
     * @return the larger value
     */
    @JvmStatic
    fun max(value: Int, other: Int): Int = maxOf(value, other)

    /**
     * Returns the maximum of an integer and a long.
     *
     * @param value the integer
     * @param other the long
     * @return the larger value as a long
     */
    @JvmStatic
    fun max(value: Int, other: Long): Long = maxOf(value.toLong(), other)

    /**
     * Returns the maximum of an integer and a float.
     *
     * @param value the integer
     * @param other the float
     * @return the larger value as a float
     */
    @JvmStatic
    fun max(value: Int, other: Float): Float = maxOf(value.toFloat(), other)

    /**
     * Returns the maximum of an integer and a double.
     *
     * @param value the integer
     * @param other the double
     * @return the larger value as a double
     */
    @JvmStatic
    fun max(value: Int, other: Double): Double = maxOf(value.toDouble(), other)

    /**
     * Returns the minimum of two integers.
     *
     * @param value the first integer
     * @param other the second integer
     * @return the smaller value
     */
    @JvmStatic
    fun min(value: Int, other: Int): Int = minOf(value, other)

    /**
     * Returns the minimum of an integer and a long.
     *
     * @param value the integer
     * @param other the long
     * @return the smaller value as a long
     */
    @JvmStatic
    fun min(value: Int, other: Long): Long = minOf(value.toLong(), other)

    /**
     * Returns the minimum of an integer and a float.
     *
     * @param value the integer
     * @param other the float
     * @return the smaller value as a float
     */
    @JvmStatic
    fun min(value: Int, other: Float): Float = minOf(value.toFloat(), other)

    /**
     * Returns the minimum of an integer and a double.
     *
     * @param value the integer
     * @param other the double
     * @return the smaller value as a double
     */
    @JvmStatic
    fun min(value: Int, other: Double): Double = minOf(value.toDouble(), other)

    /**
     * Raises an integer to the given power.
     *
     * @param value the base
     * @param exponent the exponent
     * @return the result as a double
     */
    @JvmStatic
    fun pow(value: Int, exponent: Double): Double = value.toDouble().pow(exponent)

    /**
     * Returns the rounded value of an integer (the integer itself).
     *
     * @param value the integer value
     * @return the integer value (rounding an integer returns itself)
     */
    @JvmStatic
    fun round(value: Int): Int = value

    /**
     * Creates a list of integers from start to end with the given step.
     *
     * @param start the starting value (inclusive)
     * @param end the ending value (exclusive)
     * @param step the step increment (must not be zero)
     * @return a list of integers
     * @throws IllegalArgumentException if step is zero
     */
    @JvmStatic
    fun iota(start: Int, end: Int, step: Int): ScriptList<Int> {
        if (step == 0) {
            throw IllegalArgumentException("Step cannot be zero")
        }
        val list = ListImpl<Int>()
        if (step > 0) {
            var i = start
            while (i < end) {
                list.add(i)
                i += step
            }
        } else {
            var i = start
            while (i > end) {
                list.add(i)
                i += step
            }
        }
        return list
    }

    /**
     * Returns the remainder of integer division (modulo).
     *
     * @param value the dividend
     * @param divisor the divisor
     * @return the remainder
     */
    @JvmStatic
    fun mod(value: Int, divisor: Int): Int = value % divisor

    /**
     * Creates a list of integers from this value to another value (inclusive).
     *
     * @param start the starting value (inclusive)
     * @param end the ending value (inclusive)
     * @return a list of integers
     */
    @JvmStatic
    fun to(start: Int, end: Int): ScriptList<Int> {
        val list = ListImpl<Int>()
        if (start <= end) {
            for (i in start..end) {
                list.add(i)
            }
        } else {
            for (i in start downTo end) {
                list.add(i)
            }
        }
        return list
    }

    /**
     * Converts an integer to its binary string representation.
     *
     * @param value the integer value
     * @return the binary string
     */
    @JvmStatic
    fun toBinary(value: Int): String = Integer.toBinaryString(value)

    /**
     * Converts an integer to its hexadecimal string representation.
     *
     * @param value the integer value
     * @return the hexadecimal string
     */
    @JvmStatic
    fun toHex(value: Int): String = Integer.toHexString(value)

    /**
     * Converts an integer to a string.
     *
     * @param value the integer value
     * @return the string representation
     */
    @JvmStatic
    fun toString(value: Int): String = value.toString()
}
