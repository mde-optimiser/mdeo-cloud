package com.mdeo.modeltransformation.runtime.statements

import com.mdeo.modeltransformation.ast.statements.TypedIfExpressionStatement
import com.mdeo.modeltransformation.ast.statements.TypedTransformationStatement
import com.mdeo.modeltransformation.runtime.StatementExecutor
import com.mdeo.modeltransformation.runtime.TransformationEngine
import com.mdeo.modeltransformation.runtime.TransformationExecutionContext
import com.mdeo.modeltransformation.runtime.TransformationExecutionResult

/**
 * Executor for TypedIfExpressionStatement.
 *
 * This executor handles conditional execution based on boolean expressions.
 * It evaluates the condition expression and executes the appropriate branch:
 * - If the condition is truthy, executes the thenBlock
 * - If falsy, checks each elseIfBranch in order
 * - If no conditions are true, executes the elseBlock (if present)
 *
 * The result is the combined result from the executed branch. If no branch
 * is executed (condition false with no else), returns Success with unchanged context.
 */
class IfExpressionStatementExecutor : StatementExecutor {
    
    /**
     * Determines whether this executor can handle the given statement.
     *
     * @param statement The statement to check.
     * @return True if the statement is a TypedIfExpressionStatement.
     */
    override fun canExecute(statement: TypedTransformationStatement): Boolean {
        return statement is TypedIfExpressionStatement
    }
    
    /**
     * Executes an if-expression statement.
     *
     * Evaluates the condition and executes the appropriate branch. If the condition
     * is truthy, the thenBlock is executed. Otherwise, elseIfBranches are checked
     * in order. If no conditions are true and an elseBlock exists, it is executed.
     *
     * @param statement The if-expression statement to execute.
     * @param context The current execution context.
     * @param engine The transformation engine.
     * @return The result of executing the selected branch, or Success if no branch executed.
     */
    override fun execute(
        statement: TypedTransformationStatement,
        context: TransformationExecutionContext,
        engine: TransformationEngine
    ): TransformationExecutionResult {
        val ifStatement = statement as TypedIfExpressionStatement
        val conditionEvaluator = ConditionEvaluator(engine)
        
        val conditionValue = conditionEvaluator.evaluate(ifStatement.condition, context)
        
        if (conditionValue) {
            return engine.executeBlock(ifStatement.thenBlock, context)
        }
        
        for (branch in ifStatement.elseIfBranches) {
            val branchConditionValue = conditionEvaluator.evaluate(branch.condition, context)
            if (branchConditionValue) {
                return engine.executeBlock(branch.block, context)
            }
        }
        
        if (ifStatement.elseBlock != null) {
            return engine.executeBlock(ifStatement.elseBlock, context)
        }
        
        return TransformationExecutionResult.Success.empty()
    }
    
}
