package com.mdeo.metamodel

import com.mdeo.metamodel.data.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/**
 * A metamodel split over several files: `all.mm` imports `shop.mm` and `base.mm`, `shop.mm`
 * imports `base.mm` again and `base.mm` imports `shop.mm` back.
 *
 * ```
 * // base.mm
 * import "./shop.mm"
 * enum Unit { KG, PIECE }
 * class Item { weight: int }
 *
 * // shop.mm
 * import "./base.mm"
 * class Box extends Item { unit: Unit }
 * Box.items[*] --> Item
 * ```
 */
class MetamodelImportsTest {

    private val base = MetamodelData(
        path = "/base.mm",
        classes = listOf(
            ClassData("Item", false, properties = listOf(PropertyData("weight", primitiveType = "int", multiplicity = MultiplicityData.single())))
        ),
        enums = listOf(EnumData("Unit", listOf("KG", "PIECE"))),
        importedMetamodelPaths = listOf("/shop.mm")
    )

    private val shop = MetamodelData(
        path = "/shop.mm",
        classes = listOf(
            ClassData("Box", false, extends = listOf("Item"), properties = listOf(PropertyData("unit", enumType = "Unit", multiplicity = MultiplicityData.single())))
        ),
        associations = listOf(
            AssociationData(AssociationEndData("Box", "items", MultiplicityData.many()), "-->", AssociationEndData("Item", null, MultiplicityData.many()))
        ),
        importedMetamodelPaths = listOf("/base.mm")
    )

    private val all = MetamodelData(path = "/all.mm", importedMetamodelPaths = listOf("/shop.mm", "/base.mm"))

    private val files = listOf(base, shop, all).associateBy { it.path }

    /** Runs [block], whose fetches never suspend. */
    private fun <T> load(block: suspend () -> T): T {
        var result: Result<T>? = null
        block.startCoroutine(Continuation(EmptyCoroutineContext) { result = it })
        return result!!.getOrThrow()
    }

    @Test
    fun `loads every file imported directly or not, each once`() {
        val fetched = mutableListOf<String>()
        val merged = load { MetamodelData.load("/all.mm") { fetched += it; files[it] } }!!
        assertEquals(listOf("/all.mm", "/shop.mm", "/base.mm"), fetched)
        assertEquals("/all.mm", merged.path)
        assertEquals(listOf("/shop.mm", "/base.mm"), merged.importedMetamodelPaths)
        assertEquals(listOf("Box", "Item"), merged.classes.map { it.name })
        assertEquals(listOf("Unit"), merged.enums.map { it.name })
        assertEquals(1, merged.associations.size)
    }

    @Test
    fun `classes and enums name the file declaring them`() {
        val merged = load { MetamodelData.load("/shop.mm") { files[it] } }!!
        assertEquals("/shop.mm", merged.pathOf(merged.classes.single { it.name == "Box" }))
        assertEquals("/base.mm", merged.pathOf(merged.classes.single { it.name == "Item" }))
        assertEquals("/base.mm", merged.pathOf(merged.enums.single()))
        assertNull(merged.classes.single { it.name == "Box" }.path)
    }

    @Test
    fun `a file without imports is returned as it is`() {
        val alone = base.copy(importedMetamodelPaths = emptyList())
        assertSame(alone, load { MetamodelData.load("/base.mm") { alone } })
    }

    @Test
    fun `fails when an imported file cannot be fetched`() {
        assertNull(load { MetamodelData.load("/all.mm") { if (it == "/base.mm") null else files[it] } })
    }

    @Test
    fun `the merged metamodel compiles and loads models using classes of every file`() {
        val merged = load { MetamodelData.load("/all.mm") { files[it] } }!!
        val model = Metamodel.compile(merged).loadModel(
            ModelData(
                metamodelPath = "/all.mm",
                instances = listOf(
                    ModelDataInstance("box", "Box", mapOf("weight" to ModelDataPropertyValue.NumberValue(2.0), "unit" to ModelDataPropertyValue.EnumValue("KG"))),
                    ModelDataInstance("apple", "Item", mapOf("weight" to ModelDataPropertyValue.NumberValue(1.0)))
                ),
                links = listOf(ModelDataLink("box", "items", "apple", null))
            )
        )
        assertEquals(listOf("box", "apple"), model.getAllInstances("Item").map { model.nameOf(it) })
        assertEquals(1, (model.instancesByName.getValue("box").getPropertyByKey("items") as Set<*>).size)
    }
}
