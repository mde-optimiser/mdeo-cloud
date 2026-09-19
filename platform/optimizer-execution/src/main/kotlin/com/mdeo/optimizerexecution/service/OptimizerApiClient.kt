package com.mdeo.optimizerexecution.service

import com.mdeo.execution.common.api.ScriptBackendApiClient
import com.mdeo.metamodel.data.MetamodelData
import com.mdeo.modeltransformation.ast.TypedAst as TransformationTypedAst
import com.mdeo.metamodel.data.ModelData
import com.mdeo.modeltransformation.ast.statements.TypedTransformationStatement
import com.mdeo.modeltransformation.ast.statements.TypedTransformationStatementSerializer
import com.mdeo.modeltransformation.ast.patterns.TypedPatternElement
import com.mdeo.modeltransformation.ast.patterns.TypedPatternElementSerializer
import com.mdeo.expression.ast.expressions.TypedExpression
import com.mdeo.modeltransformation.ast.expressions.TypedExpressionSerializer as TransformationExpressionSerializer
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual

/**
 * API client for fetching all data the optimizer needs from the backend.
 *
 * Script typed ASTs and the plugin contribution AST come from [ScriptBackendApiClient], whose
 * client also serves model and metamodel data. Transformation typed ASTs need their own
 * serializers, so they use a second client.
 *
 * @param baseUrl Base URL of the backend API
 */
class OptimizerApiClient(baseUrl: String) : ScriptBackendApiClient(baseUrl) {

    /**
     * HTTP client configured with transformation AST contextual serializers. 
     */
    private val transformationClient: HttpClient = createBackendClient(
        SerializersModule {
            contextual(TypedExpression::class, TransformationExpressionSerializer)
            contextual(TypedTransformationStatement::class, TypedTransformationStatementSerializer)
            contextual(TypedPatternElement::class, TypedPatternElementSerializer)
        }
    )

    /**
     * Fetches the typed AST for a model transformation file.
     *
     * @param projectId The project that owns the transformation file.
     * @param filePath Path to the transformation file within the project.
     * @param jwtToken Bearer token for backend API authentication.
     * @return The [TransformationTypedAst], or `null` if unavailable or the fetch fails.
     */
    suspend fun getTransformationTypedAst(
        projectId: String, filePath: String, jwtToken: String
    ): TransformationTypedAst? {
        return try {
            logger.info("Fetching transformation typed AST for $filePath")
            val response = transformationClient.get("$baseUrl/projects/$projectId/file-data/typed-ast") {
                parameter("path", filePath)
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer $jwtToken")
            }
            if (response.status == HttpStatusCode.OK) {
                response.body<TransformationTypedAstResponse>().data
            } else {
                logger.warn("Failed to fetch transformation typed AST for $filePath: ${response.status}")
                null
            }
        } catch (e: Exception) {
            logger.error("Error fetching transformation typed AST for $filePath", e)
            null
        }
    }

    /**
     * Fetches model data for a model file.
     *
     * @param projectId The project that owns the model file.
     * @param filePath Path to the model file within the project.
     * @param jwtToken Bearer token for backend API authentication.
     * @return The [ModelData], or `null` if unavailable or the fetch fails.
     */
    suspend fun getModelData(
        projectId: String, filePath: String, jwtToken: String
    ): ModelData? {
        return try {
            logger.info("Fetching model data for $filePath")
            val response = client.get("$baseUrl/projects/$projectId/file-data/model-data") {
                parameter("path", filePath)
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer $jwtToken")
            }
            if (response.status == HttpStatusCode.OK) {
                response.body<ModelDataResponse>().data
            } else {
                logger.warn("Failed to fetch model data for $filePath: ${response.status}")
                null
            }
        } catch (e: Exception) {
            logger.error("Error fetching model data", e)
            null
        }
    }

    /**
     * Fetches metamodel data for a metamodel file.
     *
     * @param projectId The project that owns the metamodel file.
     * @param filePath Path to the metamodel file within the project.
     * @param jwtToken Bearer token for backend API authentication.
     * @return The [MetamodelData], or `null` if unavailable or the fetch fails.
     */
    suspend fun getMetamodelData(
        projectId: String, filePath: String, jwtToken: String
    ): MetamodelData? {
        return try {
            logger.info("Fetching metamodel data for $filePath")
            val response = client.get("$baseUrl/projects/$projectId/file-data/metamodel") {
                parameter("path", filePath)
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer $jwtToken")
            }
            if (response.status == HttpStatusCode.OK) {
                response.body<MetamodelDataResponse>().data
            } else {
                logger.warn("Failed to fetch metamodel data for $filePath: ${response.status}")
                null
            }
        } catch (e: Exception) {
            logger.error("Error fetching metamodel data", e)
            null
        }
    }

    /**
     * Closes all HTTP clients including the extra one for transformations.
     */
    fun closeAll() {
        close()
        transformationClient.close()
    }
}

/**
 * API response wrapper for a transformation typed AST.
 *
 * @param data The typed AST, or null if unavailable.
 * @param version Optional schema version for cache invalidation.
 */
@Serializable
internal data class TransformationTypedAstResponse(
    val data: TransformationTypedAst?,
    val version: Int? = null
)

/**
 * API response wrapper for model data.
 *
 * @param data The model data, or null if unavailable.
 * @param version Optional schema version for cache invalidation.
 */
@Serializable
internal data class ModelDataResponse(
    val data: ModelData?,
    val version: Int? = null
)

/**
 * API response wrapper for metamodel data.
 *
 * @param data The metamodel data, or null if unavailable.
 * @param version Optional schema version for cache invalidation.
 */
@Serializable
internal data class MetamodelDataResponse(
    val data: MetamodelData?,
    val version: Int? = null
)
