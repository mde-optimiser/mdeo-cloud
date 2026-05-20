package com.mdeo.modeltransformation.compiler.expressions

import com.mdeo.expression.ast.expressions.TypedBinaryExpression
import com.mdeo.expression.ast.expressions.TypedExpression
import com.mdeo.modeltransformation.compiler.CompilationException
import com.mdeo.modeltransformation.compiler.CompilationContext
import com.mdeo.modeltransformation.compiler.CompilationResult
import com.mdeo.modeltransformation.compiler.ExpressionCompilerRegistry
import com.mdeo.modeltransformation.compiler.ExpressionCompiler
import org.apache.tinkerpop.gremlin.process.traversal.P
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.`__` as AnonymousTraversal

/**
 * Traversal-based compiler for [TypedBinaryExpression] nodes.
 *
 * Compiles binary expressions into [CompilationResult] containing GraphTraversals
 * that implement arithmetic, comparison, equality, and logical operators using pure Gremlin.
 *
 * ## Supported Operators
 *
 * ### Arithmetic Operators
 * - `+` - Addition (uses math step) OR String concatenation (for string operands)
 * - `-` - Subtraction (uses math step)
 * - `*` - Multiplication (uses math step)
 * - `/` - Division (uses math step)
 * - `%` - Modulo (uses math step)
 *
 * ### Comparison Operators
 * - `<` - Less than (uses P.lt)
 * - `>` - Greater than (uses P.gt)
 * - `<=` - Less than or equal (uses P.lte)
 * - `>=` - Greater than or equal (uses P.gte)
 *
 * ### Equality Operators
 * - `==` - Structural equality (uses P.eq)
 * - `!=` - Structural inequality (uses P.neq)
 *
 * ### Logical Operators
 * - `&&` - Logical AND (uses and step with choose)
 * - `||` - Logical OR (uses or step with choose)
 *
 * ## String Concatenation
 * The `+` operator has special handling for string operands. When both operands
 * are constant strings, compile-time string concatenation is performed. String
 * concatenation currently requires constant operands for both sides.
 *
 * ## Initial Traversal Propagation
 * The [initialTraversal] is passed ONLY to the leftmost sub-expression. The right
 * sub-expression always starts fresh (null initialTraversal). This ensures proper
 * traversal construction in match contexts.
 *
 * ## Portability
 * All operators are implemented using pure Gremlin (no lambdas) for maximum
 * portability across different graph databases.
 *
 * @param registry The traversal compiler registry for compiling sub-expressions
 */
class BinaryOperatorCompiler(
    private val registry: ExpressionCompilerRegistry
) : ExpressionCompiler {

    companion object {
        /**
         * Addition operator 
         */
        const val OPERATOR_ADD = "+"

        /**
         * Subtraction operator 
         */
        const val OPERATOR_SUBTRACT = "-"

        /**
         * Multiplication operator 
         */
        const val OPERATOR_MULTIPLY = "*"

        /**
         * Division operator 
         */
        const val OPERATOR_DIVIDE = "/"

        /**
         * Modulo operator 
         */
        const val OPERATOR_MODULO = "%"

        /**
         * Less than operator 
         */
        const val OPERATOR_LESS_THAN = "<"

        /**
         * Greater than operator 
         */
        const val OPERATOR_GREATER_THAN = ">"

        /**
         * Less than or equal operator 
         */
        const val OPERATOR_LESS_THAN_OR_EQUAL = "<="

        /**
         * Greater than or equal operator 
         */
        const val OPERATOR_GREATER_THAN_OR_EQUAL = ">="

        /**
         * Structural equality operator 
         */
        const val OPERATOR_EQUALS = "=="

        /**
         * Structural inequality operator 
         */
        const val OPERATOR_NOT_EQUALS = "!="

        /**
         * Logical AND operator 
         */
        const val OPERATOR_LOGICAL_AND = "&&"

        /**
         * Logical OR operator 
         */
        const val OPERATOR_LOGICAL_OR = "||"

        /**
         * Arithmetic operators that use the math step 
         */
        private val ARITHMETIC_OPERATORS = setOf(
            OPERATOR_ADD,
            OPERATOR_SUBTRACT,
            OPERATOR_MULTIPLY,
            OPERATOR_DIVIDE,
            OPERATOR_MODULO
        )

        /**
         * Comparison operators that produce boolean results 
         */
        private val COMPARISON_OPERATORS = setOf(
            OPERATOR_LESS_THAN,
            OPERATOR_GREATER_THAN,
            OPERATOR_LESS_THAN_OR_EQUAL,
            OPERATOR_GREATER_THAN_OR_EQUAL
        )

        /**
         * Equality operators 
         */
        private val EQUALITY_OPERATORS = setOf(
            OPERATOR_EQUALS,
            OPERATOR_NOT_EQUALS
        )

        /**
         * Logical operators 
         */
        private val LOGICAL_OPERATORS = setOf(
            OPERATOR_LOGICAL_AND,
            OPERATOR_LOGICAL_OR
        )

        /**
         * All supported operators 
         */
        private val ALL_OPERATORS = ARITHMETIC_OPERATORS +
            COMPARISON_OPERATORS +
            EQUALITY_OPERATORS +
            LOGICAL_OPERATORS

        /**
         * Math step operator symbols 
         */
        private val MATH_OPERATOR_SYMBOLS = mapOf(
            OPERATOR_ADD to "+",
            OPERATOR_SUBTRACT to "-",
            OPERATOR_MULTIPLY to "*",
            OPERATOR_DIVIDE to "/",
            OPERATOR_MODULO to "%"
        )
    }

    override fun canCompile(expression: TypedExpression): Boolean {
        if (expression !is TypedBinaryExpression) return false
        return expression.operator in ALL_OPERATORS
    }

    override fun compile(
        expression: TypedExpression,
        context: CompilationContext,
        initialTraversal: GraphTraversal<*, *>?
    ): CompilationResult {
        val binaryExpr = expression as TypedBinaryExpression
        val operator = binaryExpr.operator

        if (operator !in ALL_OPERATORS) {
            throw CompilationException.unsupportedOperator(operator, expression)
        }

        return when (operator) {
            in ARITHMETIC_OPERATORS -> compileArithmetic(binaryExpr, context, initialTraversal)
            in COMPARISON_OPERATORS -> compileComparison(binaryExpr, context, initialTraversal)
            in EQUALITY_OPERATORS -> compileEquality(binaryExpr, context, initialTraversal)
            in LOGICAL_OPERATORS -> compileLogical(binaryExpr, context, initialTraversal)
            else -> throw CompilationException.unsupportedOperator(operator, expression)
        }
    }

    /**
     * Compiles an arithmetic operator using Gremlin's math step.
     *
     * The math step expects labeled values, so we use unique IDs from context.getUniqueId()
     * to capture both operands, then apply the math operation. The pattern used is:
     * leftTraversal.as(leftLabel).map(rightTraversal).as(rightLabel).math("leftLabel op rightLabel")
     * 
     * Special case: For the + operator, checks if operands are strings and performs
     * string concatenation instead of numeric addition.
     *
     * @param expr The binary expression to compile
     * @param context The compilation context for generating unique IDs and type resolution
     * @param initialTraversal Optional initial traversal to build upon (passed to left operand)
     * @return The compiled traversal result performing the arithmetic operation
     */
    @Suppress("UNCHECKED_CAST")
    private fun compileArithmetic(
        expr: TypedBinaryExpression,
        context: CompilationContext,
        initialTraversal: GraphTraversal<*, *>?
    ): CompilationResult {
        if (expr.operator == OPERATOR_ADD && isStringConcatenation(expr, context)) {
            val leftResult = registry.compile(expr.left, context, initialTraversal)
            val rightResult = registry.compile(expr.right, context, null)
            return compileRuntimeStringConcatenation(leftResult, rightResult, context)
        }

        val leftResult = registry.compile(expr.left, context, null)
        val rightResult = registry.compile(expr.right, context, null)

        val mathSymbol = MATH_OPERATOR_SYMBOLS[expr.operator]
            ?: throw CompilationException.unsupportedOperator(expr.operator, expr)

        val leftLabel = context.getUniqueId()
        val rightLabel = context.getUniqueId()
        val projectTraversal = AnonymousTraversal.project<Any, Any>(leftLabel, rightLabel)
            .by(leftResult.traversal as GraphTraversal<Any, Any>)
            .by(rightResult.traversal as GraphTraversal<Any, Any>)
            .math("$leftLabel $mathSymbol $rightLabel")

        @Suppress("UNCHECKED_CAST")
        val traversal = if (initialTraversal != null) {
            (initialTraversal as GraphTraversal<Any, Any>).flatMap(projectTraversal)
        } else {
            projectTraversal
        }

        return CompilationResult.of(traversal)
    }

    /**
     * Checks if an expression has a string type using static type information.
     *
     * Resolves the expression's evalType to determine if it represents a string type.
     * Checks for both "builtin.string" and "string" type names.
     * 
     * @param expression The expression to check for string type
     * @param context The compilation context containing type information and resolution
     * @return true if the expression's static type is a string type, false otherwise
     * @throws CompilationException if the type cannot be resolved (indicates a type checker bug)
     */
    private fun isStringType(expression: TypedExpression, context: CompilationContext): Boolean {
        val type = context.resolveTypeOrNull(expression.evalType)
            ?: throw CompilationException(
                "Cannot resolve type for expression at index ${expression.evalType}. " +
                "This indicates a bug in the type checker - all expressions must have valid evalType indices.",
                expression
            )
        return type is com.mdeo.expression.ast.types.ClassTypeRef && 
            type.`package` == "builtin" && type.type == "string"
    }

    /**
     * Checks if this binary expression represents string concatenation.
     *
     * Uses static type information only - relies on the type checker having correctly
     * determined the types of all operands. Returns true if the + operator is being
     * used and either operand has a string type. This allows mixed-type concatenation
     * where one operand is a string and the other is coerced to string.
     * 
     * @param expr The binary expression to check for string concatenation
     * @param context The compilation context containing type information and resolution
     * @return true if this is a string concatenation operation (+ with string operand)
     */
    private fun isStringConcatenation(
        expr: TypedBinaryExpression,
        context: CompilationContext
    ): Boolean {
        if (expr.operator != OPERATOR_ADD) {
            return false
        }
        
        return isStringType(expr.left, context) || isStringType(expr.right, context)
    }

    /**
     * Compiles runtime string concatenation using a Gremlin lambda.
     * 
     * This is necessary because Gremlin doesn't have a built-in string concatenation step
     * that works without lambdas. While we try to avoid lambdas for portability, string
     * concatenation is common enough to warrant lambda support.
     * 
     * The pattern used:
     * 1. Store left value with unique ID label
     * 2. Store right value with unique ID label
     * 3. Use path objects to get values and concatenate them
     * 
     * Note: We access path objects by index to avoid label conflicts in nested concatenations.
     */
    @Suppress("UNCHECKED_CAST")
    private fun compileRuntimeStringConcatenation(
        leftResult: CompilationResult,
        rightResult: CompilationResult,
        context: CompilationContext
    ): CompilationResult {
        val leftLabel = context.getUniqueId()
        val rightLabel = context.getUniqueId()
        val traversal = AnonymousTraversal.project<Any, Any>(leftLabel, rightLabel)
            .by(leftResult.traversal as GraphTraversal<Any, Any>)
            .by(rightResult.traversal as GraphTraversal<Any, Any>)
            .format("%{$leftLabel}%{$rightLabel}")

        return CompilationResult.of(traversal)
    }

    /**
     * Compiles a comparison operator using Gremlin predicates and choose.
     *
     * Comparisons produce boolean results by using choose() with P predicates.
     * Uses labeled variables with math step to compute the difference and compare
     * to zero for dynamic comparisons where both operands may be traversals.
     *
     * @param expr The binary expression to compile (must be a comparison operator)
     * @param context The compilation context for generating unique IDs
     * @param initialTraversal Optional initial traversal to build upon (passed to left operand)
     * @return The compiled traversal result producing a boolean comparison result
     */
    @Suppress("UNCHECKED_CAST")
    private fun compileComparison(
        expr: TypedBinaryExpression,
        context: CompilationContext,
        initialTraversal: GraphTraversal<*, *>?
    ): CompilationResult {
        val leftResult = registry.compile(expr.left, context, null)
        val rightResult = registry.compile(expr.right, context, null)

        val traversal = buildDynamicComparisonTraversal(
            expr.operator,
            leftResult.traversal as GraphTraversal<Any, Any>,
            rightResult.traversal as GraphTraversal<Any, Any>,
            context
        )

        return CompilationResult.of(traversal)
    }

    /**
     * Compiles an equality operator using Gremlin predicates and choose.
     *
     * Uses labeled variables with where pattern for dynamic comparisons.
     * For equality, we use where(eq()) instead of math subtraction to support all types,
     * including non-numeric types like strings and vertices. Delegates to EqualityCompilerUtil
     * to handle collection type folding and proper predicate construction.
     *
     * @param expr The binary expression to compile (must be == or !=)
     * @param context The compilation context for type resolution and unique ID generation
     * @param initialTraversal Optional initial traversal to build upon (passed to left operand)
     * @return The compiled traversal result producing a boolean equality result
     * @throws CompilationException if operand types cannot be resolved
     */
    @Suppress("UNCHECKED_CAST")
    private fun compileEquality(
        expr: TypedBinaryExpression,
        context: CompilationContext,
        initialTraversal: GraphTraversal<*, *>?
    ): CompilationResult {
        val leftResult = registry.compile(expr.left, context, null)
        val rightResult = registry.compile(expr.right, context, null)

        val leftType = context.resolveTypeOrNull(expr.left.evalType)
            ?: throw CompilationException("Cannot resolve type for left operand of equality expression")
        val rightType = context.resolveTypeOrNull(expr.right.evalType)
            ?: throw CompilationException("Cannot resolve type for right operand of equality expression")

        val leftLabel = context.getUniqueId()
        val rightLabel = context.getUniqueId()
        val traversal = EqualityCompilerUtil.buildEqualityTraversal(
            expr.operator,
            leftResult.traversal as GraphTraversal<Any, Any>,
            rightResult.traversal as GraphTraversal<Any, Any>,
            leftType,
            rightType,
            context.typeRegistry,
            leftLabel,
            rightLabel
        )

        return CompilationResult.of(traversal)
    }

    /**
     * Compiles a logical operator using Gremlin and/or steps with choose.
     *
     * Logical operators are implemented using nested choose() steps that evaluate
     * operands as boolean values. Short-circuit evaluation is achieved through the
     * choose() structure where the false/true branches avoid evaluating the second operand.
     *
     * @param expr The binary expression to compile (must be && or ||)
     * @param context The compilation context
     * @param initialTraversal Optional initial traversal to build upon (passed to left operand)
     * @return The compiled traversal result producing a boolean logical result
     */
    @Suppress("UNCHECKED_CAST")
    private fun compileLogical(
        expr: TypedBinaryExpression,
        context: CompilationContext,
        initialTraversal: GraphTraversal<*, *>?
    ): CompilationResult {
        val leftResult = registry.compile(expr.left, context, initialTraversal)
        val rightResult = registry.compile(expr.right, context, null)

        val traversal = buildLogicalTraversal(
            expr.operator,
            leftResult.traversal as GraphTraversal<Any, Any>,
            rightResult.traversal as GraphTraversal<Any, Any>
        )

        return CompilationResult.of(traversal)
    }

    /**
     * Builds a traversal for dynamic comparison where the right operand is not constant.
     *
     * @param operator The comparison operator (<, >, <=, >=, ==, or !=)
     * @param leftTraversal The traversal producing the left operand value
     * @param rightTraversal The traversal producing the right operand value
     * @param context The compilation context for generating unique IDs
     * @return A traversal producing a boolean comparison result
     * @throws IllegalStateException if an unexpected operator is provided
     */
    @Suppress("UNCHECKED_CAST")
    private fun buildDynamicComparisonTraversal(
        operator: String,
        leftTraversal: GraphTraversal<Any, Any>,
        rightTraversal: GraphTraversal<Any, Any>,
        context: CompilationContext
    ): GraphTraversal<Any, Boolean> {
        
        val leftLabel = context.getUniqueId()
        val rightLabel = context.getUniqueId()

        val predicate: P<String> = when (operator) {
            OPERATOR_EQUALS -> P.eq(rightLabel)
            OPERATOR_NOT_EQUALS -> P.neq(rightLabel)
            OPERATOR_LESS_THAN -> P.lt(rightLabel)
            OPERATOR_GREATER_THAN -> P.gt(rightLabel)
            OPERATOR_LESS_THAN_OR_EQUAL -> P.lte(rightLabel)
            OPERATOR_GREATER_THAN_OR_EQUAL -> P.gte(rightLabel)
            else -> throw IllegalStateException("Unexpected comparison operator: $operator")
        }

        return AnonymousTraversal.project<Any, Any>(leftLabel, rightLabel)
            .by(leftTraversal)
            .by(rightTraversal)
            .choose(
                AnonymousTraversal.where<Any>(leftLabel, predicate),
                AnonymousTraversal.constant<Any>(true),
                AnonymousTraversal.constant<Any>(false)
            ) as GraphTraversal<Any, Boolean>
    }

    /**
     * Builds a logical AND/OR traversal using choose with nested conditionals.
     *
     * For AND (&&): Returns false if left is false, otherwise evaluates and returns right
     * For OR (||): Returns true if left is true, otherwise evaluates and returns right
     *
     * The choose() structure implements short-circuit evaluation by not evaluating
     * the right operand when the result can be determined from the left operand alone.
     *
     * @param operator The logical operator ("&&" or "||")
     * @param leftTraversal The traversal producing the left operand boolean value
     * @param rightTraversal The traversal producing the right operand boolean value
     * @return A traversal producing the logical AND/OR boolean result
     * @throws IllegalStateException if an unexpected operator is provided
     */
    private fun buildLogicalTraversal(
        operator: String,
        leftTraversal: GraphTraversal<Any, Any>,
        rightTraversal: GraphTraversal<Any, Any>
    ): GraphTraversal<Any, Boolean> {
        val leftBoolCheck = AnonymousTraversal.`is`(P.eq(true))

        return when (operator) {
            OPERATOR_LOGICAL_AND -> {
                leftTraversal.choose(
                    leftBoolCheck,
                    rightTraversal.choose(
                        AnonymousTraversal.`is`(P.eq(true)),
                        AnonymousTraversal.constant(true),
                        AnonymousTraversal.constant(false)
                    ),
                    AnonymousTraversal.constant(false)
                )
            }
            OPERATOR_LOGICAL_OR -> {
                leftTraversal.choose(
                    leftBoolCheck,
                    AnonymousTraversal.constant(true),
                    rightTraversal.choose(
                        AnonymousTraversal.`is`(P.eq(true)),
                        AnonymousTraversal.constant(true),
                        AnonymousTraversal.constant(false)
                    )
                )
            }
            else -> throw IllegalStateException("Unexpected logical operator: $operator")
        }
    }
}
