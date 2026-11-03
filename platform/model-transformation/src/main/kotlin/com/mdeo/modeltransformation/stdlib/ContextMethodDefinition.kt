package com.mdeo.modeltransformation.stdlib

import com.mdeo.expression.ast.expressions.TypedExpression
import com.mdeo.expression.ast.types.ClassTypeRef
import com.mdeo.expression.ast.types.ValueType
import com.mdeo.modeltransformation.compiler.CompilationContext
import com.mdeo.modeltransformation.compiler.CompilationResult
import com.mdeo.modeltransformation.compiler.ExpressionCompilerRegistry
import com.mdeo.modeltransformation.compiler.expressions.EqualityCompilerUtil
import com.mdeo.modeltransformation.compiler.registry.GremlinMethodDefinition
import com.mdeo.modeltransformation.compiler.registry.GremlinTypeDefinitionBuilder
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.`__` as AnonymousTraversal

/**
 * A method whose compilation needs the argument expressions and the compilation context, not
 * only the compiled arguments: for unique step labels, or to compare elements with the
 * argument with the semantics of `==` ([equalsTraversal]).
 *
 * Like every method of the engine it compiles to plain Gremlin steps, without lambda steps.
 */
interface ContextMethodDefinition : GremlinMethodDefinition {
    /**
     * Compiles the method call.
     *
     * @param receiver The receiver traversal that this method is called on.
     * @param arguments The argument expressions, uncompiled.
     * @param context The compilation context.
     * @param compilerRegistry The registry that compiles the arguments.
     * @return The compiled Gremlin traversal result.
     */
    fun compileWithContext(
        receiver: GraphTraversal<*, *>,
        arguments: List<TypedExpression>,
        context: CompilationContext,
        compilerRegistry: ExpressionCompilerRegistry
    ): CompilationResult
}

/**
 * Adds a [ContextMethodDefinition] to this type; it matches calls with any argument types.
 */
fun GremlinTypeDefinitionBuilder.contextMethod(
    name: String,
    parameterCount: Int,
    compiler: (
        receiver: GraphTraversal<*, *>,
        arguments: List<TypedExpression>,
        context: CompilationContext,
        compilerRegistry: ExpressionCompilerRegistry
    ) -> CompilationResult
): GremlinTypeDefinitionBuilder {
    return method(object : ContextMethodDefinition {
        override val name = name
        override val overloadKey = ""
        override val parameterCount = parameterCount

        override fun compileWithContext(
            receiver: GraphTraversal<*, *>,
            arguments: List<TypedExpression>,
            context: CompilationContext,
            compilerRegistry: ExpressionCompilerRegistry
        ): CompilationResult = compiler(receiver, arguments, context, compilerRegistry)

        override fun compile(
            receiver: GraphTraversal<*, *>,
            arguments: List<CompilationResult>
        ): CompilationResult {
            throw UnsupportedOperationException("Method '$name' must be compiled via compileWithContext()")
        }
    })
}

/**
 * A traversal that maps the current traverser — an element of a collection — to whether it
 * equals [item], with the semantics of the `==` operator ([EqualityCompilerUtil]).
 *
 * @param item The argument expression the elements are compared with.
 */
@Suppress("UNCHECKED_CAST")
internal fun equalsTraversal(
    item: TypedExpression,
    context: CompilationContext,
    compilerRegistry: ExpressionCompilerRegistry
): GraphTraversal<Any, Boolean> {
    val itemTraversal = compilerRegistry.compile(item, context, null).traversal as GraphTraversal<Any, Any>
    val itemType: ValueType = context.resolveTypeOrNull(item.evalType) ?: ClassTypeRef("builtin", "Any", true)
    return EqualityCompilerUtil.buildEqualityTraversal(
        "==",
        AnonymousTraversal.identity<Any>() as GraphTraversal<Any, Any>,
        itemTraversal,
        itemType,
        itemType,
        context.typeRegistry,
        context.getUniqueId(),
        context.getUniqueId()
    )
}
