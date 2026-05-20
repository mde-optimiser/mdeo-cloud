package com.mdeo.modeltransformation.stdlib.types

import com.mdeo.modeltransformation.compiler.CompilationResult
import com.mdeo.modeltransformation.compiler.registry.GremlinTypeDefinition
import com.mdeo.modeltransformation.compiler.registry.gremlinType
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal

/**
 * Creates the Float type definition.
 *
 * Float methods are implemented using Gremlin's `math()` step for pure
 * traversal-based computation. Includes rounding operations suitable
 * for floating-point values.
 *
 * ## Supported Operations
 * - `abs()` - Absolute value of the float
 * - `floor()` - Rounds down to the nearest integer
 * - `ceil()` - Rounds up to the nearest integer
 * - `round()` - Rounds to the nearest integer (uses floor(x + 0.5))
 * - `log()` - Natural logarithm (base e)
 * - `log10()` - Base-10 logarithm
 * - `pow(exp)` - Raises the float to the power of exp
 * - `max(other)` - Returns the maximum of this float and other
 * - `min(other)` - Returns the minimum of this float and other
 *
 * The max and min operations are overloaded to work with int, long, float, and double types.
 *
 * @return The Float type definition for the Gremlin type registry
 */
@Suppress("UNCHECKED_CAST")
fun createFloatType(): GremlinTypeDefinition {
    return gremlinType("builtin", "float")
        .extends("builtin", "Any")
        .method("abs", "", 0) { receiver, _ ->
            val traversal = (receiver as GraphTraversal<Any, Any>).math("abs(_)")
            CompilationResult.of(traversal as GraphTraversal<Any, Any>)
        }
        .method("floor", "", 0) { receiver, _ ->
            val traversal = (receiver as GraphTraversal<Any, Any>).math("floor(_)")
            CompilationResult.of(traversal as GraphTraversal<Any, Any>)
        }
        .method("ceil", "", 0) { receiver, _ ->
            val traversal = (receiver as GraphTraversal<Any, Any>).math("ceil(_)")
            CompilationResult.of(traversal as GraphTraversal<Any, Any>)
        }
        .method("round", "", 0) { receiver, _ ->
            val traversal = (receiver as GraphTraversal<Any, Any>).math("floor(_ + 0.5)")
            CompilationResult.of(traversal as GraphTraversal<Any, Any>)
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
        .method("sqrt", "", 0) { receiver, _ ->
            val traversal = (receiver as GraphTraversal<Any, Any>).math("sqrt(_)")
            CompilationResult.of(traversal as GraphTraversal<Any, Any>)
        }
        .method("sin", "", 0) { receiver, _ ->
            val traversal = (receiver as GraphTraversal<Any, Any>).math("sin(_)")
            CompilationResult.of(traversal as GraphTraversal<Any, Any>)
        }
        .method("cos", "", 0) { receiver, _ ->
            val traversal = (receiver as GraphTraversal<Any, Any>).math("cos(_)")
            CompilationResult.of(traversal as GraphTraversal<Any, Any>)
        }
        .method("tan", "", 0) { receiver, _ ->
            val traversal = (receiver as GraphTraversal<Any, Any>).math("tan(_)")
            CompilationResult.of(traversal as GraphTraversal<Any, Any>)
        }
        .method("asin", "", 0) { receiver, _ ->
            val traversal = (receiver as GraphTraversal<Any, Any>).math("asin(_)")
            CompilationResult.of(traversal as GraphTraversal<Any, Any>)
        }
        .method("acos", "", 0) { receiver, _ ->
            val traversal = (receiver as GraphTraversal<Any, Any>).math("acos(_)")
            CompilationResult.of(traversal as GraphTraversal<Any, Any>)
        }
        .method("atan", "", 0) { receiver, _ ->
            val traversal = (receiver as GraphTraversal<Any, Any>).math("atan(_)")
            CompilationResult.of(traversal as GraphTraversal<Any, Any>)
        }
        .method("sinh", "", 0) { receiver, _ ->
            val traversal = (receiver as GraphTraversal<Any, Any>).math("sinh(_)")
            CompilationResult.of(traversal as GraphTraversal<Any, Any>)
        }
        .method("cosh", "", 0) { receiver, _ ->
            val traversal = (receiver as GraphTraversal<Any, Any>).math("cosh(_)")
            CompilationResult.of(traversal as GraphTraversal<Any, Any>)
        }
        .method("tanh", "", 0) { receiver, _ ->
            val traversal = (receiver as GraphTraversal<Any, Any>).math("tanh(_)")
            CompilationResult.of(traversal as GraphTraversal<Any, Any>)
        }
        .build()
}
