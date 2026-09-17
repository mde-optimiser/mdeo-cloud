package com.mdeo.backend.routes

import com.mdeo.common.auth.Scopes
import com.mdeo.common.transport.respondError
import com.mdeo.backend.plugins.*
import com.mdeo.backend.service.CallerDeadline
import com.mdeo.backend.service.FileDataService
import com.mdeo.backend.service.JwtService
import com.mdeo.backend.service.ProjectPermission
import com.mdeo.backend.service.ProjectService
import com.mdeo.common.model.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.*

/**
 * Most entries one batch request may ask for.
 */
const val MAX_FILE_DATA_BATCH_SIZE = 256

/**
 * Most entries of one batch request that are looked up at the same time. Each one may wait on the
 * database and on a plugin, so a full batch must not take that many threads at once.
 */
const val MAX_CONCURRENT_BATCH_ENTRIES = 16

private val batchLogger = LoggerFactory.getLogger("com.mdeo.backend.routes.FileDataBatch")

/**
 * One entry of a batch file data request.
 *
 * @property path The file path
 * @property key The data key
 */
@Serializable
data class FileDataBatchEntry(val path: String, val key: String)

/**
 * A batch file data request.
 *
 * @property requests The entries, answered in the same order
 */
@Serializable
data class FileDataBatchRequest(val requests: List<FileDataBatchEntry>)

/**
 * Configures file data computation routes.
 *
 * @param fileDataService Service for file data computation
 * @param projectService Service for project access validation
 * @param jwtService Service for JWT operations
 */
fun Route.fileDataRoutes(
    fileDataService: FileDataService,
    projectService: ProjectService,
    jwtService: JwtService
) {
    route("/api/projects/{projectId}/file-data/{key}") {
        /**
         * Gets computed file data for a specific file.
         * Requires either path or language parameter, but not both.
         *
         * @param projectId Path parameter for project UUID
         * @param key Path parameter for data key (e.g., "ast")
         * @param path Query parameter for file path (mutually exclusive with language)
         * @param language Query parameter for language ID, assumes root path (mutually exclusive with path)
         * @return ApiResult with computed data or failure
         */
        get {
            val projectId = call.authorizeFileDataRead(projectService) ?: return@get

            val key = call.parameters["key"]
            if (key.isNullOrBlank()) {
                call.respondError(HttpStatusCode.BadRequest, "Missing data key")
                return@get
            }

            val path = call.request.queryParameters["path"]
            val language = call.request.queryParameters["language"]

            if (path.isNullOrBlank() && language.isNullOrBlank()) {
                call.respondError(HttpStatusCode.BadRequest, "Either path or language parameter is required")
                return@get
            }

            if (!path.isNullOrBlank() && !language.isNullOrBlank()) {
                call.respondError(HttpStatusCode.BadRequest, "path and language parameters are mutually exclusive")
                return@get
            }

            val result = fileDataService.getFileData(
                projectId, path, language, key, call.callerComputationId(), call.callerDeadline()
            )
            when (result) {
                // Served as stored, without parsing the data.
                is ApiResult.Success -> call.respondText(result.value.toResponseJson(), ContentType.Application.Json)
                is ApiResult.Failure -> call.respondApiResult(result)
            }
        }
    }

    route("/api/projects/{projectId}/file-data-batch") {
        /**
         * Gets computed file data for several files and keys in one request.
         *
         * Entries are computed concurrently, exactly as if each had been requested on its own, and
         * answered in request order. Each answer is either `{"data": …, "version": …}` or
         * `{"error": {"code": …, "message": …}}`; one failing entry does not fail the others.
         *
         * @param projectId Path parameter for project UUID
         * @return `{"results": [...]}`
         */
        post {
            val projectId = call.authorizeFileDataRead(projectService) ?: return@post

            val request = try {
                call.receive<FileDataBatchRequest>()
            } catch (e: Exception) {
                call.respondError(HttpStatusCode.BadRequest, "Invalid batch request")
                return@post
            }
            if (request.requests.size > MAX_FILE_DATA_BATCH_SIZE) {
                call.respondError(HttpStatusCode.BadRequest, "A batch may ask for at most $MAX_FILE_DATA_BATCH_SIZE entries")
                return@post
            }

            val callerComputationId = call.callerComputationId()
            val deadline = call.callerDeadline()
            val permits = Semaphore(MAX_CONCURRENT_BATCH_ENTRIES)
            fun failed(error: ApiError) = """{"error":${Json.encodeToString(ApiError.serializer(), error)}}"""
            val results = supervisorScope {
                request.requests.map { entry ->
                    async(Dispatchers.IO) {
                        if (entry.path.isBlank() || entry.key.isBlank()) {
                            return@async failed(ApiError(ErrorCodes.BAD_REQUEST, "An entry needs a path and a key"))
                        }
                        try {
                            permits.withPermit {
                                when (val result = fileDataService.getFileData(
                                    projectId, entry.path, null, entry.key, callerComputationId, deadline
                                )) {
                                    is ApiResult.Success -> result.value.toResponseJson()
                                    is ApiResult.Failure -> failed(result.error)
                                }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // One entry that fails unexpectedly must not take the others down.
                            batchLogger.error("File data batch entry ${entry.path}:${entry.key} failed", e)
                            failed(ApiError(ErrorCodes.INTERNAL, "Could not get ${entry.path}:${entry.key}"))
                        }
                    }
                }.awaitAll()
            }

            call.respondText(results.joinToString(",", """{"results":[""", "]}"), ContentType.Application.Json)
        }
    }
}


/**
 * Checks that the caller may read file data of the project in the path, answering the call when not.
 *
 * A user session needs read permission on the project; a token needs to be issued for the project
 * and carry the file data read scope.
 *
 * @param projectService Service for project access validation
 * @return The project id, or null when the call was answered with an error
 */
private suspend fun ApplicationCall.authorizeFileDataRead(projectService: ProjectService): UUID? {
    val session = getUserSession()
    val jwtPrincipal = getJwtPrincipal()

    val projectId = parameters["projectId"]?.let {
        try { UUID.fromString(it) } catch (e: Exception) { null }
    }
    if (projectId == null) {
        respondError(HttpStatusCode.BadRequest, "Invalid project ID")
        return null
    }

    if (session != null) {
        val userId = try { UUID.fromString(session.userId) } catch (e: Exception) {
            respondError(HttpStatusCode.BadRequest, "Invalid user ID")
            return null
        }

        if (!projectService.hasProjectPermission(projectId, userId, isAdmin(), ProjectPermission.READ)) {
            respondError(HttpStatusCode.Forbidden, "Access denied")
            return null
        }
    } else if (jwtPrincipal != null) {
        if (jwtPrincipal.projectId != projectId.toString()) {
            respondError(HttpStatusCode.Forbidden, "Token not valid for this project")
            return null
        }
        if (Scopes.FILE_DATA_READ !in jwtPrincipal.scopes) {
            respondError(HttpStatusCode.Forbidden, "Token missing required scope")
            return null
        }
    } else {
        respondError(HttpStatusCode.Unauthorized, "Authentication required")
        return null
    }
    return projectId
}

/**
 * The file data computation a request comes from, when a plugin asks while computing other data.
 */
private fun ApplicationCall.callerComputationId(): UUID? =
    getJwtPrincipal()?.payload?.getClaim(JwtService.CLAIM_COMPUTATION_ID)?.asString()
        ?.let { runCatching { UUID.fromString(it) }.getOrNull() }

/**
 * How long the caller is still willing to wait, if it said.
 */
private fun ApplicationCall.callerDeadline(): CallerDeadline? =
    CallerDeadline.fromHeader(request.headers[CallerDeadline.HEADER])
