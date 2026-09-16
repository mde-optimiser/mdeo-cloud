package com.mdeo.backend.service

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ContributionDeliveryTest {

    private val contributions = ContributionSet(listOf(JsonObject(mapOf("id" to JsonPrimitive("geo")))))

    /**
     * A plugin service that remembers sets by hash when [supportsHashes], and records whether each
     * request carried the payloads.
     */
    private class FakePlugin(val supportsHashes: Boolean) : AutoCloseable {
        val carriedPayloads = mutableListOf<Boolean>()
        var known = mutableSetOf<String>()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val body = exchange.requestBody.readBytes().decodeToString()
                val hash = exchange.requestHeaders.getFirst("X-Hash")
                val hasPayloads = body == "payloads"
                carriedPayloads += hasPayloads
                if (supportsHashes) exchange.responseHeaders.add(ContributionDelivery.SUPPORT_HEADER, "1")
                val status = if (supportsHashes && !hasPayloads && hash !in known) {
                    exchange.responseHeaders.add(ContributionDelivery.UNKNOWN_HEADER, "1")
                    409
                } else {
                    if (hasPayloads) known += hash
                    200
                }
                exchange.sendResponseHeaders(status, -1)
                exchange.close()
            }
            start()
        }
        val url = "http://127.0.0.1:${server.address.port}/"
        override fun close() = server.stop(0)
    }

    private fun call(plugin: FakePlugin): HttpResponse<String> = ContributionDelivery.send(plugin.url) { includePayloads ->
        HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create(plugin.url))
                .header("X-Hash", contributions.hash)
                .POST(HttpRequest.BodyPublishers.ofString(if (includePayloads) "payloads" else "hash"))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        )
    }

    @Test
    fun `a service that keeps sets gets the hash alone after the first request`() {
        FakePlugin(supportsHashes = true).use { plugin ->
            repeat(3) { assertEquals(200, call(plugin).statusCode()) }
            assertEquals(listOf(true, false, false), plugin.carriedPayloads)
        }
    }

    @Test
    fun `a service that lost the set gets it again once`() {
        FakePlugin(supportsHashes = true).use { plugin ->
            call(plugin)
            plugin.known.clear() // as after a restart
            assertEquals(200, call(plugin).statusCode())
            assertEquals(listOf(true, false, true), plugin.carriedPayloads)
        }
    }

    @Test
    fun `a service without support always gets the payloads`() {
        FakePlugin(supportsHashes = false).use { plugin ->
            repeat(3) { call(plugin) }
            assertEquals(listOf(true, true, true), plugin.carriedPayloads)
        }
    }

    @Test
    fun `equal sets hash equally and different sets differently`() {
        val same = ContributionSet(listOf(JsonObject(mapOf("id" to JsonPrimitive("geo")))))
        val other = ContributionSet(listOf(JsonObject(mapOf("id" to JsonPrimitive("routes")))))
        assertEquals(contributions.hash, same.hash)
        assertNotEquals(contributions.hash, other.hash)
    }
}
