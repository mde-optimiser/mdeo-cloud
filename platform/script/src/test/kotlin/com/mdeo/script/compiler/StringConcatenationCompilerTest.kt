package com.mdeo.script.compiler

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Tests for string concatenation using the + operator.
 */
class StringConcatenationCompilerTest {
    
    private val helper = CompilerTestHelper()
    
    // ==================== String + String ====================
    
    @Test
    fun `concatenate two strings`() {
        val ast = buildTypedAst {
            val stringType = stringType()
            function(
                name = "testFunction",
                returnType = stringType,
                body = listOf(
                    returnStmt(
                        binaryExpr(
                            stringLiteral("Hello", stringType),
                            "+",
                            stringLiteral(" World", stringType),
                            stringType
                        )
                    )
                )
            )
        }
        
        val result = helper.compileAndInvoke(ast)
        assertEquals("Hello World", result)
    }
    
    @Test
    fun `concatenate empty strings`() {
        val ast = buildTypedAst {
            val stringType = stringType()
            function(
                name = "testFunction",
                returnType = stringType,
                body = listOf(
                    returnStmt(
                        binaryExpr(
                            stringLiteral("", stringType),
                            "+",
                            stringLiteral("", stringType),
                            stringType
                        )
                    )
                )
            )
        }
        
        val result = helper.compileAndInvoke(ast)
        assertEquals("", result)
    }
    
    @Test
    fun `concatenate empty with non-empty string`() {
        val ast = buildTypedAst {
            val stringType = stringType()
            function(
                name = "testFunction",
                returnType = stringType,
                body = listOf(
                    returnStmt(
                        binaryExpr(
                            stringLiteral("", stringType),
                            "+",
                            stringLiteral("Hello", stringType),
                            stringType
                        )
                    )
                )
            )
        }
        
        val result = helper.compileAndInvoke(ast)
        assertEquals("Hello", result)
    }
    
    @Test
    fun `concatenate non-empty with empty string`() {
        val ast = buildTypedAst {
            val stringType = stringType()
            function(
                name = "testFunction",
                returnType = stringType,
                body = listOf(
                    returnStmt(
                        binaryExpr(
                            stringLiteral("Hello", stringType),
                            "+",
                            stringLiteral("", stringType),
                            stringType
                        )
                    )
                )
            )
        }
        
        val result = helper.compileAndInvoke(ast)
        assertEquals("Hello", result)
    }
    
    // ==================== String + Numbers ====================
    
    @Test
    fun `concatenate string with int`() {
        val ast = buildTypedAst {
            val stringType = stringType()
            val intType = intType()
            function(
                name = "testFunction",
                returnType = stringType,
                body = listOf(
                    returnStmt(
                        binaryExpr(
                            stringLiteral("Value: ", stringType),
                            "+",
                            intLiteral(42, intType),
                            stringType
                        )
                    )
                )
            )
        }
        
        val result = helper.compileAndInvoke(ast)
        assertEquals("Value: 42", result)
    }
    
    @Test
    fun `concatenate int with string`() {
        val ast = buildTypedAst {
            val stringType = stringType()
            val intType = intType()
            function(
                name = "testFunction",
                returnType = stringType,
                body = listOf(
                    returnStmt(
                        binaryExpr(
                            intLiteral(42, intType),
                            "+",
                            stringLiteral(" is the answer", stringType),
                            stringType
                        )
                    )
                )
            )
        }
        
        val result = helper.compileAndInvoke(ast)
        assertEquals("42 is the answer", result)
    }
    
    @Test
    fun `concatenate string with long`() {
        val ast = buildTypedAst {
            val stringType = stringType()
            val longType = longType()
            function(
                name = "testFunction",
                returnType = stringType,
                body = listOf(
                    returnStmt(
                        binaryExpr(
                            stringLiteral("Large: ", stringType),
                            "+",
                            longLiteral(9_000_000_000L, longType),
                            stringType
                        )
                    )
                )
            )
        }
        
        val result = helper.compileAndInvoke(ast)
        assertEquals("Large: 9000000000", result)
    }
    
    @Test
    fun `concatenate string with float`() {
        val ast = buildTypedAst {
            val stringType = stringType()
            val floatType = floatType()
            function(
                name = "testFunction",
                returnType = stringType,
                body = listOf(
                    returnStmt(
                        binaryExpr(
                            stringLiteral("Pi: ", stringType),
                            "+",
                            floatLiteral(3.14f, floatType),
                            stringType
                        )
                    )
                )
            )
        }
        
        val result = helper.compileAndInvoke(ast)
        assertEquals("Pi: 3.14", result)
    }
    
    @Test
    fun `concatenate string with double`() {
        val ast = buildTypedAst {
            val stringType = stringType()
            val doubleType = doubleType()
            function(
                name = "testFunction",
                returnType = stringType,
                body = listOf(
                    returnStmt(
                        binaryExpr(
                            stringLiteral("Pi: ", stringType),
                            "+",
                            doubleLiteral(3.14159, doubleType),
                            stringType
                        )
                    )
                )
            )
        }
        
        val result = helper.compileAndInvoke(ast)
        assertEquals("Pi: 3.14159", result)
    }
    
    @Test
    fun `concatenate string with negative int`() {
        val ast = buildTypedAst {
            val stringType = stringType()
            val intType = intType()
            function(
                name = "testFunction",
                returnType = stringType,
                body = listOf(
                    returnStmt(
                        binaryExpr(
                            stringLiteral("Temperature: ", stringType),
                            "+",
                            intLiteral(-10, intType),
                            stringType
                        )
                    )
                )
            )
        }
        
        val result = helper.compileAndInvoke(ast)
        assertEquals("Temperature: -10", result)
    }
    
    // ==================== String + Boolean ====================
    
    @Test
    fun `concatenate string with true`() {
        val ast = buildTypedAst {
            val stringType = stringType()
            val boolType = booleanType()
            function(
                name = "testFunction",
                returnType = stringType,
                body = listOf(
                    returnStmt(
                        binaryExpr(
                            stringLiteral("Result: ", stringType),
                            "+",
                            booleanLiteral(true, boolType),
                            stringType
                        )
                    )
                )
            )
        }
        
        val result = helper.compileAndInvoke(ast)
        assertEquals("Result: true", result)
    }
    
    @Test
    fun `concatenate string with false`() {
        val ast = buildTypedAst {
            val stringType = stringType()
            val boolType = booleanType()
            function(
                name = "testFunction",
                returnType = stringType,
                body = listOf(
                    returnStmt(
                        binaryExpr(
                            stringLiteral("Result: ", stringType),
                            "+",
                            booleanLiteral(false, boolType),
                            stringType
                        )
                    )
                )
            )
        }
        
        val result = helper.compileAndInvoke(ast)
        assertEquals("Result: false", result)
    }
    
    // ==================== Chained Concatenations ====================
    
    @Test
    fun `concatenate three strings`() {
        val ast = buildTypedAst {
            val stringType = stringType()
            function(
                name = "testFunction",
                returnType = stringType,
                body = listOf(
                    returnStmt(
                        binaryExpr(
                            binaryExpr(
                                stringLiteral("Hello", stringType),
                                "+",
                                stringLiteral(" ", stringType),
                                stringType
                            ),
                            "+",
                            stringLiteral("World", stringType),
                            stringType
                        )
                    )
                )
            )
        }
        
        val result = helper.compileAndInvoke(ast)
        assertEquals("Hello World", result)
    }
    
    @Test
    fun `concatenate string with multiple types`() {
        val ast = buildTypedAst {
            val stringType = stringType()
            val intType = intType()
            function(
                name = "testFunction",
                returnType = stringType,
                body = listOf(
                    returnStmt(
                        binaryExpr(
                            binaryExpr(
                                stringLiteral("Sum: ", stringType),
                                "+",
                                intLiteral(10, intType),
                                stringType
                            ),
                            "+",
                            stringLiteral(" + 20", stringType),
                            stringType
                        )
                    )
                )
            )
        }
        
        val result = helper.compileAndInvoke(ast)
        assertEquals("Sum: 10 + 20", result)
    }
    
    // ==================== Edge Cases ====================
    
    @Test
    fun `concatenate string with zero int`() {
        val ast = buildTypedAst {
            val stringType = stringType()
            val intType = intType()
            function(
                name = "testFunction",
                returnType = stringType,
                body = listOf(
                    returnStmt(
                        binaryExpr(
                            stringLiteral("Zero: ", stringType),
                            "+",
                            intLiteral(0, intType),
                            stringType
                        )
                    )
                )
            )
        }
        
        val result = helper.compileAndInvoke(ast)
        assertEquals("Zero: 0", result)
    }
    
    @Test
    fun `concatenate string with int max value`() {
        val ast = buildTypedAst {
            val stringType = stringType()
            val intType = intType()
            function(
                name = "testFunction",
                returnType = stringType,
                body = listOf(
                    returnStmt(
                        binaryExpr(
                            stringLiteral("Max: ", stringType),
                            "+",
                            intLiteral(Int.MAX_VALUE, intType),
                            stringType
                        )
                    )
                )
            )
        }
        
        val result = helper.compileAndInvoke(ast)
        assertEquals("Max: ${Int.MAX_VALUE}", result)
    }
    
    @Test
    fun `concatenate string with int min value`() {
        val ast = buildTypedAst {
            val stringType = stringType()
            val intType = intType()
            function(
                name = "testFunction",
                returnType = stringType,
                body = listOf(
                    returnStmt(
                        binaryExpr(
                            stringLiteral("Min: ", stringType),
                            "+",
                            intLiteral(Int.MIN_VALUE, intType),
                            stringType
                        )
                    )
                )
            )
        }
        
        val result = helper.compileAndInvoke(ast)
        assertEquals("Min: ${Int.MIN_VALUE}", result)
    }
    
    @Test
    fun `concatenate string with special characters`() {
        val ast = buildTypedAst {
            val stringType = stringType()
            function(
                name = "testFunction",
                returnType = stringType,
                body = listOf(
                    returnStmt(
                        binaryExpr(
                            stringLiteral("Line1\n", stringType),
                            "+",
                            stringLiteral("Line2", stringType),
                            stringType
                        )
                    )
                )
            )
        }
        
        val result = helper.compileAndInvoke(ast)
        assertEquals("Line1\nLine2", result)
    }
    
    @Test
    fun `concatenate string with unicode`() {
        val ast = buildTypedAst {
            val stringType = stringType()
            function(
                name = "testFunction",
                returnType = stringType,
                body = listOf(
                    returnStmt(
                        binaryExpr(
                            stringLiteral("Hello ", stringType),
                            "+",
                            stringLiteral("世界 🌍", stringType),
                            stringType
                        )
                    )
                )
            )
        }
        
        val result = helper.compileAndInvoke(ast)
        assertEquals("Hello 世界 🌍", result)
    }
    
    @Test
    fun `concatenate many strings`() {
        val ast = buildTypedAst {
            val stringType = stringType()
            function(
                name = "testFunction",
                returnType = stringType,
                body = listOf(
                    returnStmt(
                        binaryExpr(
                            binaryExpr(
                                binaryExpr(
                                    binaryExpr(
                                        stringLiteral("a", stringType),
                                        "+",
                                        stringLiteral("b", stringType),
                                        stringType
                                    ),
                                    "+",
                                    stringLiteral("c", stringType),
                                    stringType
                                ),
                                "+",
                                stringLiteral("d", stringType),
                                stringType
                            ),
                            "+",
                            stringLiteral("e", stringType),
                            stringType
                        )
                    )
                )
            )
        }
        
        val result = helper.compileAndInvoke(ast)
        assertEquals("abcde", result)
    }
    
    // ==================== String + Special Float Values ====================
    
    @Test
    fun `concatenate string with float NaN`() {
        val ast = buildTypedAst {
            val stringType = stringType()
            val floatType = floatType()
            function(
                name = "testFunction",
                returnType = stringType,
                body = listOf(
                    returnStmt(
                        binaryExpr(
                            stringLiteral("Value: ", stringType),
                            "+",
                            floatLiteral(Float.NaN, floatType),
                            stringType
                        )
                    )
                )
            )
        }
        
        val result = helper.compileAndInvoke(ast)
        assertEquals("Value: NaN", result)
    }
    
    @Test
    fun `concatenate string with float positive infinity`() {
        val ast = buildTypedAst {
            val stringType = stringType()
            val floatType = floatType()
            function(
                name = "testFunction",
                returnType = stringType,
                body = listOf(
                    returnStmt(
                        binaryExpr(
                            stringLiteral("Value: ", stringType),
                            "+",
                            floatLiteral(Float.POSITIVE_INFINITY, floatType),
                            stringType
                        )
                    )
                )
            )
        }
        
        val result = helper.compileAndInvoke(ast)
        assertEquals("Value: Infinity", result)
    }
    
    @Test
    fun `concatenate string with float negative infinity`() {
        val ast = buildTypedAst {
            val stringType = stringType()
            val floatType = floatType()
            function(
                name = "testFunction",
                returnType = stringType,
                body = listOf(
                    returnStmt(
                        binaryExpr(
                            stringLiteral("Value: ", stringType),
                            "+",
                            floatLiteral(Float.NEGATIVE_INFINITY, floatType),
                            stringType
                        )
                    )
                )
            )
        }
        
        val result = helper.compileAndInvoke(ast)
        assertEquals("Value: -Infinity", result)
    }
    
    @Test
    fun `concatenate string with double NaN`() {
        val ast = buildTypedAst {
            val stringType = stringType()
            val doubleType = doubleType()
            function(
                name = "testFunction",
                returnType = stringType,
                body = listOf(
                    returnStmt(
                        binaryExpr(
                            stringLiteral("Value: ", stringType),
                            "+",
                            doubleLiteral(Double.NaN, doubleType),
                            stringType
                        )
                    )
                )
            )
        }
        
        val result = helper.compileAndInvoke(ast)
        assertEquals("Value: NaN", result)
    }
    
    @Test
    fun `concatenate string with double positive infinity`() {
        val ast = buildTypedAst {
            val stringType = stringType()
            val doubleType = doubleType()
            function(
                name = "testFunction",
                returnType = stringType,
                body = listOf(
                    returnStmt(
                        binaryExpr(
                            stringLiteral("Value: ", stringType),
                            "+",
                            doubleLiteral(Double.POSITIVE_INFINITY, doubleType),
                            stringType
                        )
                    )
                )
            )
        }
        
        val result = helper.compileAndInvoke(ast)
        assertEquals("Value: Infinity", result)
    }
    
    @Test
    fun `concatenate string with double negative infinity`() {
        val ast = buildTypedAst {
            val stringType = stringType()
            val doubleType = doubleType()
            function(
                name = "testFunction",
                returnType = stringType,
                body = listOf(
                    returnStmt(
                        binaryExpr(
                            stringLiteral("Value: ", stringType),
                            "+",
                            doubleLiteral(Double.NEGATIVE_INFINITY, doubleType),
                            stringType
                        )
                    )
                )
            )
        }
        
        val result = helper.compileAndInvoke(ast)
        assertEquals("Value: -Infinity", result)
    }
    
    @Test
    fun `concatenate string with float negative zero`() {
        val ast = buildTypedAst {
            val stringType = stringType()
            val floatType = floatType()
            function(
                name = "testFunction",
                returnType = stringType,
                body = listOf(
                    returnStmt(
                        binaryExpr(
                            stringLiteral("Value: ", stringType),
                            "+",
                            floatLiteral(-0.0f, floatType),
                            stringType
                        )
                    )
                )
            )
        }
        
        val result = helper.compileAndInvoke(ast)
        // Java's Float.toString(-0.0f) returns "-0.0"
        assertEquals("Value: -0.0", result)
    }
    
    @Test
    fun `concatenate string with double negative zero`() {
        val ast = buildTypedAst {
            val stringType = stringType()
            val doubleType = doubleType()
            function(
                name = "testFunction",
                returnType = stringType,
                body = listOf(
                    returnStmt(
                        binaryExpr(
                            stringLiteral("Value: ", stringType),
                            "+",
                            doubleLiteral(-0.0, doubleType),
                            stringType
                        )
                    )
                )
            )
        }
        
        val result = helper.compileAndInvoke(ast)
        // Java's Double.toString(-0.0) returns "-0.0"
        assertEquals("Value: -0.0", result)
    }

    /**
     * ```
     * fun testFunction(): string {
     *     val d: double? = 1.5
     *     val n: double? = null
     *     val l: long? = 3L
     *     return "" + d + n + l
     * }
     * ```
     *
     * A nullable number is a boxed object and used to be appended as a primitive, which failed
     * bytecode verification.
     */
    @Test
    fun `concatenate string with nullable numbers`() {
        val ast = buildTypedAst {
            val stringType = stringType()
            val nullableDouble = doubleNullableType()
            val nullableLong = longNullableType()
            function(
                name = "testFunction",
                returnType = stringType,
                body = listOf(
                    varDecl("d", nullableDouble, doubleLiteral(1.5, doubleType())),
                    varDecl("n", nullableDouble, nullLiteral(anyNullableType())),
                    varDecl("l", nullableLong, longLiteral(3L, longType())),
                    returnStmt(
                        concat(
                            stringType,
                            stringLiteral("", stringType),
                            identifier("d", nullableDouble, 3),
                            identifier("n", nullableDouble, 3),
                            identifier("l", nullableLong, 3)
                        )
                    )
                )
            )
        }

        val result = helper.compileAndInvoke(ast)
        assertEquals("1.5null3", result)
    }
}
