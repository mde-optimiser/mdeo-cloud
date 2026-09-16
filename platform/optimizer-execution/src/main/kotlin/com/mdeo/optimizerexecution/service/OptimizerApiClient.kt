package com.mdeo.optimizerexecution.service

import com.mdeo.execution.common.api.BackendApiClient
import com.mdeo.metamodel.data.MetamodelData
import com.mdeo.modeltransformation.ast.TypedAst as TransformationTypedAst
import com.mdeo.metamodel.data.ModelData
import com.mdeo.modeltransformation.ast.statements.TypedTransformationStatement
import com.mdeo.modeltransformation.ast.statements.TypedTransformationStatementSerializer
import com.mdeo.modeltransformation.ast.patterns.TypedPatternElement
import com.mdeo.modeltransformation.ast.patterns.TypedPatternElementSerializer
import com.mdeo.expression.ast.expressions.TypedExpression
import com.mdeo.modeltransformation.ast.expressions.TypedExpressionSerializer as TransformationExpressionSerializer
import com.mdeo.script.ast.TypedAst as ScriptTypedAst
import com.mdeo.script.ast.TypedPluginAst as ScriptTypedPluginAst
import com.mdeo.script.ast.expressions.TypedExpressionSerializer as ScriptExpressionSerializer
import com.mdeo.expression.ast.statements.TypedStatement
import com.mdeo.script.ast.statements.TypedStatementSerializer
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
 * Uses two internal HTTP clients: one configured with transformation AST
 * serializers (for model-transformation typed ASTs) and one with script AST
 * serializers (for script typed ASTs). Model/metamodel data use the parent
 * client which needs no special serializers.
 *
 * @param baseUrl Base URL of the backend API
 */
class OptimizerApiClient(baseUrl: String) : BackendApiClient(baseUrl) {

    companion object {
        /**
         * Language id of the script language, used to address project-wide (root) file data.
         */
        const val SCRIPT_LANGUAGE_ID = "script"
    }

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
     * HTTP client configured with script AST contextual serializers. 
     */
    private val scriptClient: HttpClient = createBackendClient(
        SerializersModule {
            contextual(TypedExpression::class, ScriptExpressionSerializer)
            contextual(TypedStatement::class, TypedStatementSerializer)
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
     * Fetches the typed AST for a script file.
     *
     * @param projectId The project that owns the script file.
     * @param filePath Path to the script file within the project.
     * @param jwtToken Bearer token for backend API authentication.
     * @return The [ScriptTypedAst], or `null` if unavailable or the fetch fails.
     */
    suspend fun getScriptTypedAst(
        projectId: String, filePath: String, jwtToken: String
    ): ScriptTypedAst? {
        return try {
            logger.info("Fetching script typed AST for $filePath")
            val response = scriptClient.get("$baseUrl/projects/$projectId/file-data/typed-ast") {
                parameter("path", filePath)
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer $jwtToken")
            }
            if (response.status == HttpStatusCode.OK) {
                response.body<ScriptTypedAstResponse>().data
            } else {
                logger.warn("Failed to fetch script typed AST for $filePath: ${response.status}")
                null
            }
        } catch (e: Exception) {
            logger.error("Error fetching script typed AST for $filePath", e)
            null
        }
    }

    /**
     * Fetches the typed AST of all script functions contributed by plugins.
     *
     * The contribution AST belongs to the project rather than to any file, so it is
     * addressed by language id instead of by path.
     *
     * @param projectId The project whose enabled contributions should be collected.
     * @param jwtToken Bearer token for backend API authentication.
     * @return The [ScriptTypedPluginAst], or `null` when there are no contributions or the fetch fails.
     */
    suspend fun getScriptPluginAst(projectId: String, jwtToken: String): ScriptTypedPluginAst? {
        return try {
            logger.info("Fetching script plugin contribution AST")
            val response = scriptClient.get("$baseUrl/projects/$projectId/file-data/typed-ast") {
                parameter("language", SCRIPT_LANGUAGE_ID)
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer $jwtToken")
            }
            if (response.status == HttpStatusCode.OK) {
                response.body<ScriptTypedPluginAstResponse>().data
            } else {
                logger.warn("Failed to fetch script plugin contribution AST: ${response.status}")
                null
            }
        } catch (e: Exception) {
            logger.error("Error fetching script plugin contribution AST", e)
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
     * Closes all HTTP clients including the extra ones for transformations and scripts.
     */
    fun closeAll() {
        close()
        transformationClient.close()
        scriptClient.close()
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
 * API response wrapper for a script typed AST.
 *
 * @param data The typed AST, or null if unavailable.
 * @param version Optional schema version for cache invalidation.
 */
@Serializable
internal data class ScriptTypedAstResponse(
    val data: ScriptTypedAst?,
    val version: Int? = null
)

/**
 * API response wrapper for the script plugin contribution AST.
 *
 * @param data The contribution AST, or null if unavailable.
 * @param version Optional schema version for cache invalidation.
 */
@Serializable
internal data class ScriptTypedPluginAstResponse(
    val data: ScriptTypedPluginAst?,
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
