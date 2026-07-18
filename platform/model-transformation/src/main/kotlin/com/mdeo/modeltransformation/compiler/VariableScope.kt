package com.mdeo.modeltransformation.compiler

import com.mdeo.modeltransformation.graph.VertexRef

/**
 * Represents a variable binding in a scope.
 *
 * A variable binding can hold either a concrete value or a reference
 * to a graph element in the traversal.
 */
sealed interface VariableBinding {
    
    /**
     * A binding to a concrete value.
     *
     * @param value The bound value.
     */
    data class ValueBinding(
        val value: Any?
    ) : VariableBinding
    
    /**
     * A binding to a named instance in the model transformation.
     *
     * Named instances are graph vertices that can be:
     * - Unresolved (vertexRef = null): References to matched instances before execution
     * - Resolved (vertexRef != null): References to actual vertices after match execution
     *
     * When vertexRef is null, the identifier compiler uses select() with a step label.
     * When vertexRef is set, the identifier compiler uses V(vertexRef.rawId) to access the vertex.
     *
     * Using a [VertexRef] wrapper (rather than storing the raw ID directly) ensures
     * that the binding remains valid after [com.mdeo.modeltransformation.graph.ModelGraph.resetNondeterminism]
     * rebuilds the graph with new vertex IDs.
     *
     * @param vertexRef A stable reference to the vertex, or null if not yet resolved.
     */
    data class InstanceBinding(
        var vertexRef: VertexRef?
    ) : VariableBinding {
        /**
         * The current raw vertex ID, or null if this binding is not yet resolved. 
         */
        val vertexId: Any? get() = vertexRef?.rawId
    }
    
    /**
     * A binding to a label in a Gremlin match() clause.
     *
     * This is used for variables declared in match blocks that need to be referenced
     * within the same match. The variable is evaluated as part of the match (as another
     * match clause) and bound to a label using .as("label"). 
     *
     * After the match executes, LabelBinding should be replaced with ValueBinding 
     * so the variable can be used in subsequent scopes.
     *
     * @param label The Gremlin step label used in the match clause (typically prefixed with $)
     */
    data class LabelBinding(
        val label: String
    ) : VariableBinding
    
    companion object {
        /**
         * Generates a step label for an instance or variable name.
         * Returns the name as-is, providing a centralized place for step label generation.
         * This function can be modified to add prefixes/suffixes without breaking existing code.
         */
        fun stepLabel(name: String): String = name
        
        /**
         * Generates a step label for a variable name used in match clauses.
         * Uses dollar prefix to distinguish variables from instances.
         */
        fun variableLabel(name: String): String = "\$$name"
    }
}

/**
 * Represents a scope containing variable bindings with support for nested scopes.
 *
 * Variable scopes are used to track variables defined at different
 * levels of the expression tree, such as loop variables or pattern
 * match bindings. Each scope can have a parent scope, allowing for
 * proper variable resolution through the scope chain.
 *
 * This class supports both immutable operations (for backwards compatibility)
 * and mutable operations (for in-place updates during match execution).
 *
 * Scope levels:
 * - Global scope starts at 0
 * - ModelTransformation is scope level 1
 * - Match does NOT increment scope
 * - IfMatchConditionAndBlock, WhileMatchStatement, UntilMatchStatement, 
 *   ForMatchStatement, StatementsScope, LambdaExpression all increment scope by 1
 *
 * @param scopeIndex The index/level of this scope (0 = global, 1 = file, 2+ = nested)
 * @param bindings A map of variable names to their bindings declared in this scope (converted to mutable internally)
 * @param parent The parent scope, or null for the root scope
 */
class VariableScope(
    val scopeIndex: Int = 0,
    private val bindings: MutableMap<String, VariableBinding> = mutableMapOf(),
    val parent: VariableScope? = null
) {
    
    /**
     * Retrieves a variable binding by name.
     * Searches this scope first, then parent scopes recursively.
     *
     * @param name The name of the variable.
     * @return The [VariableBinding] for the variable, or null if not found.
     */
    fun getVariable(name: String): VariableBinding? {
        return bindings[name] ?: parent?.getVariable(name)
    }
    
    /**
     * Returns all bindings declared in this scope only (not parent scopes).
     *
     * @return An immutable copy of all bindings in this scope.
     */
    fun getAllLocalBindings(): Map<String, VariableBinding> {
        return bindings.toMap()
    }

    /**
     * Returns the binding for [name] declared in this scope only (not parent scopes),
     * or null if it is not declared locally.
     *
     * @param name The name of the variable.
     * @return The local [VariableBinding], or null.
     */
    fun getLocalBinding(name: String): VariableBinding? {
        return bindings[name]
    }

    /**
     * Finds the nearest scope in the parent chain (starting from this scope) that declares
     * [name] locally.
     *
     * Used by variable reassignment to locate the scope that owns a variable so the updated
     * value can be written back there rather than shadowed in an inner scope.
     *
     * @param name The name of the variable.
     * @return The [VariableScope] that declares [name], or null if no scope declares it.
     */
    fun findDeclaringScope(name: String): VariableScope? {
        return if (bindings.containsKey(name)) this else parent?.findDeclaringScope(name)
    }
    
    /**
     * Creates a new scope with an additional variable binding.
     * This is the immutable operation for backwards compatibility.
     *
     * @param name The name of the variable to add.
     * @param binding The binding for the variable.
     * @return A new [VariableScope] with the additional binding.
     */
    fun withVariable(name: String, binding: VariableBinding): VariableScope {
        val newBindings = bindings.toMutableMap()
        newBindings[name] = binding
        return VariableScope(scopeIndex = scopeIndex, bindings = newBindings, parent = parent)
    }
    
    /**
     * Sets a binding directly in this scope's bindings map.
     * This is the mutable operation for in-place updates during match execution.
     *
     * @param name The name of the variable to set.
     * @param binding The binding for the variable.
     */
    fun setBinding(name: String, binding: VariableBinding) {
        bindings[name] = binding
    }
    
    /**
     * Creates a child scope with this scope as its parent.
     *
     * @param childScopeIndex The scope index for the child scope.
     * @param childBindings Initial bindings for the child scope.
     * @return A new [VariableScope] that is a child of this scope.
     */
    fun createChild(childScopeIndex: Int, childBindings: Map<String, VariableBinding> = emptyMap()): VariableScope {
        return VariableScope(
            scopeIndex = childScopeIndex,
            bindings = childBindings.toMutableMap(),
            parent = this
        )
    }
    
    /**
     * Finds the scope at the specified scope index by traversing the parent chain.
     *
     * @param targetScopeIndex The scope index to find.
     * @return The scope at that index, or null if not found.
     */
    fun getScopeAtLevel(targetScopeIndex: Int): VariableScope? {
        return when {
            scopeIndex == targetScopeIndex -> this
            scopeIndex > targetScopeIndex -> parent?.getScopeAtLevel(targetScopeIndex)
            else -> null
        }
    }

    /**
     * Creates a shallow copy of this scope.
     *
     * The copy will have the same scope index, a new mutable map containing the same bindings,
     * and the same parent reference. This is useful when you need to preserve the current
     * state while allowing future modifications to diverge.
     *
     * @return A new [VariableScope] with the same scope index, bindings, and parent
     */
    fun copy(): VariableScope {
        return VariableScope(
            scopeIndex = this.scopeIndex,
            bindings = this.bindings.toMutableMap(),
            parent = this.parent
        )
    }
    
    companion object {
        /**
         * Creates an empty variable scope at index 0.
         *
         * @return An empty [VariableScope].
         */
        fun empty(): VariableScope = VariableScope(scopeIndex = 0)
        
        /**
         * Creates a variable scope from a map of variable names to bindings.
         *
         * @param bindings The initial variable bindings.
         * @param scopeIndex The scope index (default 0).
         * @param parent The parent scope (default null).
         * @return A new [VariableScope] with the specified bindings.
         */
        fun of(vararg bindings: Pair<String, VariableBinding>, scopeIndex: Int = 0, parent: VariableScope? = null): VariableScope {
            return VariableScope(scopeIndex = scopeIndex, bindings = bindings.toMap().toMutableMap(), parent = parent)
        }
    }
}