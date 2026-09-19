package com.mdeo.execution.common.api

import com.mdeo.expression.ast.expressions.TypedExpression
import com.mdeo.expression.ast.statements.TypedStatement
import com.mdeo.script.ast.TypedAst
import com.mdeo.script.ast.TypedPluginAst
import com.mdeo.script.ast.expressions.TypedExpressionSerializer
import com.mdeo.script.ast.statements.TypedStatementSerializer
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual

/**
 * Backend client for the execution stacks that run scripts.
 *
 * Fetches what compiling a script needs besides the model: the typed ASTs of the scripts, and
 * the typed AST of the functions plugins contribute.
 *
 * @param baseUrl Base URL of the backend API
 */
open class ScriptBackendApiClient(baseUrl: String) : BackendApiClient(
    baseUrl,
    SerializersModule {
        contextual(TypedExpression::class, TypedExpressionSerializer)
        contextual(TypedStatement::class, TypedStatementSerializer)
    }
) {

    companion object {
        /**
         * Language id of the script language, used to address project-wide (root) file data.
         */
        const val SCRIPT_LANGUAGE_ID = "script"

        /**
         * Brings a project file path into the form typed ASTs name files by: absolute, with a
         * leading slash. Paths given to an execution may lack it.
         *
         * @param path A path within the project
         * @return The same path, starting with exactly one slash
         */
        fun absolutePath(path: String): String = "/" + path.trimStart('/')
    }

    /**
     * Fetches the typed AST of a script together with those of every file it imports, directly or
     * not, in one request.
     *
     * @param projectId UUID of the project
     * @param filePath Path to the script
     * @param jwtToken JWT token to pass through to the backend
     * @return Typed ASTs by absolute file path (see [absolutePath]), or null when the script or
     *         one of its imports is missing or has errors, or the fetch fails
     */
    suspend fun getTypedAstClosure(projectId: String, filePath: String, jwtToken: String): Map<String, TypedAst>? {
        return try {
            val response = client.get("$baseUrl/projects/$projectId/file-data/typed-ast-closure") {
                parameter("path", filePath)
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer $jwtToken")
            }
            if (response.status == HttpStatusCode.OK) {
                response.body<TypedAstClosureResponse>().data?.files
            } else {
                logger.warn("Failed to fetch typed AST closure of $filePath: ${response.status}")
                null
            }
        } catch (e: Exception) {
            logger.error("Error fetching typed AST closure of $filePath", e)
            null
        }
    }

    /**
     * Fetches the typed AST of all script functions contributed by plugins.
     *
     * The contribution AST belongs to the project rather than to any file, so it is requested by
     * language instead of by path. The script frontend merges every contributed function of every
     * enabled contribution plugin into this single document.
     *
     * @param projectId UUID of the project
     * @param jwtToken JWT token to pass through to the backend
     * @return The contribution AST, or null when there are no contributions or the fetch fails
     */
    suspend fun getPluginAst(projectId: String, jwtToken: String): TypedPluginAst? {
        return try {
            val response = client.get("$baseUrl/projects/$projectId/file-data/typed-ast") {
                parameter("language", SCRIPT_LANGUAGE_ID)
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer $jwtToken")
            }
            if (response.status == HttpStatusCode.OK) {
                response.body<TypedPluginAstResponse>().data
            } else {
                logger.warn("Failed to fetch plugin contribution AST: ${response.status}")
                null
            }
        } catch (e: Exception) {
            logger.error("Error fetching plugin contribution AST", e)
            null
        }
    }
}

/**
 * Response of the typed AST closure file data request.
 *
 * @property data The closure, or null when a file has no typed AST
 */
@Serializable
internal data class TypedAstClosureResponse(
    val data: TypedAstClosure?
)

/**
 * A script's typed AST together with those of every file it imports.
 *
 * @property files Typed ASTs by absolute file path
 */
@Serializable
internal data class TypedAstClosure(
    val files: Map<String, TypedAst>
)

/**
 * Response of the root typed AST file data request that carries plugin contributions.
 */
@Serializable
internal data class TypedPluginAstResponse(
    val data: TypedPluginAst?,
    val version: Int? = null
)
