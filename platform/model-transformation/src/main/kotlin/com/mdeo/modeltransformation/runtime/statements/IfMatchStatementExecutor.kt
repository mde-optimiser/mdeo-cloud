package com.mdeo.modeltransformation.runtime.statements

import com.mdeo.modeltransformation.ast.statements.TypedIfMatchStatement
import com.mdeo.modeltransformation.ast.statements.TypedTransformationStatement
import com.mdeo.modeltransformation.runtime.StatementExecutor
import com.mdeo.modeltransformation.runtime.TransformationEngine
import com.mdeo.modeltransformation.runtime.TransformationExecutionContext
import com.mdeo.modeltransformation.runtime.TransformationExecutionResult
import com.mdeo.modeltransformation.runtime.match.MatchExecutor
import com.mdeo.modeltransformation.runtime.match.MatchResult

/**
 * Executor for TypedIfMatchStatement.
 *
 * This executor handles conditional execution based on pattern matching.
 * It attempts to match the pattern against the graph:
 * - If the pattern matches, the thenBlock is executed with the matched bindings
 * - If no match is found, the elseBlock is executed (if present)
 *
 * IMPORTANT: Unlike a standalone match statement, a failed match in an if-match
 * context does NOT cause a transformation failure. Instead, it simply means
 * the else branch is taken.
 *
 * The unified executor performs matching and modifications in a single Gremlin
 * query with limit=1 (default for single match operations).
 *
 * @param matchExecutor The executor used for unified pattern matching and modifications.
 */
class IfMatchStatementExecutor(
    private val matchExecutor: MatchExecutor = MatchExecutor()
) : StatementExecutor {
    
    /**
     * Determines whether this executor can handle the given statement.
     *
     * @param statement The statement to check.
     * @return True if the statement is a TypedIfMatchStatement.
     */
    override fun canExecute(statement: TypedTransformationStatement): Boolean {
        return statement is TypedIfMatchStatement
    }
    
    /**
     * Executes an if-match statement.
     *
     * Scope handling per spec:
     * - Enter a new scope (level+1) for the IfMatchConditionAndBlock
     * - The match runs in that scope
     * - The thenBlock enters ANOTHER scope (level+2 total from base)
     * - The elseBlock enters a scope (level+1 from base, since it's just StatementsScope)
     *
     * Unlike standalone match statements, a failed match here is NOT a failure -
     * it simply means we take the else path.
     *
     * @param statement The if-match statement to execute.
     * @param context The current execution context.
     * @param engine The transformation engine.
     * @return The result of executing the selected branch.
     */
    override fun execute(
        statement: TypedTransformationStatement,
        context: TransformationExecutionContext,
        engine: TransformationEngine
    ): TransformationExecutionResult {
        val ifMatchStatement = statement as TypedIfMatchStatement
        
        val conditionContext = context.enterScope()
        
        val matchResult = matchExecutor.executeMatch(
            pattern = ifMatchStatement.pattern,
            context = conditionContext,
            engine = engine
        )
        
        return when (matchResult) {
            is MatchResult.Matched -> executeThenBranch(ifMatchStatement, matchResult, conditionContext, context, engine)
            is MatchResult.NoMatch -> executeElseBranch(ifMatchStatement, context, engine)
        }
    }
    
    /**
     * Executes the then branch after a successful match.
     *
     * The thenBlock enters another scope (level+2 total from base) as it's a StatementsScope.
     * Modifications have already been applied by the unified executor.
     *
     * @param statement The if-match statement.
     * @param matched The match result containing bindings.
     * @param conditionContext The context at condition scope level (where match bindings live).
     * @param baseContext The original execution context (for returning).
     * @param engine The transformation engine.
     * @return The result of executing the thenBlock.
     */
    private fun executeThenBranch(
        statement: TypedIfMatchStatement,
        matched: MatchResult.Matched,
        conditionContext: TransformationExecutionContext,
        baseContext: TransformationExecutionContext,
        engine: TransformationEngine
    ): TransformationExecutionResult {
        val matchedContext = matched.applyTo(conditionContext)
        val matchSuccess = TransformationExecutionResult.Success(
            createdNodes = matched.createdNodeIds,
            deletedNodes = matched.deletedNodeIds,
            edgesModified = matched.edgesModified
        )
        val blockResult = engine.executeBlock(statement.thenBlock, matchedContext)
        
        return when (blockResult) {
            is TransformationExecutionResult.Success -> blockResult.merge(matchSuccess)
            is TransformationExecutionResult.Failure -> blockResult.merge(matchSuccess)
            is TransformationExecutionResult.Stopped -> blockResult
        }
    }
    
    /**
     * Executes the else branch when no match is found.
     *
     * The elseBlock enters a scope (level+1 from base) as it's a StatementsScope.
     * If an elseBlock exists, executes it. Otherwise, returns Success with
     * the unchanged context. This is the key difference from standalone match:
     * no match is NOT a failure.
     *
     * @param statement The if-match statement.
     * @param context The current execution context.
     * @param engine The transformation engine.
     * @return The result of executing the elseBlock, or Success if no else.
     */
    private fun executeElseBranch(
        statement: TypedIfMatchStatement,
        context: TransformationExecutionContext,
        engine: TransformationEngine
    ): TransformationExecutionResult {
        return if (statement.elseBlock != null) {
            return engine.executeBlock(statement.elseBlock, context)
        } else {
            TransformationExecutionResult.Success.empty()
        }
    }
}