package com.mdeo.backend.service

import com.mdeo.common.model.ExecutionState
import com.mdeo.backend.database.ExecutionsTable
import com.mdeo.backend.database.FilesTable
import com.mdeo.common.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
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
            val token =
                jwtService.generateExecutionToken(projectId, executionId, listOf(JwtService.SCOPE_EXECUTION_WRITE))
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
            val request = HttpRequest.newBuilder()
                .uri(uri)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer $token")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .timeout(Duration.ofMinutes(1))
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

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
        return withContext(Dispatchers.IO) {
            val token = jwtService.generateExecutionToken(
                projectId,
                executionId,
                listOf(JwtService.SCOPE_PLUGIN_EXECUTION_READ)
            )
            val uri = URI.create(pluginUrl).resolve("$languageId/executions/$executionId/files")

            val request = HttpRequest.newBuilder()
                .uri(uri)
                .header("Authorization", "Bearer $token")
                .GET()
                .timeout(Duration.ofMinutes(1))
                .applyExecutionMetadataHeader(metadata)
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

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
        return withContext(Dispatchers.IO) {
            val token = jwtService.generateExecutionToken(
                projectId,
                executionId,
                listOf(JwtService.SCOPE_PLUGIN_EXECUTION_READ)
            )
            val uri = URI.create(pluginUrl).resolve("$languageId/executions/$executionId/summary")

            val request = HttpRequest.newBuilder()
                .uri(uri)
                .header("Authorization", "Bearer $token")
                .GET()
                .timeout(Duration.ofMinutes(1))
                .applyExecutionMetadataHeader(metadata)
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

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
        return withContext(Dispatchers.IO) {
            val token = jwtService.generateExecutionToken(
                projectId,
                executionId,
                listOf(JwtService.SCOPE_PLUGIN_EXECUTION_READ)
            )
            val normalizedPath = normalizePath(path)
            val uri = URI.create(pluginUrl).resolve("$languageId/executions/$executionId/files/$normalizedPath")

            val request = HttpRequest.newBuilder()
                .uri(uri)
                .header("Authorization", "Bearer $token")
                .GET()
                .timeout(Duration.ofMinutes(1))
                .applyExecutionMetadataHeader(metadata)
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())

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
        withContext(Dispatchers.IO) {
            val token = jwtService.generateExecutionToken(
                projectId,
                executionId,
                listOf(JwtService.SCOPE_PLUGIN_EXECUTION_CANCEL)
            )
            val uri = URI.create(pluginUrl).resolve("$languageId/executions/$executionId/cancel")

            val request = HttpRequest.newBuilder()
                .uri(uri)
                .header("Authorization", "Bearer $token")
                .POST(HttpRequest.BodyPublishers.noBody())
                .timeout(Duration.ofMinutes(1))
                .applyExecutionMetadataHeader(metadata)
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

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
        withContext(Dispatchers.IO) {
            val token = jwtService.generateExecutionToken(
                projectId,
                executionId,
                listOf(JwtService.SCOPE_PLUGIN_EXECUTION_DELETE)
            )
            val uri = URI.create(pluginUrl).resolve("$languageId/executions/$executionId")

            val request = HttpRequest.newBuilder()
                .uri(uri)
                .header("Authorization", "Bearer $token")
                .DELETE()
                .timeout(Duration.ofMinutes(1))
                .applyExecutionMetadataHeader(metadata)
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

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
     * Adds execution metadata header to plugin requests when metadata is present.
     */
    private fun HttpRequest.Builder.applyExecutionMetadataHeader(metadata: JsonObject?): HttpRequest.Builder {
        if (metadata != null) {
            header("X-Execution-Metadata", metadata.toString())
        }
        return this
    }
}
