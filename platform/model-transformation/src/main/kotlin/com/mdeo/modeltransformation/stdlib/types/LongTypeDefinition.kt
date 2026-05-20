package com.mdeo.modeltransformation.stdlib.types

import com.mdeo.modeltransformation.compiler.CompilationResult
import com.mdeo.modeltransformation.compiler.registry.GremlinTypeDefinition
import com.mdeo.modeltransformation.compiler.registry.gremlinType
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal

/**
 * Creates the Long type definition.
 *
 * Long methods are implemented using Gremlin's `math()` step for pure
 * traversal-based computation. Provides the same operations as Int but
 * for 64-bit long integer values.
 *
 * ## Supported Operations
 * - `abs()` - Absolute value of the long
 * - `floor()`, `ceil()`, `round()` - Identity operations for longs (no change)
 * - `log()` - Natural logarithm (base e)
 * - `log10()` - Base-10 logarithm
 * - `pow(exp)` - Raises the long to the power of exp
 * - `mod(divisor)` - Modulo operation (remainder after division)
 * - `max(other)` - Returns the maximum of this long and other
 * - `min(other)` - Returns the minimum of this long and other
 *
 * The max and min operations are overloaded to work with int, long, float, and double types.
 *
 * @return The Long type definition for the Gremlin type registry
 */
@Suppress("UNCHECKED_CAST")
fun createLongType(): GremlinTypeDefinition {
    return gremlinType("builtin", "long")
        .extends("builtin", "Any")
        .method("abs", "", 0) { receiver, _ ->
            val traversal = (receiver as GraphTraversal<Any, Any>).math("abs(_)")
            CompilationResult.of(traversal as GraphTraversal<Any, Any>)
        }
        .method("floor", "", 0) { receiver, _ ->
            CompilationResult.of(receiver as GraphTraversal<Any, Any>)
        }
        .method("ceil", "", 0) { receiver, _ ->
            CompilationResult.of(receiver as GraphTraversal<Any, Any>)
        }
        .method("round", "", 0) { receiver, _ ->
            CompilationResult.of(receiver as GraphTraversal<Any, Any>)
        }
        .method("log", "", 0) { receiver, _ ->
            val traversal = (receiver as GraphTraversal<Any, Any>).math("log(_)")
            CompilationResult.of(traversal as GraphTraversal<Any, Any>)
        }
        .method("log10", "", 0) { receiver, _ ->
            val traversal = (receiver as GraphTraversal<Any, Any>).math("log10(_)")
            CompilationResult.of(traversal as GraphTraversal<Any, Any>)
        }
        .method("pow", "", 1) { receiver, args ->
            val expValue = (args.firstOrNull() as? CompilationResult.ValueResult)?.value
            val expr = if (args.firstOrNull() is CompilationResult.ValueResult == true && expValue != null) {
                "_ ^ $expValue"
            } else {
                "_ ^ exp"
            }
            val traversal = (receiver as GraphTraversal<Any, Any>).math(expr)
            val result = if (args.firstOrNull() is CompilationResult.ValueResult != true && args.isNotEmpty()) {
                traversal.by(args.first().traversal)
            } else {
                traversal
            }
            CompilationResult.of(result as GraphTraversal<Any, Any>)
        }
        .method("mod", "", 1) { receiver, args ->
            val divisorValue = (args.firstOrNull() as? CompilationResult.ValueResult)?.value
            val expr = if (args.firstOrNull() is CompilationResult.ValueResult == true && divisorValue != null) {
                "_ % $divisorValue"
            } else {
                "_ % divisor"
            }
            val traversal = (receiver as GraphTraversal<Any, Any>).math(expr)
            val result = if (args.firstOrNull() is CompilationResult.ValueResult != true && args.isNotEmpty()) {
                traversal.by(args.first().traversal)
            } else {
                traversal
            }
            CompilationResult.of(result as GraphTraversal<Any, Any>)
        }
        .method("max", "builtin.int", 1) { receiver, args ->
            val otherValue = (args.firstOrNull() as? CompilationResult.ValueResult)?.value
            val expr = if (args.firstOrNull() is CompilationResult.ValueResult == true && otherValue != null) {
                "max(_, $otherValue)"
            } else {
                "max(_, other)"
            }
            val traversal = (receiver as GraphTraversal<Any, Any>).math(expr)
            val result = if (args.firstOrNull() is CompilationResult.ValueResult != true && args.isNotEmpty()) {
                traversal.by(args.first().traversal)
            } else {
                traversal
            }
            CompilationResult.of(result as GraphTraversal<Any, Any>)
        }
        .method("max", "builtin.long", 1) { receiver, args ->
            val otherValue = (args.firstOrNull() as? CompilationResult.ValueResult)?.value
            val expr = if (args.firstOrNull() is CompilationResult.ValueResult == true && otherValue != null) {
                "max(_, $otherValue)"
            } else {
                "max(_, other)"
            }
            val traversal = (receiver as GraphTraversal<Any, Any>).math(expr)
            val result = if (args.firstOrNull() is CompilationResult.ValueResult != true && args.isNotEmpty()) {
                traversal.by(args.first().traversal)
            } else {
                traversal
            }
            CompilationResult.of(result as GraphTraversal<Any, Any>)
        }
        .method("max", "builtin.float", 1) { receiver, args ->
            val otherValue = (args.firstOrNull() as? CompilationResult.ValueResult)?.value
            val expr = if (args.firstOrNull() is CompilationResult.ValueResult == true && otherValue != null) {
                "max(_, $otherValue)"
            } else {
                "max(_, other)"
            }
            val traversal = (receiver as GraphTraversal<Any, Any>).math(expr)
            val result = if (args.firstOrNull() is CompilationResult.ValueResult != true && args.isNotEmpty()) {
                traversal.by(args.first().traversal)
            } else {
                traversal
            }
            CompilationResult.of(result as GraphTraversal<Any, Any>)
        }
        .method("max", "builtin.double", 1) { receiver, args ->
            val otherValue = (args.firstOrNull() as? CompilationResult.ValueResult)?.value
            val expr = if (args.firstOrNull() is CompilationResult.ValueResult == true && otherValue != null) {
                "max(_, $otherValue)"
            } else {
                "max(_, other)"
            }
            val traversal = (receiver as GraphTraversal<Any, Any>).math(expr)
            val result = if (args.firstOrNull() is CompilationResult.ValueResult != true && args.isNotEmpty()) {
                traversal.by(args.first().traversal)
            } else {
                traversal
            }
            CompilationResult.of(result as GraphTraversal<Any, Any>)
        }
        .method("min", "builtin.int", 1) { receiver, args ->
            val otherValue = (args.firstOrNull() as? CompilationResult.ValueResult)?.value
            val expr = if (args.firstOrNull() is CompilationResult.ValueResult == true && otherValue != null) {
                "min(_, $otherValue)"
            } else {
                "min(_, other)"
            }
            val traversal = (receiver as GraphTraversal<Any, Any>).math(expr)
            val result = if (args.firstOrNull() is CompilationResult.ValueResult != true && args.isNotEmpty()) {
                traversal.by(args.first().traversal)
            } else {
                traversal
            }
            CompilationResult.of(result as GraphTraversal<Any, Any>)
        }
        .method("min", "builtin.long", 1) { receiver, args ->
            val otherValue = (args.firstOrNull() as? CompilationResult.ValueResult)?.value
            val expr = if (args.firstOrNull() is CompilationResult.ValueResult == true && otherValue != null) {
                "min(_, $otherValue)"
            } else {
                "min(_, other)"
            }
            val traversal = (receiver as GraphTraversal<Any, Any>).math(expr)
            val result = if (args.firstOrNull() is CompilationResult.ValueResult != true && args.isNotEmpty()) {
                traversal.by(args.first().traversal)
            } else {
                traversal
            }
            CompilationResult.of(result as GraphTraversal<Any, Any>)
        }
        .method("min", "builtin.float", 1) { receiver, args ->
            val otherValue = (args.firstOrNull() as? CompilationResult.ValueResult)?.value
            val expr = if (args.firstOrNull() is CompilationResult.ValueResult == true && otherValue != null) {
                "min(_, $otherValue)"
            } else {
                "min(_, other)"
            }
            val traversal = (receiver as GraphTraversal<Any, Any>).math(expr)
            val result = if (args.firstOrNull() is CompilationResult.ValueResult != true && args.isNotEmpty()) {
                traversal.by(args.first().traversal)
            } else {
                traversal
            }
            CompilationResult.of(result as GraphTraversal<Any, Any>)
        }
        .method("min", "builtin.double", 1) { receiver, args ->
            val otherValue = (args.firstOrNull() as? CompilationResult.ValueResult)?.value
            val expr = if (args.firstOrNull() is CompilationResult.ValueResult == true && otherValue != null) {
                "min(_, $otherValue)"
            } else {
                "min(_, other)"
            }
            val traversal = (receiver as GraphTraversal<Any, Any>).math(expr)
            val result = if (args.firstOrNull() is CompilationResult.ValueResult != true && args.isNotEmpty()) {
                traversal.by(args.first().traversal)
            } else {
                traversal
            }
            CompilationResult.of(result as GraphTraversal<Any, Any>)
        }
        .build()
}
