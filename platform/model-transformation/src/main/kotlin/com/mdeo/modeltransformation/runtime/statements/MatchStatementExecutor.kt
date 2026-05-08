package com.mdeo.modeltransformation.runtime.statements

import com.mdeo.modeltransformation.ast.statements.TypedMatchStatement
import com.mdeo.modeltransformation.ast.statements.TypedTransformationStatement
import com.mdeo.modeltransformation.runtime.StatementExecutor
import com.mdeo.modeltransformation.runtime.TransformationEngine
import com.mdeo.modeltransformation.runtime.TransformationExecutionContext
import com.mdeo.modeltransformation.runtime.TransformationExecutionResult
import com.mdeo.modeltransformation.runtime.match.MatchExecutor
import com.mdeo.modeltransformation.runtime.match.MatchResult

/**
 * Executor for TypedMatchStatement.
 *
 * This executor handles simple pattern matching statements. It uses a MatchExecutor
 * to find a subgraph that matches the pattern, apply modifications (create/delete/update),
 * and update the execution context with the matched bindings.
 *
 * The unified executor performs all operations in a single Gremlin query:
 * 1. Pattern matching using match() step
 * 2. Limit to 1 match (default for single match statements)
 * 3. Create new vertices using addV()
 * 4. Update properties
 * 5. Create edges using addE()
 * 6. Delete edges and vertices using drop()
 *
 * If no match is found, the transformation fails (as per the specification).
 *
 * @param matchExecutor The executor used for unified pattern matching and modifications.
 */
class MatchStatementExecutor(
    private val matchExecutor: MatchExecutor = MatchExecutor()
) : StatementExecutor {
    
    /**
     * Determines whether this executor can handle the given statement.
     *
     * @param statement The statement to check.
     * @return True if the statement is a TypedMatchStatement.
     */
    override fun canExecute(statement: TypedTransformationStatement): Boolean {
        return statement is TypedMatchStatement
    }
    
    /**
     * Executes a match statement.
     *
     * Attempts to match the pattern against the current graph with limit=1.
     * If successful, modifications are applied within the same traversal,
     * and the execution context is updated with the matched bindings.
     * If no match is found, returns Failure as per the specification.
     *
     * @param statement The match statement to execute.
     * @param context The current execution context.
     * @param engine The transformation engine.
     * @return Success with updated context if matched, Failure if no match found.
     */
    override fun execute(
        statement: TypedTransformationStatement,
        context: TransformationExecutionContext,
        engine: TransformationEngine
    ): TransformationExecutionResult {
        val matchStatement = statement as TypedMatchStatement
        
        val matchResult = matchExecutor.executeMatch(
            pattern = matchStatement.pattern,
            context = context,
            engine = engine
        )
        
        return when (matchResult) {
            is MatchResult.Matched -> createSuccessResult(matchResult, context)
            is MatchResult.NoMatch -> createFailureResult(matchResult)
        }
    }
    
    /**
     * Creates a success result from a matched pattern.
     *
     * @param matched The match result containing bindings.
     * @param context The original execution context.
     * @return A Success result with updated context.
     */
    private fun createSuccessResult(
        matched: MatchResult.Matched,
        context: TransformationExecutionContext
    ): TransformationExecutionResult.Success {
        matched.applyTo(context)
        
        return TransformationExecutionResult.Success(
            createdNodes = matched.createdNodeIds,
            deletedNodes = matched.deletedNodeIds,
            edgesModified = matched.edgesModified
        )
    }
    
    /**
     * Creates a failure result from a failed match.
     *
     * A no-match at the match-step level is always locally deterministic (same model state
     * → same no-match) and makes no changes.  The outer accumulating executor is responsible
     * for combining this with the previously accumulated state to decide the global
     * [TransformationExecutionResult.Failure.isDeterministic] and
     * [TransformationExecutionResult.Failure.changesWereMade] values.
     *
     * @param noMatch The no-match result.
     * @return A Failure result with the reason.
     */
    private fun createFailureResult(
        noMatch: MatchResult.NoMatch
    ): TransformationExecutionResult.Failure {
        return TransformationExecutionResult.Failure(
            reason = noMatch.reason ?: "Pattern did not match",
            failedAt = "match statement",
            isDeterministic = true,
            changesWereMade = false
        )
    }
}
