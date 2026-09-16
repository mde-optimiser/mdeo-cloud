package com.mdeo.backend.service

import com.mdeo.common.transport.CompressedResponses
import com.mdeo.common.transport.acceptCompressedResponses
import com.mdeo.common.transport.installHttpCompression
import com.sun.net.httpserver.HttpServer
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.util.zip.GZIPOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The compression every hop uses: Ktor servers compress, Ktor clients and `java.net.http` inflate.
 */
class HttpCompressionTest {
    private val body = """{"ast":[${(1..2000).joinToString(",") { "\"node-$it\"" }}]}"""

    @Test
    fun `a Ktor server compresses large responses and a Ktor client inflates them`() = testApplication {
        application {
            installHttpCompression()
            routing {
                get("/large") { call.respondText(body, ContentType.Application.Json) }
                get("/small") { call.respondText("{}", ContentType.Application.Json) }
            }
        }

        val raw = client.get("/large") { header(HttpHeaders.AcceptEncoding, "gzip") }
        assertEquals("gzip", raw.headers[HttpHeaders.ContentEncoding])

        val small = client.get("/small") { header(HttpHeaders.AcceptEncoding, "gzip") }
        assertNull(small.headers[HttpHeaders.ContentEncoding], "tiny bodies are not worth compressing")

        val inflating = createClient { acceptCompressedResponses() }
        assertEquals(body, inflating.get("/large").bodyAsText())
    }

    @Test
    fun `java net http asks for gzip and inflates it`() {
        val gzipped = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(body.toByteArray()) } }.toByteArray()
        var acceptEncoding: String? = null
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                acceptEncoding = exchange.requestHeaders.getFirst("Accept-Encoding")
                exchange.responseHeaders.add("Content-Encoding", "gzip")
                exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
                exchange.sendResponseHeaders(200, gzipped.size.toLong())
                exchange.responseBody.use { it.write(gzipped) }
            }
            start()
        }
        try {
            val request = CompressedResponses.accept(HttpRequest.newBuilder())
                .uri(URI.create("http://127.0.0.1:${server.address.port}/"))
                .build()
            val client = HttpClient.newHttpClient()
            assertEquals(body, client.send(request, CompressedResponses.ofString()).body())
            assertEquals(body, String(client.send(request, CompressedResponses.ofByteArray()).body()))
            assertEquals("gzip, deflate", acceptEncoding)
        } finally {
            server.stop(0)
        }
    }
}
