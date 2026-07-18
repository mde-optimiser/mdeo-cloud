package com.mdeo.modeltransformation.ast.patterns

import kotlinx.serialization.Serializable

/**
 * Base interface for pattern elements in a transformation pattern.
 *
 * Pattern elements are the individual components that make up a pattern,
 * including variables, object instances, links, and where clauses.
 */
interface TypedPatternElement {
    /**
     * The kind of pattern element, used for polymorphic deserialization.
     */
    val kind: String
}

/**
 * Pattern element containing a variable declaration.
 *
 * @param kind Always "variable" for this element type.
 * @param variable The variable declaration.
 */
@Serializable
data class TypedPatternVariableElement(
    override val kind: String = "variable",
    val variable: TypedPatternVariable
) : TypedPatternElement

/**
 * Pattern element containing a variable reassignment.
 *
 * @param kind Always "variableReassignment" for this element type.
 * @param reassignment The variable reassignment.
 */
@Serializable
data class TypedPatternVariableReassignmentElement(
    override val kind: String = "variableReassignment",
    val reassignment: TypedPatternVariableReassignment
) : TypedPatternElement

/**
 * Pattern element containing an object instance definition.
 *
 * @param kind Always "objectInstance" for this element type.
 * @param objectInstance The object instance definition.
 */
@Serializable
data class TypedPatternObjectInstanceElement(
    override val kind: String = "objectInstance",
    val objectInstance: TypedPatternObjectInstance
) : TypedPatternElement

/**
 * Pattern element containing a link definition.
 *
 * @param kind Always "link" for this element type.
 * @param link The link definition.
 */
@Serializable
data class TypedPatternLinkElement(
    override val kind: String = "link",
    val link: TypedPatternLink
) : TypedPatternElement

/**
 * Pattern element containing a where clause.
 *
 * @param kind Always "whereClause" for this element type.
 * @param whereClause The where clause.
 */
@Serializable
data class TypedPatternWhereClauseElement(
    override val kind: String = "whereClause",
    val whereClause: TypedWhereClause
) : TypedPatternElement
