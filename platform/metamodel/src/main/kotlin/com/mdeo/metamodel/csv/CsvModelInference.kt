package com.mdeo.metamodel.csv

import com.mdeo.metamodel.data.ClassData
import com.mdeo.metamodel.data.EnumData
import com.mdeo.metamodel.data.MetamodelData
import com.mdeo.metamodel.data.ModelData
import com.mdeo.metamodel.data.ModelDataInstance
import com.mdeo.metamodel.data.ModelDataPropertyValue
import com.mdeo.metamodel.data.MultiplicityData
import com.mdeo.metamodel.data.PropertyData

object CsvModelInference {

    const val DEFAULT_ENUM_MAX_DISTINCT = 20

    data class InferenceResult(
        val metamodel: MetamodelData,
        val model: ModelData,
        val warnings: List<String>
    )

    fun inferFromCsv(
        csvText: String,
        className: String,
        metamodelPath: String,
        enumMaxDistinct: Int = DEFAULT_ENUM_MAX_DISTINCT
    ): InferenceResult {
        val rows = parseCsv(csvText)
        require(rows.isNotEmpty()) { "CSV has no header row" }

        val header = rows.first()
        require(header.isNotEmpty()) { "CSV header row is empty" }
        val duplicates = header.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        require(duplicates.isEmpty()) { "CSV header has duplicate column names: ${duplicates.joinToString()}" }

        val dataRows = rows.drop(1)
        require(dataRows.isNotEmpty()) { "CSV has a header row but no data rows" }

        val warnings = mutableListOf<String>()

        val normalizedRows = dataRows.mapIndexed { rowIndex, row ->
            when {
                row.size == header.size -> row
                row.size < header.size -> {
                    warnings.add("Row ${rowIndex + 2} has fewer columns than the header; missing values treated as blank.")
                    row + List(header.size - row.size) { "" }
                }
                else -> {
                    warnings.add("Row ${rowIndex + 2} has more columns than the header; extra values ignored.")
                    row.take(header.size)
                }
            }
        }

        val columns: List<List<String>> = header.indices.map { colIndex ->
            normalizedRows.map { it[colIndex] }
        }

        val properties = mutableListOf<PropertyData>()
        val enums = mutableListOf<EnumData>()

        header.forEachIndexed { colIndex, rawColumnName ->
            val propertyName = sanitizePropertyName(rawColumnName, fallback = "column${colIndex + 1}")
            val values = columns[colIndex]
            val nonBlankValues = values.filter { it.isNotBlank() }
            val isOptional = nonBlankValues.size < values.size

            val inferred = inferColumnType(propertyName, nonBlankValues, enumMaxDistinct)
            if (inferred is ColumnType.Enum) {
                enums.add(EnumData(name = inferred.enumName, entries = inferred.entries))
            }

            properties.add(
                PropertyData(
                    name = propertyName,
                    enumType = (inferred as? ColumnType.Enum)?.enumName,
                    primitiveType = (inferred as? ColumnType.Primitive)?.typeName,
                    multiplicity = if (isOptional) MultiplicityData.optional() else MultiplicityData.single()
                )
            )
        }

        val sanitizedClassName = sanitizePropertyName(className, fallback = "ImportedCsvClass")
            .let { it.replaceFirstChar(Char::uppercaseChar) }

        val metamodel = MetamodelData(
            path = metamodelPath,
            classes = listOf(
                ClassData(
                    name = sanitizedClassName,
                    isAbstract = false,
                    properties = properties
                )
            ),
            enums = enums
        )

        val instances = normalizedRows.mapIndexed { rowIndex, row ->
            val instanceProperties = header.indices.associate { colIndex ->
                val propertyName = properties[colIndex].name
                val property = properties[colIndex]
                val rawValue = row[colIndex]
                propertyName to cellToPropertyValue(rawValue, property)
            }
            ModelDataInstance(
                name = "${sanitizedClassName}_$rowIndex",
                className = sanitizedClassName,
                properties = instanceProperties
            )
        }

        val model = ModelData(
            metamodelPath = metamodelPath,
            instances = instances,
            links = emptyList()
        )

        return InferenceResult(metamodel = metamodel, model = model, warnings = warnings)
    }

    private sealed class ColumnType {
        data class Primitive(val typeName: String) : ColumnType()
        data class Enum(val enumName: String, val entries: List<String>) : ColumnType()
    }

    private fun inferColumnType(propertyName: String, nonBlankValues: List<String>, enumMaxDistinct: Int): ColumnType {
        if (nonBlankValues.isEmpty()) {
            return ColumnType.Primitive("string")
        }

        if (nonBlankValues.all { it.isIntegerLiteral() }) {
            return ColumnType.Primitive("int")
        }
        if (nonBlankValues.all { it.isNumberLiteral() }) {
            return ColumnType.Primitive("double")
        }
        if (nonBlankValues.all { it.isBooleanLiteral() }) {
            return ColumnType.Primitive("boolean")
        }

        val distinctValues = nonBlankValues.distinct()
        val isRepeated = distinctValues.size < nonBlankValues.size
        if (distinctValues.size <= enumMaxDistinct && isRepeated) {
            val enumName = propertyName.replaceFirstChar(Char::uppercaseChar) + "Enum"
            return ColumnType.Enum(enumName, distinctValues.map { sanitizeEnumEntry(it) })
        }

        return ColumnType.Primitive("string")
    }

    private fun String.isIntegerLiteral(): Boolean = toLongOrNull() != null

    private fun String.isNumberLiteral(): Boolean = toDoubleOrNull() != null

    private fun String.isBooleanLiteral(): Boolean =
        equals("true", ignoreCase = true) || equals("false", ignoreCase = true)

    private fun cellToPropertyValue(rawValue: String, property: PropertyData): ModelDataPropertyValue {
        if (rawValue.isBlank()) {
            return ModelDataPropertyValue.NullValue
        }
        return when {
            property.enumType != null -> ModelDataPropertyValue.EnumValue(sanitizeEnumEntry(rawValue))
            property.primitiveType == "int" -> ModelDataPropertyValue.NumberValue(rawValue.toLong().toDouble())
            property.primitiveType == "double" -> ModelDataPropertyValue.NumberValue(rawValue.toDouble())
            property.primitiveType == "boolean" -> ModelDataPropertyValue.BooleanValue(rawValue.equals("true", ignoreCase = true))
            else -> ModelDataPropertyValue.StringValue(rawValue)
        }
    }

    private fun sanitizePropertyName(raw: String, fallback: String): String {
        val cleaned = raw.trim().map { c -> if (c.isLetterOrDigit() || c == '_') c else '_' }.joinToString("")
        val withoutLeadingDigit = if (cleaned.firstOrNull()?.isDigit() == true) "_$cleaned" else cleaned
        return withoutLeadingDigit.ifBlank { fallback }
    }

    private fun sanitizeEnumEntry(raw: String): String = sanitizePropertyName(raw, fallback = "UNKNOWN")

    private fun parseCsv(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var currentRow = mutableListOf<String>()
        val currentField = StringBuilder()
        var inQuotes = false
        var i = 0
        val n = text.length

        fun endField() {
            currentRow.add(currentField.toString())
            currentField.clear()
        }

        fun endRow() {
            endField()
            if (!(currentRow.size == 1 && currentRow[0].isBlank())) {
                rows.add(currentRow)
            }
            currentRow = mutableListOf()
        }

        while (i < n) {
            val c = text[i]
            if (inQuotes) {
                when (c) {
                    '"' -> {
                        if (i + 1 < n && text[i + 1] == '"') {
                            currentField.append('"')
                            i++
                        } else {
                            inQuotes = false
                        }
                    }
                    else -> currentField.append(c)
                }
            } else {
                when (c) {
                    '"' -> inQuotes = true
                    ',' -> endField()
                    '\r' -> {}
                    '\n' -> endRow()
                    else -> currentField.append(c)
                }
            }
            i++
        }

        if (currentField.isNotEmpty() || currentRow.isNotEmpty()) {
            endRow()
        }

        return rows
    }
}
