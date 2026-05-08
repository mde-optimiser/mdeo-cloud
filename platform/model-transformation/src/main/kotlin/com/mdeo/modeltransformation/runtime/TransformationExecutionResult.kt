package com.mdeo.modeltransformation.runtime

/**
 * Represents the result of executing a model transformation.
 *
 * This sealed interface provides a type-safe way to handle transformation
 * execution outcomes. A transformation can either complete successfully
 * or fail with a reason.
 */
sealed interface TransformationExecutionResult {
    
    /**
     * Indicates that the transformation completed successfully.
     *
     * @param createdNodes Set of vertex IDs that were created during execution.
     * @param deletedNodes Set of vertex IDs that were deleted during execution.
     * @param edgesModified Whether any edges were created or deleted during execution.
     */
    data class Success(
        val createdNodes: Set<Any>,
        val deletedNodes: Set<Any>,
        val edgesModified: Boolean
    ) : TransformationExecutionResult {

        /** Returns true if any graph modifications (nodes or edges) were made. */
        val changesWereMade: Boolean get() = createdNodes.isNotEmpty() || deletedNodes.isNotEmpty() || edgesModified
        
        /**
         * Creates a new Success result with additional created nodes.
         *
         * @param nodeIds The node IDs that were created.
         * @return A new Success with the additional created nodes.
         */
        fun withCreatedNodes(vararg nodeIds: Any): Success {
            return copy(createdNodes = createdNodes + nodeIds.toSet())
        }
        
        /**
         * Creates a new Success result with additional deleted nodes.
         *
         * @param nodeIds The node IDs that were deleted.
         * @return A new Success with the additional deleted nodes.
         */
        fun withDeletedNodes(vararg nodeIds: Any): Success {
            return copy(deletedNodes = deletedNodes + nodeIds.toSet())
        }
        
        /**
         * Merges this result with a nullable previous accumulator.
         *
         * `this` is treated as the new result; [acc] is whatever was accumulated before.
         * If [acc] is `null`, nothing ran before — returns `this` unchanged.
         *
         * @param acc The previously accumulated result, or `null` if this is the first result.
         * @return Combined result, or `this` if [acc] is null.
         */
        fun merge(acc: Success?): Success {
            acc ?: return this
            return Success(
                createdNodes = createdNodes + acc.createdNodes,
                deletedNodes = deletedNodes + acc.deletedNodes,
                edgesModified = edgesModified || acc.edgesModified
            )
        }

        companion object {
            /** An empty Success result representing no graph modifications. */
            fun empty(): Success = Success(emptySet(), emptySet(), false)
        }
    }
    
    /**
     * Indicates that the transformation failed.
     *
     * @param reason A human-readable description of why the transformation failed.
     * @param failedAt Optional identifier of the statement or pattern that caused the failure.
     * @param isDeterministic When `true`, the failure is deterministic — it occurred at the very
     *   first match opportunity and no graph modifications had been made yet. A deterministic
     *   failure means re-running the same transformation on the same model state will always
     *   produce the same failure, so there is no benefit in reattempting it.
     * @param changesWereMade Whether the graph was modified before the failure occurred.
     */
    data class Failure(
        val reason: String,
        val failedAt: String?,
        val isDeterministic: Boolean,
        val changesWereMade: Boolean
    ) : TransformationExecutionResult {
        
        /**
         * Creates a new Failure with additional context about where the failure occurred.
         *
         * @param location Description of where the failure occurred.
         * @return A new Failure with the location information.
         */
        fun at(location: String): Failure {
            return copy(failedAt = location)
        }

        /**
         * Merges this failure with a nullable previous accumulator.
         *
         * - [isDeterministic]: `true` only when both this failure is locally deterministic
         *   **and** [acc] is `null` (nothing ran before this statement in the sequence).
         * - [changesWereMade]: `true` if either this failure already implies changes, or the
         *   accumulated prior results made changes.
         *
         * @param acc The previously accumulated result, or `null` if this is the first result.
         * @return A new Failure with combined flags.
         */
        fun merge(acc: Success?): Failure {
            return copy(
                isDeterministic = isDeterministic && acc == null,
                changesWereMade = changesWereMade || (acc?.changesWereMade ?: false)
            )
        }
    }
    
    /**
     * Indicates that the transformation was explicitly stopped.
     *
     * This is different from success or failure - it represents an intentional
     * termination via a stop/kill statement.
     *
     * @param keyword The keyword used to stop: "stop" for normal termination,
     *                "kill" for immediate termination.
     */
    data class Stopped(
        val keyword: String
    ) : TransformationExecutionResult {
        
        /**
         * Returns true if this was a normal stop (not a kill).
         *
         * @return True if keyword equals "stop"
         */
        val isNormalStop: Boolean get() = keyword == "stop"
        
        /**
         * Returns true if this was a kill (immediate termination).
         *
         * @return True if keyword equals "kill"
         */
        val isKill: Boolean get() = keyword == "kill"
    }
}

/**
 * Extension function to check if a result is successful.
 *
 * @return True if the result is a [TransformationExecutionResult.Success].
 */
fun TransformationExecutionResult.isSuccess(): Boolean = this is TransformationExecutionResult.Success

/**
 * Extension function to check if a result is a failure.
 *
 * @return True if the result is a [TransformationExecutionResult.Failure].
 */
fun TransformationExecutionResult.isFailure(): Boolean = this is TransformationExecutionResult.Failure

/**
 * Extension function to check if execution was stopped.
 *
 * @return True if the result is a [TransformationExecutionResult.Stopped].
 */
fun TransformationExecutionResult.isStopped(): Boolean = this is TransformationExecutionResult.Stopped

/**
 * Extension function to get the Success result or null.
 *
 * @return The [TransformationExecutionResult.Success] if successful, null otherwise.
 */
fun TransformationExecutionResult.successOrNull(): TransformationExecutionResult.Success? =
    this as? TransformationExecutionResult.Success

/**
 * Extension function to get the Failure result or null.
 *
 * @return The [TransformationExecutionResult.Failure] if failed, null otherwise.
 */
fun TransformationExecutionResult.failureOrNull(): TransformationExecutionResult.Failure? =
    this as? TransformationExecutionResult.Failure
