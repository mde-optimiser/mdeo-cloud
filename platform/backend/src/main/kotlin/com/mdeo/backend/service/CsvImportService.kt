package com.mdeo.backend.service

import com.mdeo.common.model.ApiResult
import com.mdeo.common.model.ErrorCodes
import com.mdeo.common.model.commonFailure
import com.mdeo.common.model.success
import com.mdeo.metamodel.csv.CsvModelInference
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable
data class CsvImportResult(
    val modelPath: String,
    val instanceCount: Int,
    val warnings: List<String>
)

class CsvImportService(services: InjectedServices) : BaseService(), InjectedServices by services {

    private val json = Json { prettyPrint = true }

    fun importCsv(
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

        val metamodelResult = fileService.readFile(projectId, metamodelPath)
        if (metamodelResult is ApiResult.Failure) {
            return commonFailure(ErrorCodes.CSV_IMPORT_FAILED, "Could not read metamodel at '$metamodelPath'")
        }
        val metamodelBytes = (metamodelResult as ApiResult.Success).value
        val metamodel = try {
            json.decodeFromString<com.mdeo.metamodel.data.MetamodelData>(metamodelBytes.decodeToString())
        } catch (e: Exception) {
            return commonFailure(ErrorCodes.CSV_IMPORT_FAILED, "Could not parse metamodel: ${e.message}")
        }

        val importResult = try {
            CsvModelInference.importFromCsv(
                csvText = csvText,
                className = className,
                metamodel = metamodel,
                metamodelPath = metamodelPath
            )
        } catch (e: IllegalArgumentException) {
            return commonFailure(ErrorCodes.CSV_IMPORT_FAILED, e.message ?: "Invalid CSV")
        }

        val modelBytes = json.encodeToString(importResult.model).toByteArray(Charsets.UTF_8)

        when (val result = fileService.writeFile(projectId, modelPath, modelBytes, create = true, overwrite = true)) {
            is ApiResult.Failure -> return ApiResult.Failure(result.error)
            is ApiResult.Success -> {}
        }

        return success(
            CsvImportResult(
                modelPath = modelPath,
                instanceCount = importResult.model.instances.size,
                warnings = importResult.warnings
            )
        )
    }
}
