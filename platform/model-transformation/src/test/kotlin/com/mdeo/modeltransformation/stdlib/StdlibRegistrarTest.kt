package com.mdeo.modeltransformation.stdlib

import com.mdeo.modeltransformation.compiler.registry.TypeRegistry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import com.mdeo.expression.ast.types.ClassTypeRef

/**
 * Tests for [StdlibRegistrar] and standard library type definitions.
 *
 * Tests cover:
 * - Type registration
 * - Type hierarchy and inheritance
 * - Method existence for pure traversal implementations
 *
 * Note: Value-based methods (string operations, type conversions, etc.) have been
 * intentionally removed as they cannot be implemented as pure Gremlin traversals.
 */
class StdlibRegistrarTest {

    private lateinit var registry: TypeRegistry

    @BeforeEach
    fun setUp() {
        registry = StdlibRegistrar.createRegistry()
    }

    @Nested
    inner class RegistrationTests {

        @Test
        fun `registers all primitive types`() {
            assertTrue(registry.hasType(ClassTypeRef("builtin", "Any", false)))
            assertTrue(registry.hasType(ClassTypeRef("builtin", "int", false)))
            assertTrue(registry.hasType(ClassTypeRef("builtin", "long", false)))
            assertTrue(registry.hasType(ClassTypeRef("builtin", "float", false)))
            assertTrue(registry.hasType(ClassTypeRef("builtin", "double", false)))
            assertTrue(registry.hasType(ClassTypeRef("builtin", "boolean", false)))
            assertTrue(registry.hasType(ClassTypeRef("builtin", "string", false)))
        }

        @Test
        fun `registers all collection types`() {
            assertTrue(registry.hasType(ClassTypeRef("builtin", "Collection", false)))
            assertTrue(registry.hasType(ClassTypeRef("builtin", "Collection", false)))
            assertTrue(registry.hasType(ClassTypeRef("builtin", "OrderedCollection", false)))
            assertTrue(registry.hasType(ClassTypeRef("builtin", "List", false)))
            assertTrue(registry.hasType(ClassTypeRef("builtin", "Set", false)))
            assertTrue(registry.hasType(ClassTypeRef("builtin", "OrderedSet", false)))
            assertTrue(registry.hasType(ClassTypeRef("builtin", "Bag", false)))
        }
    }

    @Nested
    inner class IntTypeTests {

        @Test
        fun `int has abs method`() {
            val method = registry.lookupMethod(ClassTypeRef("builtin", "int", false), "abs", "")
            assertNotNull(method)
        }

        @Test
        fun `int has max method`() {
            val method = registry.lookupMethod(ClassTypeRef("builtin", "int", false), "max", "builtin.int")
            assertNotNull(method)
        }

        @Test
        fun `int has min method`() {
            val method = registry.lookupMethod(ClassTypeRef("builtin", "int", false), "min", "builtin.int")
            assertNotNull(method)
        }

        @Test
        fun `int has pow method`() {
            val method = registry.lookupMethod(ClassTypeRef("builtin", "int", false), "pow", "")
            assertNotNull(method)
        }

        @Test
        fun `int has mod method`() {
            val method = registry.lookupMethod(ClassTypeRef("builtin", "int", false), "mod", "")
            assertNotNull(method)
        }

        @Test
        fun `int has log method`() {
            val method = registry.lookupMethod(ClassTypeRef("builtin", "int", false), "log", "")
            assertNotNull(method)
        }

        @Test
        fun `int has log10 method`() {
            val method = registry.lookupMethod(ClassTypeRef("builtin", "int", false), "log10", "")
            assertNotNull(method)
        }
    }

    @Nested
    inner class LongTypeTests {

        @Test
        fun `long has abs method`() {
            val method = registry.lookupMethod(ClassTypeRef("builtin", "long", false), "abs", "")
            assertNotNull(method)
        }

        @Test
        fun `long has max method`() {
            val method = registry.lookupMethod(ClassTypeRef("builtin", "long", false), "max", "builtin.long")
            assertNotNull(method)
        }
    }

    @Nested
    inner class FloatTypeTests {

        @Test
        fun `float has abs method`() {
            val method = registry.lookupMethod(ClassTypeRef("builtin", "float", false), "abs", "")
            assertNotNull(method)
        }

        @Test
        fun `float has floor method`() {
            val method = registry.lookupMethod(ClassTypeRef("builtin", "float", false), "floor", "")
            assertNotNull(method)
        }

        @Test
        fun `float has ceil method`() {
            val method = registry.lookupMethod(ClassTypeRef("builtin", "float", false), "ceil", "")
            assertNotNull(method)
        }

        @Test
        fun `float has round method`() {
            val method = registry.lookupMethod(ClassTypeRef("builtin", "float", false), "round", "")
            assertNotNull(method)
        }
    }

    @Nested
    inner class DoubleTypeTests {

        @Test
        fun `double has abs method`() {
            val method = registry.lookupMethod(ClassTypeRef("builtin", "double", false), "abs", "")
            assertNotNull(method)
        }

        @Test
        fun `double has round method`() {
            val method = registry.lookupMethod(ClassTypeRef("builtin", "double", false), "round", "")
            assertNotNull(method)
        }
    }

    @Nested
    inner class CollectionTypeTests {

        @Test
        fun `Collection has size method`() {
            val method = registry.lookupMethod(ClassTypeRef("builtin", "Collection", false), "size", "")
            assertNotNull(method)
        }

        @Test
        fun `Collection has isEmpty method`() {
            val method = registry.lookupMethod(ClassTypeRef("builtin", "Collection", false), "isEmpty", "")
            assertNotNull(method)
        }

        @Test
        fun `Collection has notEmpty method`() {
            val method = registry.lookupMethod(ClassTypeRef("builtin", "Collection", false), "notEmpty", "")
            assertNotNull(method)
        }

        @Test
        fun `Collection has sum method`() {
            val method = registry.lookupMethod(ClassTypeRef("builtin", "Collection", false), "sum", "")
            assertNotNull(method)
        }

        @Test
        fun `Collection has first method`() {
            val method = registry.lookupMethod(ClassTypeRef("builtin", "Collection", false), "first", "")
            assertNotNull(method)
        }

        @Test
        fun `Collection has last method`() {
            val method = registry.lookupMethod(ClassTypeRef("builtin", "Collection", false), "last", "")
            assertNotNull(method)
        }

        @Test
        fun `Collection has filter lambda method`() {
            val method = registry.lookupMethod(ClassTypeRef("builtin", "Collection", false), "filter", "")
            assertNotNull(method)
        }

        @Test
        fun `Collection has map lambda method`() {
            val method = registry.lookupMethod(ClassTypeRef("builtin", "Collection", false), "map", "")
            assertNotNull(method)
        }

        @Test
        fun `Collection has exists lambda method`() {
            val method = registry.lookupMethod(ClassTypeRef("builtin", "Collection", false), "exists", "")
            assertNotNull(method)
        }

        @Test
        fun `Collection has all lambda method`() {
            val method = registry.lookupMethod(ClassTypeRef("builtin", "Collection", false), "all", "")
            assertNotNull(method)
        }

        @Test
        fun `Collection has none lambda method`() {
            val method = registry.lookupMethod(ClassTypeRef("builtin", "Collection", false), "none", "")
            assertNotNull(method)
        }

        @Test
        fun `Collection has one lambda method`() {
            val method = registry.lookupMethod(ClassTypeRef("builtin", "Collection", false), "one", "")
            assertNotNull(method)
        }

        @Test
        fun `Collection has find lambda method`() {
            val method = registry.lookupMethod(ClassTypeRef("builtin", "Collection", false), "find", "")
            assertNotNull(method)
        }

        @Test
        fun `Collection has reject lambda method`() {
            val method = registry.lookupMethod(ClassTypeRef("builtin", "Collection", false), "reject", "")
            assertNotNull(method)
        }
    }

    @Nested
    inner class TypeHierarchyTests {

        @Test
        fun `Collection extends any`() {
            val collectionType = registry.getType(ClassTypeRef("builtin", "Collection", false))
            assertNotNull(collectionType)
            assertTrue(collectionType.extends.contains(ClassTypeRef("builtin", "Any", false)))
        }

        @Test
        fun `List extends OrderedCollection`() {
            val listType = registry.getType(ClassTypeRef("builtin", "List", false))
            assertNotNull(listType)
            assertTrue(listType.extends.contains(ClassTypeRef("builtin", "OrderedCollection", false)))
        }

        @Test
        fun `int extends any`() {
            val intType = registry.getType(ClassTypeRef("builtin", "int", false))
            assertNotNull(intType)
            assertTrue(intType.extends.contains(ClassTypeRef("builtin", "Any", false)))
        }

        @Test
        fun `string extends any`() {
            val stringType = registry.getType(ClassTypeRef("builtin", "string", false))
            assertNotNull(stringType)
            assertTrue(stringType.extends.contains(ClassTypeRef("builtin", "Any", false)))
        }

        @Test
        fun `OrderedCollection extends Collection`() {
            val orderedType = registry.getType(ClassTypeRef("builtin", "OrderedCollection", false))
            assertNotNull(orderedType)
            assertTrue(orderedType.extends.contains(ClassTypeRef("builtin", "Collection", false)))
        }

        @Test
        fun `OrderedSet extends OrderedCollection`() {
            val setType = registry.getType(ClassTypeRef("builtin", "OrderedSet", false))
            assertNotNull(setType)
            assertTrue(setType.extends.contains(ClassTypeRef("builtin", "OrderedCollection", false)))
        }

        @Test
        fun `Set extends Collection`() {
            val setType = registry.getType(ClassTypeRef("builtin", "Set", false))
            assertNotNull(setType)
            assertTrue(setType.extends.contains(ClassTypeRef("builtin", "Collection", false)))
        }

        @Test
        fun `Bag extends Collection`() {
            val bagType = registry.getType(ClassTypeRef("builtin", "Bag", false))
            assertNotNull(bagType)
            assertTrue(bagType.extends.contains(ClassTypeRef("builtin", "Collection", false)))
        }
    }
}
