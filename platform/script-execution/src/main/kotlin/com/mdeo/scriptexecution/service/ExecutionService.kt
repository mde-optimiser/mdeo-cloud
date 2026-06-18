package com.mdeo.scriptexecution.service

import com.mdeo.execution.common.service.ExecutionService as CommonExecutionService
import com.mdeo.execution.common.subprocess.SubprocessPool
import com.mdeo.execution.common.subprocess.SubprocessResult
import com.mdeo.execution.common.subprocess.SubprocessRunner
import com.mdeo.metamodel.data.MetamodelData
import com.mdeo.metamodel.data.ModelData
import com.mdeo.script.ast.TypedAst
import com.mdeo.common.model.ExecutionState
import com.mdeo.scriptexecution.database.ExecutionsTable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.*
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid

/**
 * Parsed execution request data.
 *
 * @param methodName The method name to execute.
 * @param modelPath Optional path to the model file.
 */
private data class ExecutionRequestData(
    val methodName: String,
    val modelPath: String?
)

/**
 * Resolved typed ASTs and the metamodel path extracted from the main file.
 *
 * @param typedAsts All dependency-resolved typed ASTs keyed by file path.
 * @param metamodelPath Absolute metamodel path from the main file's typed AST, or null.
 */
private data class ResolvedAsts(
    val typedAsts: Map<String, TypedAst>,
    val metamodelPath: String?
)

/**
 * Fetched metamodel and model data for a single execution.
 * The metamodel's absolute path is embedded in [metamodelData.path][MetamodelData.path].
 */
private data class ModelContext(
    val metamodelData: MetamodelData,
    val modelData: ModelData
)

/**
 * Merged service for managing and executing scripts.
 * Handles database operations and script execution logic.
 *
 * @param backendApiService Service for backend API communication
 * @param timeoutMs Execution timeout in milliseconds
 * @param subprocessPool Pool of reusable subprocess JVMs; avoids JVM startup overhead for
 *        frequent executions. Each pooled process is reset between executions.
 */
class ExecutionService(
    private val backendApiService: BackendApiService,
    private val timeoutMs: Long,
    private val executionScope: CoroutineScope,
    private val subprocessPool: SubprocessPool = buildDefaultPool()
) : CommonExecutionService {
    private val logger = LoggerFactory.getLogger(ExecutionService::class.java)
    private val dependencyResolver = TypedAstDependencyResolver(backendApiService)

    companion object {
        private const val MAX_PATH_LENGTH = 1000
        private const val MAX_METHOD_NAME_LENGTH = 200
        private const val CANCELLATION_CHECK_INTERVAL_MS = 5000L

        /**
         * Builds the default [SubprocessPool] using the [ScriptCommand.Reset] /
         * [ScriptResponse.ResetOk] JSON protocol.
         */
        fun buildDefaultPool(maxSize: Int = SubprocessPool.DEFAULT_POOL_SIZE): SubprocessPool =
            SubprocessPool(maxSize = maxSize) { runner ->
                val payload = Json.encodeToString(ScriptCommand.serializer(), ScriptCommand.Reset).toByteArray()
                val result = runner.sendCommand(payload)
                if (result !is SubprocessResult.Success) return@SubprocessPool false
                runCatching {
                    Json.decodeFromString(ScriptResponse.serializer(), result.data.decodeToString()) is ScriptResponse.ResetOk
                }.getOrDefault(false)
            }
    }

    /**
     * Creates a new execution and starts it asynchronously.
     *
     * @param executionId UUID of the execution
     * @param projectId UUID of the project
     * @param filePath Path to the file
     * @param data JSON data containing execution parameters. Can be:
     *             - A bare string: method name only (backwards compatible)
     *             - A JSON object: { "methodName": "...", "modelPath": "..." (optional) }
     * @param jwtToken JWT token to pass through to backend
     * @return Display name for the execution
     */
    override suspend fun createAndStartExecution(
        executionId: UUID,
        projectId: UUID,
        filePath: String,
        data: JsonElement,
        jwtToken: String
    ): String = withContext(Dispatchers.IO) {
        val requestData = parseExecutionData(data)
        validateInputs(filePath, requestData.methodName)

        val now = Instant.now()
        transaction {
            ExecutionsTable.insert {
                it[id] = executionId.toKotlinUuid()
                it[ExecutionsTable.projectId] = projectId.toKotlinUuid()
                it[ExecutionsTable.filePath] = filePath
                it[state] = ExecutionState.SUBMITTED
                it[progress] = null
                it[createdAt] = now
                it[startedAt] = null
                it[completedAt] = null
                it[ExecutionsTable.data] = data.toString()
            }
        }

        logger.info("Created execution $executionId for project $projectId")

        executionScope.launch(Dispatchers.Default) {
            try {
                executeScript(executionId, projectId, filePath, requestData, jwtToken)
            } catch (e: IllegalArgumentException) {
                logger.error("Input validation failed", e)
                storeError(executionId, "Invalid input: ${e.message}")
                updateExecutionState(
                    executionId,
                    ExecutionState.FAILED,
                    "Invalid input: ${e.message}",
                    jwtToken
                )
            } catch (e: Exception) {
                logger.error("Unexpected error during execution", e)
                storeError(executionId, e.message)
                updateExecutionState(
                    executionId,
                    ExecutionState.FAILED,
                    "Unexpected error: ${e.message}",
                    jwtToken
                )
            }
        }

        "$filePath:${requestData.methodName}"
    }

    /**
     * Parses execution request data from JSON.
     *
     * @param data The JSON element to parse.
     * @return ExecutionRequestData containing method name and optional model path.
     */
    private fun parseExecutionData(data: JsonElement): ExecutionRequestData {
        return when (data) {
            is JsonObject -> {
                val methodName = data["methodName"]?.jsonPrimitive?.content
                    ?: throw IllegalArgumentException("Missing 'methodName' in execution data")
                val modelPath = data["modelPath"]?.jsonPrimitive?.content
                ExecutionRequestData(
                    methodName = methodName,
                    modelPath = modelPath
                )
            }
            else -> throw IllegalArgumentException("Invalid execution data format: expected string or object")
        }
    }

    /**
     * Validates input parameters for security and sanity.
     *
     * @param filePath Path to the file
     * @param methodName Name of the method
     * @throws IllegalArgumentException if validation fails
     */
    private fun validateInputs(filePath: String, methodName: String) {
        require(filePath.isNotBlank()) { "filePath cannot be empty" }
        require(methodName.isNotBlank()) { "methodName cannot be empty" }
        require(!filePath.contains("..")) { "filePath cannot contain '..' (path traversal attempt)" }
        require(filePath.length <= MAX_PATH_LENGTH) {
            "filePath too long (max $MAX_PATH_LENGTH characters)"
        }
        require(methodName.length <= MAX_METHOD_NAME_LENGTH) {
            "methodName too long (max $MAX_METHOD_NAME_LENGTH characters)"
        }
        require(methodName.matches(Regex("[a-zA-Z_][a-zA-Z0-9_]*"))) {
            "methodName must be a valid identifier (alphanumeric and underscore only)"
        }
    }

    /**
     * Executes a script method. Fetches data in the parent process, then runs compilation
     * and execution in a subprocess with watchdog monitoring.
     *
     * @param executionId UUID of the execution
     * @param projectId UUID of the project
     * @param filePath Path to the file
     * @param requestData Execution request containing method name and optional model path
     * @param jwtToken JWT token to pass through to backend
     */
    private suspend fun executeScript(
        executionId: UUID,
        projectId: UUID,
        filePath: String,
        requestData: ExecutionRequestData,
        jwtToken: String
    ) {
        val resolvedAsts = resolveAndValidateAsts(
            executionId, projectId, filePath, requestData.methodName, jwtToken
        ) ?: return

        val modelContext = if (resolvedAsts.metamodelPath != null && requestData.modelPath != null) {
            fetchModelContext(
                executionId, projectId,
                resolvedAsts.metamodelPath, requestData.modelPath,
                jwtToken
            ) ?: return
        } else {
            null
        }

        updateExecutionState(executionId, ExecutionState.RUNNING, "Executing...", jwtToken)

        val subprocess = subprocessPool.acquire() ?: SubprocessRunner(
            mainClass = ScriptSubprocessMain::class.java.name,
            cancellationCheck = { id -> isExecutionCancelled(UUID.fromString(id)) },
            cancellationCheckIntervalMs = CANCELLATION_CHECK_INTERVAL_MS
        ).also { runner ->
            if (!runner.start()) {
                val msg = "Failed to start subprocess"
                storeError(executionId, msg)
                updateExecutionState(executionId, ExecutionState.FAILED, msg, jwtToken)
                return
            }
        }
        subprocess.executionId = executionId.toString()

        val payload = ScriptSubprocessMain.serializeInput(
            resolvedAsts.typedAsts,
            modelContext?.metamodelData,
            modelContext?.modelData,
            filePath,
            requestData.methodName,
            timeoutMs
        )
        val result = subprocess.sendCommand(payload)

        when (result) {
            is SubprocessResult.Success -> {
                val response = runCatching {
                    Json.decodeFromString(ScriptResponse.serializer(), result.data.decodeToString())
                }.getOrNull()
                when (val resp = response) {
                    is ScriptResponse.ExecuteOk -> {
                        transaction {
                            ExecutionsTable.update({ ExecutionsTable.id eq executionId.toKotlinUuid() }) {
                                it[ExecutionsTable.result] = resp.result ?: "null"
                                it[ExecutionsTable.output] = resp.output
                            }
                        }
                        updateExecutionState(executionId, ExecutionState.COMPLETED, "Completed successfully", jwtToken)
                        logger.info("Execution $executionId completed successfully")
                        subprocessPool.resetAndRelease(subprocess)
                    }
                    is ScriptResponse.ExecuteError -> {
                        val errorText = buildString {
                            append(resp.message)
                            if (!resp.stackTrace.isNullOrBlank()) {
                                append("\n\n")
                                append(resp.stackTrace)
                            }
                        }
                        transaction {
                            ExecutionsTable.update({ ExecutionsTable.id eq executionId.toKotlinUuid() }) {
                                it[ExecutionsTable.error] = errorText
                                it[ExecutionsTable.output] = resp.output
                            }
                        }
                        subprocess.destroy()
                        logger.error("Execution $executionId failed with runtime error: ${resp.message}")
                        updateExecutionState(
                            executionId, ExecutionState.FAILED, "Runtime error: ${resp.message}", jwtToken
                        )
                    }
                    else -> {
                        logger.error("Unexpected subprocess response format for execution $executionId")
                        subprocess.destroy()
                        val msg = "Internal error: unexpected subprocess response"
                        storeError(executionId, msg)
                        updateExecutionState(executionId, ExecutionState.FAILED, msg, jwtToken)
                    }
                }
            }
            is SubprocessResult.Timeout -> {
                logger.error("Execution timeout after ${timeoutMs}ms")
                subprocess.destroy()
                val msg = "Execution timeout: Script exceeded maximum execution time of ${timeoutMs}ms"
                storeError(executionId, msg)
                updateExecutionState(executionId, ExecutionState.FAILED, msg, jwtToken)
            }
            is SubprocessResult.Cancelled -> {
                logger.info("Execution $executionId cancelled")
                subprocess.destroy()
            }
            is SubprocessResult.Failed -> {
                transaction {
                    ExecutionsTable.update({ ExecutionsTable.id eq executionId.toKotlinUuid() }) {
                        it[ExecutionsTable.error] = result.message
                    }
                }
                subprocess.destroy()
                updateExecutionState(executionId, ExecutionState.FAILED, "Runtime error: ${result.message}", jwtToken)
            }
        }
    }

    /**
     * Resolves all typed ASTs for [filePath] and its dependencies, then checks that the
     * target [methodName] exists and has no parameters.
     *
     * Updates execution state to [ExecutionState.FAILED] and returns `null` on any error.
     *
     * @return [ResolvedAsts] on success, `null` on failure.
     */
    private suspend fun resolveAndValidateAsts(
        executionId: UUID,
        projectId: UUID,
        filePath: String,
        methodName: String,
        jwtToken: String
    ): ResolvedAsts? {
        updateExecutionState(executionId, ExecutionState.INITIALIZING, "Fetching AST and dependencies...", jwtToken)

        val typedAsts = dependencyResolver.resolveWithDependencies(
            projectId.toString(), filePath, jwtToken
        )
        if (typedAsts == null) {
            val msg = "Failed to fetch typed AST or its dependencies from backend"
            storeError(executionId, msg)
            updateExecutionState(executionId, ExecutionState.FAILED, msg, jwtToken)
            return null
        }

        val mainTypedAst = typedAsts[filePath]
        if (mainTypedAst == null) {
            val msg = "Main file typed AST not found in resolved dependencies"
            storeError(executionId, msg)
            updateExecutionState(executionId, ExecutionState.FAILED, msg, jwtToken)
            return null
        }

        val targetFunction = mainTypedAst.functions.find { it.name == methodName }
        if (targetFunction == null) {
            val msg = "Method '$methodName' not found in file"
            storeError(executionId, msg)
            updateExecutionState(executionId, ExecutionState.FAILED, msg, jwtToken)
            return null
        }

        if (targetFunction.parameters.isNotEmpty()) {
            val msg = "Method '$methodName' has parameters. Only methods with no parameters are supported."
            storeError(executionId, msg)
            updateExecutionState(executionId, ExecutionState.FAILED, msg, jwtToken)
            return null
        }

        return ResolvedAsts(typedAsts, mainTypedAst.metamodelPath)
    }

    /**
     * Fetches the metamodel and model data needed for execution.
     * The returned [ModelContext.metamodelData] carries its absolute path in
     * [MetamodelData.path].
     *
     * Updates execution state to [ExecutionState.FAILED] and returns `null` on any error.
     *
     * @return [ModelContext] on success, `null` on failure.
     */
    private suspend fun fetchModelContext(
        executionId: UUID,
        projectId: UUID,
        metamodelPath: String,
        modelPath: String,
        jwtToken: String
    ): ModelContext? {
        updateExecutionState(
            executionId, ExecutionState.INITIALIZING, "Fetching metamodel and model data...", jwtToken
        )

        val metamodelData = backendApiService.getMetamodelData(projectId.toString(), metamodelPath, jwtToken)
        if (metamodelData == null) {
            val msg = "Failed to fetch metamodel data from '$metamodelPath'"
            storeError(executionId, msg)
            updateExecutionState(executionId, ExecutionState.FAILED, msg, jwtToken)
            return null
        }

        val modelData = backendApiService.getModelData(projectId.toString(), modelPath, jwtToken)
        if (modelData == null) {
            val msg = "Failed to fetch model data from '$modelPath'"
            storeError(executionId, msg)
            updateExecutionState(executionId, ExecutionState.FAILED, msg, jwtToken)
            return null
        }

        return ModelContext(metamodelData, modelData)
    }

    /**
     * Checks whether the execution has been cancelled in the database.
     *
     * @param executionId The execution to check.
     * @return `true` if cancelled or deleted.
     */
    private fun isExecutionCancelled(executionId: UUID): Boolean {
        val state = transaction {
            ExecutionsTable.selectAll()
                .where { ExecutionsTable.id eq executionId.toKotlinUuid() }
                .firstOrNull()
                ?.get(ExecutionsTable.state)
        }
        return state == null || state == ExecutionState.CANCELLED || state == ExecutionState.FAILED
    }

    /**
     * Updates the state of an execution.
     *
     * @param executionId UUID of the execution
     * @param state New state
     * @param progressText Optional progress text
     */
    private suspend fun updateExecutionState(
        executionId: UUID,
        state: String,
        progressText: String?,
        jwtToken: String?
    ) = withContext(Dispatchers.IO) {
        val now = Instant.now()

        val currentStartedAt = transaction {
            ExecutionsTable.selectAll()
                .where { ExecutionsTable.id eq executionId.toKotlinUuid() }
                .firstOrNull()
                ?.get(ExecutionsTable.startedAt)
        }

        transaction {
            ExecutionsTable.update({ ExecutionsTable.id eq executionId.toKotlinUuid() }) {
                it[ExecutionsTable.state] = state
                it[progress] = progressText

                when (state) {
                    ExecutionState.INITIALIZING, ExecutionState.RUNNING -> {
                        if (currentStartedAt == null) {
                            it[startedAt] = now
                        }
                    }

                    ExecutionState.COMPLETED, ExecutionState.FAILED, ExecutionState.CANCELLED -> {
                        it[completedAt] = now
                    }
                }
            }
        }

        logger.info("Updated execution $executionId to state $state")
        if (jwtToken != null) {
            try {
                val ok = backendApiService.updateExecutionState(executionId.toString(), state, progressText, jwtToken)
                if (!ok) {
                    logger.warn("Backend update for execution state failed for $executionId")
                }
            } catch (e: Exception) {
                logger.error("Error while updating execution state on backend for $executionId", e)
            }
        }
    }

    private fun storeError(executionId: UUID, message: String?) {
        transaction {
            ExecutionsTable.update({ ExecutionsTable.id eq executionId.toKotlinUuid() }) {
                it[error] = message
            }
        }
    }

    /**
     * Cancels an execution.
     *
     * @param executionId UUID of the execution
     */
    override suspend fun cancelExecution(executionId: UUID) = withContext(Dispatchers.IO) {
        transaction {
            ExecutionsTable.update({ ExecutionsTable.id eq executionId.toKotlinUuid() }) {
                it[state] = ExecutionState.CANCELLED
                it[progress] = "Cancelled by user"
                it[completedAt] = Instant.now()
            }
        }
        logger.info("Cancelled execution $executionId")
    }

    /**
     * Deletes an execution.
     *
     * @param executionId UUID of the execution
     */
    override suspend fun deleteExecution(executionId: UUID) = withContext(Dispatchers.IO) {
        transaction {
            ExecutionsTable.deleteWhere { id eq executionId.toKotlinUuid() }
        }

        logger.info("Deleted execution $executionId")
    }

    /**
     * Generates a markdown summary for an execution.
     *
     * @param executionId UUID of the execution
     * @return Markdown-formatted summary, or null if execution not found
     */
    override suspend fun getSummary(executionId: UUID): String? = withContext(Dispatchers.IO) {
        val execution = transaction {
            ExecutionsTable.selectAll()
                .where { ExecutionsTable.id eq executionId.toKotlinUuid() }
                .firstOrNull()
        } ?: return@withContext null

        val state = execution[ExecutionsTable.state]
        val result = execution[ExecutionsTable.result]
        val output = execution[ExecutionsTable.output]
        val error = execution[ExecutionsTable.error]

        when (state) {
            ExecutionState.COMPLETED -> {
                buildString {
                    append("## Result\n\n")
                    append(result ?: "null")
                    append("\n\n## Output\n\n")
                    if (output.isNullOrBlank()) {
                        append("*no output produced*")
                    } else {
                        append("```\n")
                        append(output)
                        append("\n```")
                    }
                }
            }

            ExecutionState.FAILED -> {
                val errorText = error ?: "Unknown error"

                if (errorText.contains("Compilation", ignoreCase = true)) {
                    buildString {
                        append("## Compilation Error\n\n```\n")
                        append(errorText)
                        append("\n```")
                    }
                } else {
                    buildString {
                        append("## Runtime Error\n\n```\n")
                        append(errorText)
                        append("\n```\n\n## Output\n\n")
                        if (output.isNullOrBlank()) {
                            append("*no output produced*")
                        } else {
                            append("```\n")
                            append(output)
                            append("\n```")
                        }
                    }
                }
            }

            else -> {
                "Execution is in state: $state"
            }
        }
    }
}
