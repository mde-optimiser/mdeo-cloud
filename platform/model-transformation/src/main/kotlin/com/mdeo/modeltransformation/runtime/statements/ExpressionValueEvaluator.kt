package com.mdeo.modeltransformation.runtime.statements

import com.mdeo.expression.ast.expressions.TypedExpression
import com.mdeo.expression.ast.expressions.TypedIdentifierExpression
import com.mdeo.modeltransformation.compiler.CollectionValue
import com.mdeo.modeltransformation.compiler.CompilationContext
import com.mdeo.modeltransformation.compiler.CompilationResult
import com.mdeo.modeltransformation.compiler.VariableBinding
import com.mdeo.modeltransformation.runtime.TransformationEngine
import com.mdeo.modeltransformation.runtime.TransformationExecutionContext
import com.mdeo.modeltransformation.runtime.match.MatchAnalyzer
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal
import org.apache.tinkerpop.gremlin.structure.Vertex

/**
 * Evaluates an expression outside a match, to a binding: the arguments of a call and the default
 * values of a callee's inputs.
 *
 * An identifier that names a bound object yields that object's binding unchanged. Any other
 * expression is compiled and run as a traversal over the bound objects it references (as
 * [ConditionEvaluator] does for conditions); a vertex result becomes an instance binding, any
 * other result a value binding. A collection-typed expression yields a [CollectionValue] of all
 * its elements.
 *
 * @param engine The engine whose graph, types and compilers the expression uses.
 */
class ExpressionValueEvaluator(private val engine: TransformationEngine) {

    /**
     * Evaluates [expression] in [context].
     *
     * @param expression The expression.
     * @param context The context of the bound names.
     * @return The binding of the expression's value.
     */
    fun evaluate(expression: TypedExpression, context: TransformationExecutionContext): VariableBinding {
        if (expression is TypedIdentifierExpression) {
            val binding = context.variableScope.getScopeAtLevel(expression.scope)?.getVariable(expression.name)
                ?: context.variableScope.getVariable(expression.name)
            when (binding) {
                is VariableBinding.InstanceBinding -> if (binding.vertexRef != null) {
                    return VariableBinding.InstanceBinding(binding.vertexRef)
                }
                is VariableBinding.ValueBinding -> return toBinding(binding.value)
                else -> {}
            }
        }

        val analyzer = MatchAnalyzer(context.variableScope)
        analyzer.analyzeExpression(expression)
        val referenced = analyzer.getReferencedInstances()
        val instances = context.getAllInstances()

        val compilationContext = CompilationContext(
            types = engine.types,
            currentScope = context.variableScope,
            traversalSource = engine.traversalSource,
            typeRegistry = engine.typeRegistry
        )
        val result = engine.expressionCompilerRegistry.compile(expression, compilationContext)
        if (result is CompilationResult.ValueResult) {
            return VariableBinding.ValueBinding(result.value)
        }
        if (engine.isCollectionType(expression.evalType)) {
            val values = run(result.traversal, referenced, instances).unfold<Any>().toList()
            return VariableBinding.ValueBinding(
                CollectionValue.of(engine.isSetType(expression.evalType), values, engine.modelGraph)
            )
        }
        val values = run(result.traversal, referenced, instances)
        // arithmetic yields doubles; a value of an int type is an Int
        return toBinding(engine.coerceNumber(if (values.hasNext()) values.next() else null, expression.evalType))
    }

    /**
     * Runs a compiled expression with the referenced objects bound to their step labels.
     */
    @Suppress("UNCHECKED_CAST")
    private fun run(traversal: GraphTraversal<*, *>, referenced: Set<String>, instances: Map<String, Any>): GraphTraversal<Any, Any> {
        var setup: GraphTraversal<Any, Any> = engine.traversalSource.inject(1 as Any) as GraphTraversal<Any, Any>
        for (name in referenced) {
            val vertexId = instances[name] ?: continue
            setup = setup.V(vertexId).`as`(VariableBinding.stepLabel(name)) as GraphTraversal<Any, Any>
        }
        return setup.flatMap(traversal as GraphTraversal<Any, Any>)
    }

    /**
     * Turns a value into a binding: a vertex is an object, anything else a value.
     */
    private fun toBinding(value: Any?): VariableBinding =
        if (value is Vertex) {
            VariableBinding.InstanceBinding(engine.modelGraph.createVertexRef(value.id()))
        } else {
            VariableBinding.ValueBinding(value)
        }
}
