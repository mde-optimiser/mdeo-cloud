package com.mdeo.modeltransformation.runtime.statements

import com.mdeo.modeltransformation.ast.statements.TypedWhileMatchStatement
import com.mdeo.modeltransformation.ast.statements.TypedTransformationStatement
import com.mdeo.modeltransformation.runtime.StatementExecutor
import com.mdeo.modeltransformation.runtime.TransformationEngine
import com.mdeo.modeltransformation.runtime.TransformationExecutionContext
import com.mdeo.modeltransformation.runtime.TransformationExecutionResult
import com.mdeo.modeltransformation.runtime.match.MatchExecutor
import com.mdeo.modeltransformation.runtime.match.MatchResult

/**
 * Executor for TypedWhileMatchStatement.
 *
 * This executor handles repeated execution while a pattern matches.
 * It repeatedly attempts to match the pattern and executes the doBlock
 * for each successful match.
 *
 * The loop terminates when:
 * - The pattern no longer matches (this is NOT a failure)
 * - A statement in the doBlock returns Failure
 * - A statement in the doBlock returns Stopped
 *
 * IMPORTANT: Unlike standalone match statements, when the pattern stops
 * matching in a while-match context, this is NOT a failure. It simply
 * means the loop terminates normally.
 *
 * The unified executor performs matching and modifications in a single Gremlin
 * query with limit=1 (each iteration processes one match).
 *
 * @param matchExecutor The executor used for unified pattern matching and modifications.
 */
class WhileMatchStatementExecutor(
    private val matchExecutor: MatchExecutor = MatchExecutor()
) : StatementExecutor {
    
    /**
     * Determines whether this executor can handle the given statement.
     *
     * @param statement The statement to check.
     * @return True if the statement is a TypedWhileMatchStatement.
     */
    override fun canExecute(statement: TypedTransformationStatement): Boolean {
        return statement is TypedWhileMatchStatement
    }
    
    /**
     * Executes a while-match statement.
     *
     * Scope handling per spec:
     * - Enter a new scope (level+1) for WhileMatchStatement
     * - The match runs in that scope
     * - The doBlock enters another scope (level+2 from base)
     *
     * Repeatedly matches the pattern and executes the doBlock while the pattern
     * matches. Accumulates results from all iterations. When the pattern no longer
     * matches, the loop terminates normally (not a failure).
     *
     * Each iteration uses a fresh scope so that match bindings from one iteration
     * don't affect the next.
     *
     * @param statement The while-match statement to execute.
     * @param context The current execution context.
     * @param engine The transformation engine.
     * @return The accumulated result from all iterations, or the terminating result.
     */
    override fun execute(
        statement: TypedTransformationStatement,
        context: TransformationExecutionContext,
        engine: TransformationEngine
    ): TransformationExecutionResult {
        val whileMatchStatement = statement as TypedWhileMatchStatement
        
        var accumulatedResult: TransformationExecutionResult.Success? = null
        
        while (true) {
            val whileContext = context.enterScope()
            
            val matchResult = matchExecutor.executeMatch(
                pattern = whileMatchStatement.pattern,
                context = whileContext,
                engine = engine
            )
            
            when (matchResult) {
                is MatchResult.NoMatch -> break
                is MatchResult.Matched -> {
                    val matchSuccess = TransformationExecutionResult.Success(
                        createdNodes = matchResult.createdNodeIds,
                        deletedNodes = matchResult.deletedNodeIds,
                        edgesModified = matchResult.edgesModified
                    )
                    accumulatedResult = matchSuccess.merge(accumulatedResult)
                    val matchedContext = matchResult.applyTo(whileContext)
                    val iterationResult = engine.executeBlock(whileMatchStatement.doBlock, matchedContext)
                    
                    when (iterationResult) {
                        is TransformationExecutionResult.Success -> {
                            accumulatedResult = iterationResult.merge(accumulatedResult)
                        }
                        is TransformationExecutionResult.Failure -> return iterationResult.merge(accumulatedResult)
                        is TransformationExecutionResult.Stopped -> return iterationResult
                    }
                }
            }
        }
        
        return accumulatedResult ?: TransformationExecutionResult.Success.empty()
    }
    
}
