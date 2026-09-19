package com.mdeo.script.runtime

/**
 * Base class of every record, whether a script declares it or a contribution defines it.
 *
 * The compiler generates one final subclass per record, so a script's type checks tell records
 * apart, and reads and writes fields through [field] and [set] in declaration order. Fields are
 * mutable. Two records are equal when they are of the same record and their fields are equal, so a
 * record that is changed while it is an element of a set or a key of a map is not found there again.
 *
 * The generated class is what identifies the record; [recordName] is only what it is shown as, and
 * two records of different packages may share it.
 *
 * @param recordName The record's name, as scripts see it
 * @param fieldNames The field names, in declaration order
 * @param values The field values, in declaration order, boxed
 */
abstract class ScriptRecord(
    val recordName: String,
    private val fieldNames: Array<String>,
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

    /**
     * A copy of the field values, in declaration order, to construct a copy of the record from.
     */
    fun copyValues(): Array<Any?> = values.copyOf()

    /**
     * Returns a new record of the same record type with the same field values. Field values are
     * not copied themselves: a collection field of the copy is the collection of the original.
     */
    abstract fun copy(): ScriptRecord

    override fun equals(other: Any?): Boolean =
        other is ScriptRecord && other.javaClass == javaClass && values.contentEquals(other.values)

    override fun hashCode(): Int = 31 * javaClass.name.hashCode() + values.contentHashCode()

    override fun toString(): String =
        "$recordName(${fieldNames.indices.joinToString(", ") { "${fieldNames[it]}=${values[it]}" }})"

    companion object {
        /**
         * Sets one field of a record.
         *
         * Takes the record and the value in the order a script's assignment leaves them on the stack.
         *
         * @param record The record
         * @param value The new value, boxed
         * @param index The field's position in the record's declaration
         */
        @JvmStatic
        fun set(record: Any?, value: Any?, index: Int) {
            (record as ScriptRecord).values[index] = value
        }
    }
}

/**
 * Base class of every opaque class a contribution defines: a handle to state that stays on the
 * contribution's service.
 *
 * The compiler generates one final subclass per opaque class, which is what identifies it. Handles
 * are equal only to themselves.
 *
 * @param className The class's name, as scripts see it
 * @param handle The id the service knows the state under
 */
abstract class ScriptOpaque(
    val className: String,
    val handle: Long
) {
    override fun toString(): String = "$className#$handle"
}
