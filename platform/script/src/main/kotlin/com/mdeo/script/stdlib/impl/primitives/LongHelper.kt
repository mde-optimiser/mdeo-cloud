package com.mdeo.script.stdlib.impl.primitives

import com.mdeo.script.stdlib.impl.collections.ListImpl
import com.mdeo.script.stdlib.impl.collections.ScriptList
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.abs

/**
 * Helper methods for the Long type in the script language.
 * These static methods are called with the instance as the first parameter.
 */
object LongHelper {

    /**
     * Returns the absolute value of a long.
     *
     * @param value the long value
     * @return the absolute value
     */
    @JvmStatic
    fun abs(value: Long): Long = kotlin.math.abs(value)

    /**
     * Returns the ceiling of a long (the long itself).
     *
     * @param value the long value
     * @return the long value (ceiling of a long is itself)
     */
    @JvmStatic
    fun ceiling(value: Long): Long = value

    /**
     * Returns the floor of a long (the long itself).
     *
     * @param value the long value
     * @return the long value (floor of a long is itself)
     */
    @JvmStatic
    fun floor(value: Long): Long = value

    /**
     * Returns the natural logarithm of a long.
     *
     * @param value the long value
     * @return the natural logarithm
     */
    @JvmStatic
    fun log(value: Long): Double = ln(value.toDouble())

    /**
     * Returns the base-10 logarithm of a long.
     *
     * @param value the long value
     * @return the base-10 logarithm
     */
    @JvmStatic
    fun log10(value: Long): Double = kotlin.math.log10(value.toDouble())

    /**
     * Returns the maximum of a long and an int.
     *
     * @param value the long
     * @param other the int
     * @return the larger value as a long
     */
    @JvmStatic
    fun max(value: Long, other: Int): Long = maxOf(value, other.toLong())

    /**
     * Returns the maximum of two longs.
     *
     * @param value the first long
     * @param other the second long
     * @return the larger value
     */
    @JvmStatic
    fun max(value: Long, other: Long): Long = maxOf(value, other)

    /**
     * Returns the maximum of a long and a float.
     *
     * @param value the long
     * @param other the float
     * @return the larger value as a float
     */
    @JvmStatic
    fun max(value: Long, other: Float): Float = maxOf(value.toFloat(), other)

    /**
     * Returns the maximum of a long and a double.
     *
     * @param value the long
     * @param other the double
     * @return the larger value as a double
     */
    @JvmStatic
    fun max(value: Long, other: Double): Double = maxOf(value.toDouble(), other)

    /**
     * Returns the minimum of a long and an int.
     *
     * @param value the long
     * @param other the int
     * @return the smaller value as a long
     */
    @JvmStatic
    fun min(value: Long, other: Int): Long = minOf(value, other.toLong())

    /**
     * Returns the minimum of two longs.
     *
     * @param value the first long
     * @param other the second long
     * @return the smaller value
     */
    @JvmStatic
    fun min(value: Long, other: Long): Long = minOf(value, other)

    /**
     * Returns the minimum of a long and a float.
     *
     * @param value the long
     * @param other the float
     * @return the smaller value as a float
     */
    @JvmStatic
    fun min(value: Long, other: Float): Float = minOf(value.toFloat(), other)

    /**
     * Returns the minimum of a long and a double.
     *
     * @param value the long
     * @param other the double
     * @return the smaller value as a double
     */
    @JvmStatic
    fun min(value: Long, other: Double): Double = minOf(value.toDouble(), other)

    /**
     * Raises a long to the given power.
     *
     * @param value the base
     * @param exponent the exponent
     * @return the result as a double
     */
    @JvmStatic
    fun pow(value: Long, exponent: Double): Double = value.toDouble().pow(exponent)

    /**
     * Returns the rounded value of a long (the long itself).
     *
     * @param value the long value
     * @return the long value (rounding a long returns itself)
     */
    @JvmStatic
    fun round(value: Long): Long = value

    /**
     * Creates a list of longs from start to end with the given step.
     *
     * @param start the starting value (inclusive)
     * @param end the ending value (exclusive)
     * @param step the step increment (must not be zero)
     * @return a list of integers (for compatibility with the type system)
     * @throws IllegalArgumentException if step is zero
     */
    @JvmStatic
    fun iota(start: Long, end: Long, step: Long): ScriptList<Int> {
        if (step == 0L) {
            throw IllegalArgumentException("Step cannot be zero")
        }
        val list = ListImpl<Int>()
        if (step > 0) {
            var i = start
            while (i < end) {
                list.add(i.toInt())
                i += step
            }
        } else {
            var i = start
            while (i > end) {
                list.add(i.toInt())
                i += step
            }
        }
        return list
    }

    /**
     * Returns the remainder of long division (modulo).
     *
     * @param value the dividend
     * @param divisor the divisor
     * @return the remainder
     */
    @JvmStatic
    fun mod(value: Long, divisor: Long): Long = value % divisor

    /**
     * Creates a list of longs from this value to another value (inclusive).
     *
     * @param start the starting value (inclusive)
     * @param end the ending value (inclusive)
     * @return a list of integers (for compatibility with the type system)
     */
    @JvmStatic
    fun to(start: Long, end: Long): ScriptList<Int> {
        val list = ListImpl<Int>()
        if (start <= end) {
            for (i in start..end) {
                list.add(i.toInt())
            }
        } else {
            for (i in start downTo end) {
                list.add(i.toInt())
            }
        }
        return list
    }

    /**
     * Converts a long to its binary string representation.
     *
     * @param value the long value
     * @return the binary string
     */
    @JvmStatic
    fun toBinary(value: Long): String = java.lang.Long.toBinaryString(value)

    /**
     * Converts a long to its hexadecimal string representation.
     *
     * @param value the long value
     * @return the hexadecimal string
     */
    @JvmStatic
    fun toHex(value: Long): String = java.lang.Long.toHexString(value)

    /**
     * Converts a long to a string.
     *
     * @param value the long value
     * @return the string representation
     */
    @JvmStatic
    fun toString(value: Long): String = value.toString()
}
