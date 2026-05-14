package com.mdeo.modeltransformation.stdlib.types

import com.mdeo.modeltransformation.compiler.CompilationResult
import com.mdeo.modeltransformation.compiler.VariableBinding
import com.mdeo.modeltransformation.compiler.registry.GremlinTypeDefinition
import com.mdeo.modeltransformation.compiler.registry.gremlinType
import com.mdeo.modeltransformation.stdlib.lambdaMethod
import com.mdeo.modeltransformation.stdlib.withLambdaParam
import org.apache.tinkerpop.gremlin.process.traversal.P
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.`__` as AnonymousTraversal
import org.apache.tinkerpop.gremlin.structure.VertexProperty

/**
 * Creates the Collection type definition.
 *
 * This is the base collection type with all methods implemented as pure
 * Gremlin traversals. Lambda methods are defined inline using the lambdaMethod builder.
 *
 * ## Non-Lambda Methods
 * - `size()` - Returns the number of elements in the collection
 * - `isEmpty()` - Returns true if the collection has no elements
 * - `notEmpty()` - Returns true if the collection has at least one element
 * - `sum()` - Returns the sum of all numeric elements
 * - `first()` - Returns the first element
 * - `last()` - Returns the last element
 *
 * ## Lambda Methods
 * - `filter(predicate)` - Returns elements that satisfy the predicate
 * - `map(transform)` - Transforms each element using the given function
 * - `exists(predicate)` - Returns true if any element satisfies the predicate
 * - `any(predicate)` - Alias for exists
 * - `all(predicate)` - Returns true if all elements satisfy the predicate
 * - `none(predicate)` - Returns true if no elements satisfy the predicate
 * - `one(predicate)` - Returns true if exactly one element satisfies the predicate
 * - `find(predicate)` - Returns the first element that satisfies the predicate
 * - `reject(predicate)` - Returns elements that do not satisfy the predicate (inverse of filter)
 *
 * All predicate-based methods use `.is(true)` to convert boolean results to filter semantics,
 * since Gremlin's filter() passes elements when the traversal produces ANY value (not just true).
 *
 * Collection methods return multiple values (stream semantics) rather than folded lists,
 * allowing natural chaining and easier testing.
 *
 * @return The Collection type definition for the Gremlin type registry
 */
@Suppress("UNCHECKED_CAST")
fun createCollectionType(): GremlinTypeDefinition {
    return gremlinType("builtin", "Collection")
        .extends("builtin", "Any")
        .cardinality(VertexProperty.Cardinality.list)
        .method("size", "", 0) { receiver, _ ->
            val traversal = (receiver as GraphTraversal<Any, Any>).unfold<Any>().count()
            CompilationResult.of(traversal as GraphTraversal<Any, Any>)
        }
        .method("isEmpty", "", 0) { receiver, _ ->
            val traversal = (receiver as GraphTraversal<Any, Any>).unfold<Any>().count().`is`(0L)
            CompilationResult.of(traversal as GraphTraversal<Any, Any>)
        }
        .method("notEmpty", "", 0) { receiver, _ ->
            val traversal = (receiver as GraphTraversal<Any, Any>).unfold<Any>().count().`is`(P.gt(0L))
            CompilationResult.of(traversal as GraphTraversal<Any, Any>)
        }
        .method("sum", "", 0) { receiver, _ ->
            val traversal = (receiver as GraphTraversal<Any, Any>).unfold<Any>().fold()
                .coalesce(
                    AnonymousTraversal.unfold<Any>().sum<Number>(),
                    AnonymousTraversal.constant(0.0)
                )
            CompilationResult.of(traversal as GraphTraversal<Any, Any>)
        }
        .method("first", "", 0) { receiver, _ ->
            val traversal = (receiver as GraphTraversal<Any, Any>).unfold<Any>().limit(1)
            CompilationResult.of(traversal as GraphTraversal<Any, Any>)
        }
        .method("last", "", 0) { receiver, _ ->
            val traversal = (receiver as GraphTraversal<Any, Any>).unfold<Any>().tail(1)
            CompilationResult.of(traversal as GraphTraversal<Any, Any>)
        }
        .lambdaMethod("filter") { receiver, lambda, context, registry ->
            val unfolded = (receiver as GraphTraversal<Any, Any>).unfold<Any>()
            val (lambdaContext, unfoldedWithLabel) = if (lambda.parameters.isNotEmpty()) {
                val paramName = lambda.parameters.first()
                val stepLabel = context.getUniqueId()
                val lambdaCtx = context.withLambdaParam(paramName, stepLabel)
                val unfoldedLabeled = unfolded.`as`(stepLabel)
                lambdaCtx to unfoldedLabeled
            } else {
                context to unfolded
            }
            val predResult = registry.compile(lambda.body, lambdaContext, null)
            val filtered = unfoldedWithLabel.filter((predResult.traversal as GraphTraversal<Any, Any>).`is`(true))
            CompilationResult.of(filtered as GraphTraversal<Any, Any>)
        }
        .lambdaMethod("map") { receiver, lambda, context, registry ->
            val unfolded = (receiver as GraphTraversal<Any, Any>).unfold<Any>()
            val (lambdaContext, unfoldedWithLabel) = if (lambda.parameters.isNotEmpty()) {
                val paramName = lambda.parameters.first()
                val stepLabel = context.getUniqueId()
                val lambdaCtx = context.withLambdaParam(paramName, stepLabel)
                val unfoldedLabeled = unfolded.`as`(stepLabel)
                lambdaCtx to unfoldedLabeled
            } else {
                context to unfolded
            }
            val mapResult = registry.compile(lambda.body, lambdaContext, null)
            val mapped = unfoldedWithLabel.map(mapResult.traversal)
            CompilationResult.of(mapped as GraphTraversal<Any, Any>)
        }
        .lambdaMethod("exists") { receiver, lambda, context, registry ->
            val unfolded = (receiver as GraphTraversal<Any, Any>).unfold<Any>()
            val (lambdaContext, unfoldedWithLabel) = if (lambda.parameters.isNotEmpty()) {
                val paramName = lambda.parameters.first()
                val stepLabel = context.getUniqueId()
                val lambdaCtx = context.withLambdaParam(paramName, stepLabel)
                val unfoldedLabeled = unfolded.`as`(stepLabel)
                lambdaCtx to unfoldedLabeled
            } else {
                context to unfolded
            }
            val predResult = registry.compile(lambda.body, lambdaContext, null)
            val filtered = unfoldedWithLabel.filter((predResult.traversal as GraphTraversal<Any, Any>).`is`(true))
            val result = (filtered.count() as GraphTraversal<Any, Long>).choose(
                AnonymousTraversal.`is`(P.gt(0L)),
                AnonymousTraversal.constant<Any>(true),
                AnonymousTraversal.constant<Any>(false)
            )
            CompilationResult.of(result as GraphTraversal<Any, Any>)
        }
        .lambdaMethod("all") { receiver, lambda, context, registry ->
            val unfolded = (receiver as GraphTraversal<Any, Any>).unfold<Any>()
            val (lambdaContext, unfoldedWithLabel) = if (lambda.parameters.isNotEmpty()) {
                val paramName = lambda.parameters.first()
                val stepLabel = context.getUniqueId()
                val lambdaCtx = context.withLambdaParam(paramName, stepLabel)
                val unfoldedLabeled = unfolded.`as`(stepLabel)
                lambdaCtx to unfoldedLabeled
            } else {
                context to unfolded
            }
            val predResult = registry.compile(lambda.body, lambdaContext, null)
            val notMatching = unfoldedWithLabel.not((predResult.traversal as GraphTraversal<Any, Any>).`is`(true))
            val result = (notMatching.count() as GraphTraversal<Any, Long>).choose(
                AnonymousTraversal.`is`(P.eq(0L)),
                AnonymousTraversal.constant<Any>(true),
                AnonymousTraversal.constant<Any>(false)
            )
            CompilationResult.of(result as GraphTraversal<Any, Any>)
        }
        .lambdaMethod("none") { receiver, lambda, context, registry ->
            val unfolded = (receiver as GraphTraversal<Any, Any>).unfold<Any>()
            val (lambdaContext, unfoldedWithLabel) = if (lambda.parameters.isNotEmpty()) {
                val paramName = lambda.parameters.first()
                val stepLabel = context.getUniqueId()
                val lambdaCtx = context.withLambdaParam(paramName, stepLabel)
                val unfoldedLabeled = unfolded.`as`(stepLabel)
                lambdaCtx to unfoldedLabeled
            } else {
                context to unfolded
            }
            val predResult = registry.compile(lambda.body, lambdaContext, null)
            val filtered = unfoldedWithLabel.filter((predResult.traversal as GraphTraversal<Any, Any>).`is`(true))
            val result = (filtered.count() as GraphTraversal<Any, Long>).choose(
                AnonymousTraversal.`is`(P.eq(0L)),
                AnonymousTraversal.constant<Any>(true),
                AnonymousTraversal.constant<Any>(false)
            )
            CompilationResult.of(result as GraphTraversal<Any, Any>)
        }
        .lambdaMethod("one") { receiver, lambda, context, registry ->
            val unfolded = (receiver as GraphTraversal<Any, Any>).unfold<Any>()
            val (lambdaContext, unfoldedWithLabel) = if (lambda.parameters.isNotEmpty()) {
                val paramName = lambda.parameters.first()
                val stepLabel = context.getUniqueId()
                val lambdaCtx = context.withLambdaParam(paramName, stepLabel)
                val unfoldedLabeled = unfolded.`as`(stepLabel)
                lambdaCtx to unfoldedLabeled
            } else {
                context to unfolded
            }
            val predResult = registry.compile(lambda.body, lambdaContext, null)
            val filtered = unfoldedWithLabel.filter((predResult.traversal as GraphTraversal<Any, Any>).`is`(true))
            val result = (filtered.count() as GraphTraversal<Any, Long>).choose(
                AnonymousTraversal.`is`(P.eq(1L)),
                AnonymousTraversal.constant<Any>(true),
                AnonymousTraversal.constant<Any>(false)
            )
            CompilationResult.of(result as GraphTraversal<Any, Any>)
        }
        .lambdaMethod("find") { receiver, lambda, context, registry ->
            val unfolded = (receiver as GraphTraversal<Any, Any>).unfold<Any>()
            val (lambdaContext, unfoldedWithLabel) = if (lambda.parameters.isNotEmpty()) {
                val paramName = lambda.parameters.first()
                val stepLabel = context.getUniqueId()
                val lambdaCtx = context.withLambdaParam(paramName, stepLabel)
                val unfoldedLabeled = unfolded.`as`(stepLabel)
                lambdaCtx to unfoldedLabeled
            } else {
                context to unfolded
            }
            val predResult = registry.compile(lambda.body, lambdaContext, null)
            val filtered = unfoldedWithLabel.filter((predResult.traversal as GraphTraversal<Any, Any>).`is`(true))
            CompilationResult.of(filtered.limit(1) as GraphTraversal<Any, Any>)
        }
        .lambdaMethod("reject") { receiver, lambda, context, registry ->
            val unfolded = (receiver as GraphTraversal<Any, Any>).unfold<Any>()
            val (lambdaContext, unfoldedWithLabel) = if (lambda.parameters.isNotEmpty()) {
                val paramName = lambda.parameters.first()
                val stepLabel = context.getUniqueId()
                val lambdaCtx = context.withLambdaParam(paramName, stepLabel)
                val unfoldedLabeled = unfolded.`as`(stepLabel)
                lambdaCtx to unfoldedLabeled
            } else {
                context to unfolded
            }
            val predResult = registry.compile(lambda.body, lambdaContext, null)
            val rejected = unfoldedWithLabel.not((predResult.traversal as GraphTraversal<Any, Any>).`is`(true))
            CompilationResult.of(rejected as GraphTraversal<Any, Any>)
        }
        .build()
}
