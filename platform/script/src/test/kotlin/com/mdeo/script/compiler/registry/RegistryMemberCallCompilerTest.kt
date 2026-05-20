package com.mdeo.script.compiler.registry

import com.mdeo.script.compiler.registry.type.TypeRegistry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import com.mdeo.expression.ast.types.ClassTypeRef

/**
 * Integration tests for the registry-based member call functionality.
 *
 * Tests cover:
 * - Primitive type method lookups
 * - Method overload resolution
 * - Inheritance-based method lookup
 * - Type hierarchy traversal
 */
class RegistryMemberCallCompilerTest {

    @Nested
    inner class PrimitiveMethods {

        @Test
        fun `can lookup int abs method`() {
            val method = TypeRegistry.GLOBAL.lookupMethod(
                ClassTypeRef("builtin", "int", false),
                "abs",
                ""
            )
            assertNotNull(method)
            assertEquals("", method.overloadKey)
        }

        @Test
        fun `can lookup int max method with int arg`() {
            val method = TypeRegistry.GLOBAL.lookupMethod(
                ClassTypeRef("builtin", "int", false),
                "max",
                "int"
            )
            assertNotNull(method)
        }

        @Test
        fun `can lookup int max method with long arg`() {
            val method = TypeRegistry.GLOBAL.lookupMethod(
                ClassTypeRef("builtin", "int", false),
                "max",
                "long"
            )
            assertNotNull(method)
        }

        @Test
        fun `can lookup string length method`() {
            val method = TypeRegistry.GLOBAL.lookupMethod(
                ClassTypeRef("builtin", "string", false),
                "length",
                ""
            )
            assertNotNull(method)
        }

        @Test
        fun `can lookup string substring method`() {
            val method = TypeRegistry.GLOBAL.lookupMethod(
                ClassTypeRef("builtin", "string", false),
                "substring",
                "1"
            )
            assertNotNull(method)
        }

        @Test
        fun `can lookup double floor method`() {
            // Double.floor() returns long
            val method = TypeRegistry.GLOBAL.lookupMethod(
                ClassTypeRef("builtin", "double", false),
                "floor",
                ""
            )
            assertNotNull(method)
        }

        @Test
        fun `can lookup float ceil method`() {
            // Float.ceil() returns int
            val method = TypeRegistry.GLOBAL.lookupMethod(
                ClassTypeRef("builtin", "float", false),
                "ceil",
                ""
            )
            assertNotNull(method)
        }

        @Test
        fun `can lookup boolean toString method`() {
            // Boolean doesn't have negate, but has toString
            val method = TypeRegistry.GLOBAL.lookupMethod(
                ClassTypeRef("builtin", "boolean", false),
                "toString",
                ""
            )
            assertNotNull(method)
        }
    }

    @Nested
    inner class InheritedMethods {

        @Test
        fun `int inherits toString from any`() {
            val method = TypeRegistry.GLOBAL.lookupMethod(
                ClassTypeRef("builtin", "int", false),
                "toString",
                ""
            )
            assertNotNull(method)
        }

        @Test
        fun `long inherits toString from any`() {
            val method = TypeRegistry.GLOBAL.lookupMethod(
                ClassTypeRef("builtin", "long", false),
                "toString",
                ""
            )
            assertNotNull(method)
        }

        @Test
        fun `string inherits format from any`() {
            val method = TypeRegistry.GLOBAL.lookupMethod(
                ClassTypeRef("builtin", "string", false),
                "format",
                ""
            )
            assertNotNull(method)
        }

        @Test
        fun `boolean inherits hasProperty from any`() {
            val method = TypeRegistry.GLOBAL.lookupMethod(
                ClassTypeRef("builtin", "boolean", false),
                "hasProperty",
                ""
            )
            assertNotNull(method)
        }
    }

    @Nested
    inner class MethodOverloads {

        @Test
        fun `int has multiple max overloads`() {
            val methods = TypeRegistry.GLOBAL.lookupMethods(ClassTypeRef("builtin", "int", false), "max")
            assertTrue(methods.size >= 2, "Expected at least 2 max overloads, got ${methods.size}")
        }

        @Test
        fun `string has multiple substring overloads`() {
            val oneArg = TypeRegistry.GLOBAL.lookupMethod(
                ClassTypeRef("builtin", "string", false),
                "substring",
                "1"
            )
            val twoArg = TypeRegistry.GLOBAL.lookupMethod(
                ClassTypeRef("builtin", "string", false),
                "substring",
                "2"
            )

            assertNotNull(oneArg)
            assertNotNull(twoArg)
        }

        @Test
        fun `int has min overloads for int and long`() {
            val intMin = TypeRegistry.GLOBAL.lookupMethod(ClassTypeRef("builtin", "int", false), "min", "int")
            val longMin = TypeRegistry.GLOBAL.lookupMethod(ClassTypeRef("builtin", "int", false), "min", "long")

            assertNotNull(intMin)
            assertNotNull(longMin)
        }

        @Test
        fun `double has multiple arithmetic methods`() {
            val abs = TypeRegistry.GLOBAL.lookupMethod(ClassTypeRef("builtin", "double", false), "abs", "")
            val floor = TypeRegistry.GLOBAL.lookupMethod(ClassTypeRef("builtin", "double", false), "floor", "")
            val ceil = TypeRegistry.GLOBAL.lookupMethod(ClassTypeRef("builtin", "double", false), "ceil", "")
            val round = TypeRegistry.GLOBAL.lookupMethod(ClassTypeRef("builtin", "double", false), "round", "")

            assertNotNull(abs)
            assertNotNull(floor)
            assertNotNull(ceil)
            assertNotNull(round)
        }
    }

    @Nested
    inner class MethodDefinitionDetails {

        @Test
        fun `static method has correct descriptor`() {
            val method = TypeRegistry.GLOBAL.lookupMethod(
                ClassTypeRef("builtin", "int", false),
                "abs",
                ""
            )
            assertNotNull(method)
            // IntHelper.abs(int) -> returns int
            assertEquals("(I)I", method.descriptor)
            assertTrue(method.isStatic)
        }

        @Test
        fun `string method has correct descriptor`() {
            val method = TypeRegistry.GLOBAL.lookupMethod(
                ClassTypeRef("builtin", "string", false),
                "length",
                ""
            )
            assertNotNull(method)
            // StringHelper.length(String) -> returns int
            assertEquals("(Ljava/lang/String;)I", method.descriptor)
        }

        @Test
        fun `method with parameter has correct descriptor`() {
            val method = TypeRegistry.GLOBAL.lookupMethod(
                ClassTypeRef("builtin", "int", false),
                "max",
                "int"
            )
            assertNotNull(method)
            // IntHelper.max(int, int) -> returns int
            assertEquals("(II)I", method.descriptor)
        }

        @Test
        fun `inherited method from any has correct owner`() {
            val method = TypeRegistry.GLOBAL.lookupMethod(
                ClassTypeRef("builtin", "Any", false),
                "toString",
                ""
            )
            assertNotNull(method)
            assertEquals("com/mdeo/script/stdlib/impl/primitives/AnyHelper", method.ownerClass)
        }
    }

    @Nested
    inner class TypeHierarchy {

        @Test
        fun `int extends any`() {
            val intType = TypeRegistry.GLOBAL.getType(ClassTypeRef("builtin", "int", false))
            assertNotNull(intType)
            assertTrue(intType.extends.contains(ClassTypeRef("builtin", "Any", false)))
        }

        @Test
        fun `long extends any`() {
            val longType = TypeRegistry.GLOBAL.getType(ClassTypeRef("builtin", "long", false))
            assertNotNull(longType)
            assertTrue(longType.extends.contains(ClassTypeRef("builtin", "Any", false)))
        }

        @Test
        fun `double extends any`() {
            val doubleType = TypeRegistry.GLOBAL.getType(ClassTypeRef("builtin", "double", false))
            assertNotNull(doubleType)
            assertTrue(doubleType.extends.contains(ClassTypeRef("builtin", "Any", false)))
        }

        @Test
        fun `float extends any`() {
            val floatType = TypeRegistry.GLOBAL.getType(ClassTypeRef("builtin", "float", false))
            assertNotNull(floatType)
            assertTrue(floatType.extends.contains(ClassTypeRef("builtin", "Any", false)))
        }

        @Test
        fun `string extends any`() {
            val stringType = TypeRegistry.GLOBAL.getType(ClassTypeRef("builtin", "string", false))
            assertNotNull(stringType)
            assertTrue(stringType.extends.contains(ClassTypeRef("builtin", "Any", false)))
        }

        @Test
        fun `boolean extends any`() {
            val booleanType = TypeRegistry.GLOBAL.getType(ClassTypeRef("builtin", "boolean", false))
            assertNotNull(booleanType)
            assertTrue(booleanType.extends.contains(ClassTypeRef("builtin", "Any", false)))
        }
    }
}
