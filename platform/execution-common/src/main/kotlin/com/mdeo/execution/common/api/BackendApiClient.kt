package com.mdeo.execution.common.api

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.modules.SerializersModule
import org.slf4j.LoggerFactory

/**
 * Base HTTP client for interacting with the backend API.
 * Provides common functionality for fetching data from the backend service.
 *
 * @param baseUrl Base URL of the backend API
 * @param serializersModule Optional custom serializers module for type-specific deserialization
 */
open class BackendApiClient(
    protected val baseUrl: String,
    serializersModule: SerializersModule? = null
) {
    protected val logger = LoggerFactory.getLogger(this::class.java)

    protected val client: HttpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(createJsonConfig(serializersModule))
        }
    }

    /**
     * Creates JSON configuration with optional custom serializers.
     */
    private fun createJsonConfig(serializersModule: SerializersModule?): Json {
        return Json {
            ignoreUnknownKeys = true
            isLenient = true
            if (serializersModule != null) {
                this.serializersModule = serializersModule
            }
        }
    }

    /**
     * Closes the HTTP client and releases resources.
     */
    fun close() {
        client.close()
    }

    /**
     * Updates execution state on the backend.
     *
     * @param executionId UUID of the execution
     * @param state New state string
     * @param progressText Optional progress text
     * @param jwtToken JWT token to authenticate the request
     * @return true if update was successful, false otherwise
     */
    suspend fun updateExecutionState(
        executionId: String,
        state: String,
        progressText: String?,
        jwtToken: String
    ): Boolean {
        return try {
            val response = client.patch("$baseUrl/executions/$executionId/state") {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer $jwtToken")
                setBody(UpdateExecutionStateRequest(state, progressText))
            }

            response.status == HttpStatusCode.OK || response.status == HttpStatusCode.NoContent
        } catch (e: Exception) {
            logger.error("Error updating execution state on backend", e)
            false
        }
    }

    /**
     * Updates execution metadata on the backend.
     *
     * @param executionId UUID of the execution
     * @param metadata JSON metadata object
     * @param jwtToken JWT token to authenticate the request
     * @return true if update was successful, false otherwise
     */
    suspend fun updateExecutionMetadata(
        executionId: String,
        metadata: JsonObject,
        jwtToken: String
    ): Boolean {
        return try {
            logger.info("Updating backend metadata for execution $executionId")

            val response = client.patch("$baseUrl/executions/$executionId/metadata") {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer $jwtToken")
                setBody(UpdateExecutionMetadataRequest(metadata))
            }

            response.status == HttpStatusCode.OK || response.status == HttpStatusCode.NoContent
        } catch (e: Exception) {
            logger.error("Error updating execution metadata on backend", e)
            false
        }
    }

    /**
     * Makes an authenticated GET request to the backend.
     *
     * @param path The API path (relative to baseUrl)
     * @param jwtToken JWT token for authentication
     * @param queryParams Optional query parameters
     * @return The response or null if the request failed
     */
    protected suspend inline fun <reified T> authenticatedGet(
        path: String,
        jwtToken: String,
        queryParams: Map<String, String> = emptyMap()
    ): T? {
        return try {
            val response = client.get("$baseUrl$path") {
                queryParams.forEach { (key, value) -> parameter(key, value) }
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer $jwtToken")
            }

            if (response.status == HttpStatusCode.OK) {
                response.body<T>()
            } else {
                logger.warn("Request to $path failed with status: ${response.status}")
                null
            }
        } catch (e: Exception) {
            logger.error("Error making request to $path", e)
            null
        }
    }
}

/**
 * Request payload for updating execution state.
 */
@Serializable
internal data class UpdateExecutionStateRequest(
    val state: String,
    val progressText: String?
)

/**
 * Request payload for updating execution metadata.
 */
@Serializable
internal data class UpdateExecutionMetadataRequest(
    val metadata: JsonObject
)
