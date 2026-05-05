package com.mdeo.modeltransformation.runtime

import com.mdeo.modeltransformation.runtime.match.MatchResult

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
     */
    data class Success(
        val createdNodes: Set<Any> = emptySet(),
        val deletedNodes: Set<Any> = emptySet()
    ) : TransformationExecutionResult {
        
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
         * Merges another Success result into this one.
         *
         * All node sets are combined.
         *
         * @param other The other Success result to merge.
         * @return A new Success with merged results.
         */
        fun merge(other: Success): Success {
            return Success(
                createdNodes = createdNodes + other.createdNodes,
                deletedNodes = deletedNodes + other.deletedNodes
            )
        }

        /**
         * Merges a MatchResult.Matched into this Success result.
         *
         * All node sets are combined.
         *
         * @param other The MatchResult.Matched to merge.
         * @return A new Success with merged results.
         */
        fun merge(other: MatchResult.Matched): Success {
            return Success(
                createdNodes = createdNodes + other.createdNodeIds,
                deletedNodes = deletedNodes + other.deletedNodeIds
            )
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
     */
    data class Failure(
        val reason: String,
        val failedAt: String? = null,
        val isDeterministic: Boolean = false
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
