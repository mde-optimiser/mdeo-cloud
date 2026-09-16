package com.mdeo.script.runtime

/**
 * Base class of every record a contribution defines.
 *
 * The compiler generates one final subclass per record, so a script's type checks tell records
 * apart, and reads fields through [field] in declaration order. Records are deeply immutable and
 * equal when they are of the same record and their fields are equal.
 *
 * @param recordType Identifies the record as `contrib/<contribution>.<name>`
 * @param values The field values, in declaration order
 */
abstract class ScriptRecord(
    val recordType: String,
    private val values: Array<Any?>
) {
    /**
     * Returns one field.
     *
     * @param index The field's position in the record's declaration
     * @return Its value
     */
    fun field(index: Int): Any? = values[index]

    /**
     * The field values, in declaration order.
     */
    fun fields(): List<Any?> = values.toList()

    override fun equals(other: Any?): Boolean =
        other is ScriptRecord && other.javaClass == javaClass && values.contentEquals(other.values)

    override fun hashCode(): Int = 31 * recordType.hashCode() + values.contentHashCode()

    override fun toString(): String = "${recordType.substringAfterLast('.')}(${values.joinToString(", ")})"
}

/**
 * Base class of every opaque class a contribution defines: a handle to state that stays on the
 * contribution's service.
 *
 * The compiler generates one final subclass per opaque class. Handles are equal only to themselves.
 *
 * @param opaqueType Identifies the class as `contrib/<contribution>.<name>`
 * @param handle The id the service knows the state under
 */
abstract class ScriptOpaque(
    val opaqueType: String,
    val handle: Long
) {
    override fun toString(): String = "${opaqueType.substringAfterLast('.')}#$handle"
}
