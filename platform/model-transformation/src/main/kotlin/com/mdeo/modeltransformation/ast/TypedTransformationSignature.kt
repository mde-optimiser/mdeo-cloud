package com.mdeo.modeltransformation.ast

import com.mdeo.expression.ast.expressions.TypedExpression
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * The header declarations of a transformation file (`in a: T = d`, `out b: U`): every `.mt` file
 * is callable, through a path import (`import "./f.mt" as f`) that names it in the caller.
 *
 * @param name The display name of the transformation, the base name of its file including the
 *   extension (e.g. `move-component.mt`), used in diagnostics. Callers name a callee by the alias
 *   of their import instead ([com.mdeo.modeltransformation.ast.statements.TypedCallStatement.name]).
 * @param parameters The parameters, in declaration order.
 */
@Serializable
data class TypedTransformationSignature(
    val name: String,
    val parameters: List<TypedTransformationParameter> = emptyList()
) {
    /** The model inputs (`in x: model`, `in xs: List<model>`), in declaration order. */
    val modelInputs: List<TypedTransformationParameter> get() = parameters.filter { it.isInput && it.isModel }
}

/**
 * A parameter of a signature. The engine binds every parameter at the transformation's own
 * scope level (1) before the first statement: an `in` parameter to its argument (or default),
 * an `out` parameter to `null` until a pattern assigns it.
 *
 * @param name The parameter's name.
 * @param direction `in` or `out`.
 * @param type Index into the transformation's types array.
 * @param isElement Whether the parameter is a model element (a node of the graph) rather than a
 *   scalar value.
 * @param defaultValue The default of an optional input, evaluated in the callee.
 * @param collection For a collection parameter, `List` or `Set`; a collection output starts empty.
 * @param elementsAreElements For a collection parameter, whether its elements are model elements.
 * @param optional Whether the input may be left out (its type is nullable and it has no default):
 *   it is then null, or unbound when it [pinsNode].
 * @param pinsNode Whether the input pins the object of the same name of the first pattern: when it
 *   is passed and not null, the object is bound to it; otherwise the name stays unbound and the
 *   pattern matches the object freely.
 * @param isModel Whether the input is a model input: `in x: model` (no [collection]) or
 *   `in xs: List<model>` ([collection] `List`). Its value is a read-only model of the
 *   transformation's metamodel besides the implicit one it runs on; at runtime a
 *   [com.mdeo.modeltransformation.runtime.ModelSlot] (a list of them for the list form). A model
 *   input is never [isElement], has no default, is never [optional] and never [pinsNode]; it must
 *   be passed.
 */
@Serializable
data class TypedTransformationParameter(
    val name: String,
    val direction: String,
    val type: Int,
    val isElement: Boolean = false,
    @Contextual val defaultValue: TypedExpression? = null,
    val collection: String? = null,
    val elementsAreElements: Boolean = false,
    val optional: Boolean = false,
    val pinsNode: Boolean = false,
    val isModel: Boolean = false
) {
    /** Whether this is an input. */
    val isInput: Boolean get() = direction == "in"

    /** Whether this is an output. */
    val isOutput: Boolean get() = direction == "out"

    /** Whether this is a collection parameter. */
    val isCollection: Boolean get() = collection != null
}
