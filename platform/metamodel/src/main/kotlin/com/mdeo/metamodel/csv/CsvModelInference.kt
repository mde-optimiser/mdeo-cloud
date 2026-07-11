package com.mdeo.metamodel.csv

import com.mdeo.metamodel.data.ClassData
import com.mdeo.metamodel.data.MetamodelData
import com.mdeo.metamodel.data.ModelData
import com.mdeo.metamodel.data.ModelDataInstance
import com.mdeo.metamodel.data.ModelDataPropertyValue

object CsvModelInference {

    data class ImportResult(
        val model: ModelData,
        val warnings: List<String>
    )

    fun importFromCsv(
        csvText: String,
        className: String,
        metamodel: MetamodelData,
        metamodelPath: String
    ): ImportResult {
        val targetClass = metamodel.classes.find { it.name == className }
            ?: throw IllegalArgumentException(
                "Class '$className' not found in metamodel. Available classes: ${metamodel.classes.map { it.name }.joinToString()}"
            )

        val rows = parseCsv(csvText)
        require(rows.isNotEmpty()) { "CSV has no header row" }

        val header = rows.first()
        require(header.isNotEmpty()) { "CSV header row is empty" }

        val dataRows = rows.drop(1)
        require(dataRows.isNotEmpty()) { "CSV has a header row but no data rows" }

        val warnings = mutableListOf<String>()

        validateHeaders(header, targetClass, warnings)

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

        val instances = normalizedRows.mapIndexed { rowIndex, row ->
            val instanceProperties = header.indices.associate { colIndex ->
                val columnName = header[colIndex].trim()
                val property = targetClass.properties.find { it.name == columnName }
                val rawValue = row[colIndex]
                columnName to cellToPropertyValue(rawValue, property, metamodel)
            }
            ModelDataInstance(
                name = "${className}_$rowIndex",
                className = className,
                properties = instanceProperties
            )
        }

        val model = ModelData(
            metamodelPath = metamodelPath,
            instances = instances,
            links = emptyList()
        )

        return ImportResult(model = model, warnings = warnings)
    }

    private fun validateHeaders(
        header: List<String>,
        targetClass: ClassData,
        warnings: MutableList<String>
    ) {
        val propertyNames = targetClass.properties.map { it.name }.toSet()

        header.forEach { columnName ->
            val trimmed = columnName.trim()
            if (trimmed !in propertyNames) {
                warnings.add("Column '$trimmed' does not match any property in class '${targetClass.name}' and will be stored as-is.")
            }
        }

        val missingRequired = targetClass.properties.filter { property ->
            property.multiplicity.isRequired() && header.none { it.trim() == property.name }
        }
        if (missingRequired.isNotEmpty()) {
            throw IllegalArgumentException(
                "CSV is missing required properties for class '${targetClass.name}': ${missingRequired.map { it.name }.joinToString()}"
            )
        }
    }

    private fun cellToPropertyValue(
        rawValue: String,
        property: com.mdeo.metamodel.data.PropertyData?,
        metamodel: MetamodelData
    ): ModelDataPropertyValue {
        if (rawValue.isBlank()) {
            return ModelDataPropertyValue.NullValue
        }

        if (property == null) {
            return ModelDataPropertyValue.StringValue(rawValue)
        }

        return when {
            property.enumType != null -> {
                val enumDef = metamodel.enums.find { it.name == property.enumType }
                if (enumDef != null && rawValue !in enumDef.entries) {
                    ModelDataPropertyValue.StringValue(rawValue)
                } else {
                    ModelDataPropertyValue.EnumValue(rawValue)
                }
            }
            property.primitiveType == "int" || property.primitiveType == "long" -> {
                rawValue.toLongOrNull()?.let { ModelDataPropertyValue.NumberValue(it.toDouble()) }
                    ?: ModelDataPropertyValue.StringValue(rawValue)
            }
            property.primitiveType == "double" || property.primitiveType == "float" -> {
                rawValue.toDoubleOrNull()?.let { ModelDataPropertyValue.NumberValue(it) }
                    ?: ModelDataPropertyValue.StringValue(rawValue)
            }
            property.primitiveType == "boolean" -> {
                ModelDataPropertyValue.BooleanValue(rawValue.equals("true", ignoreCase = true))
            }
            else -> ModelDataPropertyValue.StringValue(rawValue)
        }
    }

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
