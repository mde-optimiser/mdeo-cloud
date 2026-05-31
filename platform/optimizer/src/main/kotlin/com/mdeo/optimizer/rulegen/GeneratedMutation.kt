package com.mdeo.optimizer.rulegen

import com.mdeo.modeltransformation.ast.TypedAst

/**
 * A named mutation operator produced by [MutationRuleGenerator].
 *
 * @param name     Human-readable rule name used for logging and operator selection.
 * @param typedAst The compiled transformation AST ready for execution.
 */
data class GeneratedMutation(
    val name: String,
    val typedAst: TypedAst
)
