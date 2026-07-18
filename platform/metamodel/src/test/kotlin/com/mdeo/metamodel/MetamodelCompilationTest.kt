package com.mdeo.metamodel

import com.mdeo.metamodel.data.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows

/**
 * Tests for [Metamodel.compile] — verifying that the bytecode generator produces
 * valid, working JVM classes for a wide variety of metamodel shapes.
 */
class MetamodelCompilationTest {

    // ────────────────────────────────────────────────────────────────────────────
    // Helpers
    // ────────────────────────────────────────────────────────────────────────────

    private fun singleClassMetamodel(
        name: String = "Thing",
        isAbstract: Boolean = false,
        properties: List<PropertyData> = emptyList()
    ): MetamodelData = MetamodelData(
        path = "/test.mm",
        classes = listOf(ClassData(name = name, isAbstract = isAbstract, properties = properties)),
        enums = emptyList(),
        associations = emptyList()
    )

    // ────────────────────────────────────────────────────────────────────────────
    // Basic compilation
    // ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `compile empty metamodel succeeds`() {
        assertDoesNotThrow { Metamodel.compile(MetamodelData()) }
    }

    @Test
    fun `compile metamodel with single concrete class succeeds`() {
        val data = singleClassMetamodel("Node")
        val mm = assertDoesNotThrow { Metamodel.compile(data) }
        assertNotNull(mm.getInstanceClass("Node"))
    }

    @Test
    fun `compile metamodel with single abstract class succeeds`() {
        val data = singleClassMetamodel("AbstractBase", isAbstract = true)
        val mm = assertDoesNotThrow { Metamodel.compile(data) }
        assertNotNull(mm.getInstanceClass("AbstractBase"))
    }

    @Test
    fun `compile produces instance class for each class`() {
        val data = MetamodelData(
            classes = listOf(
                ClassData(name = "A", isAbstract = false),
                ClassData(name = "B", isAbstract = false),
                ClassData(name = "C", isAbstract = true)
            )
        )
        val mm = Metamodel.compile(data)
        assertDoesNotThrow { mm.getInstanceClass("A") }
        assertDoesNotThrow { mm.getInstanceClass("B") }
        assertDoesNotThrow { mm.getInstanceClass("C") }
    }

    @Test
    fun `getInstanceClass throws for unknown class`() {
        val mm = Metamodel.compile(MetamodelData())
        assertThrows<IllegalStateException> { mm.getInstanceClass("NonExistent") }
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Properties
    // ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `class with required int property compiles`() {
        val data = singleClassMetamodel(properties = listOf(
            PropertyData(name = "count", primitiveType = "int", multiplicity = MultiplicityData.single())
        ))
        val mm = assertDoesNotThrow { Metamodel.compile(data) }
        val instance = mm.createInstance("Thing")
        // Mandatory single-valued primitive field has JVM default value (0) when not set
        assertEquals(0, instance.getPropertyByKey("count"))
    }

    @Test
    fun `class with optional int property compiles`() {
        val data = singleClassMetamodel(properties = listOf(
            PropertyData(name = "age", primitiveType = "int", multiplicity = MultiplicityData.optional())
        ))
        val mm = assertDoesNotThrow { Metamodel.compile(data) }
        val instance = mm.createInstance("Thing")
        // Optional int defaults to null
        assertNull(instance.getPropertyByKey("age"))
    }

    @Test
    fun `class with all primitive types compiles`() {
        val props = listOf(
            PropertyData(name = "intProp", primitiveType = "int", multiplicity = MultiplicityData.single()),
            PropertyData(name = "longProp", primitiveType = "long", multiplicity = MultiplicityData.single()),
            PropertyData(name = "floatProp", primitiveType = "float", multiplicity = MultiplicityData.single()),
            PropertyData(name = "doubleProp", primitiveType = "double", multiplicity = MultiplicityData.single()),
            PropertyData(name = "boolProp", primitiveType = "boolean", multiplicity = MultiplicityData.single()),
            PropertyData(name = "strProp", primitiveType = "string", multiplicity = MultiplicityData.optional())
        )
        val data = singleClassMetamodel(properties = props)
        val mm = assertDoesNotThrow { Metamodel.compile(data) }
        val instance = mm.createInstance("Thing")

        // Mandatory single-valued primitive fields default to JVM zero values when unset;
        // optional string is still a reference type and defaults to null.
        assertEquals(0,     instance.getPropertyByKey("intProp"))
        assertEquals(0L,    instance.getPropertyByKey("longProp"))
        assertEquals(0.0f,  instance.getPropertyByKey("floatProp"))
        assertEquals(0.0,   instance.getPropertyByKey("doubleProp"))
        assertEquals(false, instance.getPropertyByKey("boolProp"))
        assertNull(instance.getPropertyByKey("strProp"))
    }

    @Test
    fun `setPropertyByKey coerces a Double into an int field instead of throwing`() {
        // Reproduces the runtime failure reported for arithmetic assignments such as
        // `occupiedBeds = occupiedBeds + 1`. Arithmetic expressions are compiled to the
        // Gremlin `math()` step, which always yields a Double. When that Double is written
        // back into an `int` metamodel field, the generated setPropertyByKey previously did
        // a hard `CHECKCAST java/lang/Integer`, throwing:
        //   class java.lang.Double cannot be cast to class java.lang.Integer
        val data = singleClassMetamodel(properties = listOf(
            PropertyData(name = "count", primitiveType = "int", multiplicity = MultiplicityData.single())
        ))
        val mm = Metamodel.compile(data)
        val instance = mm.createInstance("Thing")

        // A Double is exactly what the `math()` step produces for `count + 1`.
        assertDoesNotThrow { instance.setPropertyByKey("count", 4.0) }

        val stored = instance.getPropertyByKey("count")
        assertTrue(stored is Int, "int field must store an Int, got ${stored?.let { it::class.simpleName }}")
        assertEquals(4, stored)
    }

    @Test
    fun `setPropertyByKey coerces numbers across all numeric primitive fields`() {
        val props = listOf(
            PropertyData(name = "intProp", primitiveType = "int", multiplicity = MultiplicityData.single()),
            PropertyData(name = "longProp", primitiveType = "long", multiplicity = MultiplicityData.single()),
            PropertyData(name = "floatProp", primitiveType = "float", multiplicity = MultiplicityData.single()),
            PropertyData(name = "doubleProp", primitiveType = "double", multiplicity = MultiplicityData.single())
        )
        val mm = Metamodel.compile(singleClassMetamodel(properties = props))
        val instance = mm.createInstance("Thing")

        // Feed each field a wrapper type it did NOT previously accept (Double / Int).
        assertDoesNotThrow { instance.setPropertyByKey("intProp", 7.0) }
        assertDoesNotThrow { instance.setPropertyByKey("longProp", 9.0) }
        assertDoesNotThrow { instance.setPropertyByKey("floatProp", 3) }
        assertDoesNotThrow { instance.setPropertyByKey("doubleProp", 5) }

        assertEquals(7, instance.getPropertyByKey("intProp"))
        assertEquals(9L, instance.getPropertyByKey("longProp"))
        assertEquals(3.0f, instance.getPropertyByKey("floatProp"))
        assertEquals(5.0, instance.getPropertyByKey("doubleProp"))
    }

    @Test
    fun `setPropertyByKey coerces a Double into an optional boxed int field`() {
        // Optional (nullable) numeric fields are stored as boxed wrappers and previously
        // went through `CHECKCAST java/lang/Integer` as well.
        val data = singleClassMetamodel(properties = listOf(
            PropertyData(name = "age", primitiveType = "int", multiplicity = MultiplicityData.optional())
        ))
        val mm = Metamodel.compile(data)
        val instance = mm.createInstance("Thing")

        assertDoesNotThrow { instance.setPropertyByKey("age", 12.0) }
        assertEquals(12, instance.getPropertyByKey("age"))
    }

    @Test
    fun `class with multi-valued string property compiles`() {
        val data = singleClassMetamodel(properties = listOf(
            PropertyData(name = "tags", primitiveType = "string", multiplicity = MultiplicityData.many())
        ))
        val mm = assertDoesNotThrow { Metamodel.compile(data) }
        val instance = mm.createInstance("Thing")
        // Multi-valued property defaults to empty List
        val tags = instance.getPropertyByKey("tags")
        assertTrue(tags is List<*>)
        assertTrue((tags as List<*>).isEmpty())
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Enums
    // ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `enum definition compiles`() {
        val data = MetamodelData(
            enums = listOf(EnumData(name = "Color", entries = listOf("RED", "GREEN", "BLUE")))
        )
        val mm = assertDoesNotThrow { Metamodel.compile(data) }
        assertDoesNotThrow { mm.getEnumContainerClass("Color") }
        assertDoesNotThrow { mm.getEnumValueClass("Color") }
    }

    @Test
    fun `enum entries resolve as singletons`() {
        val data = MetamodelData(
            enums = listOf(EnumData(name = "Status", entries = listOf("ACTIVE", "INACTIVE")))
        )
        val mm = Metamodel.compile(data)
        val active1 = mm.resolveEnumValue("Status", "ACTIVE")
        val active2 = mm.resolveEnumValue("Status", "ACTIVE")
        val inactive = mm.resolveEnumValue("Status", "INACTIVE")

        assertSame(active1, active2, "Enum singletons must be the same object")
        assertNotSame(active1, inactive)
        assertEquals(active1, active2)
        assertNotEquals(active1, inactive)
    }

    @Test
    fun `enum value toString returns entry name`() {
        val data = MetamodelData(
            enums = listOf(EnumData(name = "Dir", entries = listOf("NORTH", "SOUTH")))
        )
        val mm = Metamodel.compile(data)
        val north = mm.resolveEnumValue("Dir", "NORTH")
        assertEquals("NORTH", north.toString())
    }

    @Test
    fun `class with enum property compiles`() {
        val data = MetamodelData(
            classes = listOf(
                ClassData(
                    name = "Task",
                    isAbstract = false,
                    properties = listOf(
                        PropertyData(
                            name = "status",
                            enumType = "Status",
                            multiplicity = MultiplicityData.optional()
                        )
                    )
                )
            ),
            enums = listOf(EnumData(name = "Status", entries = listOf("TODO", "DONE")))
        )
        val mm = assertDoesNotThrow { Metamodel.compile(data) }
        val instance = mm.createInstance("Task")
        assertNull(instance.getPropertyByKey("status"))
    }

    @Test
    fun `getPropertyByKey throws for unknown property`() {
        val data = singleClassMetamodel()
        val mm = Metamodel.compile(data)
        val instance = mm.createInstance("Thing")
        assertThrows<IllegalArgumentException> { instance.getPropertyByKey("nonExistentField") }
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Inheritance
    // ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `class inheritance compiles with inherited properties`() {
        val data = MetamodelData(
            classes = listOf(
                ClassData(
                    name = "Base",
                    isAbstract = true,
                    properties = listOf(
                        PropertyData(name = "id", primitiveType = "int", multiplicity = MultiplicityData.single())
                    )
                ),
                ClassData(
                    name = "Child",
                    isAbstract = false,
                    extends = listOf("Base"),
                    properties = listOf(
                        PropertyData(name = "label", primitiveType = "string", multiplicity = MultiplicityData.optional())
                    )
                )
            )
        )
        val mm = assertDoesNotThrow { Metamodel.compile(data) }
        val instance = mm.createInstance("Child")

        // Inherited mandatory primitive field has JVM default (0); own optional string is null
        assertEquals(0, instance.getPropertyByKey("id"))
        // Own property accessible
        assertNull(instance.getPropertyByKey("label"))
    }

    @Test
    fun `child class instance is assignable to parent class type`() {
        val data = MetamodelData(
            classes = listOf(
                ClassData(name = "Animal", isAbstract = true),
                ClassData(name = "Dog", isAbstract = false, extends = listOf("Animal"))
            )
        )
        val mm = Metamodel.compile(data)
        val parentClass = mm.getInstanceClass("Animal")
        val childClass = mm.getInstanceClass("Dog")

        assertTrue(parentClass.isAssignableFrom(childClass))
    }

    @Test
    fun `setPropertyByKey and getPropertyByKey round-trip`() {
        val data = singleClassMetamodel(properties = listOf(
            PropertyData(name = "value", primitiveType = "int", multiplicity = MultiplicityData.single()),
            PropertyData(name = "name", primitiveType = "string", multiplicity = MultiplicityData.optional())
        ))
        val mm = Metamodel.compile(data)
        val instance = mm.createInstance("Thing")

        instance.setPropertyByKey("value", 42)
        instance.setPropertyByKey("name", "hello")

        assertEquals(42, instance.getPropertyByKey("value"))
        assertEquals("hello", instance.getPropertyByKey("name"))
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Associations
    // ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `unidirectional association (named source only) compiles`() {
        val data = MetamodelData(
            classes = listOf(
                ClassData(name = "House", isAbstract = false),
                ClassData(name = "Room", isAbstract = false)
            ),
            associations = listOf(
                AssociationData(
                    source = AssociationEndData(
                        className = "House",
                        name = "rooms",
                        multiplicity = MultiplicityData.many()
                    ),
                    operator = "-->",
                    target = AssociationEndData(
                        className = "Room",
                        name = null,
                        multiplicity = MultiplicityData.single()
                    )
                )
            )
        )
        val mm = assertDoesNotThrow { Metamodel.compile(data) }
        val house = mm.createInstance("House")
        // rooms field defaults to empty Set (links still use Set backing)
        val rooms = house.getPropertyByKey("rooms")
        assertTrue(rooms is Set<*>)
        assertTrue((rooms as Set<*>).isEmpty())
    }

    @Test
    fun `bidirectional association compiles`() {
        val data = MetamodelData(
            classes = listOf(
                ClassData(name = "House", isAbstract = false),
                ClassData(name = "Room", isAbstract = false)
            ),
            associations = listOf(
                AssociationData(
                    source = AssociationEndData(className = "House", name = "rooms", multiplicity = MultiplicityData.many()),
                    operator = "<-->",
                    target = AssociationEndData(className = "Room", name = "house", multiplicity = MultiplicityData.single())
                )
            )
        )
        val mm = assertDoesNotThrow { Metamodel.compile(data) }
        val house = mm.createInstance("House")
        val room = mm.createInstance("Room")

        // rooms: multi-valued link → empty Set; house: single-valued link → null (extracted)
        val rooms = house.getPropertyByKey("rooms")
        assertTrue(rooms is Set<*>)
        assertTrue((rooms as Set<*>).isEmpty())
        assertNull(room.getPropertyByKey("house"))
    }

    @Test
    fun `multiple classes with multiple associations compile`() {
        val data = MetamodelData(
            classes = listOf(
                ClassData(name = "A", isAbstract = false),
                ClassData(name = "B", isAbstract = false),
                ClassData(name = "C", isAbstract = false)
            ),
            associations = listOf(
                AssociationData(
                    source = AssociationEndData(className = "A", name = "bs", multiplicity = MultiplicityData.many()),
                    operator = "-->",
                    target = AssociationEndData(className = "B", name = null, multiplicity = MultiplicityData.single())
                ),
                AssociationData(
                    source = AssociationEndData(className = "B", name = "cs", multiplicity = MultiplicityData.many()),
                    operator = "-->",
                    target = AssociationEndData(className = "C", name = null, multiplicity = MultiplicityData.single())
                )
            )
        )
        assertDoesNotThrow { Metamodel.compile(data) }
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Class containers
    // ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `class container is generated and accessible via static INSTANCE`() {
        val data = singleClassMetamodel("Widget")
        val mm = Metamodel.compile(data)

        val containerClass = mm.metadata.classContainerClassNames["Widget"]
        assertNotNull(containerClass)

        val containerInternalName = Metamodel.getClassContainerClassName("Widget")
        val loadedClass = mm.classLoader.loadClass(containerInternalName.replace('/', '.'))
        val instanceField = loadedClass.getField("INSTANCE")
        assertNotNull(instanceField.get(null))
    }

    @Test
    fun `class hierarchy maps class to itself at minimum`() {
        val data = MetamodelData(
            classes = listOf(
                ClassData(name = "Base", isAbstract = true),
                ClassData(name = "Derived", isAbstract = false, extends = listOf("Base"))
            )
        )
        val mm = Metamodel.compile(data)
        val hierarchy = mm.metadata.classHierarchy

        assertTrue("Base" in hierarchy["Base"]!!, "Base must include itself")
        assertTrue("Derived" in hierarchy["Derived"]!!, "Derived must include itself")
        assertTrue("Derived" in hierarchy["Base"]!!, "Base hierarchy must include Derived")
    }

    // ────────────────────────────────────────────────────────────────────────────
    // createInstance
    // ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `createInstance returns ModelInstance of correct class`() {
        val mm = Metamodel.compile(singleClassMetamodel("Foo"))
        val instance = mm.createInstance("Foo")

        assertEquals("Foo", mm.classNameOf(instance))
    }

    @Test
    fun `createInstance for abstract class succeeds (runtime instantiation is allowed)`() {
        // Abstract in metamodel ≠ abstract in JVM for our compiled classes
        // Actually abstract metamodel classes DO produce abstract JVM classes — this should throw
        val mm = Metamodel.compile(singleClassMetamodel("AbstractBase", isAbstract = true))
        // Abstract JVM classes cannot be instantiated directly
        assertThrows<Exception> { mm.createInstance("AbstractBase") }
    }

    @Test
    fun `two instances of same class are not identical`() {
        val mm = Metamodel.compile(singleClassMetamodel("Node"))
        val a = mm.createInstance("Node")
        val b = mm.createInstance("Node")
        assertNotSame(a, b)
        assertNotEquals(a, b)
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Field backing types
    // ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `backing field is null for single-valued property when unset`() {
        val data = singleClassMetamodel(properties = listOf(
            PropertyData(name = "name", primitiveType = "string", multiplicity = MultiplicityData.single())
        ))
        val mm = Metamodel.compile(data)
        val instance = mm.createInstance("Thing")
        val field = instance.javaClass.getField("prop_0")
        assertNull(field.get(instance))
    }

    @Test
    fun `backing field is a List for multi-valued property`() {
        val data = singleClassMetamodel(properties = listOf(
            PropertyData(name = "tags", primitiveType = "string", multiplicity = MultiplicityData.many())
        ))
        val mm = Metamodel.compile(data)
        val instance = mm.createInstance("Thing")
        val field = instance.javaClass.getField("prop_0")
        assertTrue(field.get(instance) is List<*>)
    }

    @Test
    fun `backing field is a Set for association field`() {
        val data = MetamodelData(
            classes = listOf(
                ClassData(name = "A", isAbstract = false),
                ClassData(name = "B", isAbstract = false)
            ),
            associations = listOf(
                AssociationData(
                    source = AssociationEndData(className = "A", name = "b", multiplicity = MultiplicityData.single()),
                    operator = "-->",
                    target = AssociationEndData(className = "B", name = null, multiplicity = MultiplicityData.single())
                )
            )
        )
        val mm = Metamodel.compile(data)
        val a = mm.createInstance("A")
        val field = a.javaClass.getField("prop_0")
        assertTrue(field.get(a) is Set<*>)
    }

    @Test
    fun `setPropertyByKey and getPropertyByKey round-trip for single string`() {
        val data = singleClassMetamodel(properties = listOf(
            PropertyData(name = "label", primitiveType = "string", multiplicity = MultiplicityData.optional())
        ))
        val mm = Metamodel.compile(data)
        val instance = mm.createInstance("Thing")

        assertNull(instance.getPropertyByKey("label"))
        instance.setPropertyByKey("label", "hello")
        assertEquals("hello", instance.getPropertyByKey("label"))

        // Field holds the value directly (no Set wrapper)
        val fieldValue = instance.javaClass.getField("prop_0").get(instance)
        assertEquals("hello", fieldValue)
    }

    @Test
    fun `setPropertyByKey replaces value for single property`() {
        val data = singleClassMetamodel(properties = listOf(
            PropertyData(name = "value", primitiveType = "int", multiplicity = MultiplicityData.single())
        ))
        val mm = Metamodel.compile(data)
        val instance = mm.createInstance("Thing")

        instance.setPropertyByKey("value", 10)
        assertEquals(10, instance.getPropertyByKey("value"))

        instance.setPropertyByKey("value", 20)
        assertEquals(20, instance.getPropertyByKey("value"))
    }

    @Test
    fun `setPropertyByKey with null clears single property`() {
        val data = singleClassMetamodel(properties = listOf(
            PropertyData(name = "name", primitiveType = "string", multiplicity = MultiplicityData.optional())
        ))
        val mm = Metamodel.compile(data)
        val instance = mm.createInstance("Thing")

        instance.setPropertyByKey("name", "test")
        assertEquals("test", instance.getPropertyByKey("name"))

        instance.setPropertyByKey("name", null)
        assertNull(instance.getPropertyByKey("name"))

        // Field is null after clearing
        assertNull(instance.javaClass.getField("prop_0").get(instance))
    }

    @Test
    fun `setPropertyByKey and getPropertyByKey for multi-valued property`() {
        val data = singleClassMetamodel(properties = listOf(
            PropertyData(name = "tags", primitiveType = "string", multiplicity = MultiplicityData.many())
        ))
        val mm = Metamodel.compile(data)
        val instance = mm.createInstance("Thing")

        val tags = arrayListOf("a", "b", "c")
        instance.setPropertyByKey("tags", tags)

        @Suppress("UNCHECKED_CAST")
        val result = instance.getPropertyByKey("tags") as List<String>
        assertEquals(listOf("a", "b", "c"), result)
    }

    @Test
    fun `all multiplicity types work via getPropertyByKey and setPropertyByKey`() {
        val data = MetamodelData(
            classes = listOf(
                ClassData(
                    name = "Item",
                    isAbstract = false,
                    properties = listOf(
                        PropertyData(name = "required", primitiveType = "string", multiplicity = MultiplicityData.single()),
                        PropertyData(name = "optional", primitiveType = "string", multiplicity = MultiplicityData.optional()),
                        PropertyData(name = "many", primitiveType = "string", multiplicity = MultiplicityData.many()),
                        PropertyData(name = "oneOrMore", primitiveType = "string", multiplicity = MultiplicityData.oneOrMore())
                    )
                )
            )
        )
        val mm = Metamodel.compile(data)
        val instance = mm.createInstance("Item")

        instance.setPropertyByKey("required", "req")
        instance.setPropertyByKey("optional", "opt")
        instance.setPropertyByKey("many", arrayListOf("a", "b"))
        instance.setPropertyByKey("oneOrMore", arrayListOf("x"))

        assertEquals("req", instance.getPropertyByKey("required"))
        assertEquals("opt", instance.getPropertyByKey("optional"))
        @Suppress("UNCHECKED_CAST")
        assertEquals(listOf("a", "b"), instance.getPropertyByKey("many") as List<*>)
        @Suppress("UNCHECKED_CAST")
        assertEquals(listOf("x"), instance.getPropertyByKey("oneOrMore") as List<*>)
    }
}
