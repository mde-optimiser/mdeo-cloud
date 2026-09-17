package com.mdeo.script.ast

import com.mdeo.expression.ast.expressions.TypedExpression
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Function or lambda parameter.
 *
 * @param name Name of the parameter.
 * @param type Index into the types array for the parameter type.
 * @param defaultValue The value the parameter takes when a call passes no argument for it. It is
 *                     evaluated at every such call, after the arguments, and may refer to the
 *                     parameters declared before it.
 */
@Serializable
data class TypedParameter(
    val name: String,
    val type: Int,
    @Contextual
    val defaultValue: TypedExpression? = null
)
