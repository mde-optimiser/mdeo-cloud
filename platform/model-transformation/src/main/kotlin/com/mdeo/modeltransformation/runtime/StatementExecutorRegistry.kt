package com.mdeo.modeltransformation.runtime

import com.mdeo.modeltransformation.ast.statements.TypedTransformationStatement
import com.mdeo.modeltransformation.runtime.statements.ForMatchStatementExecutor
import com.mdeo.modeltransformation.runtime.statements.IfExpressionStatementExecutor
import com.mdeo.modeltransformation.runtime.statements.IfMatchStatementExecutor
import com.mdeo.modeltransformation.runtime.statements.MatchStatementExecutor
import com.mdeo.modeltransformation.runtime.statements.StopStatementExecutor
import com.mdeo.modeltransformation.runtime.statements.UntilMatchStatementExecutor
import com.mdeo.modeltransformation.runtime.statements.WhileExpressionStatementExecutor
import com.mdeo.modeltransformation.runtime.statements.WhileMatchStatementExecutor

/**
 * Registry for statement executors.
 *
 * The registry provides a central point for managing and dispatching to
 * statement executors. It maintains a collection of registered executors
 * and finds the appropriate executor for each statement type.
 *
 * The registry supports:
 * - Registering new executors dynamically
 * - Finding executors based on statement type
 * - Dispatching execution to the appropriate executor
 *
 * Executors are checked in registration order, so more specific executors
 * should be registered before more general ones.
 *
 * Example usage:
 * ```kotlin
 * val registry = StatementExecutorRegistry()
 * registry.register(MatchStatementExecutor())
 * registry.register(IfMatchStatementExecutor())
 *
 * val executor = registry.findExecutor(statement)
 * ```
 *
 * @see StatementExecutor
 * @see TransformationEngine
 */
class StatementExecutorRegistry {
    
    private val executors: MutableList<StatementExecutor> = mutableListOf()
    
    /**
     * Registers a statement executor with this registry.
     *
     * Executors are checked in registration order when finding an executor
     * for a statement. Register more specific executors before more
     * general ones to ensure proper dispatch.
     *
     * @param executor The executor to register.
     * @return This registry, for method chaining.
     */
    fun register(executor: StatementExecutor): StatementExecutorRegistry {
        executors.add(executor)
        return this
    }
    
    /**
     * Registers multiple statement executors with this registry.
     *
     * Convenience method for registering multiple executors at once.
     * Executors are registered in the order they appear in the vararg.
     *
     * @param executorsToRegister The executors to register.
     * @return This registry, for method chaining.
     */
    fun registerAll(vararg executorsToRegister: StatementExecutor): StatementExecutorRegistry {
        executors.addAll(executorsToRegister)
        return this
    }
    
    /**
     * Finds an executor that can handle the given statement.
     *
     * Searches through registered executors in order and returns the first
     * one that returns true from [StatementExecutor.canExecute].
     *
     * @param statement The statement to find an executor for.
     * @return The executor that can handle the statement, or null if none found.
     */
    fun findExecutor(statement: TypedTransformationStatement): StatementExecutor? {
        return executors.find { it.canExecute(statement) }
    }
    
    /**
     * Executes a statement using the appropriate registered executor.
     *
     * This method finds the appropriate executor for the statement and
     * delegates execution to it. If no executor is found, a Failure result
     * is returned.
     *
     * @param statement The statement to execute.
     * @param context The current execution context.
     * @param engine The transformation engine for nested execution.
     * @return The result of executing the statement.
     */
    fun execute(
        statement: TypedTransformationStatement,
        context: TransformationExecutionContext,
        engine: TransformationEngine
    ): TransformationExecutionResult {
        val executor = findExecutor(statement)
            ?: return TransformationExecutionResult.Failure(
                reason = "No executor found for statement type: ${statement.kind}",
                failedAt = null,
                isDeterministic = false,
                changesWereMade = false
            )
        return executor.execute(statement, context, engine)
    }
    
    /**
     * Returns the number of registered executors.
     *
     * @return The count of registered executors.
     */
    fun executorCount(): Int = executors.size
    
    /**
     * Checks if any executor is registered for the given statement type.
     *
     * @param statement The statement to check.
     * @return True if an executor exists for this statement type.
     */
    fun hasExecutor(statement: TypedTransformationStatement): Boolean {
        return findExecutor(statement) != null
    }
    
    companion object {
        /**
         * Creates a registry with all default statement executors registered.
         *
         * This includes executors for all supported statement types:
         * - **Match statements**: Pattern matching with bindings
         * - **Stop statements**: Transformation termination
         * - **If statements**: Conditional execution (match and expression based)
         * - **While statements**: Loop execution (match and expression based)
         * - **For statements**: Loop execution over match results
         * - **Until statements**: Loop execution until match succeeds
         *
         * Executors are registered to handle all standard transformation control flow.
         *
         * @return A new [StatementExecutorRegistry] with all default executors registered
         */
        fun createDefaultRegistry(): StatementExecutorRegistry {
            return StatementExecutorRegistry().registerAll(
                MatchStatementExecutor(),
                StopStatementExecutor(),
                IfMatchStatementExecutor(),
                IfExpressionStatementExecutor(),
                WhileMatchStatementExecutor(),
                WhileExpressionStatementExecutor(),
                ForMatchStatementExecutor(),
                UntilMatchStatementExecutor()
            )
        }
    }
}
