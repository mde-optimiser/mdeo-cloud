package com.mdeo.scriptfunctions.service

import com.mdeo.expression.ast.types.ClassTypeRef
import com.mdeo.expression.ast.types.ValueType
import com.mdeo.expression.ast.types.ValueTypeSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The `types` array of a contribution's payload, which the default values refer to by index.
 */
internal class PayloadTypes {
    private val types = mutableListOf<ValueType>()

    /**
     * The index of a type, added when it is not there yet.
     */
    fun indexOf(type: ValueType): Int = types.indexOf(type).takeIf { it >= 0 } ?: types.size.also { types += type }

    /**
     * The array, as the payload carries it.
     */
    fun toJson(): JsonArray = JsonArray(types.map { json.encodeToJsonElement(ValueTypeSerializer, it) })

    private companion object {
        val json = Json { explicitNulls = false }
    }
}

/**
 * Checks that a default value is a constant of the type it is the default of: an `Int` for `int`,
 * a `Long` for `long`, a `Float` for `float`, a `Double` for `double`, a `Boolean` for `boolean`, a
 * `String` for `string`, or `null` for a nullable type.
 *
 * @param value The default value
 * @param type The type of the parameter or field
 * @param where Names the parameter or field in the error
 * @throws IllegalArgumentException when the value does not fit the type
 */
internal fun requireDefaultFits(value: Any?, type: ValueType, where: String) {
    val fits = when {
        value == null -> type is ClassTypeRef && type.isNullable
        type !is ClassTypeRef || type.`package` != "builtin" -> false
        else -> when (type.type) {
            "int" -> value is Int
            "long" -> value is Long
            "float" -> value is Float
            "double" -> value is Double
            "boolean" -> value is Boolean
            "string" -> value is String
            else -> false
        }
    }
    require(fits) {
        "$where has a default value '$value' that is not a constant of its type. A default is an Int, Long, " +
                "Float, Double, Boolean or String matching the type, or null for a nullable type"
    }
}

/**
 * The typed literal expression a script evaluates for a default value checked by [requireDefaultFits].
 *
 * @param value The default value
 * @param type The type of the parameter or field
 * @param types The payload's types, which the literal's type index refers to
 * @return The literal, as the payload carries it
 */
internal fun defaultValueJson(value: Any?, type: ValueType, types: PayloadTypes): JsonObject = buildJsonObject {
    put("evalType", types.indexOf(type))
    when (value) {
        null -> put("kind", "nullLiteral")
        is Int -> { put("kind", "intLiteral"); put("value", value.toString()) }
        is Long -> { put("kind", "longLiteral"); put("value", value.toString()) }
        is Float -> { put("kind", "floatLiteral"); put("value", value.toString()) }
        is Double -> { put("kind", "doubleLiteral"); put("value", value.toString()) }
        is Boolean -> { put("kind", "booleanLiteral"); put("value", value) }
        is String -> { put("kind", "stringLiteral"); put("value", value) }
        else -> error("Unchecked default value '$value'")
    }
}
