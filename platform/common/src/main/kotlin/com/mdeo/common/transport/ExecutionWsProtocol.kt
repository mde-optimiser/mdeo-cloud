package com.mdeo.common.transport

import com.mdeo.common.model.ApiError
import com.mdeo.common.model.FileEntry
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Message protocol for execution result access over WebSocket.
 *
 * The same protocol is spoken on all three hops of an execution result read:
 *
 * 1. workbench → backend (over the browser's session-authenticated connection)
 * 2. backend → language plugin service
 * 3. language plugin service → execution service
 *
 * Only the hops between services carry [ExecutionWsContext.auth]; the browser hop is
 * authenticated by its session, and hops 2 and 3 hold no session state at all, so every
 * request repeats the bearer token that authorizes it. That is what lets the connections
 * underneath be pooled, shared between unrelated requests, and dropped when idle without
 * anything having to be re-established beyond the socket itself.
 */
object ExecutionWsProtocol {
    /**
     * Path the execution WebSocket endpoint is served under on plugin and execution services.
     */
    const val ENDPOINT_PATH = "/ws/executions"

    /**
     * JSON codec for the protocol. Unknown keys are ignored so that a peer running a
     * newer version, which may add fields, does not break an older one.
     */
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "messageType"
    }

    /**
     * Encodes a message to its wire representation.
     *
     * @param message The message to encode
     * @return The JSON text to send as a WebSocket frame
     */
    fun encode(message: ExecutionWsMessage): String = json.encodeToString(ExecutionWsMessage.serializer(), message)

    /**
     * Decodes a message from its wire representation.
     *
     * @param text The JSON text of a received WebSocket frame
     * @return The decoded message
     */
    fun decode(text: String): ExecutionWsMessage = json.decodeFromString(ExecutionWsMessage.serializer(), text)
}

/**
 * Common envelope for every execution WebSocket message.
 *
 * @property requestId Correlates a request with its responses; unique per connection
 */
@Serializable
sealed interface ExecutionWsMessage {
    val requestId: String
}

/**
 * Everything a peer needs to authorize and route a single execution request.
 *
 * Which fields are populated depends on the hop: the browser sends [projectId] and relies on
 * its session, the backend adds [auth], [languageId] and [metadata] for the plugin service,
 * and the plugin service forwards [auth] to the execution service.
 *
 * @property executionId The execution being addressed
 * @property auth Bearer token authorizing this single request, or null on session-authenticated hops
 * @property projectId The owning project, used by the backend to check the caller's permissions
 * @property languageId Language plugin that owns the execution, used to route within a plugin service
 * @property metadata Execution metadata the backend forwards so downstream services can locate results
 */
@Serializable
data class ExecutionWsContext(
    val executionId: String,
    val auth: String? = null,
    val projectId: String? = null,
    val languageId: String? = null,
    val metadata: JsonObject? = null
)

/**
 * Requests the markdown summary of an execution.
 *
 * Responds with [ExecutionWsResponse] carrying an [ExecutionSummaryPayload].
 */
@Serializable
@SerialName("exec/summary")
data class ExecutionSummaryWsRequest(
    override val requestId: String,
    val context: ExecutionWsContext
) : ExecutionWsMessage

/**
 * Requests the result file tree of an execution.
 *
 * Responds with [ExecutionWsResponse] carrying an [ExecutionFileTreePayload].
 */
@Serializable
@SerialName("exec/fileTree")
data class ExecutionFileTreeWsRequest(
    override val requestId: String,
    val context: ExecutionWsContext
) : ExecutionWsMessage

/**
 * Requests a single result file of an execution.
 *
 * Responds with [ExecutionWsResponse] carrying an [ExecutionFilePayload].
 *
 * @property path Path of the file within the execution results
 */
@Serializable
@SerialName("exec/file")
data class ExecutionFileWsRequest(
    override val requestId: String,
    val context: ExecutionWsContext,
    val path: String
) : ExecutionWsMessage

/**
 * Requests the file tree *and* the contents of result files in one round trip.
 *
 * This is the request that makes opening an execution cheap: instead of one request per
 * file per hop, the whole result set streams back over a single correlation id. Each file
 * arrives as its own [ExecutionFileDataMessage] so the receiver can populate its cache (and
 * the browser its editor) progressively, and the terminating [ExecutionWsResponse] carries
 * an [ExecutionFileTreePayload] describing everything that was sent.
 *
 * @property paths Files to include, or null to include every file in the result tree
 */
@Serializable
@SerialName("exec/files")
data class ExecutionFilesWsRequest(
    override val requestId: String,
    val context: ExecutionWsContext,
    val paths: List<String>? = null
) : ExecutionWsMessage

/**
 * Requests cancellation of a running execution.
 *
 * Responds with an [ExecutionWsResponse] with no payload.
 */
@Serializable
@SerialName("exec/cancel")
data class ExecutionCancelWsRequest(
    override val requestId: String,
    val context: ExecutionWsContext
) : ExecutionWsMessage

/**
 * Requests deletion of an execution and its results.
 *
 * Responds with an [ExecutionWsResponse] with no payload.
 */
@Serializable
@SerialName("exec/delete")
data class ExecutionDeleteWsRequest(
    override val requestId: String,
    val context: ExecutionWsContext
) : ExecutionWsMessage

/**
 * Terminating success response for a request.
 *
 * @property data The payload, or null for requests that return nothing
 */
@Serializable
@SerialName("exec/response")
data class ExecutionWsResponse(
    override val requestId: String,
    val data: JsonElement? = null
) : ExecutionWsMessage

/**
 * Terminating failure response for a request.
 *
 * @property error What went wrong, in the platform's error shape
 */
@Serializable
@SerialName("exec/error")
data class ExecutionWsError(
    override val requestId: String,
    val error: ApiError
) : ExecutionWsMessage

/**
 * One file of a streaming [ExecutionFilesWsRequest] response.
 *
 * @property path Path of the file within the execution results
 * @property content The file's text content
 */
@Serializable
@SerialName("exec/fileData")
data class ExecutionFileDataMessage(
    override val requestId: String,
    val path: String,
    val content: String
) : ExecutionWsMessage

/**
 * Payload of a successful summary request.
 *
 * @property summary Markdown-formatted summary of the execution
 */
@Serializable
data class ExecutionSummaryPayload(val summary: String)

/**
 * Payload of a successful file tree request, and the terminator of a streamed bulk load.
 *
 * @property files Flat list of entries in the execution result tree
 */
@Serializable
data class ExecutionFileTreePayload(val files: List<FileEntry>)

/**
 * Payload of a successful single-file request.
 *
 * @property content The file's text content
 */
@Serializable
data class ExecutionFilePayload(val content: String)
