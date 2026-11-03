package com.mdeo.expression.ast.expressions

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Wraps a call argument with its expected parameter type from the function signature.
 *
 * This captures both the actual argument expression and the type that the function
 * signature expects for this parameter position. For generic functions, the parameter
 * type is the resolved (substituted) type rather than the raw generic type.
 *
 * For example, in `listOf<double>(1, 2, 3)`, each argument would have:
 * - `value`: the int literal expression (evalType = int)
 * - `parameterType`: index pointing to `double` (the resolved generic type)
 *
 * @param value The actual argument expression.
 * @param parameterType Index into the types array for the type the function signature
 *                      expects at this parameter position. This is the resolved type
 *                      after generic substitution.
 * @param parameter Index of the parameter the argument is passed to, set when that is not the
 *                  argument's own position, as for a named argument. Arguments are listed in the
 *                  order they are written and evaluated; a parameter no argument is passed to
 *                  takes its default value.
 */
@Serializable
data class TypedCallArgument(
    @Contextual
    val value: TypedExpression,
    val parameterType: Int,
    val parameter: Int? = null
)
