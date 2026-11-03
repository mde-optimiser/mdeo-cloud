package com.mdeo.optimizer.evaluation

import java.util.UUID

/**
 * Represents a request to mutate and evaluate a specific solution on a specific worker node.
 *
 * The orchestrator creates mutation tasks during each generation and dispatches them
 * to the appropriate worker nodes. Each task identifies the parent solution to be
 * deep-copied, mutated, and evaluated.
 *
 * @param solutionId The identifier of the parent solution to mutate.
 * @param workerNodeId The identifier of the worker node where the solution resides.
 * @param taskId Identifies the task, and thereby its offspring, within a batch: its
 *   [EvaluationResult] carries it back. One parent may be the parent of several offspring of one
 *   batch, so results are matched to offspring by task, not by parent.
 */
data class MutationTask(
    val solutionId: String,
    val workerNodeId: String,
    val taskId: String = UUID.randomUUID().toString()
)

/**
 * Represents a request to make one child of a crossover group on the node of its primary parent:
 * copy the primary, apply a crossover operator with the donors as its model inputs (when
 * [attemptCrossover]), mutate the child with the mutation strategy, and evaluate it.
 *
 * Every donor must be present on [workerNodeId] for the batch: either it lives there, or it was
 * replicated there for the batch ([NodeBatch.replicas]).
 *
 * @param taskId Identifies the task and its offspring within a batch ([EvaluationResult.taskId]).
 * @param primary The primary parent: the child starts as a copy of it.
 * @param donors The other parents of the group, in cyclic order after the primary.
 * @param attemptCrossover Whether a crossover is attempted; decided on the coordinator by
 *   [com.mdeo.optimizer.config.CrossoverParameters.rate] so that a task is deterministic in what
 *   it is asked to do. When `false` the child is a plain copy (and still mutated).
 */
data class CrossoverTask(
    val taskId: String,
    val primary: WorkerSolutionRef,
    val donors: List<WorkerSolutionRef>,
    val attemptCrossover: Boolean
) {
    /** The node the task runs on: its primary's. */
    val workerNodeId: String get() = primary.nodeId
}
