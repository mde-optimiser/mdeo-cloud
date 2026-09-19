package com.mdeo.scriptexecution.service

import com.mdeo.execution.common.api.ScriptBackendApiClient
import com.mdeo.metamodel.data.MetamodelData
import com.mdeo.metamodel.data.ModelData
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.Serializable

/**
 * Service for interacting with the backend API.
 *
 * Typed ASTs, the plugin contribution AST and execution state updates come from
 * [ScriptBackendApiClient]; this adds the model data a script runs against.
 *
 * @param baseUrl Base URL of the backend API
 */
class BackendApiService(baseUrl: String) : ScriptBackendApiClient(baseUrl) {

    /**
     * Fetches the metamodel data for a metamodel file from the backend API.
     *
     * @param projectId UUID of the project
     * @param filePath Path to the metamodel file
     * @param jwtToken JWT token to pass through to the backend
     * @return MetamodelData object or null if not found
     */
    suspend fun getMetamodelData(projectId: String, filePath: String, jwtToken: String): MetamodelData? {
        return try {
            logger.info("Fetching metamodel data for $filePath in project $projectId")

            val response = client.get("$baseUrl/projects/$projectId/file-data/metamodel") {
                parameter("path", filePath)
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer $jwtToken")
            }

            if (response.status == HttpStatusCode.OK) {
                val result = response.body<MetamodelDataFileDataResponse>()
                result.data
            } else {
                logger.warn("Failed to fetch metamodel data: ${response.status}")
                null
            }
        } catch (e: Exception) {
            logger.error("Error fetching metamodel data", e)
            null
        }
    }

    /**
     * Fetches the model data for a model file from the backend API.
     *
     * @param projectId UUID of the project
     * @param filePath Path to the model file
     * @param jwtToken JWT token to pass through to the backend
     * @return ModelData object or null if not found
     */
    suspend fun getModelData(projectId: String, filePath: String, jwtToken: String): ModelData? {
        return try {
            logger.info("Fetching model data for $filePath in project $projectId")

            val response = client.get("$baseUrl/projects/$projectId/file-data/model-data") {
                parameter("path", filePath)
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer $jwtToken")
            }

            if (response.status == HttpStatusCode.OK) {
                val result = response.body<ModelDataFileDataResponse>()
                result.data
            } else {
                logger.warn("Failed to fetch model data: ${response.status}")
                null
            }
        } catch (e: Exception) {
            logger.error("Error fetching model data", e)
            null
        }
    }
}

/**
 * Response for the metamodel file data request.
 */
@Serializable
internal data class MetamodelDataFileDataResponse(
    val data: MetamodelData?,
    val version: Int? = null
)

/**
 * Response for the model-data file data request.
 */
@Serializable
internal data class ModelDataFileDataResponse(
    val data: ModelData?,
    val version: Int? = null
)