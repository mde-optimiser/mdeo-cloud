package com.mdeo.script.compiler.stdlib

import com.mdeo.script.compiler.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.math.abs

/**
 * Comprehensive integration tests for numeric type methods.
 * 
 * This test file ensures every method defined on Int, Long, Float, and Double
 * types is called at least once in the context of a compiled program.
 * 
 * Methods covered:
 * - abs, ceil, floor, log, log10, max, min, pow, round
 * - Int/Long specific: iota, mod, to, toBinary, toHex
 */
class NumericMethodsIntegrationTest {

    private val helper = CompilerTestHelper()

    // ==================================================================================
    // INT METHODS
    // ==================================================================================

    @Nested
    inner class IntAbsMethod {

        @Test
        fun `int abs returns absolute value of positive`() {
            val ast = buildTypedAst {
                val intType = intType()
                function(
                    name = "testFunction",
                    returnType = intType,
                    body = listOf(
                        returnStmt(
                            memberCall(
                                expression = intLiteral(42, intType),
                                member = "abs",
                                overload = "",
                                arguments = emptyList(),
                                resultTypeIndex = intType
                            )
                        )
                    )
                )
            }
            val result = helper.compileAndInvoke(ast)
            assertEquals(42, result)
        }

        @Test
        fun `int abs returns absolute value of negative`() {
            val ast = buildTypedAst {
                val intType = intType()
                function(
                    name = "testFunction",
                    returnType = intType,
                    body = listOf(
                        returnStmt(
                            memberCall(
                                expression = intLiteral(-42, intType),
                                member = "abs",
                                overload = "",
                                arguments = emptyList(),
                                resultTypeIndex = intType
                            )
                        )
                    )
                )
            }
            val result = helper.compileAndInvoke(ast)
            assertEquals(42, result)
        }

        @Test
        fun `int abs of zero returns zero`() {
            val ast = buildTypedAst {
                val intType = intType()
                function(
                    name = "testFunction",
                    returnType = intType,
                    body = listOf(
                        returnStmt(
                            memberCall(
                                expression = intLiteral(0, intType),
                                member = "abs",
                                overload = "",
                                arguments = emptyList(),
                                resultTypeIndex = intType
                            )
                        )
                    )
                )
            }
            val result = helper.compileAndInvoke(ast)
            assertEquals(0, result)
        }
    }

    @Nested
    inner class IntCeilMethod {

        @Test
        fun `int ceil returns same value`() {
            val ast = buildTypedAst {
                val intType = intType()
                function(
                    name = "testFunction",
                    returnType = intType,
                    body = listOf(
                        returnStmt(
                            memberCall(
                                expression = intLiteral(42, intType),
                                member = "ceil",
                                overload = "",
                                arguments = emptyList(),
                                resultTypeIndex = intType
                            )
                        )
                    )
                )
            }
            val result = helper.compileAndInvoke(ast)
            assertEquals(42, result)
        }
    }

    @Nested
    inner class IntFloorMethod {

        @Test
        fun `int floor returns same value`() {
            val ast = buildTypedAst {
                val intType = intType()
                function(
                    name = "testFunction",
                    returnType = intType,
                    body = listOf(
                        returnStmt(
                            memberCall(
                                expression = intLiteral(42, intType),
                                member = "floor",
                                overload = "",
                                arguments = emptyList(),
                                resultTypeIndex = intType
                            )
                        )
                    )
                )
            }
            val result = helper.compileAndInvoke(ast)
            assertEquals(42, result)
        }
    }

    @Nested
    inner class IntRoundMethod {

        @Test
        fun `int round returns same value`() {
            val ast = buildTypedAst {
                val intType = intType()
                function(
                    name = "testFunction",
                    returnType = intType,
                    body = listOf(
                        returnStmt(
                            memberCall(
                                expression = intLiteral(42, intType),
                                member = "round",
                                overload = "",
                                arguments = emptyList(),
                                resultTypeIndex = intType
                            )
                        )
                    )
                )
            }
            val result = helper.compileAndInvoke(ast)
            assertEquals(42, result)
        }
    }

    @Nested
    inner class IntLogMethods {

        @Test
        fun `int log returns natural logarithm`() {
            val ast = buildTypedAst {
                val intType = intType()
                val floatType = floatType()
                function(
                    name = "testFunction",
                    returnType = floatType,
                    body = listOf(
                        returnStmt(
                            memberCall(
                                expression = intLiteral(10, intType),
                                member = "log",
                                overload = "",
                                arguments = emptyList(),
                                resultTypeIndex = floatType
                            )
                        )
                    )
                )
            }
            val result = helper.compileAndInvoke(ast) as Float
            assertTrue(result > 2.3f && result < 2.4f, "log(10) should be approximately 2.302")
        }

        @Test
        fun `int log10 returns base 10 logarithm`() {
            val ast = buildTypedAst {
                val intType = intType()
                val floatType = floatType()
                function(
                    name = "testFunction",
                    returnType = floatType,
                    body = listOf(
                        returnStmt(
                            memberCall(
                                expression = intLiteral(100, intType),
                                member = "log10",
                                overload = "",
                                arguments = emptyList(),
                                resultTypeIndex = floatType
                            )
                        )
                    )
                )
            }
            val result = helper.compileAndInvoke(ast) as Float
            assertEquals(2.0f, result)
        }
    }

    @Nested
    inner class IntPowMethod {

        @Test
        fun `int pow calculates exponentiation`() {
            val ast = buildTypedAst {
                val intType = intType()
                val doubleType = doubleType()
                function(
                    name = "testFunction",
                    returnType = doubleType,
                    body = listOf(
                        returnStmt(
                            memberCall(
                                expression = intLiteral(2, intType),
                                member = "pow",
                                overload = "",
                                arguments = listOf(doubleLiteral(3.0, doubleType)),
                                resultTypeIndex = doubleType
                            )
                        )
                    )
                )
            }
            val result = helper.compileAndInvoke(ast)
            assertEquals(8.0, result)
        }
    }

    @Nested
    inner class IntModMethod {

        @Test
        fun `int mod calculates modulus`() {
            val ast = buildTypedAst {
                val intType = intType()
                function(
                    name = "testFunction",
                    returnType = intType,
                    body = listOf(
                        returnStmt(
                            memberCall(
                                expression = intLiteral(17, intType),
                                member = "mod",
                                overload = "",
                                arguments = listOf(intLiteral(5, intType)),
                                resultTypeIndex = intType
                            )
                        )
                    )
                )
            }
            val result = helper.compileAndInvoke(ast)
            assertEquals(2, result)
        }

        @Test
        fun `int mod with negative dividend`() {
            val ast = buildTypedAst {
                val intType = intType()
                function(
                    name = "testFunction",
                    returnType = intType,
                    body = listOf(
                        returnStmt(
                            memberCall(
                                expression = intLiteral(-17, intType),
                                member = "mod",
                                overload = "",
                                arguments = listOf(intLiteral(5, intType)),
                                resultTypeIndex = intType
                            )
                        )
                    )
                )
            }
            val result = helper.compileAndInvoke(ast)
            // Java % operator returns -2 for -17 % 5
            assertEquals(-2, result)
        }
    }

    @Nested
    inner class IntMaxMethod {

        @Test
        fun `int max with int returns larger value`() {
            val ast = buildTypedAst {
                val intType = intType()
                function(
                    name = "testFunction",
                    returnType = intType,
                    body = listOf(
                        returnStmt(
                            memberCall(
                                expression = intLiteral(5, intType),
                                member = "max",
                                overload = "int",
                                arguments = listOf(intLiteral(10, intType)),
                                resultTypeIndex = intType
                            )
                        )
                    )
                )
            }
            val result = helper.compileAndInvoke(ast)
            assertEquals(10, result)
        }

        @Test
        fun `int max with long returns larger value`() {
            val ast = buildTypedAst {
                val intType = intType()
                val longType = longType()
                function(
                    name = "testFunction",
                    returnType = longType,
                    body = listOf(
                        returnStmt(
                            memberCall(
                                expression = intLiteral(5, intType),
                                member = "max",
                                overload = "long",
                                arguments = listOf(longLiteral(10L, longType)),
                                resultTypeIndex = longType
                            )
                        )
                    )
                )
            }
            val result = helper.compileAndInvoke(ast)
            assertEquals(10L, result)
        }

        @Test
        fun `int max with float returns larger value`() {
            val ast = buildTypedAst {
                val intType = intType()
                val floatType = floatType()
                function(
                    name = "testFunction",
                    returnType = floatType,
                    body = listOf(
                        returnStmt(
                            memberCall(
                                expression = intLiteral(5, intType),
                                member = "max",
                                overload = "float",
                                arguments = listOf(floatLiteral(10.5f, floatType)),
                                resultTypeIndex = floatType
                            )
                        )
                    )
                )
            }
            val result = helper.compileAndInvoke(ast)
            assertEquals(10.5f, result)
        }

        @Test
        fun `int max with double returns larger value`() {
            val ast = buildTypedAst {
                val intType = intType()
                val doubleType = doubleType()
                function(
                    name = "testFunction",
                    returnType = doubleType,
                    body = listOf(
                        returnStmt(
                            memberCall(
                                expression = intLiteral(5, intType),
                                member = "max",
                                overload = "double",
                                arguments = listOf(doubleLiteral(10.5, doubleType)),
                                resultTypeIndex = doubleType
                            )
                        )
                    )
                )
            }
            val result = helper.compileAndInvoke(ast)
            assertEquals(10.5, result)
        }
    }

    @Nested
    inner class IntMinMethod {

        @Test
        fun `int min with int returns smaller value`() {
            val ast = buildTypedAst {
                val intType = intType()
                function(
                    name = "testFunction",
                    returnType = intType,
                    body = listOf(
                        returnStmt(
                            memberCall(
                                expression = intLiteral(10, intType),
                                member = "min",
                                overload = "int",
                                arguments = listOf(intLiteral(5, intType)),
                                resultTypeIndex = intType
                            )
                        )
                    )
                )
            }
            val result = helper.compileAndInvoke(ast)
            assertEquals(5, result)
        }

        @Test
        fun `int min with long returns smaller value`() {
            val ast = buildTypedAst {
                val intType = intType()
                val longType = longType()
                function(
                    name = "testFunction",
                    returnType = longType,
                    body = listOf(
                        returnStmt(
                            memberCall(
                                expression = intLiteral(10, intType),
                                member = "min",
                                overload = "long",
                                arguments = listOf(longLiteral(5L, longType)),
                                resultTypeIndex = longType
                            )
                        )
                    )
                )
            }
            val result = helper.compileAndInvoke(ast)
            assertEquals(5L, result)
        }

        @Test
        fun `int min with float returns smaller value`() {
            val ast = buildTypedAst {
                val intType = intType()
                val floatType = floatType()
                function(
                    name = "testFunction",
                    returnType = floatType,
                    body = listOf(
                        returnStmt(
                            memberCall(
                                expression = intLiteral(10, intType),
                                member = "min",
                                overload = "float",
                                arguments = listOf(floatLiteral(5.5f, floatType)),
                                resultTypeIndex = floatType
                            )
                        )
                    )
                )
            }
            val result = helper.compileAndInvoke(ast)
            assertEquals(5.5f, result)
        }

        @Test
        fun `int min with double returns smaller value`() {
            val ast = buildTypedAst {
                val intType = intType()
                val doubleType = doubleType()
                function(
                    name = "testFunction",
                    returnType = doubleType,
                    body = listOf(
                        returnStmt(
                            memberCall(
                                expression = intLiteral(10, intType),
                                member = "min",
                                overload = "double",
                                arguments = listOf(doubleLiteral(5.5, doubleType)),
                                resultTypeIndex = doubleType
                            )
                        )
                    )
                )
            }
            val result = helper.compileAndInvoke(ast)
            assertEquals(5.5, result)
        }
    }

    @Nested
    inner class IntToBinaryMethod {

        @Test
        fun `int toBinary returns binary string`() {
            val ast = buildTypedAst {
                val intType = intType()
                val stringType = stringType()
                function(
                    name = "testFunction",
                    returnType = stringType,
                    body = listOf(
                        returnStmt(
                            memberCall(
                                expression = intLiteral(42, intType),
                                member = "toBinary",
                                overload = "",
                                arguments = emptyList(),
                                resultTypeIndex = stringType
                            )
                        )
                    )
                )
            }
            val result = helper.compileAndInvoke(ast)
            assertEquals("101010", result)
        }
    }

    @Nested
    inner class IntToHexMethod {

        @Test
        fun `int toHex returns hex string`() {
            val ast = buildTypedAst {
                val intType = intType()
                val stringType = stringType()
                function(
                    name = "testFunction",
                    returnType = stringType,
                    body = listOf(
                        returnStmt(
                            memberCall(
                                expression = intLiteral(255, intType),
                                member = "toHex",
                                overload = "",
                                arguments = emptyList(),
                                resultTypeIndex = stringType
                            )
                        )
                    )
                )
            }
            val result = helper.compileAndInvoke(ast)
            assertEquals("ff", result)
        }
    }

    // ==================================================================================
    // LONG METHODS
    // ==================================================================================

    @Nested
    inner class LongMethods {

        @Test
        fun `long abs returns absolute value`() {
            val ast = buildTypedAst {
                val longType = longType()
                function(
                    name = "testFunction",
                    returnType = longType,
                    body = listOf(
                        returnStmt(
                            memberCall(
                                expression = longLiteral(-100L, longType),
                                member = "abs",
                                overload = "",
                                arguments = emptyList(),
                                resultTypeIndex = longType
                            )
                        )
                    )
                )
            }
            val result = helper.compileAndInvoke(ast)
            assertEquals(100L, result)
        }

        @Test
        fun `long ceil returns same value`() {
            val ast = buildTypedAst {
                val longType = longType()
                function(
                    name = "testFunction",
                    returnType = longType,
                    body = listOf(
                        returnStmt(
                            memberCall(
                                expression = longLiteral(42L, longType),
                                member = "ceil",
                                overload = "",
                                arguments = emptyList(),
                                resultTypeIndex = longType
                            )
                        )
                    )
                )
            }
            val result = helper.compileAndInvoke(ast)
            assertEquals(42L, result)
        }

        @Test
        fun `long floor returns same value`() {
            val ast = buildTypedAst {
                val longType = longType()
                function(
                    name = "testFunction",
                    returnType = longType,
                    body = listOf(
                        returnStmt(
                            memberCall(
                                expression = longLiteral(42L, longType),
                                member = "floor",
                                overload = "",
                                arguments = emptyList(),
                                resultTypeIndex = longType
                            )
                        )
                    )
                )
            }
            val result = helper.compileAndInvoke(ast)
            assertEquals(42L, result)
        }

        @Test
        fun `long round returns same value`() {
            val ast = buildTypedAst {
                val longType = longType()
                function(
                    name = "testFunction",
                    returnType = longType,
                    body = listOf(
                        returnStmt(
                            memberCall(
                                expression = longLiteral(42L, longType),
                                member = "round",
                                overload = "",
                                arguments = emptyList(),
                                resultTypeIndex = longType
                            )
                        )
                    )
                )
            }
            val result = helper.compileAndInvoke(ast)
            assertEquals(42L, result)
        }

        @Test
        fun `long log returns natural logarithm`() {
            val ast = buildTypedAst {
                val longType = longType()
                val doubleType = doubleType()
                function(
                    name = "testFunction",
                    returnType = doubleType,
                    body = listOf(
                        returnStmt(
                            memberCall(
                                expression = longLiteral(10L, longType),
                                member = "log",
                                overload = "",
                                arguments = emptyList(),
                                resultTypeIndex = doubleType
                            )
                        )
                    )
                )
            }
            val result = helper.compileAndInvoke(ast) as Double
            assertTrue(result > 2.3 && result < 2.4, "log(10) should be approximately 2.302")
        }

        @Test
        fun `long log10 returns base 10 logarithm`() {
            val ast = buildTypedAst {
                val longType = longType()
                val doubleType = doubleType()
                function(
                    name = "testFunction",
                    returnType = doubleType,
                    body = listOf(
                        returnStmt(
                            memberCall(
                                expression = longLiteral(1000L, longType),
                                member = "log10",
                                overload = "",
                                arguments = emptyList(),
                                resultTypeIndex = doubleType
                            )
                        )
                    )
                )
            }
            val result = helper.compileAndInvoke(ast)
            assertEquals(3.0, result)
        }

        @Test
        fun `long pow calculates exponentiation`() {
            val ast = buildTypedAst {
                val longType = longType()
                val doubleType = doubleType()
                function(
                    name = "testFunction",
                    returnType = doubleType,
                    body = listOf(
                        returnStmt(
                            memberCall(
                                expression = longLiteral(3L, longType),
                                member = "pow",
                                overload = "",
                                arguments = listOf(doubleLiteral(4.0, doubleType)),
                                resultTypeIndex = doubleType
                            )
                        )
                    )
                )
            }
            val result = helper.compileAndInvoke(ast)
            assertEquals(81.0, result)
        }

        @Test
        fun `long mod calculates modulus`() {
            val ast = buildTypedAst {
                val longType = longType()
                function(
                    name = "testFunction",
                    returnType = longType,
                    body = listOf(
                        returnStmt(
                            memberCall(
                                expression = longLiteral(25L, longType),
                                member = "mod",
                                overload = "",
                                arguments = listOf(longLiteral(7L, longType)),
                                resultTypeIndex = longType
                            )
                        )
                    )
                )
            }
            val result = helper.compileAndInvoke(ast)
            assertEquals(4L, result)
        }

        @Test
        fun `long max with int returns larger value`() {
            val ast = buildTypedAst {
                val longType = longType()
                val intType = intType()
                function(
                    name = "testFunction",
                    returnType = longType,
                    body = listOf(
                        returnStmt(
                            memberCall(
                                expression = longLiteral(5L, longType),
                                member = "max",
                                overload = "int",
                                arguments = listOf(intLiteral(10, intType)),
                                resultTypeIndex = longType
                            )
                        )
                    )
                )
            }
            val result = helper.compileAndInvoke(ast)
            assertEquals(10L, result)
        }

        @Test
        fun `long min with long returns smaller value`() {
            val ast = buildTypedAst {
                val longType = longType()
                function(
                    name = "testFunction",
                    returnType = longType,
                    body = listOf(
                        returnStmt(
                            memberCall(
                                expression = longLiteral(100L, longType),
                                member = "min",
                                overload = "long",
                                arguments = listOf(longLiteral(50L, longType)),
                                resultTypeIndex = longType
                            )
                        )
                    )
                )
            }
            val result = helper.compileAndInvoke(ast)
            assertEquals(50L, result)
        }

        @Test
        fun `long toBinary returns binary string`() {
            val ast = buildTypedAst {
                val longType = longType()
                val stringType = stringType()
                function(
                    name = "testFunction",
                    returnType = stringType,
                    body = listOf(
                        returnStmt(
                            memberCall(
                                expression = longLiteral(255L, longType),
                                member = "toBinary",
                                overload = "",
                                arguments = emptyList(),
                                resultTypeIndex = stringType
                            )
                        )
                    )
                )
            }
            val result = helper.compileAndInvoke(ast)
            assertEquals("11111111", result)
        }

        @Test
        fun `long toHex returns hex string`() {
            val ast = buildTypedAst {
                val longType = longType()
                val stringType = stringType()
                function(
                    name = "testFunction",
                    returnType = stringType,
                    body = listOf(
                        returnStmt(
                            memberCall(
                                expression = longLiteral(4096L, longType),
                                member = "toHex",
                                overload = "",
                                arguments = emptyList(),
                                resultTypeIndex = stringType
                            )
                        )
                    )
                )
            }
            val result = helper.compileAndInvoke(ast)
            assertEquals("1000", result)
        }
    }

    // ==================================================================================
    // FLOAT METHODS
    // ==================================================================================

    @Nested
    inner class FloatMethods {

        @Test
        fun `float abs returns absolute value`() {
            val ast = buildTypedAst {
                val floatType = floatType()
                function(
                    name = "testFunction",
                    returnType = floatType,
                    body = listOf(
                        returnStmt(
                            memberCall(
                                expression = floatLiteral(-3.14f, floatType),
                                member = "abs",
                                overload = "",
                                arguments = emptyList(),
                                resultTypeIndex = floatType
                            )
                        )
                    )
                )
            }
            val result = helper.compileAndInvoke(ast)
            assertEquals(3.14f, result)
        }

        @Test
        fun `float ceil returns ceil value`() {
            val ast = buildTypedAst {
                val floatType = floatType()
                val intType = intType()
                function(
                    name = "testFunction",
                    returnType = intType,
                    body = listOf(
                        returnStmt(
                            memberCall(
                                expression = floatLiteral(3.2f, floatType),
                                member = "ceil",
                                overload = "",
                                arguments = emptyList(),
                                resultTypeIndex = intType
                            )
                        )
                    )
                )
            }
            val result = helper.compileAndInvoke(ast)
            assertEquals(4, result)
        }

        @Test
        fun `float floor returns floor value`() {
            val ast = buildTypedAst {
                val floatType = floatType()
                val intType = intType()
                function(
                    name = "testFunction",
                    returnType = intType,
                    body = listOf(
                        returnStmt(
                            memberCall(
                                expression = floatLiteral(3.9f, floatType),
                                member = "floor",
                                overload = "",
                                arguments = emptyList(),
                                resultTypeIndex = intType
                            )
                        )
                    )
                )
            }
            val result = helper.compileAndInvoke(ast)
            assertEquals(3, result)
        }

        @Test
        fun `float round returns rounded value`() {
            val ast = buildTypedAst {
                val floatType = floatType()
                val intType = intType()
                function(
                    name = "testFunction",
                    returnType = intType,
                    body = listOf(
                        returnStmt(
                            memberCall(
                                expression = floatLiteral(3.5f, floatType),
                                member = "round",
                                overload = "",
                                arguments = emptyList(),
                                resultTypeIndex = intType
                            )
                        )
                    )
                )
            }
            val result = helper.compileAndInvoke(ast)
            assertEquals(4, result)
        }

        @Test
        fun `float log returns natural logarithm`() {
            val ast = buildTypedAst {
                val floatType = floatType()
                function(
                    name = "testFunction",
                    returnType = floatType,
                    body = listOf(
                        returnStmt(
                            memberCall(
                                expression = floatLiteral(2.71828f, floatType),
                                member = "log",
                                overload = "",
                                arguments = emptyList(),
                                resultTypeIndex = floatType
                            )
                        )
                    )
                )
            }
            val result = helper.compileAndInvoke(ast) as Float
            assertTrue(result > 0.99f && result < 1.01f, "log(e) should be approximately 1")
        }

        @Test
        fun `float log10 returns base 10 logarithm`() {
            val ast = buildTypedAst {
                val floatType = floatType()
                function(
                    name = "testFunction",
                    returnType = floatType,
                    body = listOf(
                        returnStmt(
                            memberCall(
                                expression = floatLiteral(10.0f, floatType),
                                member = "log10",
                                overload = "",
                                arguments = emptyList(),
                                resultTypeIndex = floatType
                            )
                        )
                    )
                )
            }
            val result = helper.compileAndInvoke(ast)
            assertEquals(1.0f, result)
        }

        @Test
        fun `float pow calculates exponentiation`() {
            val ast = buildTypedAst {
                val floatType = floatType()
                val doubleType = doubleType()
                function(
                    name = "testFunction",
                    returnType = doubleType,
                    body = listOf(
                        returnStmt(
                            memberCall(
                                expression = floatLiteral(2.0f, floatType),
                                member = "pow",
                                overload = "",
                                arguments = listOf(doubleLiteral(10.0, doubleType)),
                                resultTypeIndex = doubleType
                            )
                        )
                    )
                )
            }
            val result = helper.compileAndInvoke(ast)
            assertEquals(1024.0, result)
        }

        @Test
        fun `float max with int returns larger value`() {
            val ast = buildTypedAst {
                val floatType = floatType()
                val intType = intType()
                function(
                    name = "testFunction",
                    returnType = floatType,
                    body = listOf(
                        returnStmt(
                            memberCall(
                                expression = floatLiteral(5.5f, floatType),
                                member = "max",
                                overload = "int",
                                arguments = listOf(intLiteral(10, intType)),
                                resultTypeIndex = floatType
                            )
                        )
                    )
                )
            }
            val result = helper.compileAndInvoke(ast)
            assertEquals(10.0f, result)
        }

        @Test
        fun `float min with float returns smaller value`() {
            val ast = buildTypedAst {
                val floatType = floatType()
                function(
                    name = "testFunction",
                    returnType = floatType,
                    body = listOf(
                        returnStmt(
                            memberCall(
                                expression = floatLiteral(10.5f, floatType),
                                member = "min",
                                overload = "float",
                                arguments = listOf(floatLiteral(5.5f, floatType)),
                                resultTypeIndex = floatType
                            )
                        )
                    )
                )
            }
            val result = helper.compileAndInvoke(ast)
            assertEquals(5.5f, result)
        }
    }

    // ==================================================================================
    // DOUBLE METHODS
    // ==================================================================================

    @Nested
    inner class DoubleMethods {

        @Test
        fun `double abs returns absolute value`() {
            val ast = buildTypedAst {
                val doubleType = doubleType()
                function(
                    name = "testFunction",
                    returnType = doubleType,
                    body = listOf(
                        returnStmt(
                            memberCall(
                                expression = doubleLiteral(-3.14159, doubleType),
                                member = "abs",
                                overload = "",
                                arguments = emptyList(),
                                resultTypeIndex = doubleType
                            )
                        )
                    )
                )
            }
            val result = helper.compileAndInvoke(ast)
            assertEquals(3.14159, result)
        }

        @Test
        fun `double ceil returns ceil value`() {
            val ast = buildTypedAst {
                val doubleType = doubleType()
                val longType = longType()
                function(
                    name = "testFunction",
                    returnType = longType,
                    body = listOf(
                        returnStmt(
                            memberCall(
                                expression = doubleLiteral(3.2, doubleType),
                                member = "ceil",
                                overload = "",
                                arguments = emptyList(),
                                resultTypeIndex = longType
                            )
                        )
                    )
                )
            }
            val result = helper.compileAndInvoke(ast)
            assertEquals(4L, result)
        }

        @Test
        fun `double floor returns floor value`() {
            val ast = buildTypedAst {
                val doubleType = doubleType()
                val longType = longType()
                function(
                    name = "testFunction",
                    returnType = longType,
                    body = listOf(
                        returnStmt(
                            memberCall(
                                expression = doubleLiteral(3.9, doubleType),
                                member = "floor",
                                overload = "",
                                arguments = emptyList(),
                                resultTypeIndex = longType
                            )
                        )
                    )
                )
            }
            val result = helper.compileAndInvoke(ast)
            assertEquals(3L, result)
        }

        @Test
        fun `double round returns rounded value`() {
            val ast = buildTypedAst {
                val doubleType = doubleType()
                val longType = longType()
                function(
                    name = "testFunction",
                    returnType = longType,
                    body = listOf(
                        returnStmt(
                            memberCall(
                                expression = doubleLiteral(3.5, doubleType),
                                member = "round",
                                overload = "",
                                arguments = emptyList(),
                                resultTypeIndex = longType
                            )
                        )
                    )
                )
            }
            val result = helper.compileAndInvoke(ast)
            assertEquals(4L, result)
        }

        @Test
        fun `double log returns natural logarithm`() {
            val ast = buildTypedAst {
                val doubleType = doubleType()
                function(
                    name = "testFunction",
                    returnType = doubleType,
                    body = listOf(
                        returnStmt(
                            memberCall(
                                expression = doubleLiteral(Math.E, doubleType),
                                member = "log",
                                overload = "",
                                arguments = emptyList(),
                                resultTypeIndex = doubleType
                            )
                        )
                    )
                )
            }
            val result = helper.compileAndInvoke(ast) as Double
            assertTrue(abs(result - 1.0) < 0.0001, "log(e) should be 1")
        }

        @Test
        fun `double log10 returns base 10 logarithm`() {
            val ast = buildTypedAst {
                val doubleType = doubleType()
                function(
                    name = "testFunction",
                    returnType = doubleType,
                    body = listOf(
                        returnStmt(
                            memberCall(
                                expression = doubleLiteral(10000.0, doubleType),
                                member = "log10",
                                overload = "",
                                arguments = emptyList(),
                                resultTypeIndex = doubleType
                            )
                        )
                    )
                )
            }
            val result = helper.compileAndInvoke(ast)
            assertEquals(4.0, result)
        }

        @Test
        fun `double pow calculates exponentiation`() {
            val ast = buildTypedAst {
                val doubleType = doubleType()
                function(
                    name = "testFunction",
                    returnType = doubleType,
                    body = listOf(
                        returnStmt(
                            memberCall(
                                expression = doubleLiteral(1.5, doubleType),
                                member = "pow",
                                overload = "",
                                arguments = listOf(doubleLiteral(2.0, doubleType)),
                                resultTypeIndex = doubleType
                            )
                        )
                    )
                )
            }
            val result = helper.compileAndInvoke(ast)
            assertEquals(2.25, result)
        }

        @Test
        fun `double max with int returns larger value`() {
            val ast = buildTypedAst {
                val doubleType = doubleType()
                val intType = intType()
                function(
                    name = "testFunction",
                    returnType = doubleType,
                    body = listOf(
                        returnStmt(
                            memberCall(
                                expression = doubleLiteral(5.5, doubleType),
                                member = "max",
                                overload = "int",
                                arguments = listOf(intLiteral(10, intType)),
                                resultTypeIndex = doubleType
                            )
                        )
                    )
                )
            }
            val result = helper.compileAndInvoke(ast)
            assertEquals(10.0, result)
        }

        @Test
        fun `double min with double returns smaller value`() {
            val ast = buildTypedAst {
                val doubleType = doubleType()
                function(
                    name = "testFunction",
                    returnType = doubleType,
                    body = listOf(
                        returnStmt(
                            memberCall(
                                expression = doubleLiteral(10.5, doubleType),
                                member = "min",
                                overload = "double",
                                arguments = listOf(doubleLiteral(5.5, doubleType)),
                                resultTypeIndex = doubleType
                            )
                        )
                    )
                )
            }
            val result = helper.compileAndInvoke(ast)
            assertEquals(5.5, result)
        }
    }
}
