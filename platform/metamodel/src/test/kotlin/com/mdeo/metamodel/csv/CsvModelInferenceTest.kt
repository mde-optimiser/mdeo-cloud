package com.mdeo.metamodel.csv

import com.mdeo.metamodel.data.ModelDataPropertyValue
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CsvModelInferenceTest {

    @Test
    fun `infers int, double, boolean and string columns`() {
        val csv = """
            id,price,active,name
            1,9.99,true,Widget
            2,12.5,false,Gadget
            3,7,true,Gizmo
        """.trimIndent()

        val result = CsvModelInference.inferFromCsv(csv, className = "product", metamodelPath = "/products.metamodel")

        val productClass = result.metamodel.classes.single()
        assertEquals("Product", productClass.name)

        val byName = productClass.properties.associateBy { it.name }
        assertEquals("int", byName["id"]?.primitiveType)
        assertEquals("double", byName["price"]?.primitiveType)
        assertEquals("boolean", byName["active"]?.primitiveType)
        assertEquals("string", byName["name"]?.primitiveType)

        assertEquals(3, result.model.instances.size)
        assertEquals("Product_0", result.model.instances[0].name)
        assertEquals(
            ModelDataPropertyValue.NumberValue(9.99),
            result.model.instances[0].properties["price"]
        )
        assertEquals(
            ModelDataPropertyValue.BooleanValue(true),
            result.model.instances[0].properties["active"]
        )
        assertEquals("/products.metamodel", result.model.metamodelPath)
    }

    @Test
    fun `infers enum for low-cardinality repeated text column`() {
        val csv = """
            id,status
            1,OPEN
            2,CLOSED
            3,OPEN
            4,OPEN
            5,CLOSED
        """.trimIndent()

        val result = CsvModelInference.inferFromCsv(csv, className = "ticket", metamodelPath = "/t.metamodel")

        val statusProp = result.metamodel.classes.single().properties.first { it.name == "status" }
        assertNotNull(statusProp.enumType)
        assertNull(statusProp.primitiveType)

        val enumDef = result.metamodel.enums.single()
        assertEquals(setOf("OPEN", "CLOSED"), enumDef.entries.toSet())

        val firstStatus = result.model.instances[0].properties["status"]
        assertTrue(firstStatus is ModelDataPropertyValue.EnumValue)
        assertEquals("OPEN", (firstStatus as ModelDataPropertyValue.EnumValue).enumEntry)
    }

    @Test
    fun `does not infer enum for high-cardinality or non-repeated text`() {
        val csv = """
            id,name
            1,Alice
            2,Bob
            3,Carol
        """.trimIndent()

        val result = CsvModelInference.inferFromCsv(csv, className = "person", metamodelPath = "/p.metamodel")
        val nameProp = result.metamodel.classes.single().properties.first { it.name == "name" }
        assertEquals("string", nameProp.primitiveType)
        assertNull(nameProp.enumType)
    }

    @Test
    fun `marks columns with blank values as optional`() {
        val csv = """
            id,nickname
            1,Ace
            2,
            3,Trey
        """.trimIndent()

        val result = CsvModelInference.inferFromCsv(csv, className = "player", metamodelPath = "/pl.metamodel")
        val props = result.metamodel.classes.single().properties.associateBy { it.name }

        assertTrue(props["id"]!!.multiplicity.isRequired())
        assertFalse(props["nickname"]!!.multiplicity.isRequired())

        assertEquals(ModelDataPropertyValue.NullValue, result.model.instances[1].properties["nickname"])
    }

    @Test
    fun `handles quoted fields with embedded commas and escaped quotes`() {
        val csv = "id,description\n1,\"Contains, a comma\"\n2,\"Has \"\"quotes\"\" inside\""

        val result = CsvModelInference.inferFromCsv(csv, className = "item", metamodelPath = "/i.metamodel")
        val descriptions = result.model.instances.map {
            (it.properties["description"] as ModelDataPropertyValue.StringValue).value
        }

        assertEquals(listOf("Contains, a comma", "Has \"quotes\" inside"), descriptions)
    }

    @Test
    fun `sanitizes header names into valid identifiers`() {
        val csv = """
            Item ID,Item Name,Price (USD)
            1,Widget,9.99
        """.trimIndent()

        val result = CsvModelInference.inferFromCsv(csv, className = "catalog item", metamodelPath = "/c.metamodel")
        val propNames = result.metamodel.classes.single().properties.map { it.name }

        assertEquals(listOf("Item_ID", "Item_Name", "Price__USD_"), propNames)
        assertEquals("Catalog_item", result.metamodel.classes.single().name)
    }

    @Test
    fun `rejects empty csv`() {
        assertThrows(IllegalArgumentException::class.java) {
            CsvModelInference.inferFromCsv("", className = "x", metamodelPath = "/x.metamodel")
        }
    }

    @Test
    fun `rejects header-only csv with no data rows`() {
        assertThrows(IllegalArgumentException::class.java) {
            CsvModelInference.inferFromCsv("a,b,c", className = "x", metamodelPath = "/x.metamodel")
        }
    }

    @Test
    fun `rejects duplicate column names`() {
        val csv = "a,a,b\n1,2,3"
        assertThrows(IllegalArgumentException::class.java) {
            CsvModelInference.inferFromCsv(csv, className = "x", metamodelPath = "/x.metamodel")
        }
    }

    @Test
    fun `pads ragged short rows and warns`() {
        val csv = "a,b,c\n1,2,3\n4,5"
        val result = CsvModelInference.inferFromCsv(csv, className = "x", metamodelPath = "/x.metamodel")

        assertEquals(2, result.model.instances.size)
        assertEquals(ModelDataPropertyValue.NullValue, result.model.instances[1].properties["c"])
        assertTrue(result.warnings.any { it.contains("fewer columns") })
    }
}
