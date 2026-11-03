package com.mdeo.backend.service

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * The contribution plugins a language gets with every request, and the hash that stands for them.
 *
 * @property plugins The contribution payloads, in a stable order
 * @property hash SHA-256 of the payloads, which identifies the set
 */
class ContributionSet(val plugins: List<JsonObject>) {
    val hash: String = MessageDigest.getInstance("SHA-256")
        .digest(JsonArray(plugins).toString().toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

/**
 * Sends a language's contribution plugins to its service only when the service does not hold them.
 *
 * Every request to a language service carries the contribution plugins of the project, and for a
 * language with several contributions that is tens of kilobytes each time. A service that supports
 * it keeps the sets it was sent, by hash, and says so in [SUPPORT_HEADER] on its answers. From then
 * on such a service is sent the hash alone. When it does not hold the set — because it restarted, or
 * the set changed — it answers `409` with [UNKNOWN_HEADER], and the request is sent once more with
 * the full set. A service that never sends [SUPPORT_HEADER] always gets the full set.
 */
object ContributionDelivery {
    /**
     * Header a service answers with to say it accepts a contribution hash instead of the payloads.
     */
    const val SUPPORT_HEADER = "X-Mdeo-Contribution-Hashes"

    /**
     * Header on a `409` answer saying the service does not hold the set the hash stands for.
     */
    const val UNKNOWN_HEADER = "X-Mdeo-Contributions-Unknown"

    private val hashCapable: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Sends one request, with or without the contribution payloads as the service needs.
     *
     * @param pluginUrl The service's base URL, which its support is remembered by
     * @param send Sends the request, including the payloads when told to; the hash is always sent
     * @return The answer to the request that counted
     */
    fun <T> send(pluginUrl: String, send: (includePayloads: Boolean) -> HttpResponse<T>): HttpResponse<T> {
        val hashOnly = pluginUrl in hashCapable
        var response = send(!hashOnly)
        if (hashOnly && response.statusCode() == 409 && response.headers().firstValue(UNKNOWN_HEADER).isPresent) {
            response = send(true)
        }
        if (response.headers().firstValue(SUPPORT_HEADER).isPresent) {
            hashCapable += pluginUrl
        } else {
            hashCapable -= pluginUrl
        }
        return response
    }
}
