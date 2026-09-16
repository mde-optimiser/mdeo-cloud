package com.mdeo.common.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Represents an execution in the system.
 *
 * @property id Unique identifier for the execution
 * @property projectId ID of the project this execution belongs to
 * @property filePath Path to the source file at the time of execution
 * @property languageId ID of the language plugin handling this execution
 * @property name Display name of the execution
 * @property state Current state of the execution
 * @property progressText Optional progress indication text
 * @property metadata Optional execution-scoped JSON metadata
 * @property createdAt ISO 8601 timestamp when the execution was created/submitted
 * @property startedAt ISO 8601 timestamp when the execution started running, or null if not yet started
 * @property finishedAt ISO 8601 timestamp when the execution finished, or null if not yet finished
 */
@Serializable
data class Execution(
    val id: String,
    val projectId: String,
    val filePath: String,
    val languageId: String,
    val name: String,
    val state: String,
    val progressText: String?,
    val metadata: JsonObject? = null,
    val createdAt: String,
    val startedAt: String? = null,
    val finishedAt: String? = null
)

/**
 * Represents an execution with its result file tree.
 *
 * @property execution The execution metadata
 * @property fileTree Optional file tree for execution results (only available when completed)
 */
@Serializable
data class ExecutionWithTree(
    val execution: Execution,
    val fileTree: List<FileEntry>? = null
)

/**
 * Request payload for creating a new execution.
 *
 * @property filePath Path to the file to execute
 * @property data Arbitrary JSON data for the execution (e.g., function to execute)
 */
@Serializable
data class CreateExecutionRequest(
    val filePath: String,
    val data: JsonElement
)

/**
 * Response from plugin when creating an execution.
 *
 * @property name The display name for the execution (provided by the plugin)
 */
@Serializable
data class CreateExecutionResponse(
    val name: String
)

/**
 * Request payload for updating execution state (used by services via JWT).
 *
 * @property state New state of the execution
 * @property progressText Optional progress text
 */
@Serializable
data class UpdateExecutionStateRequest(
    val state: String,
    val progressText: String? = null
)

/**
 * Request payload for updating execution metadata (used by services via JWT).
 *
 * @property metadata Small JSON object to store with the execution
 */
@Serializable
data class UpdateExecutionMetadataRequest(
    val metadata: JsonObject
)

/**
 * Request payload forwarded to plugin for creating an execution.
 *
 * @property executionId Unique identifier for the new execution
 * @property project Project ID
 * @property filePath Path to the file being executed
 * @property data Arbitrary JSON data for the execution
 * @property contributionPlugins Server contribution plugins associated with this language; left out
 *           when the service is sent [contributionHash] alone
 * @property contributionHash Identifies [contributionPlugins]. A service that already holds the set
 *           under this hash is sent the hash alone, see `ContributionDelivery` in the backend.
 */
@Serializable
data class PluginCreateExecutionRequest(
    val executionId: String,
    val project: String,
    val filePath: String,
    val fileContent: String,
    val fileVersion: Int,
    val data: JsonElement,
    val contributionPlugins: List<JsonElement>? = null,
    val contributionHash: String? = null
)

/**
 * Response from plugin execution file tree endpoint.
 *
 * @property files List of file entries in the execution result
 */
@Serializable
data class ExecutionFileTreeResponse(
    val files: List<FileEntry>
)

/**
 * Response from plugin execution summary endpoint.
 *
 * @property summary The summary content for the execution result
 */
@Serializable
data class ExecutionSummaryResponse(
    val summary: String
)

/**
 * Execution state constants used across services.
 * Centralized here to avoid inconsistent definitions in multiple modules.
 */
object ExecutionState {
    const val SUBMITTED = "submitted"
    const val INITIALIZING = "initializing"
    const val RUNNING = "running"
    const val COMPLETED = "completed"
    const val CANCELLED = "cancelled"
    const val FAILED = "failed"
}
