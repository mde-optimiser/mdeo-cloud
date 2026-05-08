package com.mdeo.modeltransformation.runtime.statements

import com.mdeo.modeltransformation.ast.statements.TypedUntilMatchStatement
import com.mdeo.modeltransformation.ast.statements.TypedTransformationStatement
import com.mdeo.modeltransformation.runtime.StatementExecutor
import com.mdeo.modeltransformation.runtime.TransformationEngine
import com.mdeo.modeltransformation.runtime.TransformationExecutionContext
import com.mdeo.modeltransformation.runtime.TransformationExecutionResult
import com.mdeo.modeltransformation.runtime.match.MatchExecutor
import com.mdeo.modeltransformation.runtime.match.MatchResult

/**
 * Executor for TypedUntilMatchStatement.
 *
 * This executor handles repeated execution UNTIL a pattern matches.
 * It's the inverse of while-match: executes the body first, then checks
 * if the pattern matches. The loop terminates when the pattern DOES match.
 *
 * The loop terminates when:
 * - The pattern matches (this is the normal termination)
 * - A statement in the doBlock returns Failure
 * - A statement in the doBlock returns Stopped
 *
 * The unified executor performs matching and modifications in a single Gremlin
 * query with limit=1 (for the condition check).
 *
 * @param matchExecutor The executor used for unified pattern matching and modifications.
 */
class UntilMatchStatementExecutor(
    private val matchExecutor: MatchExecutor = MatchExecutor()
) : StatementExecutor {
    
    /**
     * Determines whether this executor can handle the given statement.
     *
     * @param statement The statement to check.
     * @return True if the statement is a TypedUntilMatchStatement.
     */
    override fun canExecute(statement: TypedTransformationStatement): Boolean {
        return statement is TypedUntilMatchStatement
    }
    
    /**
     * Executes an until-match statement.
     *
     * Scope handling per spec:
     * - Enter a new scope (level+1) for UntilMatchStatement
     * - The doBlock runs in that scope (as StatementsScope, level+2)
     * - The match check runs in the UntilMatchStatement scope (level+1)
     *
     * Repeatedly executes the doBlock and then checks if the pattern matches.
     * The loop continues while the pattern does NOT match.
     * When the pattern matches, the loop terminates normally.
     *
     * @param statement The until-match statement to execute.
     * @param context The current execution context.
     * @param engine The transformation engine.
     * @return The accumulated result from all iterations, or the terminating result.
     */
    override fun execute(
        statement: TypedTransformationStatement,
        context: TransformationExecutionContext,
        engine: TransformationEngine
    ): TransformationExecutionResult {
        val untilMatchStatement = statement as TypedUntilMatchStatement
        
        var accumulatedResult: TransformationExecutionResult.Success? = null
        
        while (true) {
            val matchResult = matchExecutor.executeMatch(
                pattern = untilMatchStatement.pattern,
                context = context,
                engine = engine
            )

            when (matchResult) {
                is MatchResult.Matched -> {
                    matchResult.applyTo(context)
                    val matchSuccess = TransformationExecutionResult.Success(
                        createdNodes = matchResult.createdNodeIds,
                        deletedNodes = matchResult.deletedNodeIds,
                        edgesModified = matchResult.edgesModified
                    )
                    return matchSuccess.merge(accumulatedResult)
                }
                is MatchResult.NoMatch -> {
                    val blockResult = engine.executeBlock(untilMatchStatement.doBlock, context)
                    
                    when (blockResult) {
                        is TransformationExecutionResult.Success -> {
                            accumulatedResult = blockResult.merge(accumulatedResult)
                        }
                        is TransformationExecutionResult.Failure -> return blockResult.merge(accumulatedResult)
                        is TransformationExecutionResult.Stopped -> return blockResult
                    }
                }
            }
        }
    }
}
