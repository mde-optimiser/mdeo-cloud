package com.mdeo.scriptfunctions.protocol

/**
 * The mapping between plain values and the scalar [WireValue]s: `null`, [Boolean], [Int], [Long],
 * [Float], [Double] and [String].
 *
 * Both sides of the protocol read and write scalars the same way, so they share this one mapping;
 * each handles the other kinds of value — collections, instances, records, handles, enum entries —
 * itself.
 */
object WireScalars {

    /**
     * Encodes a scalar.
     *
     * @param value The value
     * @return Its wire form, or null when [value] is not a scalar
     */
    fun encode(value: Any?): WireValue? = when (value) {
        null -> WireValue.Null
        is Boolean -> WireValue.Bool(value)
        is Int -> WireValue.IntValue(value)
        is Long -> WireValue.LongValue(value)
        is Float -> WireValue.FloatValue(value)
        is Double -> WireValue.DoubleValue(value)
        is String -> WireValue.StringValue(value)
        else -> null
    }

    /**
     * Whether a wire value is a scalar, which [decode] accepts.
     *
     * @param value The wire value
     */
    fun isScalar(value: WireValue): Boolean = when (value) {
        WireValue.Null, is WireValue.Bool, is WireValue.IntValue, is WireValue.LongValue,
        is WireValue.FloatValue, is WireValue.DoubleValue, is WireValue.StringValue -> true
        else -> false
    }

    /**
     * Decodes a scalar.
     *
     * @param value The wire value
     * @return The plain value
     * @throws IllegalArgumentException when [value] is not a scalar
     */
    fun decode(value: WireValue): Any? = when (value) {
        WireValue.Null -> null
        is WireValue.Bool -> value.value
        is WireValue.IntValue -> value.value
        is WireValue.LongValue -> value.value
        is WireValue.FloatValue -> value.value
        is WireValue.DoubleValue -> value.value
        is WireValue.StringValue -> value.value
        else -> throw IllegalArgumentException("${value::class.simpleName} is not a scalar")
    }
}
