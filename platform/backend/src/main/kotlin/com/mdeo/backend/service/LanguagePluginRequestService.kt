package com.mdeo.backend.service

import com.mdeo.common.model.*
import com.mdeo.common.transport.CompressedResponses
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.time.Duration
import java.util.*

/**
 * Service responsible for sending requests to language plugins and returning their responses.
 *
 * @param services injected dependencies required by this service.
 */
class LanguagePluginRequestService(services: InjectedServices) : BaseService(), InjectedServices by services {
    companion object {
        /**
         * Header a caller sets to `true` to hand its own token to the plugin, instead of the
         * read-only plugin request token the plugin gets otherwise.
         */
        const val DELEGATE_TOKEN_HEADER = "X-Mdeo-Delegate-Token"

        /**
         * The caller's token, when the caller hands its work to the plugin; null otherwise, and the
         * plugin then gets a plugin request token that can only read the project.
         *
         * @param authorization The caller's `Authorization` header
         * @param delegate The caller's [DELEGATE_TOKEN_HEADER]
         * @return The token to forward, or null to mint a read-only one
         */
        fun delegatedToken(authorization: String?, delegate: String?): String? =
            authorization?.takeIf { delegate == "true" && it.startsWith("Bearer ") }?.removePrefix("Bearer ")
    }

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
     * [callerJwt] is supplied — a plugin delegating its work with [DELEGATE_TOKEN_HEADER] — it is
     * forwarded as it is, so that its full scope set (e.g. plugin:execution:start) reaches the
     * plugin. Every other request gets a fresh plugin request token instead.
     *
     * Contribution plugin metadata is included in the request payload.
     *
     * @param projectId the UUID of the project making the request.
     * @param languageId the identifier of the language whose plugin should handle the request.
     * @param key the plugin route key to call.
     * @param body the JSON payload to forward to the plugin.
     * @param callerJwt the caller's JWT, when the caller delegates its work to the plugin.
     * @return an [ApiResult] containing the plugin response data on success or an error on failure.
     */
    suspend fun executeRequest(
        projectId: UUID,
        languageId: String,
        key: String,
        body: JsonElement,
        callerJwt: String? = null,
        deadline: CallerDeadline? = null
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

        val contributions = ContributionSet(pluginService.getContributionPluginsForLanguage(projectId, languageId))

        return try {
            val token = callerJwt ?: jwtService.generatePluginRequestToken(projectId)

            val responseData = callPlugin(
                pluginId,
                pluginUrl,
                languageId,
                key,
                projectId,
                body,
                token,
                contributions,
                deadline
            )

            success(LanguagePluginResponse(data = responseData))
        } catch (e: CancellationException) {
            throw e
        } catch (e: DeadlineExceededException) {
            languagePluginRequestFailure(ErrorCodes.DEADLINE_EXCEEDED, e.message ?: "Deadline exceeded")
        } catch (e: java.net.http.HttpTimeoutException) {
            // The wait is the shorter of the caller's deadline and the backend's maximum; only an
            // expired deadline makes it the caller's.
            if (deadline?.isExpired == true) {
                languagePluginRequestFailure(
                    ErrorCodes.DEADLINE_EXCEEDED,
                    "The plugin did not answer $languageId:$key before the caller's deadline"
                )
            } else {
                logger.error("Language plugin request $languageId:$key timed out", e)
                languagePluginRequestFailure(
                    ErrorCodes.UNAVAILABLE,
                    "The plugin did not answer $languageId:$key within ${config.timeouts.pluginRequestSeconds} seconds"
                )
            }
        } catch (e: Exception) {
            logger.error("Failed to execute language plugin request for $languageId:$key", e)
            languagePluginRequestFailure(ErrorCodes.UNAVAILABLE, "The plugin could not answer $languageId:$key")
        }
    }

    /**
     * Internal helper to call the plugin's HTTP endpoint.
     *
     * @param pluginId the plugin, whose answer shows whether its manifest changed.
     * @param pluginUrl base URL of the plugin.
     * @param languageId language identifier used to resolve the plugin route.
     * @param key specific plugin route key.
     * @param project project UUID used in the request payload.
     * @param body JSON body forwarded to the plugin.
     * @param token JWT token included as a Bearer token in the Authorization header.
     * @param contributions contribution plugins to include, sent as a hash when the plugin holds them.
     * @param deadline the caller's deadline, which shortens the wait and is forwarded to the plugin.
     * @return the deserialized plugin response JSON element.
     * @throws RuntimeException when the plugin returns a non-200 status or when decoding fails.
     */
    private suspend fun callPlugin(
        pluginId: UUID,
        pluginUrl: String,
        languageId: String,
        key: String,
        project: UUID,
        body: JsonElement,
        token: String,
        contributions: ContributionSet,
        deadline: CallerDeadline?
    ): JsonElement {
        return withContext(Dispatchers.IO) {
            val timeout = CallerDeadline.effective(deadline, Duration.ofSeconds(config.timeouts.pluginRequestSeconds))
            if (timeout.isZero) {
                throw DeadlineExceededException("The caller's deadline passed before $languageId:$key was sent to the plugin")
            }
            val requestUrl = URI.create(pluginUrl).resolve("request/$languageId/$key")

            val response = ContributionDelivery.send(pluginUrl) { includePayloads ->
                val requestBody = json.encodeToString(
                    LanguagePluginRequest.serializer(),
                    LanguagePluginRequest(
                        project = project.toString(),
                        body = body,
                        contributionPlugins = contributions.plugins.takeIf { includePayloads },
                        contributionHash = contributions.hash
                    )
                )

                val request = CompressedResponses.accept(HttpRequest.newBuilder())
                    .uri(requestUrl)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer $token")
                    .header(CallerDeadline.HEADER, CallerDeadline.headerValue(timeout))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(timeout)
                    .build()

                httpClient.send(request, CompressedResponses.ofString())
            }
            pluginService.observeManifestFingerprint(pluginId, response)

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
