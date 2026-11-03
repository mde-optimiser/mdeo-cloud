package com.mdeo.modeltransformation.runtime.statements

import com.mdeo.modeltransformation.ast.statements.TypedIfCallStatement
import com.mdeo.modeltransformation.ast.statements.TypedTransformationStatement
import com.mdeo.modeltransformation.runtime.StatementExecutor
import com.mdeo.modeltransformation.runtime.TransformationEngine
import com.mdeo.modeltransformation.runtime.TransformationExecutionContext
import com.mdeo.modeltransformation.runtime.TransformationExecutionResult

/**
 * Executor for [TypedIfCallStatement]: `if call name(...) -> (...) then { ... } else { ... }`.
 *
 * Scopes mirror `if match`: the call runs one level below the statement, where its outputs are
 * bound on success, and the then block enters another level; the else block enters one level
 * from the statement's.
 *
 * - The callee succeeds (also with `stop`): the outputs are bound and the then block runs.
 * - The callee fails: the else block runs, or nothing when there is none. The language only
 *   accepts callees that fail before they change the model; should a failing callee have changed
 *   it anyway, the failure is propagated rather than running the else block on a changed model.
 * - `kill` in the callee ends everything.
 */
class IfCallStatementExecutor(
    private val callExecutor: CallStatementExecutor = CallStatementExecutor()
) : StatementExecutor {

    override fun canExecute(statement: TypedTransformationStatement): Boolean {
        return statement is TypedIfCallStatement
    }

    override fun execute(
        statement: TypedTransformationStatement,
        context: TransformationExecutionContext,
        engine: TransformationEngine
    ): TransformationExecutionResult {
        val ifCall = statement as TypedIfCallStatement
        val conditionContext = context.enterScope()

        return when (val result = callExecutor.call(ifCall.call, conditionContext, engine)) {
            is TransformationExecutionResult.Success -> {
                when (val block = engine.executeBlock(ifCall.thenBlock, conditionContext)) {
                    is TransformationExecutionResult.Success -> block.merge(result)
                    is TransformationExecutionResult.Failure -> block.merge(result)
                    is TransformationExecutionResult.Stopped -> block
                }
            }
            is TransformationExecutionResult.Failure -> when {
                result.changesWereMade -> result
                ifCall.elseBlock != null -> engine.executeBlock(ifCall.elseBlock, context)
                else -> TransformationExecutionResult.Success.empty()
            }
            is TransformationExecutionResult.Stopped -> result
        }
    }
}
