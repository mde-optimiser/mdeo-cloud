package com.mdeo.scriptfunctions.service

import com.mdeo.expression.ast.types.ClassTypeRef
import com.mdeo.expression.ast.types.ValueType

/**
 * The package a contribution's classes are referred to by, followed by `/<contribution id>`.
 */
const val CONTRIBUTED_CLASS_PACKAGE = "contrib"

/**
 * A record a contribution defines: a deeply immutable value with named fields that scripts read
 * as readonly properties and compare by content.
 *
 * Declare one with [ScriptContributionBuilder.record], use [type] in signatures, and create values
 * with [of].
 *
 * @property name The record's name
 * @property type The type to use in signatures and in other records' fields
 * @property fields The fields, in declaration order
 */
class RecordType internal constructor(
    val name: String,
    val type: ClassTypeRef,
    val fields: List<Pair<String, ValueType>>
) {
    private val fieldNames = fields.map { it.first }.toSet()

    /**
     * Creates a value of this record.
     *
     * ```kotlin
     * point.of("x" to 1.0, "label" to "home")
     * ```
     *
     * @param values Every field, by name
     * @return The record value
     * @throws IllegalArgumentException when a field is missing or unknown
     */
    fun of(vararg values: Pair<String, Any?>): RecordValue {
        val given = values.toMap()
        require(given.keys == fieldNames) {
            "Record '$name' has the fields $fieldNames, but was given ${given.keys}"
        }
        return RecordValue(name, given)
    }
}

/**
 * One value of a record, as operations receive and return it.
 *
 * Equal when of the same record with equal fields, like the records scripts see.
 *
 * @property recordName The record's name
 * @property fields Every field, by name
 */
data class RecordValue(val recordName: String, val fields: Map<String, Any?>) {
    /**
     * Returns one field.
     *
     * @param name The field name
     * @return Its value
     */
    operator fun get(name: String): Any? = fields[name]
}

/**
 * An opaque class a contribution defines: a handle scripts hold to state that stays on the service,
 * such as an index built once and queried by later calls.
 *
 * Declare one with [ScriptContributionBuilder.opaque], use [type] in signatures, and return
 * [wrap] of the state. When the handle is passed back, the operation receives the state itself.
 *
 * The state lives as long as the script holds the handle, and never longer than the model the
 * script ran on when it was created: a new model drops every handle, like everything else derived
 * from the old one.
 *
 * @property name The class's name
 * @property type The type to use in signatures
 */
class OpaqueType internal constructor(
    val name: String,
    val type: ClassTypeRef
) {
    /**
     * Makes state returnable as a handle of this class.
     *
     * @param state The state the handle stands for
     * @return The value to return from an operation
     */
    fun wrap(state: Any): OpaqueValue = OpaqueValue(name, state)
}

/**
 * State returned from an operation as a handle, see [OpaqueType.wrap].
 *
 * @property className The opaque class's name
 * @property state The state
 */
class OpaqueValue internal constructor(val className: String, val state: Any)
