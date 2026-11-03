package com.mdeo.modeltransformation.ast.statements

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Polymorphic serializer for TypedTransformationStatement.
 *
 * This is a fixed serializer for model transformation statements. Unlike the expression
 * serializer which extends a base class for contextual extensibility, this serializer
 * handles all transformation statement types directly. This design decision is based on
 * the expectation that the set of transformation statements is relatively stable and
 * unlikely to be extended significantly in the future.
 *
 * The serializer uses the "kind" field in the JSON to determine which concrete
 * statement type to deserialize to.
 */
object TypedTransformationStatementSerializer : JsonContentPolymorphicSerializer<TypedTransformationStatement>(
    TypedTransformationStatement::class
) {
    
    /**
     * Selects the appropriate deserializer based on the "kind" field in the JSON element.
     *
     * @param element The JSON element to deserialize.
     * @return The deserializer for the concrete statement type.
     * @throws IllegalArgumentException If the "kind" field is missing or unknown.
     */
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<TypedTransformationStatement> {
        val kindValue = element.jsonObject["kind"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("Missing 'kind' field in transformation statement: $element")
        
        return when (kindValue) {
            "match" -> TypedMatchStatement.serializer()
            "ifMatch" -> TypedIfMatchStatement.serializer()
            "whileMatch" -> TypedWhileMatchStatement.serializer()
            "untilMatch" -> TypedUntilMatchStatement.serializer()
            "forMatch" -> TypedForMatchStatement.serializer()
            "ifExpression" -> TypedIfExpressionStatement.serializer()
            "whileExpression" -> TypedWhileExpressionStatement.serializer()
            "stop" -> TypedStopStatement.serializer()
            "call" -> TypedCallStatement.serializer()
            "ifCall" -> TypedIfCallStatement.serializer()
            "forIn" -> TypedForInStatement.serializer()
            "assignment" -> TypedAssignmentStatement.serializer()
            
            else -> throw IllegalArgumentException("Unknown transformation statement kind: $kindValue")
        }
    }
}
