package com.mdeo.optimizer.evaluation

import kotlinx.serialization.Serializable

/**
 * Discriminates between the three possible outcomes of a mutation or evaluation task.
 *
 * Using an explicit enum (rather than a `succeeded` boolean combined with a nullable
 * `errorMessage`) makes the calling code exhaustive and eliminates the ambiguity of
 * "what does `succeeded = false, errorMessage = null` mean?".
 */
@Serializable
enum class ResultStatus {
    /** Task completed successfully; fitness values are valid. */
    SUCCESS,

    /**
     * Soft failure: the mutation strategy could not find a matching rule for the current
     * model state. The orchestrator applies penalty fitness to the offspring and continues
     * evolution — this is an expected, recoverable outcome.
     */
    SOFT_FAILURE,

    /**
     * Hard failure: an unexpected, unrecoverable error occurred (e.g. a guidance function
     * threw an exception, an incoming-solution arrival timed out, or a peer push failed).
     * The orchestrator must abort the entire optimization execution when it sees this status.
     */
    HARD_FAILURE
}
