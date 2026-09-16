package com.mdeo.scriptexecution.service

import com.mdeo.common.transport.acceptCompressedResponses
import com.mdeo.metamodel.data.MetamodelData
import com.mdeo.metamodel.data.ModelData
import com.mdeo.script.ast.TypedAst
import com.mdeo.script.ast.TypedPluginAst
import com.mdeo.common.model.ApiError
import com.mdeo.expression.ast.expressions.TypedExpression
import com.mdeo.expression.ast.statements.TypedStatement
import com.mdeo.script.ast.expressions.TypedExpressionSerializer
import com.mdeo.script.ast.statements.TypedStatementSerializer
import com.mdeo.execution.common.api.BACKEND_REQUEST_TIMEOUT_MS
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import io.ktor.client.request.*

/**
 * Service for interacting with the backend API.
 *
 * @param baseUrl Base URL of the backend API
 */
class BackendApiService(val baseUrl: String) {
    private val logger = LoggerFactory.getLogger(BackendApiService::class.java)

    companion object {
        /**
         * Language id of the script language, used to address project-wide (root) file data.
         */
        const val SCRIPT_LANGUAGE_ID = "script"
    }

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
                serializersModule = SerializersModule {
                    contextual(TypedExpression::class, TypedExpressionSerializer)
                    contextual(TypedStatement::class, TypedStatementSerializer)
                }
            })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = BACKEND_REQUEST_TIMEOUT_MS
            socketTimeoutMillis = BACKEND_REQUEST_TIMEOUT_MS
        }
        acceptCompressedResponses()
    }

    /**
     * Fetches the typed AST for a file from the backend API.
     *
     * @param projectId UUID of the project
     * @param filePath Path to the file
     * @param jwtToken JWT token to pass through to the backend
     * @return TypedAst object or null if not found
     */
    suspend fun getTypedAst(projectId: String, filePath: String, jwtToken: String): TypedAst? {
        return try {
            logger.info("Fetching typed AST for $filePath in project $projectId")

            val response = client.get("$baseUrl/projects/$projectId/file-data/typed-ast") {
                parameter("path", filePath)
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer $jwtToken")
            }

            if (response.status == HttpStatusCode.OK) {
                val result = response.body<TypedAstFileDataResponse>()
                result.data
            } else {
                logger.warn("Failed to fetch typed AST: ${response.status}")
                null
            }
        } catch (e: Exception) {
            logger.error("Error fetching typed AST", e)
            null
        }
    }

    /**
     * Fetches the typed AST of all plugin-contributed script functions.
     *
     * The contribution AST lives at the project root, so it is requested by language
     * instead of by path. The script frontend merges every contributed function of every
     * enabled contribution plugin into this single document.
     *
     * @param projectId UUID of the project
     * @param jwtToken JWT token to pass through to the backend
     * @return TypedPluginAst object, or null when no contributions exist or the fetch fails
     */
    suspend fun getPluginAst(projectId: String, jwtToken: String): TypedPluginAst? {
        return try {
            logger.info("Fetching plugin contribution AST for project $projectId")

            val response = client.get("$baseUrl/projects/$projectId/file-data/typed-ast") {
                parameter("language", SCRIPT_LANGUAGE_ID)
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer $jwtToken")
            }

            if (response.status == HttpStatusCode.OK) {
                response.body<TypedPluginAstFileDataResponse>().data
            } else {
                logger.warn("Failed to fetch plugin contribution AST: ${response.status}")
                null
            }
        } catch (e: Exception) {
            logger.error("Error fetching plugin contribution AST", e)
            null
        }
    }

    /**
     * Updates execution state on the backend.
     *
     * @param executionId UUID of the execution
     * @param state New state string
     * @param progressText Optional progress text
     * @param jwtToken JWT token to authenticate the request
     * @return true if update was successful, false otherwise
     */
    suspend fun updateExecutionState(
        executionId: String,
        state: String,
        progressText: String?,
        jwtToken: String
    ): Boolean {
        @Serializable
        data class UpdateExecutionStateRequest(val state: String, val progressText: String?)

        return try {
            logger.info("Updating backend state for execution $executionId to $state")

            val response = client.patch("$baseUrl/executions/$executionId/state") {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer $jwtToken")
                setBody(UpdateExecutionStateRequest(state, progressText))
            }

            if (response.status == HttpStatusCode.OK || response.status == HttpStatusCode.NoContent) {
                true
            } else {
                logger.warn("Failed to update execution state on backend: ${response.status}")
                false
            }
        } catch (e: Exception) {
            logger.error("Error updating execution state on backend", e)
            false
        }
    }

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

    /**
     * Closes the HTTP client and releases resources.
     */
    fun close() {
        client.close()
        logger.info("Backend API client closed")
    }
}

/**
 * Response for the typed-ast file data request.
 */
@Serializable
data class TypedAstFileDataResponse(
    val data: TypedAst?,
    val version: Int? = null
)

/**
 * Response for the root typed-ast file data request that carries plugin contributions.
 */
@Serializable
data class TypedPluginAstFileDataResponse(
    val data: TypedPluginAst?,
    val version: Int? = null
)

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