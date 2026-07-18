package com.mdeo.modeltransformation.ast.patterns

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Polymorphic serializer for TypedPatternElement.
 *
 * This serializer handles the different types of pattern elements that can appear
 * within a transformation pattern. It uses the "kind" field to determine which
 * concrete element type to deserialize to.
 *
 * Pattern elements include:
 * - variable: Variable declarations within patterns
 * - variableReassignment: Reassignment of a variable declared in an enclosing scope
 * - objectInstance: Object instance definitions (including delete with modifier)
 * - link: Link definitions between objects
 * - whereClause: Constraint expressions
 */
object TypedPatternElementSerializer : JsonContentPolymorphicSerializer<TypedPatternElement>(
    TypedPatternElement::class
) {
    
    /**
     * Selects the appropriate deserializer based on the "kind" field in the JSON element.
     *
     * @param element The JSON element to deserialize.
     * @return The deserializer for the concrete pattern element type.
     * @throws IllegalArgumentException If the "kind" field is missing or unknown.
     */
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<TypedPatternElement> {
        val kindValue = element.jsonObject["kind"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("Missing 'kind' field in pattern element: $element")
        
        return when (kindValue) {
            "variable" -> TypedPatternVariableElement.serializer()
            "variableReassignment" -> TypedPatternVariableReassignmentElement.serializer()
            "objectInstance" -> TypedPatternObjectInstanceElement.serializer()
            "link" -> TypedPatternLinkElement.serializer()
            "whereClause" -> TypedPatternWhereClauseElement.serializer()
            else -> throw IllegalArgumentException("Unknown pattern element kind: $kindValue")
        }
    }
}
