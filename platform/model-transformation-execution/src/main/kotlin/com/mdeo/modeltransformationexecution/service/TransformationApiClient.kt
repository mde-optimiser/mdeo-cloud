package com.mdeo.modeltransformationexecution.service

import com.mdeo.execution.common.api.BackendApiClient
import com.mdeo.metamodel.data.MetamodelData
import com.mdeo.modeltransformation.ast.TypedAst
import com.mdeo.metamodel.data.ModelData
import com.mdeo.metamodel.data.load
import com.mdeo.modeltransformation.ast.statements.TypedTransformationStatement
import com.mdeo.modeltransformation.ast.statements.TypedTransformationStatementSerializer
import com.mdeo.modeltransformation.ast.patterns.TypedPatternElement
import com.mdeo.modeltransformation.ast.patterns.TypedPatternElementSerializer
import com.mdeo.expression.ast.expressions.TypedExpression
import com.mdeo.modeltransformation.ast.expressions.TypedExpressionSerializer
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual

/**
 * API client for fetching transformation and model data from the backend.
 * Extends BackendApiClient with transformation-specific operations.
 *
 * @param baseUrl Base URL of the backend API
 */
class TransformationApiClient(baseUrl: String) : BackendApiClient(
    baseUrl,
    createTransformationSerializersModule()
) {
    
    /**
     * Fetches the typed AST for a model transformation file.
     *
     * @param projectId UUID of the project
     * @param filePath Path to the transformation file
     * @param jwtToken JWT token for authentication
     * @return TypedAst or null if not found/error
     */
    suspend fun getTransformationTypedAst(
        projectId: String,
        filePath: String,
        jwtToken: String
    ): TypedAst? {
        return try {
            logger.info("Fetching typed AST for transformation $filePath")
            
            val response = client.get("$baseUrl/projects/$projectId/file-data/typed-ast") {
                parameter("path", filePath)
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer $jwtToken")
            }

            if (response.status == HttpStatusCode.OK) {
                val result = response.body<TypedAstFileDataResponse>()
                result.data
            } else {
                logger.warn("Failed to fetch transformation typed AST: ${response.status}")
                null
            }
        } catch (e: Exception) {
            logger.error("Error fetching transformation typed AST", e)
            null
        }
    }
    
    /**
     * Fetches the model data for a model file.
     *
     * @param projectId UUID of the project
     * @param filePath Path to the model file
     * @param jwtToken JWT token for authentication
     * @return ModelData or null if not found/error
     */
    suspend fun getModelData(
        projectId: String,
        filePath: String,
        jwtToken: String
    ): ModelData? {
        return try {
            logger.info("Fetching model data for $filePath")
            
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
     * Fetches the metamodel data for a metamodel file, merged with the metamodel files it imports.
     *
     * @param projectId UUID of the project
     * @param filePath Path to the metamodel file
     * @param jwtToken JWT token for authentication
     * @return MetamodelData or null if not found/error
     */
    suspend fun getMetamodelData(
        projectId: String,
        filePath: String,
        jwtToken: String
    ): MetamodelData? = MetamodelData.load(filePath) { getMetamodelFileData(projectId, it, jwtToken) }

    /**
     * Fetches the metamodel data of one metamodel file, without the files it imports.
     */
    private suspend fun getMetamodelFileData(
        projectId: String,
        filePath: String,
        jwtToken: String
    ): MetamodelData? {
        return try {
            logger.info("Fetching metamodel data for $filePath")
            
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

    companion object {
        private fun createTransformationSerializersModule(): SerializersModule {
            return SerializersModule {
                contextual(TypedExpression::class, TypedExpressionSerializer)
                contextual(TypedTransformationStatement::class, TypedTransformationStatementSerializer)
                contextual(TypedPatternElement::class, TypedPatternElementSerializer)
            }
        }
    }
}

/**
 * Response wrapper for typed-ast file data endpoint.
 */
@Serializable
internal data class TypedAstFileDataResponse(
    val data: TypedAst?,
    val version: Int? = null
)

/**
 * Response wrapper for model-data file data endpoint.
 */
@Serializable
internal data class ModelDataFileDataResponse(
    val data: ModelData?,
    val version: Int? = null
)

/**
 * Response wrapper for metamodel file data endpoint.
 */
@Serializable
internal data class MetamodelDataFileDataResponse(
    val data: MetamodelData?,
    val version: Int? = null
)
