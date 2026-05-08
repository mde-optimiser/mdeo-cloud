package com.mdeo.modeltransformation.runtime.statements

import com.mdeo.modeltransformation.ast.statements.TypedWhileExpressionStatement
import com.mdeo.modeltransformation.ast.statements.TypedTransformationStatement
import com.mdeo.modeltransformation.runtime.StatementExecutor
import com.mdeo.modeltransformation.runtime.TransformationEngine
import com.mdeo.modeltransformation.runtime.TransformationExecutionContext
import com.mdeo.modeltransformation.runtime.TransformationExecutionResult

/**
 * Executor for TypedWhileExpressionStatement.
 *
 * This executor handles repeated execution based on a boolean expression.
 * It evaluates the condition before each iteration and executes the block
 * as long as the condition remains truthy.
 *
 * The executor accumulates results from all iterations. If any iteration
 * results in Failure or Stopped, the loop terminates and that result is returned.
 */
class WhileExpressionStatementExecutor : StatementExecutor {
    
    /**
     * Determines whether this executor can handle the given statement.
     *
     * @param statement The statement to check.
     * @return True if the statement is a TypedWhileExpressionStatement.
     */
    override fun canExecute(statement: TypedTransformationStatement): Boolean {
        return statement is TypedWhileExpressionStatement
    }
    
    /**
     * Executes a while-expression statement.
     *
     * Scope handling per spec:
     * - The while loop enters a new scope (level+1)
     * - The doBlock enters another scope (level+2)
     *
     * Repeatedly evaluates the condition and executes the block as long as
     * the condition is truthy. Accumulates results from all iterations.
     * Terminates immediately if any iteration returns Failure or Stopped.
     *
     * @param statement The while-expression statement to execute.
     * @param context The current execution context.
     * @param engine The transformation engine.
     * @return The accumulated result from all iterations, or the terminating result.
     */
    override fun execute(
        statement: TypedTransformationStatement,
        context: TransformationExecutionContext,
        engine: TransformationEngine
    ): TransformationExecutionResult {
        val whileStatement = statement as TypedWhileExpressionStatement
        var accumulatedResult: TransformationExecutionResult.Success? = null
        val conditionEvaluator = ConditionEvaluator(engine)

        while (true) {
            val whileContext = context.enterScope()
            val conditionValue = conditionEvaluator.evaluate(whileStatement.condition, whileContext)
            
            if (!conditionValue) {
                break
            }
            
            val iterationResult = engine.executeBlock(whileStatement.block, whileContext)
            
            when (iterationResult) {
                is TransformationExecutionResult.Success -> {
                    accumulatedResult = iterationResult.merge(accumulatedResult)
                }
                is TransformationExecutionResult.Failure -> return iterationResult.merge(accumulatedResult)
                is TransformationExecutionResult.Stopped -> return iterationResult
            }
        }

        return accumulatedResult ?: TransformationExecutionResult.Success.empty()
    }

}
