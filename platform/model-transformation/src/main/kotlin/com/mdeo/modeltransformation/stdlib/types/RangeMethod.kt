package com.mdeo.modeltransformation.stdlib.types

import com.mdeo.expression.ast.expressions.TypedExpression
import com.mdeo.modeltransformation.compiler.CompilationContext
import com.mdeo.modeltransformation.compiler.CompilationResult
import com.mdeo.modeltransformation.compiler.ExpressionCompilerRegistry
import org.apache.tinkerpop.gremlin.process.traversal.GType
import org.apache.tinkerpop.gremlin.process.traversal.P
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.`__` as AnonymousTraversal

/**
 * Compiles `start.to(end)` of `int` and `long`: the integers from `start` to `end`, both
 * included, counting down when `end < start`, as `int` values — the script language's
 * `IntHelper.to`/`LongHelper.to`. Like every collection of the engine the result is a stream of
 * values.
 *
 * Pure Gremlin: both bounds are projected, the end is labelled, and a `repeat` steps from the
 * start towards the end with `math("_ + signum(end - _)")`, emitting every value until the end
 * is reached (`until` before `repeat`, so `start == end` yields one value). `asNumber(INT)`
 * turns `math`'s doubles back into integers.
 */
@Suppress("UNCHECKED_CAST")
internal fun compileRange(
    receiver: GraphTraversal<*, *>,
    arguments: List<TypedExpression>,
    context: CompilationContext,
    registry: ExpressionCompilerRegistry
): CompilationResult {
    val startKey = context.getUniqueId()
    val endKey = context.getUniqueId()
    val boundsLabel = context.getUniqueId()
    val endLabel = context.getUniqueId()
    val end = registry.compile(arguments.single(), context, null).traversal
    val traversal = AnonymousTraversal.project<Any, Any>(startKey, endKey)
        .by(receiver)
        .by(end)
        .`as`(boundsLabel)
        .select<Any>(endKey).`as`(endLabel)
        .select<Any>(boundsLabel).select<Any>(startKey)
        .until(AnonymousTraversal.where<Any>(P.eq(endLabel)))
        .emit()
        .repeat(AnonymousTraversal.math<Any>("_ + signum($endLabel - _)") as GraphTraversal<Any, Any>)
        .asNumber(GType.INT)
    return CompilationResult.of(traversal as GraphTraversal<Any, Any>)
}
