package com.mdeo.optimizer.evaluation

/**
 * Represents the result of a mutation and evaluation operation performed by a worker node.
 *
 * After a worker deep-copies a parent solution, applies a mutation, and evaluates
 * fitness, it returns this result containing both the lineage information and the
 * computed objective/constraint values.
 *
 * @param parentSolutionId The identifier of the parent solution that was mutated.
 * @param newSolutionId The identifier assigned to the newly created mutated solution.
 * @param workerNodeId The identifier of the worker node that performed the operation.
 * @param objectives The computed objective function values for the new solution.
 * @param constraints The computed constraint function values for the new solution.
 * @param status The outcome of the task; see [ResultStatus] for semantics of each value.
 * @param executedTransformations Number of transformation operators that were actually tried
 *   during mutation (successful applications + failed attempts, excluding pre-skipped ones).
 *   Zero for [ResultStatus.SOFT_FAILURE] mutations where the strategy threw before any
 *   attempt, or for evaluation-only tasks.
 * @param skippedOperatorSlots Number of operator slots that were pre-skipped because they are
 *   known to deterministically fail on the parent's current model state.
 * @param errorMessage Human-readable diagnostic detail accompanying a [ResultStatus.HARD_FAILURE].
 *   `null` for [ResultStatus.SUCCESS] and [ResultStatus.SOFT_FAILURE].
 */
data class EvaluationResult(
    val parentSolutionId: String,
    val newSolutionId: String,
    val workerNodeId: String,
    val objectives: List<Double>,
    val constraints: List<Double>,
    val status: ResultStatus,
    val executedTransformations: Int = 0,
    val skippedOperatorSlots: Int = 0,
    val errorMessage: String? = null
)
