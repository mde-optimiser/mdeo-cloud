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
 * Reassignment of an already-declared pattern variable within a transformation pattern.
 *
 * Unlike [TypedPatternVariable], a reassignment does not declare a new variable; it
 * updates the value of a variable declared in an enclosing scope (e.g. a variable
 * declared before a `while` loop). The reassignment is evaluated as part of the match —
 * "before any access in that match block" — using the incoming (old) value of the
 * variable for its right-hand side (so `counter = counter + 1` reads the current value
 * and stores the incremented one). After the match, the new value is written back to the
 * scope in which the variable was originally declared, so subsequent statements and loop
 * iterations observe it.
 *
 * @param name Name of the variable being reassigned. Must refer to a variable declared in
 *             an enclosing scope.
 * @param value The value expression computed and assigned to the variable.
 */
@Serializable
data class TypedPatternVariableReassignment(
    val name: String,
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
 *                 "delete" to delete a matched object, or null for simple matching.
 *                 Negative and positive application conditions are not modifiers; they are
 *                 expressed as [TypedPatternApplicationCondition] blocks.
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
 *                 "delete" to delete a matched link, or null for simple matching.
 *                 Negative and positive application conditions are not modifiers; they are
 *                 expressed as [TypedPatternApplicationCondition] blocks.
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

/**
 * A negative (`forbid`) or positive (`require`) application condition.
 *
 * A condition carries a graph of its own: its elements are matched together, and
 * independently of the enclosing pattern and of every other condition. A negative
 * condition rejects the match as soon as its whole graph can be found; a positive one
 * demands that its whole graph is found. Two negative conditions therefore reject the
 * match if *either* of them matches, whereas the elements of a single condition only
 * reject it when they *all* match together.
 *
 * The elements are restricted to object instances, links and where clauses. An instance
 * with a `className` declares a node that belongs to the condition graph alone; an
 * instance without one references a node of the enclosing pattern (an *anchor*) and only
 * contributes the property constraints listed on it. A where clause constrains the
 * condition graph as a whole and may read both the condition's own nodes and the nodes
 * and variables bound by the enclosing match.
 *
 * @param negative `true` for a negative application condition (`forbid`), `false` for a
 *                 positive one (`require`).
 * @param name Optional name identifying the condition graph in diagnostics and in the
 *             graphical syntax.
 * @param elements The elements forming the condition graph.
 */
@Serializable
data class TypedPatternApplicationCondition(
    val negative: Boolean,
    val name: String? = null,
    val elements: List<@Contextual TypedPatternElement>
)
