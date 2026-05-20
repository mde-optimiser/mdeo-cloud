package com.mdeo.script.compiler.registry

import com.mdeo.script.ast.TypedParameter
import com.mdeo.script.compiler.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Nested
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

/**
 * Tests to find bugs in the registry-based member call/property access infrastructure.
 * 
 * Focus areas:
 * - Calling inherited Any methods on primitives (needs boxing)
 * - Boxing/unboxing when crossing type boundaries  
 * - Null-safe chaining edge cases
 * - Type coercion in method arguments
 * - Method overload resolution
 */
class RegistryMemberCallBugTest {

    private val helper = CompilerTestHelper()

    // =================================================================================
    // BUG CATEGORY 1: Calling Any methods on primitives
    // 
    // When a primitive type (like int) doesn't define a method, it should be looked up
    // from the parent Any type. However, Any methods expect Object (boxed) not primitives.
    // The compiler must box the primitive before calling the Any method.
    // 
    // STATUS: FIXED - boxing is now emitted when method descriptor expects Object
    // =================================================================================

    @Nested
    inner class InheritedAnyMethodsOnPrimitives {

        @Test
        fun `calling hasProperty on long - needs boxing`() {
            // 100L.hasProperty("foo") should return false
            val ast = buildTypedAst {
                val longType = longType()
                val stringType = stringType()
                val boolType = booleanType()
                
                function(
                    name = "testFunction",
                    returnType = boolType,
                    body = listOf(
                        returnStmt(
                            memberCall(
                                expression = longLiteral(100L, longType),
                                member = "hasProperty",
                                overload = "",
                                arguments = listOf(stringLiteral("foo", stringType)),
                                resultTypeIndex = boolType
                            )
                        )
                    )
                )
            }
            
            val result = helper.compileAndInvoke(ast)
            assertEquals(false, result)
        }

        @Test
        fun `calling format on int - needs boxing`() {
            // 42.format("Value: %d") should return "Value: 42"
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
                                arguments = listOf(stringLiteral("{0}", stringType)),
                                resultTypeIndex = stringType
                            )
                        )
                    )
                )
            }
            
            val result = helper.compileAndInvoke(ast)
            assertEquals("42", result)
        }
    }

    // =================================================================================
    // BUG CATEGORY 3: Methods that shadow Any methods
    // These should use the more specific implementation, not the Any version
    // =================================================================================

    @Nested
    inner class ShadowedMethodResolution {

        @Test
        fun `int toString uses IntHelper not AnyHelper`() {
            // 42.toString() should use IntHelper.toString(int), not AnyHelper.toString(Object)
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
        fun `double toString uses DoubleHelper`() {
            // 3.14.toString() should use DoubleHelper.toString(double)
            val ast = buildTypedAst {
                val doubleType = doubleType()
                val stringType = stringType()
                
                function(
                    name = "testFunction",
                    returnType = stringType,
                    body = listOf(
                        returnStmt(
                            memberCall(
                                expression = doubleLiteral(3.14, doubleType),
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

    // =================================================================================
    // BUG CATEGORY 4: Method overload resolution
    // =================================================================================

    @Nested
    inner class MethodOverloadResolution {

        @Test
        fun `int max with int argument`() {
            // 5.max(10) should return 10
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
        fun `int max with long argument`() {
            // 5.max(10L) should return 10L
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
        fun `int max with double argument`() {
            // 5.max(10.5) should return 10.5
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

        @Test
        fun `int min with float argument`() {
            // 5.min(2.5f) should return 2.5f
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
                                member = "min",
                                overload = "float",
                                arguments = listOf(floatLiteral(2.5f, floatType)),
                                resultTypeIndex = floatType
                            )
                        )
                    )
                )
            }
            
            val result = helper.compileAndInvoke(ast)
            assertEquals(2.5f, result)
        }
    }

    // =================================================================================
    // BUG CATEGORY 5: Type conversions in return values
    // =================================================================================

    @Nested
    inner class ReturnTypeConversions {

        @Test
        fun `int log returns float`() {
            // 100.log() should return approximately 4.605
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
            assertTrue(result > 4.6f && result < 4.7f)
        }

        @Test
        fun `double floor returns long`() {
            // 3.7.floor() should return 3L
            val ast = buildTypedAst {
                val doubleType = doubleType()
                val longType = longType()
                
                function(
                    name = "testFunction",
                    returnType = longType,
                    body = listOf(
                        returnStmt(
                            memberCall(
                                expression = doubleLiteral(3.7, doubleType),
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
        fun `float ceil returns int`() {
            // 3.2f.ceil() should return 4
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
    }

    // =================================================================================
    // BUG CATEGORY 6: Chained method calls
    // =================================================================================

    @Nested
    inner class ChainedMethodCalls {

        @Test
        fun `chained int operations - abs then max`() {
            // (-5).abs().max(3) should return 5
            val ast = buildTypedAst {
                val intType = intType()
                
                function(
                    name = "testFunction",
                    returnType = intType,
                    body = listOf(
                        returnStmt(
                            memberCall(
                                expression = memberCall(
                                    expression = intLiteral(-5, intType),
                                    member = "abs",
                                    overload = "",
                                    arguments = emptyList(),
                                    resultTypeIndex = intType
                                ),
                                member = "max",
                                overload = "int",
                                arguments = listOf(intLiteral(3, intType)),
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
        fun `chained string operations - trim then toUpperCase then length`() {
            // "  hello  ".trim().toUpperCase().length() should return 5
            val ast = buildTypedAst {
                val stringType = stringType()
                val intType = intType()
                
                function(
                    name = "testFunction",
                    returnType = intType,
                    body = listOf(
                        returnStmt(
                            memberCall(
                                expression = memberCall(
                                    expression = memberCall(
                                        expression = stringLiteral("  hello  ", stringType),
                                        member = "trim",
                                        overload = "",
                                        arguments = emptyList(),
                                        resultTypeIndex = stringType
                                    ),
                                    member = "toUpperCase",
                                    overload = "",
                                    arguments = emptyList(),
                                    resultTypeIndex = stringType
                                ),
                                member = "length",
                                overload = "",
                                arguments = emptyList(),
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
        fun `method result used as argument to another method`() {
            // 10.min((-3).abs()) should return 3
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
                                arguments = listOf(
                                    memberCall(
                                        expression = intLiteral(-3, intType),
                                        member = "abs",
                                        overload = "",
                                        arguments = emptyList(),
                                        resultTypeIndex = intType
                                    )
                                ),
                                resultTypeIndex = intType
                            )
                        )
                    )
                )
            }
            
            val result = helper.compileAndInvoke(ast)
            assertEquals(3, result)
        }
    }

    // =================================================================================
    // BUG CATEGORY 7: Boolean type methods
    // =================================================================================

    @Nested
    inner class BooleanTypeMethods {

        @Test
        fun `boolean toString returns true or false`() {
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
    }
}
