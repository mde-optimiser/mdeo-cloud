package com.mdeo.script.ast

import com.mdeo.expression.ast.TypedCallableBody
import kotlinx.serialization.Serializable

/**
 * Record declaration.
 *
 * @param name Name of the record, which is also the name of its constructor.
 * @param package The type package of the record, as types in the types array refer to it.
 * @param type Index into the types array for the non-nullable record type.
 * @param fields The fields of the record in declaration order, with their default values.
 */
@Serializable
data class TypedRecord(
    val name: String,
    val `package`: String,
    val type: Int,
    val fields: List<TypedParameter>
) {
    /**
     * The type the record's class stands for.
     */
    val key: TypeKey get() = TypeKey(`package`, name)

    /**
     * The record's constructor as a function: it takes the fields and returns a new record. Its
     * body is generated rather than written, so it has none here.
     */
    fun toConstructor(): TypedFunction = TypedFunction(name, fields, type, TypedCallableBody(emptyList()))
}
