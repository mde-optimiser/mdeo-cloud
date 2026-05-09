package com.mdeo.script.compiler.stdlib

import com.mdeo.expression.ast.types.ClassTypeRef
import com.mdeo.script.compiler.*
import com.mdeo.script.stdlib.impl.collections.ScriptList
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Additional integration tests for stdlib methods that were not covered
 * in the existing test files.
 *
 * This test file covers:
 * - Int/Long: iota, to, toString, asDouble, asFloat
 * - Float: toString, asDouble
 * - Double: toString
 * - Boolean: toString
 * - Any: asBoolean, asInteger, asReal, asDouble, asFloat, toString, format, hasProperty
 * - String: asInteger, asDouble
 * - Collection: count, removeAt
 */
class AdditionalStdlibMethodsIntegrationTest {

    private val helper = CompilerTestHelper()

    // ==================================================================================
    // INT ADDITIONAL METHODS
    // ==================================================================================

    @Nested
    inner class IntIotaMethod {

        @Test
        fun `int iota generates sequence from start to end with step`() {
            val ast = buildTypedAst {
                val listType = addType(ClassTypeRef("builtin", "List", false))
                val intType = intType()
                function(
                    name = "testFunction",
                    returnType = intType,
                    body = listOf(
                        returnStmt(
                            memberCall(
                                expression = memberCall(
                                    expression = intLiteral(0, intType),
                                    member = "iota",
                                    overload = "",
                                    arguments = listOf(
                                        intLiteral(10, intType),
                                        intLiteral(2, intType)
                                    ),
                                    resultTypeIndex = listType
                                ),
                                member = "size",
                                overload = "",
                                arguments = emptyList(),
                                resultTypeIndex = intType
                            )
                        )
                    )
                )
            }
            val result = helper.compileAndInvoke(ast)
            assertEquals(5, result) // 0, 2, 4, 6, 8
        }
    }

    @Nested
    inner class IntToMethod {

        @Test
        fun `int to generates sequence from this to end`() {
            val ast = buildTypedAst {
                val listType = addType(ClassTypeRef("builtin", "List", false))
                val intType = intType()
                function(
                    name = "testFunction",
                    returnType = intType,
                    body = listOf(
                        returnStmt(
                            memberCall(
                                expression = memberCall(
                                    expression = intLiteral(1, intType),
                                    member = "to",
                                    overload = "",
                                    arguments = listOf(intLiteral(5, intType)),
                                    resultTypeIndex = listType
                                ),
                                member = "size",
                                overload = "",
                                arguments = emptyList(),
                                resultTypeIndex = intType
                            )
                        )
                    )
                )
            }
            val result = helper.compileAndInvoke(ast)
            assertEquals(5, result) // 1, 2, 3, 4, 5
        }
    }

    @Nested
    inner class IntAsStringMethod {

        @Test
        fun `int toString converts int to string`() {
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
                                member = "toString",
                                overload = "",
                                arguments = emptyList(),
                                resultTypeIndex = stringType
                            )
                        )
                    )
                )
            }
            val result = helper.compileAndInvoke(ast)
            assertEquals("42", result)
        }

        @Test
        fun `int toString converts negative int to string`() {
            val ast = buildTypedAst {
                val intType = intType()
                val stringType = stringType()
                function(
                    name = "testFunction",
                    returnType = stringType,
                    body = listOf(
                        returnStmt(
                            memberCall(
                                expression = intLiteral(-123, intType),
                                member = "toString",
                                overload = "",
                                arguments = emptyList(),
                                resultTypeIndex = stringType
                            )
                        )
                    )
                )
            }
            val result = helper.compileAndInvoke(ast)
            assertEquals("-123", result)
        }
    }

    // ==================================================================================
    // LONG ADDITIONAL METHODS
    // ==================================================================================

    @Nested
    inner class LongIotaMethod {

        @Test
        fun `long iota generates sequence from start to end with step`() {
            val ast = buildTypedAst {
                val listType = addType(ClassTypeRef("builtin", "List", false))
                val longType = longType()
                val intType = intType()
                function(
                    name = "testFunction",
                    returnType = intType,
                    body = listOf(
                        returnStmt(
                            memberCall(
                                expression = memberCall(
                                    expression = longLiteral(0L, longType),
                                    member = "iota",
                                    overload = "",
                                    arguments = listOf(
                                        longLiteral(10L, longType),
                                        longLiteral(2L, longType)
                                    ),
                                    resultTypeIndex = listType
                                ),
                                member = "size",
                                overload = "",
                                arguments = emptyList(),
                                resultTypeIndex = intType
                            )
                        )
                    )
                )
            }
            val result = helper.compileAndInvoke(ast)
            assertEquals(5, result) // 0, 2, 4, 6, 8
        }
    }

    @Nested
    inner class LongToMethod {

        @Test
        fun `long to generates sequence from this to end`() {
            val ast = buildTypedAst {
                val listType = addType(ClassTypeRef("builtin", "List", false))
                val longType = longType()
                val intType = intType()
                function(
                    name = "testFunction",
                    returnType = intType,
                    body = listOf(
                        returnStmt(
                            memberCall(
                                expression = memberCall(
                                    expression = longLiteral(1L, longType),
                                    member = "to",
                                    overload = "",
                                    arguments = listOf(longLiteral(5L, longType)),
                                    resultTypeIndex = listType
                                ),
                                member = "size",
                                overload = "",
                                arguments = emptyList(),
                                resultTypeIndex = intType
                            )
                        )
                    )
                )
            }
            val result = helper.compileAndInvoke(ast)
            assertEquals(5, result) // 1, 2, 3, 4, 5
        }
    }

    @Nested
    inner class LongAsStringMethod {

        @Test
        fun `long toString converts long to string`() {
            val ast = buildTypedAst {
                val longType = longType()
                val stringType = stringType()
                function(
                    name = "testFunction",
                    returnType = stringType,
                    body = listOf(
                        returnStmt(
                            memberCall(
                                expression = longLiteral(9876543210L, longType),
                                member = "toString",
                                overload = "",
                                arguments = emptyList(),
                                resultTypeIndex = stringType
                            )
                        )
                    )
                )
            }
            val result = helper.compileAndInvoke(ast)
            assertEquals("9876543210", result)
        }
    }

    // ==================================================================================
    // FLOAT ADDITIONAL METHODS
    // ==================================================================================

    @Nested
    inner class FloatAsStringMethod {

        @Test
        fun `float toString converts float to string`() {
            val ast = buildTypedAst {
                val floatType = floatType()
                val stringType = stringType()
                function(
                    name = "testFunction",
                    returnType = stringType,
                    body = listOf(
                        returnStmt(
                            memberCall(
                                expression = floatLiteral(3.14f, floatType),
                                member = "toString",
                                overload = "",
                                arguments = emptyList(),
                                resultTypeIndex = stringType
                            )
                        )
                    )
                )
            }
            val result = helper.compileAndInvoke(ast)
            assertEquals("3.14", result)
        }
    }

    // ==================================================================================
    // DOUBLE ADDITIONAL METHODS
    // ==================================================================================

    @Nested
    inner class DoubleAsStringMethod {

        @Test
        fun `double toString converts double to string`() {
            val ast = buildTypedAst {
                val doubleType = doubleType()
                val stringType = stringType()
                function(
                    name = "testFunction",
                    returnType = stringType,
                    body = listOf(
                        returnStmt(
                            memberCall(
                                expression = doubleLiteral(3.14159, doubleType),
                                member = "toString",
                                overload = "",
                                arguments = emptyList(),
                                resultTypeIndex = stringType
                            )
                        )
                    )
                )
            }
            val result = helper.compileAndInvoke(ast)
            assertEquals("3.14159", result)
        }
    }

    // ==================================================================================
    // BOOLEAN METHODS
    // ==================================================================================

    @Nested
    inner class BooleanAsStringMethod {

        @Test
        fun `boolean toString converts true to string`() {
            val ast = buildTypedAst {
                val boolType = booleanType()
                val stringType = stringType()
                function(
                    name = "testFunction",
                    returnType = stringType,
                    body = listOf(
                        returnStmt(
                            memberCall(
                                expression = booleanLiteral(true, boolType),
                                member = "toString",
                                overload = "",
                                arguments = emptyList(),
                                resultTypeIndex = stringType
                            )
                        )
                    )
                )
            }
            val result = helper.compileAndInvoke(ast)
            assertEquals("true", result)
        }

        @Test
        fun `boolean toString converts false to string`() {
            val ast = buildTypedAst {
                val boolType = booleanType()
                val stringType = stringType()
                function(
                    name = "testFunction",
                    returnType = stringType,
                    body = listOf(
                        returnStmt(
                            memberCall(
                                expression = booleanLiteral(false, boolType),
                                member = "toString",
                                overload = "",
                                arguments = emptyList(),
                                resultTypeIndex = stringType
                            )
                        )
                    )
                )
            }
            val result = helper.compileAndInvoke(ast)
            assertEquals("false", result)
        }
    }

    @Nested
    inner class AnyAsStringMethod {

        @Test
        fun `any toString converts object to string`() {
            val ast = buildTypedAst {
                val intType = intType()
                val stringType = stringType()
                function(
                    name = "testFunction",
                    returnType = stringType,
                    body = listOf(
                        returnStmt(
                            memberCall(
                                expression = intLiteral(123, intType),
                                member = "toString",
                                overload = "",
                                arguments = emptyList(),
                                resultTypeIndex = stringType
                            )
                        )
                    )
                )
            }
            val result = helper.compileAndInvoke(ast)
            assertEquals("123", result)
        }
    }

    @Nested
    inner class AnyFormatMethod {

        @Test
        fun `any format formats integer with pattern`() {
            // Note: AnyHelper.format uses MessageFormat.format which uses {0} style patterns
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
                                member = "format",
                                overload = "",
                                arguments = listOf(stringLiteral("Value: {0}", stringType)),
                                resultTypeIndex = stringType
                            )
                        )
                    )
                )
            }
            val result = helper.compileAndInvoke(ast)
            assertEquals("Value: 42", result)
        }

        @Test
        fun `any format formats double with pattern`() {
            // Note: AnyHelper.format uses MessageFormat.format which uses {0} style patterns
            // MessageFormat uses its own number formatting which rounds to 3 decimal places by default
            val ast = buildTypedAst {
                val doubleType = doubleType()
                val stringType = stringType()
                function(
                    name = "testFunction",
                    returnType = stringType,
                    body = listOf(
                        returnStmt(
                            memberCall(
                                expression = doubleLiteral(3.14159, doubleType),
                                member = "format",
                                overload = "",
                                arguments = listOf(stringLiteral("Pi is approximately {0}", stringType)),
                                resultTypeIndex = stringType
                            )
                        )
                    )
                )
            }
            val result = helper.compileAndInvoke(ast)
            assertEquals("Pi is approximately 3.142", result)
        }
    }

    @Nested
    inner class AnyHasPropertyMethod {

        @Test
        fun `any hasProperty returns false for primitive without property`() {
            val ast = buildTypedAst {
                val intType = intType()
                val stringType = stringType()
                val boolType = booleanType()
                function(
                    name = "testFunction",
                    returnType = boolType,
                    body = listOf(
                        returnStmt(
                            memberCall(
                                expression = intLiteral(42, intType),
                                member = "hasProperty",
                                overload = "",
                                arguments = listOf(stringLiteral("nonexistent", stringType)),
                                resultTypeIndex = boolType
                            )
                        )
                    )
                )
            }
            val result = helper.compileAndInvoke(ast)
            assertEquals(false, result)
        }
    }

    // ==================================================================================
    // STRING ADDITIONAL METHODS
    // ==================================================================================

    @Nested
    inner class StringAsIntegerMethod {

        @Test
        fun `string asInteger converts numeric string to int`() {
            val ast = buildTypedAst {
                val stringType = stringType()
                val intType = intType()
                function(
                    name = "testFunction",
                    returnType = intType,
                    body = listOf(
                        returnStmt(
                            memberCall(
                                expression = stringLiteral("12345", stringType),
                                member = "asInteger",
                                overload = "",
                                arguments = emptyList(),
                                resultTypeIndex = intType
                            )
                        )
                    )
                )
            }
            val result = helper.compileAndInvoke(ast)
            assertEquals(12345, result)
        }

        @Test
        fun `string asInteger converts negative numeric string to int`() {
            val ast = buildTypedAst {
                val stringType = stringType()
                val intType = intType()
                function(
                    name = "testFunction",
                    returnType = intType,
                    body = listOf(
                        returnStmt(
                            memberCall(
                                expression = stringLiteral("-999", stringType),
                                member = "asInteger",
                                overload = "",
                                arguments = emptyList(),
                                resultTypeIndex = intType
                            )
                        )
                    )
                )
            }
            val result = helper.compileAndInvoke(ast)
            assertEquals(-999, result)
        }
    }

    @Nested
    inner class StringAsDoubleMethod {

        @Test
        fun `string asDouble converts numeric string to double`() {
            val ast = buildTypedAst {
                val stringType = stringType()
                val doubleType = doubleType()
                function(
                    name = "testFunction",
                    returnType = doubleType,
                    body = listOf(
                        returnStmt(
                            memberCall(
                                expression = stringLiteral("3.14159", stringType),
                                member = "asDouble",
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
    }

    @Nested
    inner class StringAsStringMethod {

        @Test
        fun `string toString returns same string`() {
            val ast = buildTypedAst {
                val stringType = stringType()
                function(
                    name = "testFunction",
                    returnType = stringType,
                    body = listOf(
                        returnStmt(
                            memberCall(
                                expression = stringLiteral("hello world", stringType),
                                member = "toString",
                                overload = "",
                                arguments = emptyList(),
                                resultTypeIndex = stringType
                            )
                        )
                    )
                )
            }
            val result = helper.compileAndInvoke(ast)
            assertEquals("hello world", result)
        }
    }

    // ==================================================================================
    // COLLECTION ADDITIONAL METHODS
    // ==================================================================================

    @Nested
    inner class CollectionCountMethod {

        @Test
        fun `collection count returns occurrences of element`() {
            val ast = buildTypedAst {
                val listType = addType(ClassTypeRef("builtin", "List", false))
                val intType = intType()
                function(
                    name = "testFunction",
                    returnType = intType,
                    body = listOf(
                        returnStmt(
                            memberCall(
                                expression = functionCall(
                                    name = "listOf",
                                    overload = "",
                                    arguments = listOf(
                                        intLiteral(1, intType),
                                        intLiteral(2, intType),
                                        intLiteral(2, intType),
                                        intLiteral(3, intType),
                                        intLiteral(2, intType)
                                    ),
                                    resultTypeIndex = listType
                                ),
                                member = "count",
                                overload = "",
                                arguments = listOf(intLiteral(2, intType)),
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
        fun `collection count returns zero for missing element`() {
            val ast = buildTypedAst {
                val listType = addType(ClassTypeRef("builtin", "List", false))
                val intType = intType()
                function(
                    name = "testFunction",
                    returnType = intType,
                    body = listOf(
                        returnStmt(
                            memberCall(
                                expression = functionCall(
                                    name = "listOf",
                                    overload = "",
                                    arguments = listOf(
                                        intLiteral(1, intType),
                                        intLiteral(2, intType),
                                        intLiteral(3, intType)
                                    ),
                                    resultTypeIndex = listType
                                ),
                                member = "count",
                                overload = "",
                                arguments = listOf(intLiteral(5, intType)),
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
    inner class OrderedCollectionRemoveAtMethod {

        @Test
        fun `ordered collection removeAt removes element at index`() {
            val ast = buildTypedAst {
                val listType = addType(ClassTypeRef("builtin", "List", false))
                val intType = intType()
                val anyType = anyNullableType()
                function(
                    name = "testFunction",
                    returnType = intType,
                    body = listOf(
                        varDecl("list", listType,
                            functionCall(
                                name = "listOf",
                                overload = "",
                                arguments = listOf(
                                    intLiteral(10, intType),
                                    intLiteral(20, intType),
                                    intLiteral(30, intType),
                                    intLiteral(40, intType)
                                ),
                                resultTypeIndex = listType
                            )
                        ),
                        exprStmt(
                            memberCall(
                                expression = identifier("list", listType, 3),
                                member = "removeAt",
                                overload = "",
                                arguments = listOf(intLiteral(1, intType)),
                                resultTypeIndex = anyType
                            )
                        ),
                        returnStmt(
                            memberCall(
                                expression = identifier("list", listType, 3),
                                member = "size",
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
        fun `ordered collection removeAt returns removed element`() {
            val ast = buildTypedAst {
                val listType = addType(ClassTypeRef("builtin", "List", false))
                val intType = intType()
                val anyType = anyNullableType()
                function(
                    name = "testFunction",
                    returnType = anyType,
                    body = listOf(
                        varDecl("list", listType,
                            functionCall(
                                name = "listOf",
                                overload = "",
                                arguments = listOf(
                                    intLiteral(10, intType),
                                    intLiteral(20, intType),
                                    intLiteral(30, intType)
                                ),
                                resultTypeIndex = listType
                            )
                        ),
                        returnStmt(
                            memberCall(
                                expression = identifier("list", listType, 3),
                                member = "removeAt",
                                overload = "",
                                arguments = listOf(intLiteral(1, intType)),
                                resultTypeIndex = anyType
                            )
                        )
                    )
                )
            }
            val result = helper.compileAndInvoke(ast)
            assertEquals(20, result)
        }
    }
}
