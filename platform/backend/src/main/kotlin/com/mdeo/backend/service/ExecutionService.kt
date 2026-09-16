package com.mdeo.backend.service

import com.mdeo.common.transport.CompressedResponses
import com.mdeo.common.model.ExecutionState
import com.mdeo.backend.database.ExecutionsTable
import com.mdeo.backend.database.FilesTable
import com.mdeo.common.model.*
import com.mdeo.common.transport.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.*
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid

/**
 * Service for managing executions within projects.
 * Handles CRUD operations and communication with plugins for execution-related actions.
 *
 * @param services The injected services providing access to configuration and other services
 */
class ExecutionService(services: InjectedServices) : BaseService(), InjectedServices by services {
    private val logger = LoggerFactory.getLogger(ExecutionService::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    private val httpClient by lazy {
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .version(if (config.plugin.forceHttp1) HttpClient.Version.HTTP_1_1 else HttpClient.Version.HTTP_2)
            .build()
    }

    /**
     * Connections to plugin services for reading execution results.
     *
     * Result reads are the one plugin interaction that happens in bursts — opening a
     * completed run asks for a tree and then a file at a time — and each one used to be its
     * own HTTP request across two further hops. These connections are held open across such
     * a burst and dropped again once the user moves on. Each request still carries the token
     * that authorizes it, so a dropped connection costs nothing but a reconnect.
     */
    private val executionWsClient by lazy { ExecutionWsClient() }

    companion object {
        /**
         * JWT scope for reading execution data 
         */
        const val SCOPE_EXECUTION_READ = "execution:read"

        /**
         * JWT scope for writing execution state 
         */
        const val SCOPE_EXECUTION_WRITE = "execution:write"
    }

    /**
     * Lists all executions for a project.
     *
     * @param projectId The UUID of the project
     * @return List of executions in the project
     */
    fun listExecutions(projectId: UUID): List<Execution> {
        return transaction {
            ExecutionsTable
                .selectAll()
                .where { ExecutionsTable.projectId eq projectId.toKotlinUuid() }
                .orderBy(ExecutionsTable.createdAt, SortOrder.DESC)
                .map { row -> rowToExecution(row) }
        }
    }

    /**
     * Gets a single execution by ID.
     *
     * @param projectId The UUID of the project (for authorization)
     * @param executionId The UUID of the execution
     * @return ApiResult containing the execution or an error
     */
    fun getExecution(projectId: UUID, executionId: UUID): ApiResult<Execution> {
        return transaction {
            val row = ExecutionsTable
                .selectAll()
                .where {
                    (ExecutionsTable.id eq executionId.toKotlinUuid()) and
                            (ExecutionsTable.projectId eq projectId.toKotlinUuid())
                }
                .firstOrNull()

            if (row == null) {
                return@transaction executionFailure(
                    ErrorCodes.EXECUTION_NOT_FOUND,
                    "Execution not found: $executionId"
                )
            }

            success(rowToExecution(row))
        }
    }

    /**
     * Gets an execution by ID (without project validation, for JWT auth).
     *
     * @param executionId The UUID of the execution
     * @return ApiResult containing the execution or an error
     */
    fun getExecutionById(executionId: UUID): ApiResult<Execution> {
        return transaction {
            val row = ExecutionsTable
                .selectAll()
                .where { ExecutionsTable.id eq executionId.toKotlinUuid() }
                .firstOrNull()

            if (row == null) {
                return@transaction executionFailure(
                    ErrorCodes.EXECUTION_NOT_FOUND,
                    "Execution not found: $executionId"
                )
            }

            success(rowToExecution(row))
        }
    }

    /**
     * Creates a new execution and forwards the request to the plugin.
     *
     * @param projectId The UUID of the project
     * @param filePath Path to the file to execute
     * @param data Arbitrary JSON data for the execution
     * @return ApiResult containing the created execution or an error
     */
    suspend fun createExecution(
        projectId: UUID,
        filePath: String,
        data: JsonElement
    ): ApiResult<Execution> {
        val normalizedPath = normalizePath(filePath)

        val fileInfo = transaction {
            FilesTable.selectAll()
                .where {
                    (FilesTable.projectId eq projectId.toKotlinUuid()) and
                            (FilesTable.path eq normalizedPath)
                }
                .firstOrNull()
        }

        if (fileInfo == null) {
            return executionFailure(
                ErrorCodes.FILE_NOT_FOUND,
                "File not found: $filePath"
            )
        }

        val pluginInfo = pluginService.findPluginForFile(projectId, normalizedPath)
            ?: return executionFailure(
                ErrorCodes.FILE_DATA_NO_PLUGIN_FOUND,
                "No plugin found for file: $filePath"
            )

        val (pluginId, languagePlugin) = pluginInfo
        val pluginUrl = pluginService.getPluginUrl(pluginId, useInternal = true)
            ?: return executionFailure(
                ErrorCodes.PLUGIN_NOT_FOUND,
                "Plugin URL not found"
            )

        val fileContent = when (val result = fileService.readFile(projectId, normalizedPath)) {
            is ApiResult.Success -> String(result.value, Charsets.UTF_8)
            is ApiResult.Failure -> return ApiResult.Failure(result.error)
        }

        val fileVersion = when (val result = fileService.getFileVersion(projectId, normalizedPath)) {
            is ApiResult.Success -> result.value
            is ApiResult.Failure -> return ApiResult.Failure(result.error)
        }

        val contributionPlugins = pluginService.getContributionPluginsForLanguage(projectId, languagePlugin.id)

        val executionId = UUID.randomUUID()
        val now = Instant.now()

        transaction {
            ExecutionsTable.insert {
                it[id] = executionId.toKotlinUuid()
                it[ExecutionsTable.projectId] = projectId.toKotlinUuid()
                it[name] = "Execution $executionId"
                it[state] = ExecutionState.SUBMITTED
                it[progressText] = null
                it[metadata] = null
                it[ExecutionsTable.filePath] = normalizedPath
                it[ExecutionsTable.languageId] = languagePlugin.id
                it[createdAt] = now
                it[updatedAt] = now
            }
        }

        val createResponse = try {
            callPluginCreateExecution(
                pluginUrl,
                languagePlugin.id,
                executionId,
                projectId,
                normalizedPath,
                fileContent,
                fileVersion,
                data,
                contributionPlugins
            )
        } catch (e: Exception) {
            logger.error("Failed to create execution via plugin", e)
            val failedAt = Instant.now()
            transaction {
                ExecutionsTable.update({ ExecutionsTable.id eq executionId.toKotlinUuid() }) {
                    it[state] = ExecutionState.FAILED
                    it[progressText] = "Failed to create execution: ${e.message}"
                    it[updatedAt] = failedAt
                    it[finishedAt] = failedAt
                }
            }
            val failedExecution = getExecution(projectId, executionId)
            if (failedExecution is ApiResult.Success) {
                notifyExecutionStateChange(failedExecution.value)
            }
            return executionFailure(
                ErrorCodes.EXECUTION_PLUGIN_ERROR,
                "Failed to create execution: ${e.message}"
            )
        }

        transaction {
            ExecutionsTable.update({ ExecutionsTable.id eq executionId.toKotlinUuid() }) {
                it[name] = createResponse.name
            }
        }

        return getExecution(projectId, executionId)
    }

    /**
     * Updates the state of an execution (called by services via JWT).
     * Broadcasts a WebSocket notification to all subscribed clients.
     *
     * @param executionId The UUID of the execution
     * @param newState The new state
     * @param progressText Optional progress text
     * @return ApiResult indicating success or containing an error
     */
    suspend fun updateExecutionState(
        executionId: UUID,
        newState: String,
        progressText: String?
    ): ApiResult<Execution> {
        val validationResult = validateExecutionState(newState)
        if (validationResult != null) {
            return validationResult
        }

        val result = performStateUpdateTransaction(executionId, newState, progressText)

        if (result is ApiResult.Success) {
            notifyExecutionStateChange(result.value)
        }

        return result
    }

    /**
     * Updates the metadata of an execution (called by services via JWT).
     * Metadata is only writable while execution is non-terminal.
     *
     * @param executionId The UUID of the execution
     * @param metadata Small JSON metadata object
     * @return ApiResult containing the updated execution or an error
     */
    suspend fun updateExecutionMetadata(
        executionId: UUID,
        metadata: JsonObject
    ): ApiResult<Execution> {
        return transaction {
            val existing = ExecutionsTable.selectAll()
                .where { ExecutionsTable.id eq executionId.toKotlinUuid() }
                .firstOrNull()

            if (existing == null) {
                return@transaction executionFailure(
                    ErrorCodes.EXECUTION_NOT_FOUND,
                    "Execution not found: $executionId"
                )
            }

            val currentState = existing[ExecutionsTable.state]
            if (isTerminalState(currentState)) {
                return@transaction executionFailure(
                    ErrorCodes.EXECUTION_ALREADY_COMPLETED,
                    "Execution is already in terminal state: $currentState"
                )
            }

            val now = Instant.now()
            ExecutionsTable.update({ ExecutionsTable.id eq executionId.toKotlinUuid() }) {
                it[ExecutionsTable.metadata] = metadata
                it[updatedAt] = now
            }

            val row = ExecutionsTable
                .selectAll()
                .where { ExecutionsTable.id eq executionId.toKotlinUuid() }
                .first()

            success(rowToExecution(row))
        }
    }

    /**
     * Performs the database transaction for state update.
     *
     * @param executionId The UUID of the execution
     * @param newState The new state
     * @param progressText Optional progress text
     * @return ApiResult containing the updated execution or an error
     */
    private fun performStateUpdateTransaction(
        executionId: UUID,
        newState: String,
        progressText: String?
    ): ApiResult<Execution> {
        return transaction {
            val existing = ExecutionsTable.selectAll()
                .where { ExecutionsTable.id eq executionId.toKotlinUuid() }
                .firstOrNull()

            if (existing == null) {
                return@transaction executionFailure(
                    ErrorCodes.EXECUTION_NOT_FOUND,
                    "Execution not found: $executionId"
                )
            }

            val currentState = existing[ExecutionsTable.state]

            if (isTerminalState(currentState)) {
                return@transaction executionFailure(
                    ErrorCodes.EXECUTION_ALREADY_COMPLETED,
                    "Execution is already in terminal state: $currentState"
                )
            }

            val updatedCount = performStateUpdate(executionId, newState, progressText, currentState)

            if (updatedCount == 0) {
                return@transaction executionFailure(
                    ErrorCodes.EXECUTION_ALREADY_COMPLETED,
                    "Execution transitioned to a terminal state concurrently"
                )
            }

            val row = ExecutionsTable
                .selectAll()
                .where { ExecutionsTable.id eq executionId.toKotlinUuid() }
                .first()

            success(rowToExecution(row))
        }
    }

    /**
     * Sends a WebSocket notification for an execution state change.
     *
     * @param execution The updated execution
     * @return Unit
     */
    private suspend fun notifyExecutionStateChange(execution: Execution) {
        val projectId = try {
            UUID.fromString(execution.projectId)
        } catch (e: Exception) {
            logger.warn("Invalid project ID in execution: ${execution.projectId}")
            return
        }

        webSocketNotificationService.broadcastExecutionStateChange(projectId, execution)
    }

    /**
     * Validates that the given state is a valid execution state.
     *
     * @param state The state to validate
     * @return An error result if invalid, null if valid
     */
    private fun validateExecutionState(state: String): ApiResult<Execution>? {
        val validStates = listOf(
            ExecutionState.SUBMITTED,
            ExecutionState.INITIALIZING,
            ExecutionState.RUNNING,
            ExecutionState.COMPLETED,
            ExecutionState.CANCELLED,
            ExecutionState.FAILED
        )
        if (state !in validStates) {
            return executionFailure(
                ErrorCodes.EXECUTION_INVALID_STATE,
                "Invalid execution state: $state"
            )
        }
        return null
    }

    /**
     * Checks if a state is a terminal state (completed, cancelled, or failed).
     *
     * @param state The state to check
     * @return True if the state is terminal
     */
    private fun isTerminalState(state: String): Boolean {
        return state in listOf(ExecutionState.COMPLETED, ExecutionState.CANCELLED, ExecutionState.FAILED)
    }

    /**
     * Performs the actual database update for state changes.
     *
     * @param executionId The UUID of the execution
     * @param newState The new state to set
     * @param progressText Optional progress text
     * @param currentState The current state (for determining timing updates)
     */
    private fun performStateUpdate(
        executionId: UUID,
        newState: String,
        progressText: String?,
        currentState: String
    ): Int {
        val now = Instant.now()
        val terminalStates = listOf(ExecutionState.COMPLETED, ExecutionState.CANCELLED, ExecutionState.FAILED)

        return ExecutionsTable.update({
            (ExecutionsTable.id eq executionId.toKotlinUuid()) and
                (ExecutionsTable.state notInList terminalStates)
        }) {
            it[state] = newState
            it[ExecutionsTable.progressText] = progressText
            it[updatedAt] = now

            if (newState == ExecutionState.RUNNING && currentState != ExecutionState.RUNNING) {
                it[startedAt] = now
            }

            if (isTerminalState(newState) && !isTerminalState(currentState)) {
                it[finishedAt] = now
            }
        }
    }

    /**
     * Gets the execution with its file tree by forwarding to the plugin.
     *
     * @param projectId The UUID of the project
     * @param executionId The UUID of the execution
     * @return ApiResult containing the execution with file tree or an error
     */
    suspend fun getExecutionWithTree(
        projectId: UUID,
        executionId: UUID
    ): ApiResult<ExecutionWithTree> {
        val executionResult = getExecution(projectId, executionId)
        if (executionResult is ApiResult.Failure) {
            return ApiResult.Failure(executionResult.error)
        }

        val execution = (executionResult as ApiResult.Success).value

        if (execution.state != ExecutionState.COMPLETED) {
            return success(ExecutionWithTree(execution, null))
        }

        val pluginUrl = getPluginUrlForExecution(execution)
            ?: return executionFailure(
                ErrorCodes.PLUGIN_NOT_FOUND,
                "Plugin not found for execution"
            )

        val fileTree = try {
            callPluginGetFileTree(pluginUrl, execution.languageId, executionId, projectId, execution.metadata)
        } catch (e: Exception) {
            logger.error("Failed to get file tree from plugin", e)
            return executionFailure(
                ErrorCodes.EXECUTION_PLUGIN_ERROR,
                "Failed to get file tree: ${e.message}"
            )
        }

        return success(ExecutionWithTree(execution, fileTree))
    }

    /**
     * Gets the summary for an execution by forwarding to the plugin.
     *
     * @param projectId The UUID of the project
     * @param executionId The UUID of the execution
     * @return ApiResult containing the summary content or an error
     */
    suspend fun getExecutionSummary(
        projectId: UUID,
        executionId: UUID
    ): ApiResult<String> {
        val executionResult = getExecution(projectId, executionId)
        if (executionResult is ApiResult.Failure) {
            return ApiResult.Failure(executionResult.error)
        }

        val execution = (executionResult as ApiResult.Success).value

        val pluginUrl = getPluginUrlForExecution(execution)
            ?: return executionFailure(
                ErrorCodes.PLUGIN_NOT_FOUND,
                "Plugin not found for execution"
            )

        return try {
            val summary = callPluginGetSummary(pluginUrl, execution.languageId, executionId, projectId, execution.metadata)
            success(summary)
        } catch (e: Exception) {
            logger.error("Failed to get summary from plugin", e)
            executionFailure(
                ErrorCodes.EXECUTION_PLUGIN_ERROR,
                "Failed to get summary: ${e.message}"
            )
        }
    }

    /**
     * Gets a result file for an execution by forwarding to the plugin.
     *
     * @param projectId The UUID of the project
     * @param executionId The UUID of the execution
     * @param path Path to the result file
     * @return ApiResult containing the file contents or an error
     */
    suspend fun getExecutionFile(
        projectId: UUID,
        executionId: UUID,
        path: String
    ): ApiResult<ByteArray> {
        val executionResult = getExecution(projectId, executionId)
        if (executionResult is ApiResult.Failure) {
            return ApiResult.Failure(executionResult.error)
        }

        val execution = (executionResult as ApiResult.Success).value

        val pluginUrl = getPluginUrlForExecution(execution)
            ?: return executionFailure(
                ErrorCodes.PLUGIN_NOT_FOUND,
                "Plugin not found for execution"
            )

        return try {
            val fileContent = callPluginGetFile(
                pluginUrl,
                execution.languageId,
                executionId,
                projectId,
                path,
                execution.metadata
            )
            success(fileContent)
        } catch (e: Exception) {
            logger.error("Failed to get file from plugin", e)
            executionFailure(
                ErrorCodes.EXECUTION_PLUGIN_ERROR,
                "Failed to get file: ${e.message}"
            )
        }
    }

    /**
     * Reads a whole execution result set in one request to the plugin, reporting each file
     * as it arrives.
     *
     * This is what makes opening a completed execution cheap. Reading the results a file at
     * a time cost a request per file at every hop between the browser and the service that
     * stores them; this asks once and lets the answer stream back, so the caller can hand
     * files to the user as they land rather than after the last one.
     *
     * If the plugin does not serve the WebSocket endpoint, the tree and each file are fetched
     * over HTTP instead — the caller still makes a single request, and only the hops below
     * this one lose the saving.
     *
     * @param projectId The UUID of the project
     * @param executionId The UUID of the execution
     * @param paths Files to read, or null for every file in the result tree
     * @param onFile Invoked with each file's path and text content as it arrives
     * @return The entries that were read, or an error
     */
    suspend fun streamExecutionFiles(
        projectId: UUID,
        executionId: UUID,
        paths: List<String>?,
        onFile: suspend (path: String, content: String) -> Unit
    ): ApiResult<List<FileEntry>> {
        val executionResult = getExecution(projectId, executionId)
        if (executionResult is ApiResult.Failure) {
            return ApiResult.Failure(executionResult.error)
        }

        val execution = (executionResult as ApiResult.Success).value
        val pluginUrl = getPluginUrlForExecution(execution)
            ?: return executionFailure(ErrorCodes.PLUGIN_NOT_FOUND, "Plugin not found for execution")

        return try {
            val response = pluginWsRequest(
                pluginUrl,
                JwtService.SCOPE_PLUGIN_EXECUTION_READ,
                executionId,
                projectId,
                { token ->
                    ExecutionFilesWsRequest(
                        "",
                        wsContext(executionId, projectId, execution.languageId, execution.metadata, token),
                        paths
                    )
                },
                { message ->
                    if (message is ExecutionFileDataMessage) {
                        onFile(message.path, message.content)
                    }
                }
            )

            if (response != null) {
                success(decodePayload<ExecutionFileTreePayload>(response.data).files)
            } else {
                success(streamExecutionFilesOverHttp(pluginUrl, execution, executionId, projectId, paths, onFile))
            }
        } catch (e: Exception) {
            logger.error("Failed to load execution files from plugin $pluginUrl for execution $executionId", e)
            executionFailure(ErrorCodes.EXECUTION_PLUGIN_ERROR, "Failed to load execution files: ${e.message}")
        }
    }

    /**
     * Fallback for [streamExecutionFiles] against a plugin without the WebSocket endpoint.
     *
     * A file that cannot be read is skipped rather than failing the whole load: a partially
     * readable result set is still worth showing, and the tree the caller receives back tells
     * it exactly which files it did not get.
     */
    private suspend fun streamExecutionFilesOverHttp(
        pluginUrl: String,
        execution: Execution,
        executionId: UUID,
        projectId: UUID,
        paths: List<String>?,
        onFile: suspend (path: String, content: String) -> Unit
    ): List<FileEntry> {
        val tree = callPluginGetFileTreeHttp(
            pluginUrl, execution.languageId, executionId, projectId, execution.metadata
        )
        val requested = paths?.toSet()
        val sent = mutableListOf<FileEntry>()

        for (entry in tree) {
            if (entry.type != FileType.FILE) {
                sent.add(entry)
                continue
            }
            if (requested != null && entry.name !in requested) {
                continue
            }
            try {
                val content = callPluginGetFileHttp(
                    pluginUrl, execution.languageId, executionId, projectId, entry.name, execution.metadata
                )
                onFile(entry.name, String(content, Charsets.UTF_8))
                sent.add(entry)
            } catch (e: Exception) {
                logger.warn("Skipping unreadable execution file ${entry.name}: ${e.message}")
            }
        }

        return sent
    }

    /**
     * Cancels an execution by forwarding to the plugin.
     *
     * @param projectId The UUID of the project
     * @param executionId The UUID of the execution
     * @return ApiResult indicating success or containing an error
     */
    suspend fun cancelExecution(
        projectId: UUID,
        executionId: UUID
    ): ApiResult<Unit> {
        val executionResult = getExecution(projectId, executionId)
        if (executionResult is ApiResult.Failure) {
            return ApiResult.Failure(executionResult.error)
        }

        val execution = (executionResult as ApiResult.Success).value

        if (isTerminalState(execution.state)) {
            return executionFailure(
                ErrorCodes.EXECUTION_ALREADY_COMPLETED,
                "Execution is already in terminal state: ${execution.state}"
            )
        }

        val pluginUrl = getPluginUrlForExecution(execution)
            ?: return executionFailure(
                ErrorCodes.PLUGIN_NOT_FOUND,
                "Plugin not found for execution"
            )

        return try {
            callPluginCancel(pluginUrl, execution.languageId, executionId, projectId, execution.metadata)

            val updatedExecution = transaction {
                val now = Instant.now()
                ExecutionsTable.update({ ExecutionsTable.id eq executionId.toKotlinUuid() }) {
                    it[state] = ExecutionState.CANCELLED
                    it[updatedAt] = now
                    it[finishedAt] = now
                }
                val row = ExecutionsTable.selectAll()
                    .where { ExecutionsTable.id eq executionId.toKotlinUuid() }
                    .first()
                rowToExecution(row)
            }

            notifyExecutionStateChange(updatedExecution)

            success(Unit)
        } catch (e: Exception) {
            logger.error("Failed to cancel execution via plugin", e)
            executionFailure(
                ErrorCodes.EXECUTION_PLUGIN_ERROR,
                "Failed to cancel execution: ${e.message}"
            )
        }
    }

    /**
     * Deletes an execution (implies cancel if running) by forwarding to the plugin.
     *
     * @param projectId The UUID of the project
     * @param executionId The UUID of the execution
     * @return ApiResult indicating success or containing an error
     */
    suspend fun deleteExecution(
        projectId: UUID,
        executionId: UUID
    ): ApiResult<Unit> {
        val executionResult = getExecution(projectId, executionId)
        if (executionResult is ApiResult.Failure) {
            return ApiResult.Failure(executionResult.error)
        }

        val execution = (executionResult as ApiResult.Success).value

        val pluginUrl = getPluginUrlForExecution(execution)
            ?: return executionFailure(
                ErrorCodes.PLUGIN_NOT_FOUND,
                "Plugin not found for execution"
            )

        return try {
            callPluginDelete(pluginUrl, execution.languageId, executionId, projectId, execution.metadata)

            transaction {
                ExecutionsTable.deleteWhere { ExecutionsTable.id eq executionId.toKotlinUuid() }
            }

            success(Unit)
        } catch (e: Exception) {
            logger.error("Failed to delete execution via plugin", e)
            executionFailure(
                ErrorCodes.EXECUTION_PLUGIN_ERROR,
                "Failed to delete execution: ${e.message}"
            )
        }
    }

    /**
     * Deletes all executions for a project.
     *
     * @param projectId The UUID of the project
     * @return ApiResult indicating success or containing an error
     */
    suspend fun deleteAllExecutions(projectId: UUID): ApiResult<Unit> {
        val executions = listExecutions(projectId)

        if (executions.isEmpty()) {
            return success(Unit)
        }

        var hasError = false
        var lastError: String? = null

        for (execution in executions) {
            val executionId = try {
                UUID.fromString(execution.id)
            } catch (e: Exception) {
                logger.error("Invalid execution ID: ${execution.id}")
                continue
            }

            val result = deleteExecution(projectId, executionId)
            if (result is ApiResult.Failure) {
                hasError = true
                lastError = result.error.message
                logger.error("Failed to delete execution ${execution.id}: ${result.error.message}")
            }
        }

        return if (hasError) {
            executionFailure(
                ErrorCodes.EXECUTION_PLUGIN_ERROR,
                lastError ?: "Failed to delete some executions"
            )
        } else {
            success(Unit)
        }
    }

    /**
     * Checks if a project has any execution in the initializing state.
     * Used for project locking during initialization.
     *
     * @param projectId The UUID of the project
     * @return true if the project has an initializing execution
     */
    fun hasInitializingExecution(projectId: UUID): Boolean {
        return transaction {
            ExecutionsTable.selectAll()
                .where {
                    (ExecutionsTable.projectId eq projectId.toKotlinUuid()) and
                            (ExecutionsTable.state eq ExecutionState.INITIALIZING)
                }
                .count() > 0
        }
    }

    /**
     * Converts a database row to an Execution object.
     *
     * @param row The database result row to convert
     * @return The Execution object with all fields populated
     */
    private fun rowToExecution(row: ResultRow): Execution {
        return Execution(
            id = row[ExecutionsTable.id].toJavaUuid().toString(),
            projectId = row[ExecutionsTable.projectId].toJavaUuid().toString(),
            filePath = row[ExecutionsTable.filePath],
            languageId = row[ExecutionsTable.languageId],
            name = row[ExecutionsTable.name],
            state = row[ExecutionsTable.state],
            progressText = row[ExecutionsTable.progressText],
            metadata = row[ExecutionsTable.metadata],
            createdAt = row[ExecutionsTable.createdAt].toString(),
            startedAt = row[ExecutionsTable.startedAt]?.toString(),
            finishedAt = row[ExecutionsTable.finishedAt]?.toString()
        )
    }

    /**
     * Gets the plugin URL for an execution based on its language.
     *
     * @param execution The execution to get the plugin URL for
     * @return The plugin URL, or null if not found
     */
    private fun getPluginUrlForExecution(execution: Execution): String? {
        val projectId = UUID.fromString(execution.projectId)
        val pluginInfo = pluginService.findPluginByLanguage(projectId, execution.languageId)
            ?: return null
        return pluginService.getPluginUrl(pluginInfo.first, useInternal = true)
    }

    /**
     * Calls the plugin to create an execution.
     *
     * @param pluginUrl The base URL of the plugin
     * @param languageId The language identifier for routing the request
     * @param executionId The UUID of the execution
     * @param projectId The UUID of the project
     * @param filePath The path to the file to execute
     * @param data Arbitrary JSON data for the execution
     * @return The CreateExecutionResponse from the plugin
     */
    private suspend fun callPluginCreateExecution(
        pluginUrl: String,
        languageId: String,
        executionId: UUID,
        projectId: UUID,
        filePath: String,
        fileContent: String,
        fileVersion: Int,
        data: JsonElement,
        contributionPlugins: List<JsonElement>
    ): CreateExecutionResponse {
        return withContext(Dispatchers.IO) {
            // The execution node keeps this token for the entire run and reports progress and the
            // terminal state with it, so it gets the longer execution lifetime rather than the
            // general (request-scoped) one. Being bound to the execution keeps that lifetime from
            // outliving the run: the update that reports the terminal state is the last request the
            // token is accepted for.
            val token = jwtService.generateExecutionRunToken(
                projectId,
                executionId,
                ttlSeconds = config.jwt.executionExpirationSeconds
            )
            val requestBody = json.encodeToString(
                PluginCreateExecutionRequest.serializer(),
                PluginCreateExecutionRequest(
                    executionId = executionId.toString(),
                    project = projectId.toString(),
                    filePath = filePath,
                    fileContent = fileContent,
                    fileVersion = fileVersion,
                    data = data,
                    contributionPlugins = contributionPlugins
                )
            )

            val uri = URI.create(pluginUrl).resolve("$languageId/executions")
            val request = CompressedResponses.accept(HttpRequest.newBuilder())
                .uri(uri)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer $token")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                // Starting an execution can require the plugin to have file data computed first,
                // which for a large model takes minutes, so this must not give up before a single
                // computation would have been abandoned anyway.
                .timeout(Duration.ofSeconds(config.fileData.computationTimeoutSeconds))
                .build()

            val response = httpClient.send(request, CompressedResponses.ofString())

            if (response.statusCode() != 200 && response.statusCode() != 201) {
                throw RuntimeException("Plugin returned status ${response.statusCode()}: ${response.body()}")
            }

            json.decodeFromString<CreateExecutionResponse>(response.body())
        }
    }

    /**
     * Calls the plugin to get the file tree for an execution.
     *
     * @param pluginUrl The base URL of the plugin
     * @param languageId The language identifier for routing the request
     * @param executionId The UUID of the execution
     * @param projectId The UUID of the project
     * @return A list of file entries representing the execution's file tree
     */
    private suspend fun callPluginGetFileTree(
        pluginUrl: String,
        languageId: String,
        executionId: UUID,
        projectId: UUID,
        metadata: JsonObject?
    ): List<FileEntry> {
        val overWs = pluginWsRequest(pluginUrl, JwtService.SCOPE_PLUGIN_EXECUTION_READ, executionId, projectId, build = { token ->
            ExecutionFileTreeWsRequest("", wsContext(executionId, projectId, languageId, metadata, token))
        })
        if (overWs != null) {
            return decodePayload<ExecutionFileTreePayload>(overWs.data).files
        }
        return callPluginGetFileTreeHttp(pluginUrl, languageId, executionId, projectId, metadata)
    }

    private suspend fun callPluginGetFileTreeHttp(
        pluginUrl: String,
        languageId: String,
        executionId: UUID,
        projectId: UUID,
        metadata: JsonObject?
    ): List<FileEntry> {
        return withContext(Dispatchers.IO) {
            val token = jwtService.generatePluginExecutionToken(
                projectId,
                executionId,
                JwtService.SCOPE_PLUGIN_EXECUTION_READ
            )
            val uri = URI.create(pluginUrl).resolve("$languageId/executions/$executionId/files")

            val request = CompressedResponses.accept(HttpRequest.newBuilder())
                .uri(uri)
                .header("Authorization", "Bearer $token")
                .GET()
                .timeout(Duration.ofMinutes(1))
                .applyExecutionMetadataHeader(metadata)
                .build()

            val response = httpClient.send(request, CompressedResponses.ofString())

            if (response.statusCode() != 200) {
                throw RuntimeException("Plugin returned status ${response.statusCode()}: ${response.body()}")
            }

            json.decodeFromString<ExecutionFileTreeResponse>(response.body()).files
        }
    }

    /**
     * Calls the plugin to get the summary for an execution.
     *
     * @param pluginUrl The base URL of the plugin
     * @param languageId The language identifier for routing the request
     * @param executionId The UUID of the execution
     * @param projectId The UUID of the project
     * @return The execution summary as a string
     */
    private suspend fun callPluginGetSummary(
        pluginUrl: String,
        languageId: String,
        executionId: UUID,
        projectId: UUID,
        metadata: JsonObject?
    ): String {
        val overWs = pluginWsRequest(pluginUrl, JwtService.SCOPE_PLUGIN_EXECUTION_READ, executionId, projectId, build = { token ->
            ExecutionSummaryWsRequest("", wsContext(executionId, projectId, languageId, metadata, token))
        })
        if (overWs != null) {
            return decodePayload<ExecutionSummaryPayload>(overWs.data).summary
        }
        return callPluginGetSummaryHttp(pluginUrl, languageId, executionId, projectId, metadata)
    }

    private suspend fun callPluginGetSummaryHttp(
        pluginUrl: String,
        languageId: String,
        executionId: UUID,
        projectId: UUID,
        metadata: JsonObject?
    ): String {
        return withContext(Dispatchers.IO) {
            val token = jwtService.generatePluginExecutionToken(
                projectId,
                executionId,
                JwtService.SCOPE_PLUGIN_EXECUTION_READ
            )
            val uri = URI.create(pluginUrl).resolve("$languageId/executions/$executionId/summary")

            val request = CompressedResponses.accept(HttpRequest.newBuilder())
                .uri(uri)
                .header("Authorization", "Bearer $token")
                .GET()
                .timeout(Duration.ofMinutes(1))
                .applyExecutionMetadataHeader(metadata)
                .build()

            val response = httpClient.send(request, CompressedResponses.ofString())

            if (response.statusCode() != 200) {
                throw RuntimeException("Plugin returned status ${response.statusCode()}: ${response.body()}")
            }

            json.decodeFromString<ExecutionSummaryResponse>(response.body()).summary
        }
    }

    /**
     * Calls the plugin to get a result file for an execution.
     *
     * @param pluginUrl The base URL of the plugin
     * @param languageId The language identifier for routing the request
     * @param executionId The UUID of the execution
     * @param projectId The UUID of the project
     * @param path The path to the requested file
     * @return The file contents as a byte array
     */
    private suspend fun callPluginGetFile(
        pluginUrl: String,
        languageId: String,
        executionId: UUID,
        projectId: UUID,
        path: String,
        metadata: JsonObject?
    ): ByteArray {
        val overWs = pluginWsRequest(pluginUrl, JwtService.SCOPE_PLUGIN_EXECUTION_READ, executionId, projectId, build = { token ->
            ExecutionFileWsRequest("", wsContext(executionId, projectId, languageId, metadata, token), normalizePath(path))
        })
        if (overWs != null) {
            return decodePayload<ExecutionFilePayload>(overWs.data).content.toByteArray(Charsets.UTF_8)
        }
        return callPluginGetFileHttp(pluginUrl, languageId, executionId, projectId, path, metadata)
    }

    private suspend fun callPluginGetFileHttp(
        pluginUrl: String,
        languageId: String,
        executionId: UUID,
        projectId: UUID,
        path: String,
        metadata: JsonObject?
    ): ByteArray {
        return withContext(Dispatchers.IO) {
            val token = jwtService.generatePluginExecutionToken(
                projectId,
                executionId,
                JwtService.SCOPE_PLUGIN_EXECUTION_READ
            )
            val normalizedPath = normalizePath(path)
            val uri = URI.create(pluginUrl).resolve("$languageId/executions/$executionId/files/$normalizedPath")

            val request = CompressedResponses.accept(HttpRequest.newBuilder())
                .uri(uri)
                .header("Authorization", "Bearer $token")
                .GET()
                .timeout(Duration.ofMinutes(1))
                .applyExecutionMetadataHeader(metadata)
                .build()

            val response = httpClient.send(request, CompressedResponses.ofByteArray())

            if (response.statusCode() != 200) {
                throw RuntimeException("Plugin returned status ${response.statusCode()}")
            }

            response.body()
        }
    }

    /**
     * Calls the plugin to cancel an execution.
     *
     * @param pluginUrl The base URL of the plugin
     * @param languageId The language identifier for routing the request
     * @param executionId The UUID of the execution
     * @param projectId The UUID of the project
     * @return Unit
     */
    private suspend fun callPluginCancel(
        pluginUrl: String,
        languageId: String,
        executionId: UUID,
        projectId: UUID,
        metadata: JsonObject?
    ) {
        val overWs = pluginWsRequest(
            pluginUrl, JwtService.SCOPE_PLUGIN_EXECUTION_CANCEL, executionId, projectId,
            build = { token ->
                ExecutionCancelWsRequest("", wsContext(executionId, projectId, languageId, metadata, token))
            }
        )
        if (overWs != null) {
            return
        }
        callPluginCancelHttp(pluginUrl, languageId, executionId, projectId, metadata)
    }

    private suspend fun callPluginCancelHttp(
        pluginUrl: String,
        languageId: String,
        executionId: UUID,
        projectId: UUID,
        metadata: JsonObject?
    ) {
        withContext(Dispatchers.IO) {
            val token = jwtService.generatePluginExecutionToken(
                projectId,
                executionId,
                JwtService.SCOPE_PLUGIN_EXECUTION_CANCEL
            )
            val uri = URI.create(pluginUrl).resolve("$languageId/executions/$executionId/cancel")

            val request = CompressedResponses.accept(HttpRequest.newBuilder())
                .uri(uri)
                .header("Authorization", "Bearer $token")
                .POST(HttpRequest.BodyPublishers.noBody())
                .timeout(Duration.ofMinutes(1))
                .applyExecutionMetadataHeader(metadata)
                .build()

            val response = httpClient.send(request, CompressedResponses.ofString())

            if (response.statusCode() != 200 && response.statusCode() != 204) {
                throw RuntimeException("Plugin returned status ${response.statusCode()}: ${response.body()}")
            }
        }
    }

    /**
     * Calls the plugin to delete an execution.
     *
     * @param pluginUrl The base URL of the plugin
     * @param languageId The language identifier for routing the request
     * @param executionId The UUID of the execution
     * @param projectId The UUID of the project
     * @return Unit
     */
    private suspend fun callPluginDelete(
        pluginUrl: String,
        languageId: String,
        executionId: UUID,
        projectId: UUID,
        metadata: JsonObject?
    ) {
        val overWs = try {
            pluginWsRequest(
                pluginUrl, JwtService.SCOPE_PLUGIN_EXECUTION_DELETE, executionId, projectId,
                build = { token ->
                    ExecutionDeleteWsRequest("", wsContext(executionId, projectId, languageId, metadata, token))
                }
            )
        } catch (e: ExecutionWsException) {
            if (e.code == ExecutionWsErrorCodes.NOT_FOUND) {
                logger.warn("Plugin reports execution $executionId as unknown when deleting; assuming already deleted")
                return
            }
            throw e
        }
        if (overWs != null) {
            return
        }
        callPluginDeleteHttp(pluginUrl, languageId, executionId, projectId, metadata)
    }

    private suspend fun callPluginDeleteHttp(
        pluginUrl: String,
        languageId: String,
        executionId: UUID,
        projectId: UUID,
        metadata: JsonObject?
    ) {
        withContext(Dispatchers.IO) {
            val token = jwtService.generatePluginExecutionToken(
                projectId,
                executionId,
                JwtService.SCOPE_PLUGIN_EXECUTION_DELETE
            )
            val uri = URI.create(pluginUrl).resolve("$languageId/executions/$executionId")

            val request = CompressedResponses.accept(HttpRequest.newBuilder())
                .uri(uri)
                .header("Authorization", "Bearer $token")
                .DELETE()
                .timeout(Duration.ofMinutes(1))
                .applyExecutionMetadataHeader(metadata)
                .build()

            val response = httpClient.send(request, CompressedResponses.ofString())

            if (response.statusCode() == 404) {
                logger.warn("Plugin returned 404 when deleting execution $executionId; assuming already deleted")
                return@withContext
            }

            if (response.statusCode() != 200 && response.statusCode() != 204) {
                throw RuntimeException("Plugin returned status ${response.statusCode()}: ${response.body()}")
            }
        }
    }

    /**
     * Performs a plugin request over the pooled WebSocket connection, or reports that the
     * plugin does not serve one.
     *
     * A plugin service that predates the WebSocket endpoint, or one that is momentarily
     * unreachable on it, must not make results unreadable — every caller falls back to the
     * HTTP route it used before. Errors the plugin itself raises are not fallback material
     * and propagate.
     *
     * @param pluginUrl Base URL of the plugin service
     * @param scope Scope the generated token must carry
     * @param executionId The execution being addressed
     * @param projectId The owning project
     * @param build Builds the request; the request id it is given is replaced by the client
     * @param onStream Invoked for each intermediate message of a streaming request
     * @return The response, or null if the plugin could not be reached over WebSocket
     */
    private suspend fun pluginWsRequest(
        pluginUrl: String,
        scope: String,
        executionId: UUID,
        projectId: UUID,
        build: (token: String) -> ExecutionWsMessage,
        onStream: (suspend (ExecutionWsMessage) -> Unit)? = null
    ): ExecutionWsResponse? {
        val token = jwtService.generatePluginExecutionToken(projectId, executionId, scope)
        return try {
            executionWsClient.request(
                pluginUrl,
                { requestId -> build(token).withRequestId(requestId) },
                onStream
            )
        } catch (e: ExecutionWsException) {
            if (e.code != ExecutionWsErrorCodes.UNAVAILABLE) {
                // The plugin answered, and said no. That is a real result, not a reason to ask
                // again over HTTP — the code travels with the exception so callers that treat
                // some failures as expected can still recognise them.
                logger.warn(
                    "Plugin WS request to {} for execution {} failed with {}: {}",
                    pluginUrl, executionId, e.code, e.message
                )
                throw e
            }
            logger.warn("Plugin at {} unreachable over WebSocket, falling back to HTTP: {}", pluginUrl, e.message)
            null
        }
    }

    /**
     * Builds the request context sent to a plugin service.
     */
    private fun wsContext(
        executionId: UUID,
        projectId: UUID,
        languageId: String,
        metadata: JsonObject?,
        token: String
    ): ExecutionWsContext = ExecutionWsContext(
        executionId = executionId.toString(),
        auth = token,
        projectId = projectId.toString(),
        languageId = languageId,
        metadata = metadata
    )

    private inline fun <reified T> decodePayload(data: JsonElement?): T {
        if (data == null) {
            throw RuntimeException("Plugin returned an empty payload")
        }
        return ExecutionWsProtocol.json.decodeFromJsonElement(data)
    }

    /**
     * Copies a request with the request id the connection assigned it.
     */
    private fun ExecutionWsMessage.withRequestId(requestId: String): ExecutionWsMessage = when (this) {
        is ExecutionSummaryWsRequest -> copy(requestId = requestId)
        is ExecutionFileTreeWsRequest -> copy(requestId = requestId)
        is ExecutionFileWsRequest -> copy(requestId = requestId)
        is ExecutionFilesWsRequest -> copy(requestId = requestId)
        is ExecutionCancelWsRequest -> copy(requestId = requestId)
        is ExecutionDeleteWsRequest -> copy(requestId = requestId)
        else -> this
    }

    /**
     * Adds execution metadata header to plugin requests when metadata is present.
     */
    private fun HttpRequest.Builder.applyExecutionMetadataHeader(metadata: JsonObject?): HttpRequest.Builder {
        if (metadata != null) {
            header("X-Execution-Metadata", metadata.toString())
        }
        return this
    }
}
