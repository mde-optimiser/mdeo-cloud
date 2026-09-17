package com.mdeo.backend.service

import com.mdeo.common.transport.CompressedResponses
import com.mdeo.common.transport.describeErrorResponse
import com.mdeo.backend.database.DataDependenciesTable
import com.mdeo.backend.database.FileDependenciesTable
import com.mdeo.backend.database.FileDataComputationsTable
import com.mdeo.backend.database.FileDataTable
import com.mdeo.backend.database.FilesTable
import com.mdeo.common.model.*
import com.mdeo.common.model.FileType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.time.Duration
import java.time.Instant
import java.util.*
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid

/**
 * Service for computing and caching file data (e.g., AST) with dependency tracking.
 *
 * @param services The injected services providing access to configuration and other services
 */
class FileDataService(services: InjectedServices) : BaseService(), InjectedServices by services {
    private val logger = LoggerFactory.getLogger(FileDataService::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val computationLog = FileDataComputationLog()
    /**
     * Where shared computations run, apart from the requests waiting for them.
     */
    private val computationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val flights = ComputationFlights<FileDataTarget, ApiResult<RawFileData>>(computationScope)

    /**
     * One piece of file data, as computations are shared by it.
     */
    private data class FileDataTarget(val projectId: UUID, val path: String, val key: String)

    /**
     * File data configuration settings 
     */
    private val fileDataConfig get() = config.fileData

    private val httpClient by lazy {
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(config.timeouts.connectSeconds))
            .version(if (config.plugin.forceHttp1) HttpClient.Version.HTTP_1_1 else HttpClient.Version.HTTP_2)
            .build()
    }

    /**
     * Gets computed file data for a specific file and key.
     * Checks cache validity and computes if necessary.
     * Supports both path-based and language-based lookups.
     *
     * @param projectId The UUID of the project
     * @param path The normalized path to the file (optional if language is provided)
     * @param languageId The language ID (optional if path is provided, assumes root path)
     * @param key The data key (e.g., "ast")
     * @param callerComputationId The file data computation the request comes from, when a plugin
     *        asks for this data while computing other data
     * @param deadline How long the caller is still willing to wait, if it said so
     * @return ApiResult containing the computed data with version or an error
     */
    suspend fun getFileData(
        projectId: UUID,
        path: String?,
        languageId: String?,
        key: String,
        callerComputationId: UUID? = null,
        deadline: CallerDeadline? = null
    ): ApiResult<RawFileData> {
        val normalizedPath = when {
            path != null -> normalizePath(path)
            languageId != null -> normalizePath("/")
            else -> return fileDataFailure(
                ErrorCodes.UNKNOWN,
                "Either path or languageId must be provided"
            )
        }

        cachedFileData(projectId, normalizedPath, key)?.let { return success(it) }

        // Requests for the same data while it is being computed wait for that computation. It is
        // shared, so it waits on the plugin as long as the backend allows rather than as long as the
        // request that started it happens to; each request only stops waiting at its own deadline.
        val shared = suspend {
            flights.run(FileDataTarget(projectId, normalizedPath, key), callerComputationId) { computationId ->
                // Another computation may have finished between the check above and taking the flight.
                cachedFileData(projectId, normalizedPath, key)?.let { success(it) }
                    ?: computeFileData(projectId, normalizedPath, languageId, key, computationId)
            }
        }
        if (deadline == null) return shared()
        val waited = if (deadline.isExpired) null else withTimeoutOrNull(deadline.remaining().toMillis()) { shared() }
        return waited
            ?: fileDataFailure(
                ErrorCodes.DEADLINE_EXCEEDED,
                "$normalizedPath:$key was not ready before the caller's deadline"
            )
    }

    /**
     * Returns the stored data for a file and key, if it is still current.
     *
     * @param projectId The UUID of the project
     * @param normalizedPath The normalized file path
     * @param key The data key
     * @return The data with its source version, or null when there is none or it is outdated
     */
    private fun cachedFileData(projectId: UUID, normalizedPath: String, key: String): RawFileData? {
        val row = transaction {
            FileDataTable.selectAll()
                .where {
                    (FileDataTable.projectId eq projectId.toKotlinUuid()) and
                            (FileDataTable.path eq normalizedPath) and
                            (FileDataTable.dataKey eq key)
                }
                .firstOrNull()
        } ?: return null

        if (!isDataCurrent(projectId, row)) return null
        return RawFileData(json = row[FileDataTable.data], version = row[FileDataTable.sourceVersion])
    }

    /**
     * Computes file data with the responsible plugin and stores it.
     *
     * @param projectId The UUID of the project
     * @param normalizedPath The normalized file path
     * @param languageId The language ID, when the data is addressed by language
     * @param key The data key
     * @param computationId The id the computation is recorded and its token issued under
     * @return The computed data, or an error
     */
    private suspend fun computeFileData(
        projectId: UUID,
        normalizedPath: String,
        languageId: String?,
        key: String,
        computationId: UUID
    ): ApiResult<RawFileData> {

        val fileRow = transaction {
            FilesTable.selectAll()
                .where { (FilesTable.projectId eq projectId.toKotlinUuid()) and (FilesTable.path eq normalizedPath) }
                .firstOrNull()
        } ?: return fileDataFailure(
            ErrorCodes.FILE_NOT_FOUND,
            "File not found: $normalizedPath"
        )

        val fileType = fileRow[FilesTable.fileType]
        val isDirectory = fileType == FileType.DIRECTORY

        val fileSource = if (!isDirectory) {
            val fileContent = when (val result = fileService.readFile(projectId, normalizedPath)) {
                is ApiResult.Success -> result.value
                is ApiResult.Failure -> return ApiResult.Failure(result.error)
            }

            val fileVersion = when (val result = fileService.getFileVersion(projectId, normalizedPath)) {
                is ApiResult.Success -> result.value
                is ApiResult.Failure -> return ApiResult.Failure(result.error)
            }

            FileSource(
                version = fileVersion,
                content = String(fileContent, Charsets.UTF_8),
                path = normalizedPath
            )
        } else {
            null
        }

        val pluginInfo = if (languageId != null) {
            pluginService.findPluginByLanguage(projectId, languageId)
                ?: return fileDataFailure(
                    ErrorCodes.FILE_DATA_NO_PLUGIN_FOUND,
                    "No plugin found for language: $languageId"
                )
        } else {
            pluginService.findPluginForFile(projectId, normalizedPath)
                ?: return fileDataFailure(
                    ErrorCodes.FILE_DATA_NO_PLUGIN_FOUND,
                    "No plugin found to compute data for file: $normalizedPath"
                )
        }

        val (pluginId, languagePlugin) = pluginInfo
        val pluginUrl = pluginService.getPluginUrl(pluginId, useInternal = true)
            ?: return fileDataFailure(
                ErrorCodes.PLUGIN_NOT_FOUND,
                "Plugin URL not found"
            )

        val contributions = ContributionSet(pluginService.getContributionPluginsForLanguage(projectId, languagePlugin.id))

        // Recorded before the plugin is called so the token below is backed by a computation that is
        // already visible to token verification, and removed again as soon as the call is done.
        beginComputation(projectId, normalizedPath, key, computationId)
        val logged = computationLog.start(projectId, normalizedPath, key)

        try {
            val token = jwtService.generateFileDataComputationToken(projectId, computationId)

            val call =
                computeFromPlugin(pluginId, pluginUrl, languagePlugin.id, key, projectId, fileSource, token, contributions)
            logged.finish(call.requestBytes, call.responseBytes)
            val computedData = call.response

            storeFileData(projectId, normalizedPath, key, computedData, fileSource?.version)

            for (additional in computedData.additionalFileData) {
                storeFileData(
                    projectId,
                    normalizePath(additional.path),
                    additional.key,
                    FileDataComputeResponse(
                        data = additional.data,
                        fileDependencies = additional.fileDependencies,
                        dataDependencies = additional.dataDependencies,
                        additionalFileData = emptyList()
                    ),
                    additional.sourceVersion
                )
            }

            return success(RawFileData(json = computedData.data.toString(), version = fileSource?.version ?: -1))
        } catch (e: CancellationException) {
            logged.fail()
            throw e
        } catch (e: java.net.http.HttpTimeoutException) {
            logged.fail()
            logger.error("Plugin did not compute $normalizedPath:$key in time", e)
            return fileDataFailure(
                ErrorCodes.FILE_DATA_COMPUTATION_FAILED,
                "The plugin did not compute $normalizedPath:$key within ${fileDataConfig.computationTimeoutSeconds} seconds"
            )
        } catch (e: Exception) {
            logged.fail()
            logger.error("Failed to compute file data for $normalizedPath:$key", e)
            return fileDataFailure(
                ErrorCodes.FILE_DATA_COMPUTATION_FAILED,
                "Failed to compute file data: ${e.message}"
            )
        } finally {
            endComputation(computationId)
        }
    }

    /**
     * Records a file data computation as running and returns its ID, which is what the token handed
     * to the computing plugin is bound to.
     *
     * Also purges rows left behind by requests that died before they could clean up, so that a crash
     * cannot keep a token alive for the rest of its lifetime.
     *
     * @param projectId The UUID of the project
     * @param path The normalized path of the file being computed
     * @param key The data key being computed
     * @param computationId The UUID to record the computation under
     */
    private fun beginComputation(projectId: UUID, path: String, key: String, computationId: UUID) {
        val now = Instant.now()
        val staleBefore = now.minusSeconds(fileDataConfig.computationBindingSeconds)

        transaction {
            FileDataComputationsTable.deleteWhere { startedAt less staleBefore }

            FileDataComputationsTable.insert {
                it[id] = computationId.toKotlinUuid()
                it[FileDataComputationsTable.projectId] = projectId.toKotlinUuid()
                it[FileDataComputationsTable.path] = path
                it[dataKey] = key
                it[startedAt] = now
            }
        }
    }

    /**
     * Marks a file data computation as finished, which stops its token from being accepted.
     *
     * @param computationId The UUID of the computation to clear
     */
    private fun endComputation(computationId: UUID) {
        try {
            transaction {
                FileDataComputationsTable.deleteWhere { id eq computationId.toKotlinUuid() }
            }
        } catch (e: Exception) {
            // The row is ignored once it is older than the computation binding, so a failure here
            // delays the token becoming unusable rather than leaving it valid indefinitely.
            logger.warn("Failed to clear file data computation $computationId", e)
        }
    }

    /**
     * Checks if cached file data is still current, and deletes it when it is not.
     *
     * Data is current when its source file still has the version it was computed from, and every
     * file and data dependency — of the data itself and, transitively, of every data it depends
     * on — still has the version recorded for it. All of that is one query, however deep the
     * dependencies go.
     */
    private fun isDataCurrent(projectId: UUID, row: ResultRow): Boolean {
        val path = row[FileDataTable.path]
        val dataKey = row[FileDataTable.dataKey]
        val sourceVersion = row[FileDataTable.sourceVersion]

        val current = transaction {
            exec(
                VALIDITY_QUERY,
                listOf(
                    TextColumnType() to projectId.toString(),
                    TextColumnType() to path,
                    IntegerColumnType() to sourceVersion,
                    TextColumnType() to projectId.toString(),
                    TextColumnType() to path,
                    TextColumnType() to dataKey
                )
            ) { resultSet -> resultSet.next() && resultSet.getBoolean(1) } ?: false
        }

        if (!current) {
            deleteFileData(projectId, path, dataKey)
        }
        return current
    }

    /**
     * Computes file data by calling the responsible plugin.
     * Path is now included inside the FileSource object.
     * For files, fileSource contains version, content, and path.
     * For directories, fileSource is null.
     *
     * @param pluginId The plugin, whose answer shows whether its manifest changed
     * @param pluginUrl Base URL of the plugin
     * @param languageId The language identifier for routing the request
     * @param key The data key to compute (e.g., "ast")
     * @param project Project UUID
     * @param fileSource Source data with version, content, and path (null for directories)
     * @param token JWT token for authentication
     * @param contributions The contribution plugins the plugin needs, sent as a hash when it holds them
     * @return Computed data response from the plugin, with the sizes of both messages
     */
    private suspend fun computeFromPlugin(
        pluginId: UUID,
        pluginUrl: String,
        languageId: String,
        key: String,
        project: UUID,
        fileSource: FileSource?,
        token: String,
        contributions: ContributionSet
    ): PluginComputation {
        return withContext(Dispatchers.IO) {
            val timeout = Duration.ofSeconds(fileDataConfig.computationTimeoutSeconds)
            val dataUrl = URI.create(pluginUrl).resolve("data/$languageId/$key")
            var requestBytes = ByteArray(0)

            val response = ContributionDelivery.send(pluginUrl) { includePayloads ->
                requestBytes = json.encodeToString(
                    FileDataComputeRequest(
                        project = project.toString(),
                        source = fileSource,
                        contributionPlugins = contributions.plugins.takeIf { includePayloads },
                        contributionHash = contributions.hash
                    )
                ).toByteArray(Charsets.UTF_8)

                val request = CompressedResponses.accept(HttpRequest.newBuilder())
                    .uri(dataUrl)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer $token")
                    .header(CallerDeadline.HEADER, CallerDeadline.headerValue(timeout))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(requestBytes))
                    .timeout(timeout)
                    .build()

                httpClient.send(request, CompressedResponses.ofByteArray())
            }
            pluginService.observeManifestFingerprint(pluginId, response)
            val responseText = String(response.body(), Charsets.UTF_8)

            if (response.statusCode() != 200) {
                throw RuntimeException("Plugin returned ${describeErrorResponse(response.statusCode(), responseText)}")
            }

            PluginComputation(
                response = json.decodeFromString<FileDataComputeResponse>(responseText),
                requestBytes = requestBytes.size,
                responseBytes = response.body().size
            )
        }
    }

    /**
     * What a plugin computed, and how large the exchange was in bytes.
     */
    private class PluginComputation(
        val response: FileDataComputeResponse,
        val requestBytes: Int,
        val responseBytes: Int
    )

    /**
     * Stores computed file data in the database with dependencies.
     */
    private fun storeFileData(
        projectId: UUID,
        path: String,
        key: String,
        response: FileDataComputeResponse,
        sourceVersion: Int?
    ) {
        val now = Instant.now()

        transaction {
            FileDataTable.deleteWhere {
                (FileDataTable.projectId eq projectId.toKotlinUuid()) and
                        (FileDataTable.path eq path) and
                        (FileDataTable.dataKey eq key)
            }

            FileDataTable.insert {
                it[FileDataTable.projectId] = projectId.toKotlinUuid()
                it[FileDataTable.path] = path
                it[FileDataTable.dataKey] = key
                it[FileDataTable.data] = response.data.toString()
                it[FileDataTable.sourceVersion] = sourceVersion ?: -1
                it[FileDataTable.createdAt] = now
                it[FileDataTable.updatedAt] = now
            }

            for (fileDep in response.fileDependencies.toSet()) {
                FileDependenciesTable.insert {
                    it[FileDependenciesTable.projectId] = projectId.toKotlinUuid()
                    it[FileDependenciesTable.path] = path
                    it[FileDependenciesTable.dataKey] = key
                    it[FileDependenciesTable.dependencyPath] = normalizePath(fileDep.path)
                    it[FileDependenciesTable.dependencyVersion] = fileDep.version ?: -1
                }
            }

            for (dataDep in response.dataDependencies.toSet()) {
                DataDependenciesTable.insert {
                    it[DataDependenciesTable.projectId] = projectId.toKotlinUuid()
                    it[DataDependenciesTable.path] = path
                    it[DataDependenciesTable.dataKey] = key
                    it[DataDependenciesTable.dependencyPath] = normalizePath(dataDep.path)
                    it[DataDependenciesTable.dependencyKey] = dataDep.key
                    it[DataDependenciesTable.dependencyVersion] = dataDep.version ?: -1
                }
            }
        }
    }

    /**
     * Deletes cached file data for a specific entry along with its dependencies.
     */
    private fun deleteFileData(projectId: UUID, path: String, key: String) {
        transaction {
            FileDataTable.deleteWhere {
                (FileDataTable.projectId eq projectId.toKotlinUuid()) and
                        (FileDataTable.path eq path) and
                        (FileDataTable.dataKey eq key)
            }
        }
    }

    /**
     * Invalidates all file data for a project.
     * Cascading deletes automatically handle dependencies.
     *
     * @param projectId The UUID of the project
     */
    fun invalidateProjectData(projectId: UUID) {
        logger.info("Invalidating all file data for project $projectId")
        transaction {
            FileDataTable.deleteWhere { FileDataTable.projectId eq projectId.toKotlinUuid() }
        }
    }

    /**
     * Invalidates all file data for projects using a specific plugin.
     *
     * @param pluginId The UUID of the plugin
     */
    fun invalidatePluginData(pluginId: UUID) {
        logger.info("Invalidating file data for all projects using plugin $pluginId")
        val projectIds = pluginService.getProjectsUsingPlugin(pluginId)
        for (projectId in projectIds) {
            invalidateProjectData(projectId)
        }
    }
}

/**
 * File data as stored: the JSON text of the data, and the version of the source it was computed from.
 *
 * @property json The data, as JSON text
 * @property version The source file version, -1 for data computed for a directory
 */
class RawFileData(val json: String, val version: Int?) {
    /**
     * The response body of a file data request, `{"data": …, "version": …}`, built without parsing
     * the data.
     */
    fun toResponseJson(): String = """{"data":$json,"version":${version ?: "null"}}"""
}

/**
 * Whether one piece of file data is current, in a single statement.
 *
 * Parameters: project id, path and source version for the source check, then project id, path and
 * data key for the dependency walk. The recursive part collects the data itself and everything it
 * depends on; the data is stale if any of those has a file or data dependency whose file changed.
 */
private val VALIDITY_QUERY = """
    SELECT
      EXISTS (
        SELECT 1 FROM files f
        WHERE f.project_id = CAST(? AS uuid) AND f.path = ? AND f.version = ?
      )
      AND NOT EXISTS (
        WITH RECURSIVE nodes(project_id, path, data_key) AS (
            SELECT CAST(? AS uuid), CAST(? AS varchar), CAST(? AS varchar)
          UNION
            SELECT dd.project_id, dd.dependency_path, dd.dependency_key
            FROM data_dependencies dd
            JOIN nodes n ON dd.project_id = n.project_id AND dd.path = n.path AND dd.data_key = n.data_key
        )
        SELECT 1 FROM nodes n
        WHERE EXISTS (
            SELECT 1 FROM file_dependencies d
            LEFT JOIN files f ON f.project_id = d.project_id AND f.path = d.dependency_path
            WHERE d.project_id = n.project_id AND d.path = n.path AND d.data_key = n.data_key
              AND (f.version IS NULL OR f.version <> d.dependency_version)
          )
          OR EXISTS (
            SELECT 1 FROM data_dependencies d
            LEFT JOIN files f ON f.project_id = d.project_id AND f.path = d.dependency_path
            WHERE d.project_id = n.project_id AND d.path = n.path AND d.data_key = n.data_key
              AND (f.version IS NULL OR f.version <> d.dependency_version)
          )
      )
""".trimIndent()