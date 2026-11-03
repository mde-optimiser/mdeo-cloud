package com.mdeo.modeltransformation.runtime.statements

import com.mdeo.modeltransformation.ast.statements.TypedAssignmentStatement
import com.mdeo.modeltransformation.ast.statements.TypedTransformationStatement
import com.mdeo.modeltransformation.compiler.CollectionValue
import com.mdeo.modeltransformation.compiler.VariableBinding
import com.mdeo.modeltransformation.runtime.StatementExecutor
import com.mdeo.modeltransformation.runtime.TransformationEngine
import com.mdeo.modeltransformation.runtime.TransformationExecutionContext
import com.mdeo.modeltransformation.runtime.TransformationExecutionResult

/**
 * Executor for [TypedAssignmentStatement]: `name = value` and `name += value` outside a pattern.
 *
 * The value is evaluated in the current context and written to the scope declaring [name] (an
 * `out` parameter at the transformation's level, a variable where its match declared it), so
 * the change is seen after the enclosing block and by later loop iterations, like a
 * reassignment inside a pattern.
 *
 * - `=` binds the value; a collection output gets a collection of its own kind with the value's
 *   elements.
 * - `+=` adds the value (or, for a collection value, all its elements) to a collection output.
 *
 * An assignment does not change the model and does not fail.
 */
class AssignmentStatementExecutor : StatementExecutor {

    override fun canExecute(statement: TypedTransformationStatement): Boolean {
        return statement is TypedAssignmentStatement
    }

    override fun execute(
        statement: TypedTransformationStatement,
        context: TransformationExecutionContext,
        engine: TransformationEngine
    ): TransformationExecutionResult {
        val assignment = statement as TypedAssignmentStatement
        val value = ExpressionValueEvaluator(engine).evaluate(assignment.value, context)
        val scope = context.variableScope.findDeclaringScope(assignment.name) ?: context.variableScope
        val current = ((scope.getLocalBinding(assignment.name) as? VariableBinding.ValueBinding)?.value)
            as? CollectionValue

        if (assignment.operator == "+=") {
            val target = current ?: return TransformationExecutionResult.Failure(
                reason = "'${assignment.name}' is not a collection; '+=' adds to a collection output",
                failedAt = "${assignment.name} += …",
                isDeterministic = true,
                changesWereMade = false
            )
            add(target, value, engine)
        } else if (current != null) {
            val replacement = CollectionValue(current.isSet)
            add(replacement, value, engine)
            scope.setBinding(assignment.name, VariableBinding.ValueBinding(replacement))
        } else {
            scope.setBinding(assignment.name, value)
        }
        return TransformationExecutionResult.Success.empty()
    }

    /**
     * Adds a value to a collection: all elements of a collection value, or the value itself.
     */
    private fun add(target: CollectionValue, value: VariableBinding, engine: TransformationEngine) {
        val collection = (value as? VariableBinding.ValueBinding)?.value as? CollectionValue
        if (collection != null) {
            collection.items.forEach { target.add(it) }
        } else {
            target.add(CollectionValue.item(value, engine.modelGraph))
        }
    }
}
