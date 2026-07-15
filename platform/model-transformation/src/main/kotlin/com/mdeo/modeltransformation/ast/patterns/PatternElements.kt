package com.mdeo.modeltransformation.ast.patterns

import com.mdeo.expression.ast.expressions.TypedExpression
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Pattern variable declaration within a transformation pattern.
 *
 * Pattern variables are used to bind values during pattern matching that can
 * be referenced in subsequent expressions or statements. The type annotation
 * is optional - if not provided, the type is inferred from the value expression.
 *
 * @param name Name of the variable as declared in the pattern.
 * @param type Optional index into the types array for the variable's type.
 *             If null, the type is inferred from the value expression.
 * @param value The value expression assigned to the variable.
 */
@Serializable
data class TypedPatternVariable(
    val name: String,
    val type: Int? = null,
    @Contextual val value: TypedExpression
)

/**
 * Property assignment within a pattern object instance.
 *
 * Represents either an assignment (setting a property value) or a comparison
 * (matching against a property value) within a pattern.
 *
 * @param propertyName Name of the property being assigned or compared.
 * @param operator The operator used:
 *   - `"="` — assignment (sets the property value).
 *   - `"=="` — equality comparison constraint.
 *   - `"!="` — inequality comparison constraint.
 *   - `"<"`, `">"`, `"<="`, `">="` — relational comparison constraints
 *     (only valid for comparable / numeric property types).
 * @param value The value expression for the assignment or comparison.
 */
@Serializable
data class TypedPatternPropertyAssignment(
    val propertyName: String,
    val operator: String,
    @Contextual val value: TypedExpression
)

/**
 * Pattern object instance definition.
 *
 * Represents an object instance in a transformation pattern. Object instances
 * can be matched against existing objects in the model, or can specify creation
 * or deletion of objects through modifiers.
 *
 * @param modifier Optional modifier for the object instance: "create" to create a new object,
 *                 "delete" to delete a matched object, "forbid" to specify objects that must not exist,
 *                 or null for simple matching.
 * @param name Name of the object instance, used for referencing in links and expressions.
 * @param className Fully qualified class name of the object's type. When null, refers to a previously
 *                  matched node with the same name, allowing property assignments and comparisons on
 *                  already matched nodes.
 * @param properties Property assignments for this object instance.
 */
@Serializable
data class TypedPatternObjectInstance(
    val modifier: String? = null,
    val name: String,
    val className: String? = null,
    val properties: List<TypedPatternPropertyAssignment>
)

/**
 * Link end in a pattern link definition.
 *
 * Represents one end of a link between two object instances in a pattern.
 *
 * @param objectName Name of the object instance this end connects to.
 * @param propertyName Optional property name specifying which property the link represents.
 */
@Serializable
data class TypedPatternLinkEnd(
    val objectName: String,
    val propertyName: String? = null
)

/**
 * Pattern link definition.
 *
 * Represents a link (reference/association) between two object instances in a pattern.
 * Links can be matched, created, or deleted similar to object instances.
 *
 * The edge label can be computed from the source and target property names using
 * [com.mdeo.modeltransformation.ast.EdgeLabelUtils.computeEdgeLabel].
 *
 * @param modifier Optional modifier for the link: "create" to create a new link,
 *                 "delete" to delete a matched link, "forbid" to specify links that must not exist,
 *                 or null for simple matching.
 * @param source Source end of the link.
 * @param target Target end of the link.
 */
@Serializable
data class TypedPatternLink(
    val modifier: String? = null,
    val source: TypedPatternLinkEnd,
    val target: TypedPatternLinkEnd
)

/**
 * Where clause in a pattern.
 *
 * Where clauses allow specifying additional constraints on pattern matches
 * using boolean expressions.
 *
 * @param expression The boolean condition expression that must be satisfied.
 */
@Serializable
data class TypedWhereClause(
    @Contextual val expression: TypedExpression
)
