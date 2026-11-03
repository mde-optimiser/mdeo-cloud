package com.mdeo.modeltransformation.runtime.statements

import com.mdeo.modeltransformation.ast.statements.TypedForInStatement
import com.mdeo.modeltransformation.ast.statements.TypedTransformationStatement
import com.mdeo.modeltransformation.compiler.CollectionValue
import com.mdeo.modeltransformation.compiler.VariableBinding
import com.mdeo.modeltransformation.graph.VertexRef
import com.mdeo.modeltransformation.runtime.StatementExecutor
import com.mdeo.modeltransformation.runtime.TransformationEngine
import com.mdeo.modeltransformation.runtime.TransformationExecutionContext
import com.mdeo.modeltransformation.runtime.TransformationExecutionResult

/**
 * Executor for [TypedForInStatement]: `for name in collection do { ... }`.
 *
 * The collection is evaluated once, before the first iteration, so additions to it in the body
 * do not change the iterations. The loop enters one scope level, where the loop variable is
 * bound to each element in turn (a model element as a bound object, a scalar or a model of a
 * `List<model>` input — its [com.mdeo.modeltransformation.runtime.ModelSlot] — as a value); the do
 * block enters another. An empty collection runs no iteration and succeeds. A failure or a stop
 * in an iteration ends the loop with it, as in `for match`.
 */
class ForInStatementExecutor : StatementExecutor {

    override fun canExecute(statement: TypedTransformationStatement): Boolean {
        return statement is TypedForInStatement
    }

    override fun execute(
        statement: TypedTransformationStatement,
        context: TransformationExecutionContext,
        engine: TransformationEngine
    ): TransformationExecutionResult {
        val forIn = statement as TypedForInStatement
        val forContext = context.enterScope()
        val items = elements(ExpressionValueEvaluator(engine).evaluate(forIn.collection, forContext))

        var accumulated: TransformationExecutionResult.Success? = null
        for (item in items) {
            val scope = forContext.variableScope.copy()
            scope.setBinding(
                forIn.variable,
                if (item is VertexRef) VariableBinding.InstanceBinding(item) else VariableBinding.ValueBinding(item)
            )
            when (val result = engine.executeBlock(forIn.doBlock, forContext.withScope(scope))) {
                is TransformationExecutionResult.Success -> accumulated = result.merge(accumulated)
                is TransformationExecutionResult.Failure -> return result.merge(accumulated)
                is TransformationExecutionResult.Stopped -> return result
            }
        }
        return accumulated ?: TransformationExecutionResult.Success.empty()
    }

    /**
     * The elements a binding holds, as a snapshot: the items of a collection, the reference of a
     * single bound object, a single value, or nothing for null.
     */
    private fun elements(binding: VariableBinding): List<Any?> = when (binding) {
        is VariableBinding.ValueBinding -> when (val value = binding.value) {
            null -> emptyList()
            is CollectionValue -> value.items.toList()
            is Iterable<*> -> value.toList()
            else -> listOf(value)
        }
        is VariableBinding.InstanceBinding -> listOfNotNull(binding.vertexRef)
        is VariableBinding.LabelBinding -> emptyList()
    }
}
