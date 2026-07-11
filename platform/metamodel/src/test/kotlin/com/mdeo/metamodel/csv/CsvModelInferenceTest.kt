package com.mdeo.metamodel.csv

import com.mdeo.metamodel.data.ClassData
import com.mdeo.metamodel.data.EnumData
import com.mdeo.metamodel.data.MetamodelData
import com.mdeo.metamodel.data.ModelDataPropertyValue
import com.mdeo.metamodel.data.MultiplicityData
import com.mdeo.metamodel.data.PropertyData
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CsvModelInferenceTest {

    private fun metamodel(vararg classes: ClassData, enums: List<EnumData> = emptyList()) =
        MetamodelData(path = "/test.metamodel", classes = classes.toList(), enums = enums)

    private fun cls(name: String, vararg properties: PropertyData) =
        ClassData(name = name, isAbstract = false, properties = properties.toList())

    private fun prop(name: String, primitiveType: String, required: Boolean = true) =
        PropertyData(
            name = name,
            primitiveType = primitiveType,
            enumType = null,
            multiplicity = if (required) MultiplicityData.single() else MultiplicityData.optional()
        )

    private fun enumProp(name: String, enumType: String, required: Boolean = true) =
        PropertyData(
            name = name,
            primitiveType = null,
            enumType = enumType,
            multiplicity = if (required) MultiplicityData.single() else MultiplicityData.optional()
        )

    @Test
    fun `maps columns to metamodel properties by name`() {
        val mm = metamodel(cls("Employee",
            prop("id", "int"),
            prop("name", "string"),
            prop("salary", "double"),
            prop("active", "boolean")
        ))

        val csv = """
            id,name,salary,active
            1,Alice,95000.0,true
            2,Bob,72000.5,false
        """.trimIndent()

        val result = CsvModelInference.importFromCsv(csv, "Employee", mm, "/test.metamodel")

        assertEquals(2, result.model.instances.size)
        val alice = result.model.instances[0]
        assertEquals("Employee_0", alice.name)
        assertEquals(ModelDataPropertyValue.NumberValue(1.0), alice.properties["id"])
        assertEquals(ModelDataPropertyValue.StringValue("Alice"), alice.properties["name"])
        assertEquals(ModelDataPropertyValue.NumberValue(95000.0), alice.properties["salary"])
        assertEquals(ModelDataPropertyValue.BooleanValue(true), alice.properties["active"])
    }

    @Test
    fun `maps enum values from metamodel`() {
        val mm = metamodel(
            cls("Ticket", enumProp("status", "StatusEnum")),
            enums = listOf(EnumData(name = "StatusEnum", entries = listOf("OPEN", "CLOSED")))
        )

        val csv = "status\nOPEN\nCLOSED\nOPEN"
        val result = CsvModelInference.importFromCsv(csv, "Ticket", mm, "/test.metamodel")

        assertEquals(ModelDataPropertyValue.EnumValue("OPEN"), result.model.instances[0].properties["status"])
        assertEquals(ModelDataPropertyValue.EnumValue("CLOSED"), result.model.instances[1].properties["status"])
    }

    @Test
    fun `treats blank cells as null`() {
        val mm = metamodel(cls("Person",
            prop("name", "string"),
            prop("nickname", "string", required = false)
        ))

        val csv = "name,nickname\nAlice,Ace\nBob,"
        val result = CsvModelInference.importFromCsv(csv, "Person", mm, "/test.metamodel")

        assertEquals(ModelDataPropertyValue.NullValue, result.model.instances[1].properties["nickname"])
    }

    @Test
    fun `warns about unknown columns`() {
        val mm = metamodel(cls("Person", prop("name", "string")))
        val csv = "name,unknown_col\nAlice,extra"
        val result = CsvModelInference.importFromCsv(csv, "Person", mm, "/test.metamodel")

        assertTrue(result.warnings.any { it.contains("unknown_col") })
    }

    @Test
    fun `throws when class not found in metamodel`() {
        val mm = metamodel(cls("Person", prop("name", "string")))
        val csv = "name\nAlice"

        assertThrows(IllegalArgumentException::class.java) {
            CsvModelInference.importFromCsv(csv, "NonExistent", mm, "/test.metamodel")
        }
    }

    @Test
    fun `throws when required property is missing from csv`() {
        val mm = metamodel(cls("Person",
            prop("name", "string"),
            prop("id", "int")
        ))
        val csv = "name\nAlice"

        assertThrows(IllegalArgumentException::class.java) {
            CsvModelInference.importFromCsv(csv, "Person", mm, "/test.metamodel")
        }
    }

    @Test
    fun `pads ragged short rows and warns`() {
        val mm = metamodel(cls("Person",
            prop("name", "string"),
            prop("age", "int", required = false)
        ))
        val csv = "name,age\nAlice,30\nBob"
        val result = CsvModelInference.importFromCsv(csv, "Person", mm, "/test.metamodel")

        assertEquals(2, result.model.instances.size)
        assertEquals(ModelDataPropertyValue.NullValue, result.model.instances[1].properties["age"])
        assertTrue(result.warnings.any { it.contains("fewer columns") })
    }

    @Test
    fun `handles quoted fields with embedded commas`() {
        val mm = metamodel(cls("Item", prop("description", "string")))
        val csv = "description\n\"Contains, a comma\""
        val result = CsvModelInference.importFromCsv(csv, "Item", mm, "/test.metamodel")

        assertEquals(
            ModelDataPropertyValue.StringValue("Contains, a comma"),
            result.model.instances[0].properties["description"]
        )
    }

    @Test
    fun `gracefully falls back to string for unparseable int`() {
        val mm = metamodel(cls("Item", prop("count", "int")))
        val csv = "count\nnot_a_number"
        val result = CsvModelInference.importFromCsv(csv, "Item", mm, "/test.metamodel")

        assertEquals(
            ModelDataPropertyValue.StringValue("not_a_number"),
            result.model.instances[0].properties["count"]
        )
    }
}
