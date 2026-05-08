package com.mdeo.modeltransformation.runtime.match

import com.mdeo.modeltransformation.ast.patterns.TypedPattern
import com.mdeo.modeltransformation.graph.VertexRef
import com.mdeo.modeltransformation.runtime.TransformationExecutionContext

/**
 * Result of executing a pattern match operation.
 *
 * This sealed interface represents the possible outcomes of attempting to match
 * a pattern against the graph. A match can either succeed (finding one or more
 * matches) or fail to find any matching subgraph.
 */
sealed interface MatchResult {
    
    /**
     * Indicates that the pattern matched successfully.
     *
     * Contains all the bindings established by the match, including both
     * variable bindings and instance mappings. Also includes information
     * about which nodes were matched vs created.
     *
     * @param bindings Variable bindings established by the match.
     * @param instanceMappings Maps instance names to their matched/created [VertexRef]s.
     * @param matchedNodeIds Set of raw vertex IDs that were matched (not created).
     * @param createdNodeIds Set of raw vertex IDs that were created by the pattern.
     * @param deletedNodeIds Set of raw vertex IDs that were deleted by the pattern.
     * @param edgesModified Whether any edges were created or deleted by the pattern.
     */
    data class Matched(
        val bindings: Map<String, Any?> = emptyMap(),
        val instanceMappings: Map<String, VertexRef> = emptyMap(),
        val matchedNodeIds: Set<Any> = emptySet(),
        val createdNodeIds: Set<Any> = emptySet(),
        val deletedNodeIds: Set<Any> = emptySet(),
        val edgesModified: Boolean = false,
    ) : MatchResult {

        /** Whether this match result itself made any graph changes (nodes or edges). */
        val changesWereMade: Boolean get() = createdNodeIds.isNotEmpty() || deletedNodeIds.isNotEmpty() || edgesModified
        
        /**
         * Applies this match result to an execution context.
         * 
         * Updates the CURRENT scope's bindings in place:
         * - For each matched instance, updates the binding's vertexId
         * - For each variable binding, sets a ValueBinding
         *
         * This is the new behavior per the scope handling specification:
         * match does NOT create new scopes, it updates bindings in the current scope.
         *
         * @param context The context to update with the match bindings.
         * @return The same context with bindings updated in place.
         */
        fun applyTo(context: TransformationExecutionContext): TransformationExecutionContext {
            val scope = context.variableScope
            
            for ((name, vertexRef) in instanceMappings) {
                val binding = scope.getVariable(name) as? com.mdeo.modeltransformation.compiler.VariableBinding.InstanceBinding
                if (binding != null) {
                    binding.vertexRef = vertexRef
                } else {
                    scope.setBinding(name, com.mdeo.modeltransformation.compiler.VariableBinding.InstanceBinding(vertexRef))
                }
            }
            
            for ((name, value) in bindings) {
                scope.setBinding(name, com.mdeo.modeltransformation.compiler.VariableBinding.ValueBinding(value))
            }
            
            return context
        }

        /**
        * Applies this match result to a copy of the given context.
        *
        * Creates a new scope with the match bindings applied, leaving the original context unchanged.
        * This is useful for scenarios where we want to preserve the original context and work with a modified copy.
        *
        * Note: This method creates a new scope, which is different from the applyTo() method that updates in place.
        * The choice between these methods depends on whether you want to modify the existing context or work with a new one.
        * This does NOT create a child scope!
        *
        * @param context The context to copy and update with the match bindings.
        * @return A new context with the match bindings applied in a new scope.
        */
        fun applyToCopy(context: TransformationExecutionContext): TransformationExecutionContext {
            val scope = context.variableScope.copy()
            
            for ((name, vertexRef) in instanceMappings) {
                val binding = scope.getVariable(name) as? com.mdeo.modeltransformation.compiler.VariableBinding.InstanceBinding
                if (binding != null) {
                    binding.vertexRef = vertexRef
                } else {
                    scope.setBinding(name, com.mdeo.modeltransformation.compiler.VariableBinding.InstanceBinding(vertexRef))
                }
            }
            
            for ((name, value) in bindings) {
                scope.setBinding(name, com.mdeo.modeltransformation.compiler.VariableBinding.ValueBinding(value))
            }
            
            return context.withScope(scope)
        }
    }
    
    /**
     * Indicates that no match was found.
     *
     * @param reason Optional explanation of why the match failed.
     */
    data class NoMatch(
        val reason: String? = null
    ) : MatchResult
}

/**
 * Extension function to check if a match was successful.
 *
 * @return True if the result is a [MatchResult.Matched].
 */
fun MatchResult.isMatched(): Boolean = this is MatchResult.Matched

/**
 * Extension function to check if a match failed.
 *
 * @return True if the result is a [MatchResult.NoMatch].
 */
fun MatchResult.isNoMatch(): Boolean = this is MatchResult.NoMatch

/**
 * Extension function to get the Matched result or null.
 *
 * @return The [MatchResult.Matched] if successful, null otherwise.
 */
fun MatchResult.matchedOrNull(): MatchResult.Matched? = this as? MatchResult.Matched
