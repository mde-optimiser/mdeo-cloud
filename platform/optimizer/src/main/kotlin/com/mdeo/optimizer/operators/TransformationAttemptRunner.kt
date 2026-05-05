package com.mdeo.optimizer.operators

import com.mdeo.modeltransformation.ast.TypedAst
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
     * The transformation failed deterministically: no match was found before any graph
     * modification, so re-running it on the same model state will always produce the
     * same result. Safe to skip on future attempts against this model state.
     */
    object DeterministicFailure : TransformationAttemptResult()

    /**
     * The transformation failed but the outcome may differ on a re-try (e.g. a later
     * match statement failed after some partial modifications, or an unexpected exception
     * occurred). Not safe to permanently skip.
     */
    object NonDeterministicFailure : TransformationAttemptResult()

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
 *
 * @param transformations Map of transformation path to its compiled TypedAst.
 */
class TransformationAttemptRunner(
    private val transformations: Map<String, TypedAst>
) {
    private val logger = LoggerFactory.getLogger(TransformationAttemptRunner::class.java)

    /**
     * Attempts to apply the named transformation to the given solution.
     *
     * Creates a [TransformationEngine] in non-deterministic mode and runs the
     * compiled transformation AST against the solution's model graph. On success
     * the graph is modified in place; on failure the graph is left unchanged.
     *
     * @param solution The candidate solution (modified in place on success).
     * @param transformationPath Path identifying which transformation to apply.
     * @return [TransformationAttemptResult.Applied] on success,
     *   [TransformationAttemptResult.DeterministicFailure] when the failure is guaranteed
     *   to recur on the same model state, or [TransformationAttemptResult.NonDeterministicFailure]
     *   otherwise.
     */
    fun tryApply(solution: Solution, transformationPath: String): TransformationAttemptResult {
        val typedAst = transformations[transformationPath]
            ?: throw IllegalArgumentException("Unknown transformation: $transformationPath")

        return try {
            val engine = TransformationEngine.create(
                solution.modelGraph, typedAst, deterministic = false
            )
            val result = engine.execute()

            when (result) {
                is TransformationExecutionResult.Success -> TransformationAttemptResult.Applied
                is TransformationExecutionResult.Stopped ->
                    if (result.isNormalStop) TransformationAttemptResult.Applied
                    else TransformationAttemptResult.NonDeterministicFailure
                is TransformationExecutionResult.Failure ->
                    if (result.isDeterministic) TransformationAttemptResult.DeterministicFailure
                    else TransformationAttemptResult.NonDeterministicFailure
            }
        } catch (e: Exception) {
            logger.warn("Transformation $transformationPath threw exception: ${e.message}")
            TransformationAttemptResult.NonDeterministicFailure
        }
    }
}
