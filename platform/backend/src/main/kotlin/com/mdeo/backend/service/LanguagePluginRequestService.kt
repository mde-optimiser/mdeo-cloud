package com.mdeo.backend.service

import com.mdeo.common.transport.CompressedResponses
import com.mdeo.common.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.*

/**
 * Service responsible for sending requests to language plugins and returning their responses.
 *
 * @param services injected dependencies required by this service.
 */
class LanguagePluginRequestService(services: InjectedServices) : BaseService(), InjectedServices by services {
    private val logger = LoggerFactory.getLogger(LanguagePluginRequestService::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    private val httpClient by lazy {
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(config.timeouts.connectSeconds))
            .version(if (config.plugin.forceHttp1) HttpClient.Version.HTTP_1_1 else HttpClient.Version.HTTP_2)
            .build()
    }

    /**
     * Execute a request against the language plugin for the given language.
     *
     * This method locates the appropriate plugin for the language, prepares a JWT,
     * and forwards the provided JSON body to the plugin endpoint. When a
     * [callerJwt] is supplied (service-to-service calls) it is forwarded directly
     * so that its full scope set (e.g. execution:write) reaches the plugin.
     * For browser-session calls where no [callerJwt] is available a fresh
     * project token is generated instead.
     *
     * Contribution plugin metadata is included in the request payload.
     *
     * @param projectId the UUID of the project making the request.
     * @param languageId the identifier of the language whose plugin should handle the request.
     * @param key the plugin route key to call.
     * @param body the JSON payload to forward to the plugin.
     * @param callerJwt optional JWT from the caller to forward directly (preserves scopes).
     * @return an [ApiResult] containing the plugin response data on success or an error on failure.
     */
    suspend fun executeRequest(
        projectId: UUID,
        languageId: String,
        key: String,
        body: JsonElement,
        callerJwt: String? = null
    ): ApiResult<LanguagePluginResponse> {
        val pluginInfo = pluginService.findPluginByLanguage(projectId, languageId)
            ?: return languagePluginRequestFailure(
                ErrorCodes.FILE_DATA_NO_PLUGIN_FOUND,
                "No plugin found for language: $languageId"
            )

        val (pluginId, _) = pluginInfo
        val pluginUrl = pluginService.getPluginUrl(pluginId, useInternal = true)
            ?: return languagePluginRequestFailure(
                ErrorCodes.PLUGIN_NOT_FOUND,
                "Plugin URL not found"
            )

        val contributionPlugins = pluginService.getContributionPluginsForLanguage(projectId, languageId)

        return try {
            val token = callerJwt ?: jwtService.generateProjectToken(projectId)

            val responseData = callPlugin(
                pluginUrl,
                languageId,
                key,
                projectId,
                body,
                token,
                contributionPlugins
            )

            success(LanguagePluginResponse(data = responseData))
        } catch (e: Exception) {
            logger.error("Failed to execute language plugin request for $languageId:$key", e)
            languagePluginRequestFailure(
                ErrorCodes.FILE_DATA_COMPUTATION_FAILED,
                "Failed to execute request: ${e.message}"
            )
        }
    }

    /**
     * Internal helper to call the plugin's HTTP endpoint.
     *
     * @param pluginUrl base URL of the plugin.
     * @param languageId language identifier used to resolve the plugin route.
     * @param key specific plugin route key.
     * @param project project UUID used in the request payload.
     * @param body JSON body forwarded to the plugin.
     * @param token JWT token included as a Bearer token in the Authorization header.
     * @param contributionPlugins list of contribution plugin metadata to include in the payload.
     * @return the deserialized plugin response JSON element.
     * @throws RuntimeException when the plugin returns a non-200 status or when decoding fails.
     */
    private suspend fun callPlugin(
        pluginUrl: String,
        languageId: String,
        key: String,
        project: UUID,
        body: JsonElement,
        token: String,
        contributionPlugins: List<JsonObject>
    ): JsonElement {
        return withContext(Dispatchers.IO) {
            val requestBody = json.encodeToString(
                LanguagePluginRequest.serializer(),
                LanguagePluginRequest(
                    project = project.toString(),
                    body = body,
                    contributionPlugins = contributionPlugins
                )
            )

            val requestUrl = URI.create(pluginUrl).resolve("request/$languageId/$key")

            val requestBuilder = CompressedResponses.accept(HttpRequest.newBuilder())
                .uri(requestUrl)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer $token")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .timeout(Duration.ofSeconds(config.timeouts.pluginRequestSeconds))

            val request = requestBuilder.build()

            val response = httpClient.send(request, CompressedResponses.ofString())

            if (response.statusCode() != 200) {
                throw RuntimeException("Plugin returned status ${response.statusCode()}: ${response.body()}")
            }

            json.decodeFromString<LanguagePluginResponse>(response.body()).data
        }
    }

    /**
     * Helper to create a failure ApiResult for language plugin requests.
     *
     * @param code error code.
     * @param message human-readable error message.
     */
    private fun <T> languagePluginRequestFailure(code: String, message: String): ApiResult<T> {
        return ApiResult.Failure(ApiError(code = code, message = message))
    }
}
