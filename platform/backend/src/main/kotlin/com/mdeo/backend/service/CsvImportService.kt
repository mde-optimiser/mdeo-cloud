package com.mdeo.backend.service

import com.mdeo.common.model.ApiResult
import com.mdeo.common.model.ErrorCodes
import com.mdeo.common.model.commonFailure
import com.mdeo.common.model.success
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.util.UUID

@Serializable
data class CsvImportResult(
    val modelPath: String,
    val instanceCount: Int,
    val warnings: List<String>
)

class CsvImportService(services: InjectedServices) : BaseService(), InjectedServices by services {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    suspend fun importCsv(
        projectId: UUID,
        csvText: String,
        basePath: String,
        className: String,
        metamodelPath: String
    ): ApiResult<CsvImportResult> {
        val normalizedBasePath = basePath.trim().trim('/')
        if (normalizedBasePath.isBlank()) {
            return commonFailure(ErrorCodes.CSV_IMPORT_FAILED, "Invalid path parameter")
        }
        val modelPath = "$normalizedBasePath.m"

        val metamodelDataResult = fileDataService.getFileData(projectId, metamodelPath, null, "metamodel")
        if (metamodelDataResult is ApiResult.Failure) {
            return commonFailure(ErrorCodes.CSV_IMPORT_FAILED, "Could not compute metamodel data for '$metamodelPath'")
        }
        val metamodelJson = (metamodelDataResult as ApiResult.Success).value.data
        if (metamodelJson == kotlinx.serialization.json.JsonNull) {
            return commonFailure(ErrorCodes.CSV_IMPORT_FAILED, "Metamodel '$metamodelPath' has errors or could not be parsed")
        }

        val metamodelData = try {
            json.decodeFromJsonElement<MetamodelAstData>(metamodelJson)
        } catch (e: Exception) {
            return commonFailure(ErrorCodes.CSV_IMPORT_FAILED, "Could not parse metamodel data: ${e.message}")
        }

        val targetClass = metamodelData.classes.find { it.name == className }
            ?: return commonFailure(
                ErrorCodes.CSV_IMPORT_FAILED,
                "Class '$className' not found in metamodel. Available: ${metamodelData.classes.map { it.name }}"
            )

        val warnings = mutableListOf<String>()

        val rows = parseCsv(csvText)
        if (rows.isEmpty()) return commonFailure(ErrorCodes.CSV_IMPORT_FAILED, "CSV has no header row")
        val header = rows.first()
        if (header.isEmpty()) return commonFailure(ErrorCodes.CSV_IMPORT_FAILED, "CSV header row is empty")
        val dataRows = rows.drop(1)
        if (dataRows.isEmpty()) return commonFailure(ErrorCodes.CSV_IMPORT_FAILED, "CSV has no data rows")

        val propertyNames = targetClass.properties.map { it.name }.toSet()
        header.forEach { col ->
            if (col != "_id" && col !in propertyNames) {
                warnings.add("Column '$col' does not match any property in class '$className' — it will be ignored.")
            }
        }

        val missingRequired = targetClass.properties.filter { prop ->
            prop.multiplicity.lower > 0 && header.none { it == prop.name }
        }
        if (missingRequired.isNotEmpty()) {
            return commonFailure(
                ErrorCodes.CSV_IMPORT_FAILED,
                "CSV is missing required properties: ${missingRequired.map { it.name }}"
            )
        }

        val instances = dataRows.mapIndexed { rowIndex, row ->
            val normalizedRow = normalizeRow(row, header.size, rowIndex + 2, warnings)
            val properties = buildJsonObject {
                header.forEachIndexed { colIndex, colName ->
                    if (colName == "_id") return@forEachIndexed
                    val prop = targetClass.properties.find { it.name == colName } ?: return@forEachIndexed
                    val rawValue = normalizedRow[colIndex]
                    put(colName, convertCell(rawValue, prop, metamodelData))
                }
            }
            buildJsonObject {
                put("name", "${className}_$rowIndex")
                put("className", className)
                put("properties", properties)
            }
        }

        val modelContent = buildJsonObject {
            put("metamodelPath", metamodelPath)
            put("instances", JsonArray(instances))
            put("links", JsonArray(emptyList()))
        }

        val modelBytes = json.encodeToString(modelContent).toByteArray(Charsets.UTF_8)

        when (val result = fileService.writeFile(projectId, modelPath, modelBytes, create = true, overwrite = true)) {
            is ApiResult.Failure -> return ApiResult.Failure(result.error)
            is ApiResult.Success -> {}
        }

        return success(
            CsvImportResult(
                modelPath = modelPath,
                instanceCount = instances.size,
                warnings = warnings
            )
        )
    }

    private fun convertCell(rawValue: String, prop: PropertyData, metamodel: MetamodelAstData): JsonElement {
        if (rawValue.isBlank()) return JsonNull
        return when {
            prop.enumType != null -> JsonPrimitive(rawValue)
            prop.primitiveType == "int" || prop.primitiveType == "long" ->
                rawValue.toLongOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(rawValue)
            prop.primitiveType == "double" || prop.primitiveType == "float" ->
                rawValue.toDoubleOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(rawValue)
            prop.primitiveType == "boolean" ->
                JsonPrimitive(rawValue.equals("true", ignoreCase = true))
            else -> JsonPrimitive(rawValue)
        }
    }

    private fun normalizeRow(row: List<String>, expectedSize: Int, rowNumber: Int, warnings: MutableList<String>): List<String> {
        return when {
            row.size == expectedSize -> row
            row.size < expectedSize -> {
                warnings.add("Row $rowNumber has fewer columns than the header; missing values treated as blank.")
                row + List(expectedSize - row.size) { "" }
            }
            else -> {
                warnings.add("Row $rowNumber has more columns than the header; extra values ignored.")
                row.take(expectedSize)
            }
        }
    }

    private fun parseCsv(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var currentRow = mutableListOf<String>()
        val currentField = StringBuilder()
        var inQuotes = false
        var i = 0

        fun endField() { currentRow.add(currentField.toString()); currentField.clear() }
        fun endRow() {
            endField()
            if (!(currentRow.size == 1 && currentRow[0].isBlank())) rows.add(currentRow)
            currentRow = mutableListOf()
        }

        while (i < text.length) {
            val c = text[i]
            if (inQuotes) {
                when (c) {
                    '"' -> if (i + 1 < text.length && text[i + 1] == '"') { currentField.append('"'); i++ }
                           else inQuotes = false
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
        if (currentField.isNotEmpty() || currentRow.isNotEmpty()) endRow()
        return rows
    }

    @Serializable
    private data class MultiplicityData(val lower: Int, val upper: Int)

    @Serializable
    private data class PropertyData(
        val name: String,
        val enumType: String? = null,
        val primitiveType: String? = null,
        val multiplicity: MultiplicityData
    )

    @Serializable
    private data class ClassData(
        val name: String,
        val isAbstract: Boolean,
        val properties: List<PropertyData>
    )

    @Serializable
    private data class EnumData(val name: String, val entries: List<String>)

    @Serializable
    private data class AssociationEndData(val className: String, val name: String? = null, val multiplicity: MultiplicityData)

    @Serializable
    private data class AssociationData(val source: AssociationEndData, val operator: String, val target: AssociationEndData)

    @Serializable
    private data class MetamodelAstData(
        val path: String,
        val classes: List<ClassData>,
        val enums: List<EnumData>,
        val associations: List<AssociationData>,
        val importedMetamodelPaths: List<String> = emptyList()
    )
}
