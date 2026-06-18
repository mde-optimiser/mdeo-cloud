package com.mdeo.modeltransformationexecution.service

import com.mdeo.common.model.ExecutionState
import com.mdeo.execution.common.routes.FileEntry
import com.mdeo.execution.common.service.ExecutionServiceWithFileTree
import com.mdeo.execution.common.subprocess.SubprocessPool
import com.mdeo.execution.common.subprocess.SubprocessResult
import com.mdeo.execution.common.subprocess.SubprocessRunner
import com.mdeo.metamodel.data.ModelData
import com.mdeo.modeltransformationexecution.database.TransformationExecutionsTable
import com.mdeo.modeltransformationexecution.database.TransformationResultFilesTable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.*
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid

/**
 * Service for executing model transformations.
 * Implements ExecutionServiceWithFileTree to support result file trees.
 *
 * Execution is asynchronous: [createAndStartExecution] returns as soon as the config is
 * parsed and the execution record is created.  The transformation itself runs in a
 * subprocess with a watchdog that monitors for timeouts and external cancellation.
 *
 * @param apiClient Client for backend API communication
 * @param timeoutMs Execution timeout in milliseconds
 * @param executionScope Scope in which background execution coroutines are launched
 * @param subprocessPool Pool of reusable subprocess JVMs; avoids JVM startup overhead for
 *        frequent executions. Each pooled process is reset between executions.
 */
class TransformationExecutionService(
    private val apiClient: TransformationApiClient,
    private val timeoutMs: Long,
    private val executionScope: CoroutineScope,
    private val subprocessPool: SubprocessPool = buildDefaultPool()
) : ExecutionServiceWithFileTree {
    private val logger = LoggerFactory.getLogger(TransformationExecutionService::class.java)
    private val json = Json { prettyPrint = true }

    companion object {
        private const val MAX_PATH_LENGTH = 1000
        private const val RESULT_FILE_NAME = "result.m_gen"
        private const val MIME_TYPE_JSON = "application/json"
        private const val CANCELLATION_CHECK_INTERVAL_MS = 5000L

        /**
         * Builds the default [SubprocessPool] using the [TransformationCommand.Reset] /
         * [TransformationResponse.ResetOk] JSON protocol.
         */
        fun buildDefaultPool(maxSize: Int = SubprocessPool.DEFAULT_POOL_SIZE): SubprocessPool =
            SubprocessPool(maxSize = maxSize) { runner ->
                val payload = Json.encodeToString(
                    TransformationCommand.serializer(), TransformationCommand.Reset
                ).toByteArray()
                val result = runner.sendCommand(payload)
                if (result !is SubprocessResult.Success) return@SubprocessPool false
                runCatching {
                    Json.decodeFromString(
                        TransformationResponse.serializer(), result.data.decodeToString()
                    ) is TransformationResponse.ResetOk
                }.getOrDefault(false)
            }
    }

    override suspend fun createAndStartExecution(
        executionId: UUID,
        projectId: UUID,
        filePath: String,
        data: JsonElement,
        jwtToken: String
    ): String = withContext(Dispatchers.IO) {
        val modelPath = extractModelPath(data)
        validateInputs(filePath, modelPath)
        
        createExecutionRecord(executionId, projectId, filePath, modelPath)

        logger.info("Created transformation execution $executionId for project $projectId")

        executionScope.launch(Dispatchers.Default) {
            try {
                executeTransformation(executionId, projectId, filePath, modelPath, jwtToken)
            } catch (e: Exception) {
                handleUnexpectedError(executionId, e, jwtToken)
            }
        }

        "$filePath -> $modelPath"
    }
    
    override suspend fun cancelExecution(executionId: UUID) = withContext(Dispatchers.IO) {
        transaction {
            TransformationExecutionsTable.update({
                TransformationExecutionsTable.id eq executionId.toKotlinUuid()
            }) {
                it[state] = ExecutionState.CANCELLED
                it[progress] = "Cancelled by user"
                it[completedAt] = Instant.now()
            }
        }
        logger.info("Cancelled execution $executionId")
    }
    
    override suspend fun deleteExecution(executionId: UUID) = withContext(Dispatchers.IO) {
        transaction {
            TransformationResultFilesTable.deleteWhere { 
                TransformationResultFilesTable.executionId eq executionId.toKotlinUuid() 
            }
            TransformationExecutionsTable.deleteWhere { id eq executionId.toKotlinUuid() }
        }
        logger.info("Deleted execution $executionId")
    }
    
    override suspend fun getSummary(executionId: UUID): String? = withContext(Dispatchers.IO) {
        val execution = findExecution(executionId) ?: return@withContext null
        buildExecutionSummary(execution)
    }
    
    override suspend fun getFileTree(executionId: UUID, path: String?): List<FileEntry>? {
        return withContext(Dispatchers.IO) {
            val execution = findExecution(executionId) ?: return@withContext null
            
            val files = transaction {
                TransformationResultFilesTable.selectAll()
                    .where { TransformationResultFilesTable.executionId eq executionId.toKotlinUuid() }
                    .map { row ->
                        val filePath = row[TransformationResultFilesTable.filePath]
                        FileEntry(filePath, FileEntry.TYPE_FILE)
                    }
            }
            
            filterFilesByPath(files, path)
        }
    }
    
    override suspend fun getFileContents(executionId: UUID, filePath: String): String? {
        return withContext(Dispatchers.IO) {
            transaction {
                TransformationResultFilesTable.selectAll()
                    .where {
                        (TransformationResultFilesTable.executionId eq executionId.toKotlinUuid()) and
                            (TransformationResultFilesTable.filePath eq filePath)
                    }
                    .firstOrNull()
                    ?.get(TransformationResultFilesTable.content)
            }
        }
    }
    
    /**
     * Extracts the model path from the execution request data.
     *
     * @param data The JSON request payload.
     * @return The model path string.
     */
    private fun extractModelPath(data: JsonElement): String {
        return data.jsonObject["modelPath"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("modelPath is required in data")
    }
    
    /**
     * Validates that the provided paths are non-blank, within length limits, and contain no
     * path-traversal sequences.
     *
     * @param transformationPath Path to the transformation file.
     * @param modelPath Path to the input model file.
     */
    private fun validateInputs(transformationPath: String, modelPath: String) {
        require(transformationPath.isNotBlank()) { "transformationPath cannot be empty" }
        require(modelPath.isNotBlank()) { "modelPath cannot be empty" }
        require(!transformationPath.contains("..")) { "Path traversal not allowed" }
        require(!modelPath.contains("..")) { "Path traversal not allowed" }
        require(transformationPath.length <= MAX_PATH_LENGTH) { "Path too long" }
        require(modelPath.length <= MAX_PATH_LENGTH) { "Path too long" }
    }
    
    /**
     * Inserts a new execution record in the database with state [ExecutionState.SUBMITTED].
     *
     * @param executionId Unique identifier for this execution.
     * @param projectId Project that owns the execution.
     * @param transformationPath Path to the transformation file.
     * @param modelPath Path to the input model file.
     */
    private fun createExecutionRecord(
        executionId: UUID,
        projectId: UUID,
        transformationPath: String,
        modelPath: String
    ) {
        val now = Instant.now()
        transaction {
            TransformationExecutionsTable.insert {
                it[id] = executionId.toKotlinUuid()
                it[TransformationExecutionsTable.projectId] = projectId.toKotlinUuid()
                it[TransformationExecutionsTable.transformationPath] = transformationPath
                it[TransformationExecutionsTable.modelPath] = modelPath
                it[state] = ExecutionState.SUBMITTED
                it[progress] = null
                it[createdAt] = now
                it[startedAt] = null
                it[completedAt] = null
            }
        }
    }
    
    /**
     * Fetches all required data, runs the transformation in a subprocess with watchdog
     * monitoring, and stores the result. Updates execution state at each phase.
     *
     * @param executionId The execution to drive.
     * @param projectId Project context for API calls.
     * @param transformationPath Path to the transformation file.
     * @param modelPath Path to the input model file.
     * @param jwtToken Bearer token for backend API authentication.
     */
    private suspend fun executeTransformation(
        executionId: UUID,
        projectId: UUID,
        transformationPath: String,
        modelPath: String,
        jwtToken: String
    ) {
        updateState(executionId, ExecutionState.INITIALIZING, "Fetching data...", jwtToken)

        val typedAst = fetchTypedAst(executionId, projectId, transformationPath, jwtToken)
            ?: return

        val modelData = fetchModelData(executionId, projectId, modelPath, jwtToken)
            ?: return

        val metamodelData = fetchMetamodelData(executionId, projectId, typedAst.metamodelPath, jwtToken)
            ?: return

        updateState(executionId, ExecutionState.RUNNING, "Executing transformation...", jwtToken)

        val subprocess = subprocessPool.acquire() ?: SubprocessRunner(
            mainClass = TransformationSubprocessMain::class.java.name,
            cancellationCheck = { id -> isExecutionCancelled(UUID.fromString(id)) },
            cancellationCheckIntervalMs = CANCELLATION_CHECK_INTERVAL_MS
        ).also { runner ->
            if (!runner.start()) {
                val msg = "Failed to start subprocess"
                storeError(executionId, msg)
                updateState(executionId, ExecutionState.FAILED, msg, jwtToken)
                return
            }
        }
        subprocess.executionId = executionId.toString()

        val payload = TransformationSubprocessMain.serializeInput(typedAst, metamodelData, modelData, timeoutMs)
        val result = subprocess.sendCommand(payload)

        when (result) {
            is SubprocessResult.Success -> {
                val response = runCatching {
                    Json.decodeFromString(TransformationResponse.serializer(), result.data.decodeToString())
                }.getOrNull()
                val executeOk = response as? TransformationResponse.ExecuteOk
                if (executeOk != null) {
                    storeResult(executionId, executeOk.modelData)
                    updateState(executionId, ExecutionState.COMPLETED, "Completed successfully", jwtToken)
                    logger.info("Execution $executionId completed successfully")
                    subprocessPool.resetAndRelease(subprocess)
                } else {
                    logger.error("Unexpected subprocess response format for execution $executionId")
                    subprocess.destroy()
                    val msg = "Internal error: unexpected subprocess response"
                    storeError(executionId, msg)
                    updateState(executionId, ExecutionState.FAILED, msg, jwtToken)
                }
            }
            is SubprocessResult.Timeout -> {
                subprocess.destroy()
                handleTimeout(executionId, jwtToken)
            }
            is SubprocessResult.Cancelled -> {
                logger.info("Execution $executionId cancelled")
                subprocess.destroy()
            }
            is SubprocessResult.Failed -> {
                subprocess.destroy()
                val msg = "Transformation failed: ${result.message}"
                storeError(executionId, msg)
                updateState(executionId, ExecutionState.FAILED, msg, jwtToken)
            }
        }
    }
    
    /**
     * Fetches the typed AST for the given transformation file from the backend API.
     * Sets execution state to FAILED if the fetch returns null.
     *
     * @param executionId Execution to update on failure.
     * @param projectId Project context.
     * @param transformationPath Path to the transformation file.
     * @param jwtToken Authentication token.
     * @return The typed AST, or `null` if unavailable.
     */
    private suspend fun fetchTypedAst(
        executionId: UUID,
        projectId: UUID,
        transformationPath: String,
        jwtToken: String
    ): com.mdeo.modeltransformation.ast.TypedAst? {
        val typedAst = apiClient.getTransformationTypedAst(
            projectId.toString(),
            transformationPath,
            jwtToken
        )
        
        if (typedAst == null) {
            val msg = "Failed to fetch transformation typed AST"
            storeError(executionId, msg)
            updateState(executionId, ExecutionState.FAILED, msg, jwtToken)
        }
        return typedAst
    }

    /**
     * Fetches the metamodel data for the given path from the backend API.
     * Sets execution state to FAILED if the fetch returns null.
     *
     * @param executionId Execution to update on failure.
     * @param projectId Project context.
     * @param metamodelPath Path to the metamodel file.
     * @param jwtToken Authentication token.
     * @return The metamodel data, or `null` if unavailable.
     */
    private suspend fun fetchMetamodelData(
        executionId: UUID,
        projectId: UUID,
        metamodelPath: String,
        jwtToken: String
    ): com.mdeo.metamodel.data.MetamodelData? {
        val metamodelData = apiClient.getMetamodelData(
            projectId.toString(),
            metamodelPath,
            jwtToken
        )
        
        if (metamodelData == null) {
            val msg = "Failed to fetch metamodel data for $metamodelPath"
            storeError(executionId, msg)
            updateState(executionId, ExecutionState.FAILED, msg, jwtToken)
        }
        return metamodelData
    }
    
    /**
     * Fetches the model data for the given path from the backend API.
     * Sets execution state to FAILED if the fetch returns null.
     *
     * @param executionId Execution to update on failure.
     * @param projectId Project context.
     * @param modelPath Path to the model file.
     * @param jwtToken Authentication token.
     * @return The model data, or `null` if unavailable.
     */
    private suspend fun fetchModelData(
        executionId: UUID,
        projectId: UUID,
        modelPath: String,
        jwtToken: String
    ): ModelData? {
        val modelData = apiClient.getModelData(projectId.toString(), modelPath, jwtToken)
        
        if (modelData == null) {
            val msg = "Failed to fetch model data"
            storeError(executionId, msg)
            updateState(executionId, ExecutionState.FAILED, msg, jwtToken)
        }
        return modelData
    }
    
    /**
     * Serialises [resultModel] to JSON and inserts it as the result file in the database.
     *
     * @param executionId The execution whose result is being stored.
     * @param resultModel The transformed model to persist.
     */
    private fun storeResult(executionId: UUID, resultModel: ModelData) {
        val jsonContent = json.encodeToString(resultModel)
        
        transaction {
            TransformationResultFilesTable.insert {
                it[id] = Uuid.random()
                it[TransformationResultFilesTable.executionId] = executionId.toKotlinUuid()
                it[filePath] = RESULT_FILE_NAME
                it[content] = jsonContent
                it[mimeType] = MIME_TYPE_JSON
            }
        }
    }
    
    /**
     * Updates the execution state in the database and calls the backend progress API.
     * Also sets [startedAt] or [completedAt] timestamps as appropriate.
     *
     * @param executionId The execution to update.
     * @param state New [ExecutionState] string.
     * @param progressText Human-readable progress message, or `null` to clear.
     * @param jwtToken Authentication token for the backend API call.
     */
    private suspend fun updateState(
        executionId: UUID,
        state: String,
        progressText: String?,
        jwtToken: String
    ) {
        val now = Instant.now()
        
        transaction {
            TransformationExecutionsTable.update({
                TransformationExecutionsTable.id eq executionId.toKotlinUuid()
            }) {
                it[TransformationExecutionsTable.state] = state
                it[progress] = progressText
                
                when (state) {
                    ExecutionState.INITIALIZING, ExecutionState.RUNNING -> {
                        it[startedAt] = now
                    }
                    ExecutionState.COMPLETED, ExecutionState.FAILED, ExecutionState.CANCELLED -> {
                        it[completedAt] = now
                    }
                }
            }
        }
        
        apiClient.updateExecutionState(executionId.toString(), state, progressText, jwtToken)
    }
    
    /**
     * Sets the execution state to FAILED with a timeout message.
     *
     * @param executionId The timed-out execution.
     * @param jwtToken Authentication token for the state update API call.
     */
    private suspend fun handleTimeout(executionId: UUID, jwtToken: String) {
        logger.error("Execution timeout after ${timeoutMs}ms")
        val msg = "Execution timeout: Transformation exceeded maximum time of ${timeoutMs}ms"
        storeError(executionId, msg)
        updateState(executionId, ExecutionState.FAILED, msg, jwtToken)
    }
    
    /**
     * Persists the exception message and sets the execution state to FAILED.
     *
     * @param executionId The failed execution.
     * @param e The unexpected exception.
     * @param jwtToken Authentication token for the state update API call.
     */
    private suspend fun handleUnexpectedError(executionId: UUID, e: Exception, jwtToken: String) {
        logger.error("Unexpected error during execution", e)
        
        transaction {
            TransformationExecutionsTable.update({
                TransformationExecutionsTable.id eq executionId.toKotlinUuid()
            }) {
                it[error] = e.message
            }
        }
        
        updateState(
            executionId,
            ExecutionState.FAILED,
            "Unexpected error: ${e.message}",
            jwtToken
        )
    }
    
    /**
     * Checks whether the execution has been cancelled in the database.
     *
     * @param executionId The execution to check.
     * @return `true` if cancelled or deleted.
     */
    private fun isExecutionCancelled(executionId: UUID): Boolean {
        val state = transaction {
            TransformationExecutionsTable.selectAll()
                .where { TransformationExecutionsTable.id eq executionId.toKotlinUuid() }
                .firstOrNull()
                ?.get(TransformationExecutionsTable.state)
        }
        return state == null || state == ExecutionState.CANCELLED || state == ExecutionState.FAILED
    }

    private fun storeError(executionId: UUID, message: String?) {
        transaction {
            TransformationExecutionsTable.update({
                TransformationExecutionsTable.id eq executionId.toKotlinUuid()
            }) {
                it[error] = message
            }
        }
    }

    /**
     * Looks up an execution record by its ID.
     *
     * @param executionId The execution to look up.
     * @return The database row, or `null` if not found.
     */
    private fun findExecution(executionId: UUID) = transaction {
        TransformationExecutionsTable.selectAll()
            .where { TransformationExecutionsTable.id eq executionId.toKotlinUuid() }
            .firstOrNull()
    }
    
    /**
     * Dispatches to the appropriate summary builder based on execution state.
     *
     * @param execution The database row for the execution.
     * @return Markdown-formatted summary string.
     */
    private fun buildExecutionSummary(execution: org.jetbrains.exposed.v1.core.ResultRow): String {
        val state = execution[TransformationExecutionsTable.state]
        val error = execution[TransformationExecutionsTable.error]
        val logs = execution[TransformationExecutionsTable.logMessages]
        val modelPath = execution[TransformationExecutionsTable.modelPath]
        
        return when (state) {
            ExecutionState.COMPLETED -> buildCompletedSummary(modelPath, logs)
            ExecutionState.FAILED -> buildFailedSummary(error, logs)
            else -> "Execution is in state: $state"
        }
    }
    
    /**
     * Builds a summary for a completed transformation execution.
     * Includes embedded graphical editor views for the source model (absolute path)
     * and the result model (relative path to execution result file).
     *
     * @param modelPath The path to the original model file
     * @param logs Optional log messages from the execution
     * @return Markdown summary string
     */
    private fun buildCompletedSummary(modelPath: String, logs: String?): String {
        return buildString {
            append("## Transformation Completed\n\n")
            append("The model transformation executed successfully.\n\n")
            append("### Result Model\n\n")
            append("![Result Model](${RESULT_FILE_NAME})\n")
            append("### Source Model\n\n")
            append("Caution: The source model may have been modifed since the transformation was run.\n\n")
            append("![Source Model](${modelPath})\n\n")
            
            if (!logs.isNullOrBlank()) {
                append("\n### Log Messages\n\n```\n")
                append(logs)
                append("\n```")
            }
        }
    }
    
    /**
     * Builds a summary for a failed transformation execution.
     *
     * @param error Optional error message from the execution.
     * @param logs Optional log messages captured during execution.
     * @return Markdown summary string.
     */
    private fun buildFailedSummary(error: String?, logs: String?): String {
        return buildString {
            append("## Transformation Failed\n\n")
            append(error ?: "Unknown error")
            
            if (!logs.isNullOrBlank()) {
                append("\n\n### Log Messages\n\n```\n")
                append(logs)
                append("\n```")
            }
        }
    }
    
    /**
     * Filters a list of file entries to those whose name starts with [path].
     * Returns all entries when [path] is null or blank.
     *
     * @param files The full list of result file entries.
     * @param path Optional path prefix filter.
     * @return Filtered list of file entries.
     */
    private fun filterFilesByPath(files: List<FileEntry>, path: String?): List<FileEntry> {
        if (path.isNullOrBlank()) return files
        return files.filter { it.name.startsWith(path) }
    }
}
