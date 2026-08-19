package com.mdeo.modeltransformation.compiler.expressions

import com.mdeo.expression.ast.expressions.TypedExpression
import com.mdeo.expression.ast.expressions.TypedIdentifierExpression
import com.mdeo.modeltransformation.compiler.CompilationException
import com.mdeo.modeltransformation.compiler.CompilationContext
import com.mdeo.modeltransformation.compiler.CompilationResult
import com.mdeo.modeltransformation.compiler.ExpressionCompiler
import com.mdeo.modeltransformation.compiler.VariableBinding
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.`__` as AnonymousTraversal

/**
 * Traversal-based compiler for [TypedIdentifierExpression] nodes.
 *
 * Compiles identifier expressions into [CompilationResult] containing GraphTraversals
 * that reference variables bound in the match context or from variable scopes.
 *
 * ## Scope Resolution
 * Identifiers are resolved from the variable scope map based on the scope index
 * assigned by the TypeScript type checker. The scope index represents nesting depth:
 * - Scope 0: Global scope (built-ins)
 * - Scope 1: Top-level model transformation scope
 * - Scope 2+: Nested scopes (if-match, while-match, for-match conditions, `forbid` /
 *             `require` blocks, lambdas, etc.)
 *
 * Variable bindings can be:
 * - [VariableBinding.ValueBinding]: Produces a constant traversal
 * - [VariableBinding.InstanceBinding]: Uses `__.select()` for unresolved instances (vertexId=null)
 *                                       or `__.V(id)` for resolved instances (vertexId!=null)
 *
 * ## Important Note on Scope Indices
 * The scope index is determined by the TypeScript type checker based on AST nesting.
 * Match statements can appear at any nesting level (inside if/while/for blocks),
 * so we cannot assume any particular scope index for match variables.
 * The caller (e.g., MatchExecutor.buildCompilationContextWithTransformation) is
 * responsible for setting up the variableScopes map with bindings at all relevant
 * scope levels.
 *
 * ## Initial Traversal Handling
 * When an [initialTraversal] is provided, `select()` or `V()` is appended to it.
 * Otherwise, a new anonymous traversal is created with `__.select()` or `__.V()`.
 *
 * @see TypedIdentifierExpression
 */
class IdentifierCompiler : ExpressionCompiler {

    /**
     * Determines whether this compiler can handle the given expression.
     *
     * @param expression The expression to check
     * @return `true` if the expression is a [TypedIdentifierExpression], `false` otherwise
     */
    override fun canCompile(expression: TypedExpression): Boolean {
        return expression is TypedIdentifierExpression
    }

    /**
     * Compiles an identifier expression into a Gremlin traversal.
     *
     * This method resolves the identifier from the variable scope and compiles it
     * to the appropriate Gremlin traversal based on the type of binding (value,
     * instance, or label).
     *
     * @param expression The identifier expression to compile
     * @param context The compilation context containing variable scope information
     * @param initialTraversal Optional initial traversal to build upon
     * @return A [CompilationResult] containing the compiled identifier traversal
     * @throws CompilationException if the variable cannot be resolved in the scope
     */
    override fun compile(
        expression: TypedExpression,
        context: CompilationContext,
        initialTraversal: GraphTraversal<*, *>?
    ): CompilationResult {
        val identifierExpression = expression as TypedIdentifierExpression
        val binding = resolveVariable(identifierExpression, context)
        return compileBinding(binding, identifierExpression.name, context, initialTraversal)
    }

    /**
     * Resolves an identifier to its variable binding from the scope.
     *
     * All identifiers are resolved uniformly from the variableScopes map.
     * The caller is responsible for setting up the correct bindings at all
     * scope levels where variables should be accessible.
     *
     * @param expression The identifier expression containing the name and scope index
     * @param context The compilation context with variable scope mappings
     * @return The [VariableBinding] for the identifier
     * @throws CompilationException if the scope is not found or the variable is not in scope
     */
    private fun resolveVariable(
        expression: TypedIdentifierExpression,
        context: CompilationContext
    ): VariableBinding {
        val scope = context.getScope(expression.scope)
            ?: throw CompilationException(
                "Scope not found at index ${expression.scope}",
                expression
            )

        return scope.getVariable(expression.name)
            ?: throw CompilationException.unresolvedVariable(
                expression.name,
                expression.scope,
                expression
            )
    }

    /**
     * Compiles a variable binding into the appropriate Gremlin traversal.
     *
     * This method dispatches to the appropriate compilation method based on the
     * type of binding:
     * - [VariableBinding.ValueBinding]: Compiles to a constant traversal
     * - [VariableBinding.InstanceBinding]: Compiles to select() or V() traversal
     * - [VariableBinding.LabelBinding]: Compiles to select() with label
     *
     * @param binding The variable binding to compile
     * @param name The name of the variable (used for step labels)
     * @param context The compilation context
     * @param initialTraversal Optional initial traversal to build upon
     * @return A [CompilationResult] representing the variable reference
     */
    @Suppress("UNCHECKED_CAST")
    private fun compileBinding(
        binding: VariableBinding,
        name: String,
        context: CompilationContext,
        initialTraversal: GraphTraversal<*, *>?
    ): CompilationResult {
        return when (binding) {
            is VariableBinding.ValueBinding -> {
                CompilationResult.constant(binding.value, initialTraversal)
            }
            is VariableBinding.InstanceBinding -> {
                compileInstanceBinding(binding, name, context, initialTraversal)
            }
            is VariableBinding.LabelBinding -> {
                compileLabelBinding(binding, context, initialTraversal)
            }
        }
    }
    
    /**
     * Compiles an instance binding to a traversal that references the vertex.
     *
     * This method handles two cases:
     * - If vertexId is null: Uses select() with a step label derived from the variable name
     * - If vertexId is set: Uses __.V(vertexId) for resolved instances (after match execution)
     *
     * The step label is generated using VariableBinding.stepLabel() which currently just
     * returns the variable name as-is, but provides a centralized place for step label logic.
     *
     * Uses __.V(vertexId) to create an ANONYMOUS traversal that starts from the specific vertex.
     * This allows subsequent property access like .values("address") to work correctly.
     *
     * IMPORTANT: We always use anonymous traversals (__.V) instead of graph-bound (g.V) because:
     * 1. Anonymous traversals can be used in flatMap(), coalesce(), and other steps that expect spawned traversals
     * 2. The calling code (compilePropertyValueWithContext) uses g.inject().flatMap() to execute the result
     * 3. Using g.V() would cause "child traversal was not spawned anonymously" errors
     *
     * @param binding The instance binding containing optional vertex ID
     * @param name The variable name used to derive the step label
     * @param context The compilation context
     * @param initialTraversal Optional initial traversal to build upon
     * @return A [CompilationResult] with select() traversal for the instance
     */
    @Suppress("UNCHECKED_CAST")
    private fun compileInstanceBinding(
        binding: VariableBinding.InstanceBinding,
        name: String,
        context: CompilationContext,
        initialTraversal: GraphTraversal<*, *>?
    ): CompilationResult {
        val stepLabel = VariableBinding.stepLabel(name)
        val traversal =  if (initialTraversal != null) {
            initialTraversal.select<Any>(stepLabel) as GraphTraversal<Any, Any>
        } else {
            AnonymousTraversal.select<Any, Any>(stepLabel)
        }
        return CompilationResult.of(traversal)
    }
    
    /**
     * Compiles a label binding to a traversal that uses select() to reference the label.
     *
     * LabelBinding is used for variables declared in match blocks that are evaluated
     * as part of the match using .as(label). The label is retrieved using select().
     *
     * @param binding The LabelBinding containing the step label
     * @param context The compilation context
     * @param initialTraversal Optional initial traversal to append to
     * @return A [CompilationResult] containing the select() traversal
     */
    @Suppress("UNCHECKED_CAST")
    private fun compileLabelBinding(
        binding: VariableBinding.LabelBinding,
        context: CompilationContext,
        initialTraversal: GraphTraversal<*, *>?
    ): CompilationResult {
        val traversal = if (initialTraversal != null) {
            initialTraversal.select<Any>(binding.label) as GraphTraversal<Any, Any>
        } else {
            AnonymousTraversal.select<Any, Any>(binding.label)
        }
        return CompilationResult.of(traversal)
    }
}
