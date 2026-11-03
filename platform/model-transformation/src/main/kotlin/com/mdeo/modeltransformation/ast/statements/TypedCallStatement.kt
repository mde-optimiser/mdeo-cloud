package com.mdeo.modeltransformation.ast.statements

import com.mdeo.expression.ast.expressions.TypedExpression
import kotlinx.serialization.Contextual
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable

/**
 * Call of another transformation: `call name(a = expr, ...) -> (b: x, ...)`.
 *
 * The callee runs as an engine of its own on the same model graph, with its inputs bound. On
 * success (also when the callee ends with `stop`) its outputs are bound in the caller's scope,
 * as a match binds its objects. A callee failure fails the call, and `kill` in the callee ends
 * the whole transformation.
 *
 * A model argument (for a `model` or `List<model>` input of the callee) is an identifier of a
 * model value of the caller: it passes the caller's model by reference, so the callee matches in
 * the very model the caller was given, which is never loaded twice.
 *
 * @param callee Absolute path of the callee's file, its key in the root's callees.
 * @param name The alias the caller imports the callee by (`import "./f.mt" as name`), for messages.
 * @param arguments The arguments passed.
 * @param outputs The outputs bound.
 */
@Serializable
data class TypedCallStatement(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    override val kind: String = "call",
    val callee: String,
    val name: String,
    val arguments: List<TypedTransformationCallArgument> = emptyList(),
    val outputs: List<TypedTransformationCallOutput> = emptyList()
) : TypedTransformationStatement

/**
 * A named argument of a call.
 *
 * @param name The callee parameter the argument is passed to.
 * @param value The value, evaluated in the caller.
 */
@Serializable
data class TypedTransformationCallArgument(
    val name: String,
    @Contextual val value: TypedExpression
)

/**
 * The binding of a callee output to a caller name.
 *
 * @param parameter The callee's `out` parameter.
 * @param name The caller's name.
 */
@Serializable
data class TypedTransformationCallOutput(
    val parameter: String,
    val name: String
)

/**
 * Conditional call: `if call name(...) -> (...) then { ... } else { ... }`.
 *
 * The call runs one scope level below the statement; on success its outputs are bound there and
 * the then block runs; on failure the else block runs. `kill` in the callee ends everything.
 *
 * @param call The call.
 * @param thenBlock Statements run after a successful call.
 * @param elseBlock Statements run after a failed call.
 */
@Serializable
data class TypedIfCallStatement(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    override val kind: String = "ifCall",
    val call: TypedCallStatement,
    val thenBlock: List<@Contextual TypedTransformationStatement> = emptyList(),
    val elseBlock: List<@Contextual TypedTransformationStatement>? = null
) : TypedTransformationStatement

/**
 * Loop over a collection: `for name in collection do { ... }`.
 *
 * The collection is evaluated once; the do block runs once per element with [variable] bound one
 * scope level below the statement: a model element as a bound object, a scalar as a value, and a
 * model of a `List<model>` input as a model value.
 *
 * @param variable The loop variable.
 * @param collection The collection.
 * @param doBlock Statements run per element.
 */
@Serializable
data class TypedForInStatement(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    override val kind: String = "forIn",
    val variable: String,
    @Contextual val collection: TypedExpression,
    val doBlock: List<@Contextual TypedTransformationStatement> = emptyList()
) : TypedTransformationStatement

/**
 * Assignment outside a pattern: `name = value` or `name += value`.
 *
 * @param name The assigned variable or output.
 * @param operator `=` (assign) or `+=` (add to a collection output).
 * @param value The value.
 */
@Serializable
data class TypedAssignmentStatement(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    override val kind: String = "assignment",
    val name: String,
    val operator: String = "=",
    @Contextual val value: TypedExpression
) : TypedTransformationStatement
