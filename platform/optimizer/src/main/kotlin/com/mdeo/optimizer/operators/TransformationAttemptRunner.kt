package com.mdeo.optimizer.operators

import com.mdeo.modeltransformation.ast.TransformationOperator
import com.mdeo.modeltransformation.runtime.TransformationEngine
import com.mdeo.modeltransformation.runtime.TransformationExecutionResult
import com.mdeo.optimizer.solution.Solution
import org.slf4j.LoggerFactory

/**
 * Possible outcomes of a single transformation attempt.
 */
sealed class TransformationAttemptResult {
    /** The transformation was applied successfully and the model was modified. */
    object Applied : TransformationAttemptResult()

    /**
     * The transformation failed.
     *
     * @param isDeterministic `true` when the failure is guaranteed to recur on the same
     *   model state (no match was found before any graph modification), so the operator
     *   can be safely skipped on future attempts against this state.
     *   `false` when the outcome may differ on a re-try (e.g. a later match statement
     *   failed after some partial modifications, or an unexpected exception occurred).
     * @param changesWereMade whether the graph may have been modified before the failure.
     *   For deterministic failures this is always `false`; for non-deterministic failures
     *   it defaults to `true` (pessimistic).
     */
    data class Failure(val isDeterministic: Boolean, val changesWereMade: Boolean) : TransformationAttemptResult()

    /** `true` when this result represents a successful application. */
    val isApplied: Boolean get() = this is Applied
}

/**
 * Runs a model transformation attempt against a candidate solution.
 *
 * Nondeterministic behaviour is now reset automatically inside
 * [com.mdeo.modeltransformation.runtime.match.MatchExecutor] before every individual
 * match step, so each match within a single transformation execution sees a freshly
 * shuffled vertex iteration order. This prevents earlier matches from constraining
 * the possible outcomes of later ones.
 */
class TransformationAttemptRunner {
    private val logger = LoggerFactory.getLogger(TransformationAttemptRunner::class.java)

    /**
     * Attempts to apply the given [operator] to the solution's model graph.
     *
     * Creates a [TransformationEngine] in non-deterministic mode and runs the
     * compiled transformation AST against the solution's model graph. On success
     * the graph is modified in place; on failure the graph may have been partially
     * modified (see [TransformationAttemptResult.Failure.changesWereMade]).
     *
     * @param solution The candidate solution (model graph modified in place on success).
     * @param operator The compiled [TransformationOperator] to apply.
     * @return [TransformationAttemptResult.Applied] on success, or
     *   [TransformationAttemptResult.Failure] with [TransformationAttemptResult.Failure.isDeterministic]
     *   set accordingly.
     */
    fun tryApply(solution: Solution, operator: TransformationOperator): TransformationAttemptResult {
        return try {
            val engine = TransformationEngine.create(
                solution.modelGraph, operator.ast, deterministic = false
            )
            val result = engine.execute()

            when (result) {
                is TransformationExecutionResult.Success -> TransformationAttemptResult.Applied
                is TransformationExecutionResult.Stopped ->
                    if (result.isNormalStop) TransformationAttemptResult.Applied
                    else TransformationAttemptResult.Failure(isDeterministic = false, changesWereMade = true)
                is TransformationExecutionResult.Failure ->
                    if (result.isDeterministic)
                        TransformationAttemptResult.Failure(isDeterministic = true, changesWereMade = result.changesWereMade)
                    else
                        TransformationAttemptResult.Failure(isDeterministic = false, changesWereMade = result.changesWereMade)
            }
        } catch (e: Exception) {
            logger.warn("Transformation operator {} threw exception: {}", operator.id, e.message)
            TransformationAttemptResult.Failure(isDeterministic = false, changesWereMade = true)
        }
    }
}
