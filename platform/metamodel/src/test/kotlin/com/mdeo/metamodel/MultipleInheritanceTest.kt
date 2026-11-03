package com.mdeo.metamodel

import com.mdeo.metamodel.data.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.IdentityHashMap

/**
 * Classes with several superclasses: every property and association end they inherit, through
 * whichever superclass, must be there at run time, in the generated classes, their metadata and
 * everything built on it.
 *
 * ```
 * class Named { name: string }
 * class Sized { size: int }
 * class Box extends Named, Sized { depth: double }
 * class Crate extends Box
 * class Item
 * Sized.items[*] --> Item
 * ```
 */
class MultipleInheritanceTest {

    private val data = MetamodelData(
        path = "/mi.mm",
        classes = listOf(
            ClassData(
                name = "Named", isAbstract = true,
                properties = listOf(PropertyData("name", primitiveType = "string", multiplicity = MultiplicityData.optional()))
            ),
            ClassData(
                name = "Sized", isAbstract = true,
                properties = listOf(PropertyData("size", primitiveType = "int", multiplicity = MultiplicityData.optional()))
            ),
            ClassData(
                name = "Box", isAbstract = false, extends = listOf("Named", "Sized"),
                properties = listOf(PropertyData("depth", primitiveType = "double", multiplicity = MultiplicityData.optional()))
            ),
            ClassData(name = "Crate", isAbstract = false, extends = listOf("Box")),
            ClassData(name = "Item", isAbstract = false)
        ),
        associations = listOf(
            AssociationData(
                source = AssociationEndData("Sized", "items", MultiplicityData.many()),
                operator = "-->",
                target = AssociationEndData("Item", null, MultiplicityData.many())
            )
        )
    )

    private val metamodel = Metamodel.compile(data)

    private fun model(): Model = metamodel.loadModel(
        ModelData(
            metamodelPath = "/mi.mm",
            instances = listOf(
                ModelDataInstance(
                    "crate", "Crate", mapOf(
                        "name" to ModelDataPropertyValue.StringValue("apples"),
                        "size" to ModelDataPropertyValue.NumberValue(3.0),
                        "depth" to ModelDataPropertyValue.NumberValue(0.5)
                    )
                ),
                ModelDataInstance("item", "Item", emptyMap())
            ),
            links = listOf(ModelDataLink("crate", "items", "item", null))
        )
    )

    @Test
    fun `a class has the properties and association ends of every superclass`() {
        for (className in listOf("Box", "Crate")) {
            val meta = metamodel.metadata.classes.getValue(className)
            assertEquals(setOf("name", "size", "depth"), meta.propertyFields.keys, className)
            assertEquals(setOf("items"), meta.linkFields.keys, className)
        }
    }

    @Test
    fun `a property has the same field in every class that has it`() {
        val classes = metamodel.metadata.classes
        for ((className, meta) in classes) {
            val indices = meta.propertyFields.values.map { it.fieldIndex } + meta.linkFields.values.map { it.fieldIndex }
            assertEquals(indices.size, indices.toSet().size, "$className has two properties on one field")
        }
        assertEquals(classes.getValue("Sized").propertyFields.getValue("size").fieldIndex, classes.getValue("Box").propertyFields.getValue("size").fieldIndex)
        assertEquals(classes.getValue("Sized").propertyFields.getValue("size").fieldIndex, classes.getValue("Crate").propertyFields.getValue("size").fieldIndex)
        assertEquals(classes.getValue("Sized").linkFields.getValue("items").fieldIndex, classes.getValue("Crate").linkFields.getValue("items").fieldIndex)
    }

    @Test
    fun `single inheritance keeps the linear layout`() {
        val single = Metamodel.compile(
            MetamodelData(
                classes = listOf(
                    ClassData("A", false, properties = listOf(PropertyData("a", primitiveType = "int", multiplicity = MultiplicityData.single()))),
                    ClassData("B", false, extends = listOf("A"), properties = listOf(PropertyData("b", primitiveType = "int", multiplicity = MultiplicityData.single()))),
                    ClassData("C", false, extends = listOf("A"), properties = listOf(PropertyData("c", primitiveType = "int", multiplicity = MultiplicityData.single())))
                )
            )
        )
        val classes = single.metadata.classes
        assertEquals(0, classes.getValue("A").propertyFields.getValue("a").fieldIndex)
        assertEquals(1, classes.getValue("B").propertyFields.getValue("b").fieldIndex)
        assertEquals(1, classes.getValue("C").propertyFields.getValue("c").fieldIndex)
        assertEquals(2, classes.getValue("B").totalFieldCount)
    }

    @Test
    fun `values inherited through the second superclass are loaded, read and written`() {
        val crate = model().instancesByName.getValue("crate")
        assertEquals("apples", crate.getPropertyByKey("name"))
        assertEquals(3, crate.getPropertyByKey("size"))
        assertEquals(0.5, crate.getPropertyByKey("depth"))
        assertEquals(1, (crate.getPropertyByKey("items") as Set<*>).size)
        crate.setPropertyByKey("size", 7)
        assertEquals(7, crate.getPropertyByKey("size"))
    }

    @Test
    fun `an instance implements the type interface of every superclass`() {
        val crate = model().instancesByName.getValue("crate")
        for (className in listOf("Named", "Sized", "Box", "Crate")) {
            val typeInterface = metamodel.classLoader.loadClass(Metamodel.getInstanceInterfaceName(className).replace('/', '.'))
            assertTrue(typeInterface.isInstance(crate), "not a $className")
        }
        val sized = metamodel.classLoader.loadClass(Metamodel.getInstanceInterfaceName("Sized").replace('/', '.'))
        val sizeField = metamodel.metadata.classes.getValue("Sized").propertyFields.getValue("size").fieldIndex
        assertEquals(3, sized.getMethod(Metamodel.getFieldGetterName(sizeField)).invoke(crate))
    }

    @Test
    fun `all instances of a class include those inheriting through a second superclass`() {
        val model = model()
        assertEquals(listOf("crate"), model.getAllInstances("Sized").map { model.nameOf(it) })
        assertTrue("Crate" in metamodel.metadata.classHierarchy.getValue("Sized"))
    }

    @Test
    fun `copies, model data and binary serialization keep every inherited value`() {
        val model = model()
        val crate = model.instancesByName.getValue("crate")
        val copy = crate.copy()
        crate.copyReferences(copy, IdentityHashMap<ModelInstance, ModelInstance>())
        assertEquals(3, copy.getPropertyByKey("size"))
        assertEquals(1, (copy.getPropertyByKey("items") as Set<*>).size)

        val crateData = model.toModelData().instances.single { it.name == "crate" }
        assertEquals(ModelDataPropertyValue.NumberValue(3.0), crateData.properties["size"])
        assertTrue(model.toModelData().links.any { it.sourceName == "crate" && it.sourceProperty == "items" })

        val names = IdentityHashMap<ModelInstance, String>()
        for ((name, instance) in model.instancesByName) names[instance] = name
        val serializer = ModelBinarySerializer(metamodel)
        val (restored, _) = serializer.deserialize(serializer.serialize(model, names))
        val restoredCrate = restored.instancesByName.getValue("crate")
        assertEquals("apples", restoredCrate.getPropertyByKey("name"))
        assertEquals(3, restoredCrate.getPropertyByKey("size"))
        assertEquals(1, (restoredCrate.getPropertyByKey("items") as Set<*>).size)
    }
}
