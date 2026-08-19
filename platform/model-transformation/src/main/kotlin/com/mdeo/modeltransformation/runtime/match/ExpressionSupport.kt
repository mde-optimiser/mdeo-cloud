package com.mdeo.modeltransformation.runtime.match

import com.mdeo.expression.ast.expressions.TypedExpression
import com.mdeo.expression.ast.types.ValueType
import com.mdeo.expression.ast.types.ClassTypeRef
import com.mdeo.modeltransformation.ast.patterns.TypedPatternPropertyAssignment
import com.mdeo.modeltransformation.compiler.CompilationContext
import com.mdeo.modeltransformation.compiler.CompilationResult
import com.mdeo.modeltransformation.compiler.LabelIdGenerator
import com.mdeo.modeltransformation.compiler.VariableScope
import com.mdeo.modeltransformation.compiler.expressions.EqualityCompilerUtil
import com.mdeo.modeltransformation.runtime.TransformationExecutionContext
import com.mdeo.modeltransformation.runtime.TransformationEngine
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.`__` as AnonymousTraversal
import org.apache.tinkerpop.gremlin.structure.Vertex
import org.apache.tinkerpop.gremlin.structure.VertexProperty

/** Anchor step label used throughout the match traversal. */
internal const val MATCH_ANCHOR_LABEL = "_"

/**
 * Bundles expression-compilation utilities used across the match pipeline.
 *
 * Centralises the common boilerplate of creating [CompilationContext] instances,
 * compiling expressions, and applying property equality filters — eliminating the
 * need to pass `engine`, `context`, and `labelIdGenerator` separately to every helper.
 *
 * @property scope The scope every expression is compiled in. It is the match's own scope
 *           except inside an application condition block, which is a scope of its own and
 *           compiles its constraints against a child of it — see [withScope].
 */
internal class ExpressionSupport(
    val engine: TransformationEngine,
    val context: TransformationExecutionContext,
    private val labelIdGenerator: LabelIdGenerator,
    val scope: VariableScope = context.variableScope
) {
    /** Creates a fresh [CompilationContext] backed by the shared [labelIdGenerator]. */
    fun newCompilationContext(): CompilationContext = CompilationContext(
        types = engine.types,
        currentScope = scope,
        traversalSource = engine.traversalSource,
        typeRegistry = engine.typeRegistry,
        idGenerator = labelIdGenerator
    )

    /**
     * Returns a view of this support that compiles expressions in [scope].
     *
     * The label generator is shared with the original, so labels stay unique across the
     * whole traversal even though the scopes differ.
     *
     * @param scope The scope to compile in.
     * @return A support object over the same engine and execution context.
     */
    fun withScope(scope: VariableScope): ExpressionSupport =
        ExpressionSupport(engine, context, labelIdGenerator, scope)

    /** Returns the [ValueType] for [expression], or null when index is out of range. */
    fun resolveExpressionType(expression: TypedExpression): ValueType? {
        val idx = expression.evalType
        if (idx < 0 || idx >= engine.types.size) return null
        return engine.types[idx] as? ValueType
    }

    /** Returns true when [type] is a list or set cardinality type. */
    fun isCollectionType(type: ValueType?): Boolean {
        if (type !is ClassTypeRef) return false
        val def = engine.typeRegistry.getType(type) ?: return false
        val card = def.cardinality
        return card == VertexProperty.Cardinality.list || card == VertexProperty.Cardinality.set
    }

    /**
     * Compiles [expression] and returns the [CompilationResult], or null on failure.
     *
     * @throws IllegalStateException when no compiler is registered for the expression type.
     */
    fun compilePropertyExpression(
        expression: TypedExpression,
        matchedInstanceNames: List<String>
    ): CompilationResult? {
        if (!engine.expressionCompilerRegistry.canCompile(expression)) {
            throw IllegalStateException(
                "Expression compiler not found for '${expression::class.simpleName}'. " +
                "Ensure a compiler is registered for this expression kind."
            )
        }
        return try {
            engine.expressionCompilerRegistry.compile(expression, newCompilationContext())
        } catch (e: Exception) {
            throw IllegalStateException(
                "Failed to compile expression '${expression::class.simpleName}': ${e.message}",
                e
            )
        }
    }

    /**
     * Compiles [expression] to a [GraphTraversal] using the expression compiler registry.
     *
     * @throws IllegalStateException when the expression type is not supported.
     */
    fun compileToTraversal(
        expression: TypedExpression,
        initialTraversal: GraphTraversal<*, *>? = null
    ): GraphTraversal<*, *> {
        if (!engine.expressionCompilerRegistry.canCompile(expression)) {
            throw IllegalStateException(
                "Cannot compile expression to traversal: '${expression.javaClass.simpleName}'."
            )
        }
        return engine.expressionCompilerRegistry.compile(
            expression,
            newCompilationContext(),
            initialTraversal
        ).traversal
    }

    /**
     * Resolves [expression] to a concrete value, executing the compiled traversal when needed.
     */
    @Suppress("UNCHECKED_CAST")
    fun getPropertyValue(
        expression: TypedExpression,
        matchedInstanceNames: List<String>
    ): Any? {
        val result = compilePropertyExpression(expression, matchedInstanceNames) ?: return null
        if (result is CompilationResult.ValueResult) return result.value
        val traversal = result.traversal as GraphTraversal<Any, Any>
        val injected = engine.traversalSource.inject(null as Any?).flatMap(traversal)
        return if (injected.hasNext()) injected.next() else null
    }

    /**
     * Applies `==` property equality constraints from [properties] to [traversal].
     *
     * Constant values are inlined as `.has(key, value)`; non-constant expressions use
     * [EqualityCompilerUtil] to build a comparison traversal.
     *
     * @param anchorLabel Step label used as the anchor when building expression traversals.
     */
    @Suppress("UNCHECKED_CAST")
    fun applyPropertyEqualityConstraints(
        traversal: GraphTraversal<Any, Any>,
        className: String?,
        properties: List<TypedPatternPropertyAssignment>,
        anchorLabel: String = MATCH_ANCHOR_LABEL
    ): GraphTraversal<Any, Any> {
        var result = traversal
        for (property in properties) {
            if (property.operator != "==") continue
            val graphKey = engine.resolvePropertyGraphKey(className, property.propertyName)
            val compiled = compilePropertyExpression(property.value, emptyList())
            if (compiled is CompilationResult.ValueResult) {
                result = result.has(graphKey, compiled.value) as GraphTraversal<Any, Any>
            } else if (compiled != null) {
                val propertyTraversal = AnonymousTraversal.values<Vertex, Any>(graphKey) as GraphTraversal<Any, Any>
                val expressionTraversal = compileToTraversal(
                    property.value,
                    AnonymousTraversal.`as`<Any>(anchorLabel)
                ) as GraphTraversal<Any, Any>
                val propertyType = resolveExpressionType(property.value)
                    ?: throw IllegalStateException(
                        "Cannot resolve type for property expression: ${property.propertyName}"
                    )
                val equalityTraversal = EqualityCompilerUtil.buildEqualityTraversal(
                    "==",
                    propertyTraversal,
                    expressionTraversal,
                    propertyType,
                    propertyType,
                    engine.typeRegistry,
                    labelIdGenerator.getUniqueId(),
                    labelIdGenerator.getUniqueId()
                )
                result = result.filter(equalityTraversal.`is`(true)) as GraphTraversal<Any, Any>
            }
        }
        return result
    }

    /** Builds an anonymous traversal that emits each element from a constant collection. */
    @Suppress("UNCHECKED_CAST")
    fun buildConstantCollectionTraversal(value: Any?): GraphTraversal<Any, Any> {
        val values = when (value) {
            null -> emptyList()
            is Iterable<*> -> value.toList()
            is Array<*> -> value.toList()
            else -> listOf(value)
        }
        return if (values.isEmpty()) {
            AnonymousTraversal.not<Any>(AnonymousTraversal.identity<Any>()) as GraphTraversal<Any, Any>
        } else {
            AnonymousTraversal.inject<Any>(*values.toTypedArray()) as GraphTraversal<Any, Any>
        }
    }
}
