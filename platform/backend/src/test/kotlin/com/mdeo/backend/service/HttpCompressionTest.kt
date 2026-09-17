package com.mdeo.backend.service

import com.mdeo.common.transport.CompressedResponses
import com.mdeo.common.transport.MAX_DECODED_REQUEST_BYTES
import com.mdeo.common.transport.acceptCompressedResponses
import com.mdeo.common.transport.installDeflate
import com.mdeo.common.transport.installHttpCompression
import com.sun.net.httpserver.HttpServer
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.util.zip.GZIPOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    fun `a compressed request body is inflated only up to the request limit`() = testApplication {
        application {
            installHttpCompression()
            routing {
                post("/echo-size") {
                    val size = try {
                        call.receive<ByteArray>().size.toString()
                    } catch (e: Exception) {
                        "refused"
                    }
                    call.respondText(size)
                }
            }
        }

        fun gzip(bytes: Int): ByteArray = ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { zip ->
                val chunk = ByteArray(1024 * 1024)
                repeat(bytes / chunk.size) { zip.write(chunk) }
            }
        }.toByteArray()

        val small = client.post("/echo-size") {
            header(HttpHeaders.ContentEncoding, "gzip")
            setBody(gzip(1024 * 1024))
        }
        assertEquals((1024 * 1024).toString(), small.bodyAsText())

        val bomb = gzip((MAX_DECODED_REQUEST_BYTES + 1024 * 1024).toInt())
        assertTrue(bomb.size < 1024 * 1024, "the test body must be small on the wire")
        val refused = client.post("/echo-size") {
            header(HttpHeaders.ContentEncoding, "gzip")
            setBody(bomb)
        }
        assertEquals("refused", refused.bodyAsText())
    }

    @Test
    fun `a compressed WebSocket message is refused once it inflates past the limit`() = testApplication {
        val limit = 1024L * 1024
        application {
            install(io.ktor.server.websocket.WebSockets) {
                maxFrameSize = limit
                extensions { installDeflate(limit) }
            }
            routing {
                webSocket("/ws") {
                    for (frame in incoming) {
                        send(Frame.Text(frame.data.size.toString()))
                    }
                }
            }
        }
        val wsClient = createClient {
            this.install(io.ktor.client.plugins.websocket.WebSockets) {
                extensions { installDeflate(8 * limit) }
            }
        }

        wsClient.webSocket("/ws") {
            send(Frame.Binary(true, ByteArray((limit / 2).toInt())))
            assertEquals((limit / 2).toString(), (incoming.receive() as Frame.Text).readText())

            // Zeros compress to a few kilobytes, well under the wire limit.
            send(Frame.Binary(true, ByteArray((2 * limit).toInt())))
            val closed = runCatching { incoming.receive() }
            assertTrue(closed.isFailure, "the oversized message must not be delivered")
            assertEquals(CloseReason.Codes.TOO_BIG.code, closeReason.await()?.code)
        }
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
