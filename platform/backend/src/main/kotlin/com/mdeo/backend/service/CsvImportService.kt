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
    val metamodelPath: String,
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
        className: String
    ): ApiResult<CsvImportResult> {
        val normalizedBasePath = basePath.trim().trim('/')
        val metamodelPath = "$normalizedBasePath.metamodel"
        val modelPath = "$normalizedBasePath.model"

        val inference = try {
            CsvModelInference.inferFromCsv(
                csvText = csvText,
                className = className,
                metamodelPath = metamodelPath
            )
        } catch (e: IllegalArgumentException) {
            return commonFailure(ErrorCodes.CSV_IMPORT_FAILED, e.message ?: "Invalid CSV")
        }

        val metamodelBytes = json.encodeToString(inference.metamodel).toByteArray(Charsets.UTF_8)
        val modelBytes = json.encodeToString(inference.model).toByteArray(Charsets.UTF_8)

        when (val result = fileService.writeFile(projectId, metamodelPath, metamodelBytes, create = true, overwrite = true)) {
            is ApiResult.Failure -> return ApiResult.Failure(result.error)
            is ApiResult.Success -> {}
        }

        when (val result = fileService.writeFile(projectId, modelPath, modelBytes, create = true, overwrite = true)) {
            is ApiResult.Failure -> return ApiResult.Failure(result.error)
            is ApiResult.Success -> {}
        }

        return success(
            CsvImportResult(
                metamodelPath = metamodelPath,
                modelPath = modelPath,
                instanceCount = inference.model.instances.size,
                warnings = inference.warnings
            )
        )
    }
}
