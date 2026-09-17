package com.mdeo.pluginservice

import com.mdeo.common.model.SessionType
import com.mdeo.pluginservice.session.SessionCloseCodes
import com.mdeo.pluginservice.session.SessionPeer
import com.mdeo.pluginservice.session.SessionTokenClaims
import com.mdeo.pluginservice.session.SessionTokenVerifier
import com.mdeo.pluginservice.session.closeReason
import com.mdeo.pluginservice.session.negotiateVersion
import com.mdeo.common.transport.MAX_SERVICE_WEBSOCKET_MESSAGE_BYTES
import com.mdeo.common.transport.installDeflate
import com.mdeo.common.transport.respondError
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import io.ktor.websocket.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PluginServiceTest {

    private val closedReasons = mutableListOf<String>()

    private val echo = ServedSession(SessionType("echo", listOf(2, 1))) { context ->
        object : SessionPeer {
            override suspend fun onMessage(data: ByteArray) {
                context.send(byteArrayOf(context.version.toByte()) + data)
            }

            override fun onClose(reason: String) {
                closedReasons += reason
            }
        }
    }

    private val contribution = object : Contribution {
        override val id = "echoes"
        override val languageId = "script"
        override val description = "Echoes"
        override val sessions = mapOf("echo" to echo)
        override fun payload() = JsonObject(mapOf("type" to Json.parseToJsonElement("\"test\"")))
    }

    private val definition = PluginDefinition(
        id = "echo-service",
        name = "Echo",
        description = "Echoes",
        icon = icon("path" to mapOf("d" to "M0 0")),
        contributions = listOf(contribution)
    )

    /** Stands in for the backend: each token name maps to the claims a real token would carry. */
    private val verifier = SessionTokenVerifier { token ->
        when (token) {
            "good" -> SessionTokenClaims("p", "e", listOf("plugin:session:connect"), "contrib:echoes", "echo")
            "other" -> SessionTokenClaims("p", "e", listOf("plugin:session:connect"), "contrib:other", "echo")
            "unserved" -> SessionTokenClaims("p", "e", listOf("plugin:session:connect"), "contrib:echoes", "nope")
            "unscoped" -> SessionTokenClaims("p", "e", listOf("session:open"), "contrib:echoes", "echo")
            else -> null
        }
    }

    private fun service(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { pluginService(definition, verifier) }
        block()
    }

    private suspend fun ApplicationTestBuilder.closeCodeOf(path: String, token: String?): Short? {
        val client = createClient { install(WebSockets) }
        var code: Short? = null
        client.webSocket(path, request = { token?.let { header(HttpHeaders.Authorization, "Bearer $it") } }) {
            code = closeReason.await()?.code
        }
        return code
    }

    @Test
    fun `the manifest lists the contribution with its sessions`() = service {
        val response = client.get("/")
        val body = response.bodyAsText()
        val expectedFingerprint = java.security.MessageDigest.getInstance("SHA-256").digest(body.toByteArray())
            .joinToString("") { "%02x".format(it) }
        assertEquals(expectedFingerprint, response.headers[MANIFEST_FINGERPRINT_HEADER], "every answer names the manifest")
        val manifest = Json.parseToJsonElement(body).jsonObject
        assertEquals("echo-service", manifest["id"]!!.jsonPrimitive.content)
        assertEquals(0, manifest["languagePlugins"]!!.jsonArray.size)
        val entry = manifest["contributionPlugins"]!!.jsonArray.single().jsonObject
        assertEquals("script", entry["languageId"]!!.jsonPrimitive.content)
        val payload = entry["serverContributionPlugins"]!!.jsonArray.single().jsonObject
        assertEquals("echoes", payload["id"]!!.jsonPrimitive.content)
        assertEquals("test", payload["type"]!!.jsonPrimitive.content)
        val session = payload["sessions"]!!.jsonObject["echo"]!!.jsonObject
        assertEquals("echo", session["protocol"]!!.jsonPrimitive.content)
        assertEquals(listOf("2", "1"), session["versions"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `a failing route answers in the platform's error shape`() = testApplication {
        application {
            pluginService(definition, verifier)
            routing {
                get("/fails") { error("broken on purpose at jdbc://internal-host") }
                get("/missing") { call.respondError(HttpStatusCode.NotFound, "No such thing") }
            }
        }
        fun errorOf(body: String) = Json.parseToJsonElement(body).jsonObject["error"]!!.jsonObject

        val failed = client.get("/fails")
        assertEquals(HttpStatusCode.InternalServerError, failed.status)
        assertEquals("Internal", errorOf(failed.bodyAsText())["code"]!!.jsonPrimitive.content)
        assertEquals("Internal server error", errorOf(failed.bodyAsText())["message"]!!.jsonPrimitive.content)

        val unknown = client.get("/no-such-route")
        assertEquals(HttpStatusCode.NotFound, unknown.status)
        assertEquals("NotFound", errorOf(unknown.bodyAsText())["code"]!!.jsonPrimitive.content)

        val missing = client.get("/missing")
        assertEquals("No such thing", errorOf(missing.bodyAsText())["message"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an authorized session negotiates a version and exchanges messages`() = service {
        val client = createClient { install(WebSockets) }
        var answer: ByteArray? = null
        client.webSocket("/ws/sessions/contrib/echoes/echo?v=3,1", request = { header(HttpHeaders.Authorization, "Bearer good") }) {
            send(Frame.Binary(true, byteArrayOf(42)))
            answer = (incoming.receive() as Frame.Binary).readBytes()
            close(CloseReason(CloseReason.Codes.NORMAL, "done"))
        }
        assertEquals(listOf<Byte>(1, 42), answer!!.toList())
    }

    @Test
    fun `messages are deflated when the caller offers it`() {
        // Ktor's test engine does not negotiate WebSocket extensions, so this needs a real server.
        val server = embeddedServer(Netty, port = 0, host = "127.0.0.1") { pluginService(definition, verifier) }.start()
        val client = HttpClient(CIO) { install(WebSockets) { extensions { installDeflate(MAX_SERVICE_WEBSOCKET_MESSAGE_BYTES) } } }
        try {
            val port = runBlocking { server.engine.resolvedConnectors().first().port }
            val large = ByteArray(64_000) { (it % 7).toByte() }
            runBlocking {
                client.webSocket("ws://127.0.0.1:$port/ws/sessions/contrib/echoes/echo", request = {
                    header(HttpHeaders.Authorization, "Bearer good")
                }) {
                    assertTrue(extensions.any { it is WebSocketDeflateExtension }, "permessage-deflate was not negotiated")
                    send(Frame.Binary(true, large))
                    assertEquals(large.toList(), (incoming.receive() as Frame.Binary).readBytes().drop(1))
                }
            }
        } finally {
            client.close()
            server.stop(100, 1000)
        }
    }

    @Test
    fun `a token in the query is not read`() = service {
        assertEquals(SessionCloseCodes.UNAUTHORIZED, closeCodeOf("/ws/sessions/contrib/echoes/echo?token=good", null))
    }

    @Test
    fun `a long close reason is cut to fit a close frame`() {
        val reason = closeReason(SessionCloseCodes.NOT_FOUND, "é".repeat(200))
        assertTrue(reason.message.toByteArray(Charsets.UTF_8).size <= 123)
        assertTrue(reason.message.isNotEmpty())
    }

    @Test
    fun `a session beyond the limit is refused until one closes`() = testApplication {
        application { pluginService(definition, verifier, maxSessions = 1) }
        val client = createClient { install(WebSockets) }
        val bearer: io.ktor.client.request.HttpRequestBuilder.() -> Unit = { header(HttpHeaders.Authorization, "Bearer good") }

        client.webSocket("/ws/sessions/contrib/echoes/echo", request = bearer) {
            send(Frame.Binary(true, byteArrayOf(1)))
            incoming.receive()
            // While this one is open, a second is refused.
            assertEquals(SessionCloseCodes.UNAVAILABLE, closeCodeOf("/ws/sessions/contrib/echoes/echo", "good"))
            close(CloseReason(CloseReason.Codes.NORMAL, "done"))
        }

        var answered = false
        repeat(50) {
            if (answered) return@repeat
            client.webSocket("/ws/sessions/contrib/echoes/echo", request = bearer) {
                send(Frame.Binary(true, byteArrayOf(2)))
                answered = incoming.receiveCatching().getOrNull() is Frame.Binary
            }
            if (!answered) kotlinx.coroutines.delay(20)
        }
        assertTrue(answered, "a slot frees once the first session closed")
    }

    @Test
    fun `a missing, invalid, unscoped or misdirected token is refused`() = service {
        assertEquals(SessionCloseCodes.UNAUTHORIZED, closeCodeOf("/ws/sessions/contrib/echoes/echo", null))
        assertEquals(SessionCloseCodes.UNAUTHORIZED, closeCodeOf("/ws/sessions/contrib/echoes/echo", "forged"))
        assertEquals(SessionCloseCodes.UNAUTHORIZED, closeCodeOf("/ws/sessions/contrib/echoes/echo", "unscoped"))
        assertEquals(SessionCloseCodes.UNAUTHORIZED, closeCodeOf("/ws/sessions/contrib/echoes/echo", "other"))
    }

    @Test
    fun `an unknown session and a version mismatch are refused`() = service {
        assertEquals(SessionCloseCodes.NOT_FOUND, closeCodeOf("/ws/sessions/contrib/echoes/nope", "unserved"))
        assertEquals(SessionCloseCodes.VERSION_MISMATCH, closeCodeOf("/ws/sessions/contrib/echoes/echo?v=9", "good"))
    }

    @Test
    fun `version negotiation prefers the caller's order`() {
        assertEquals(1, negotiateVersion(listOf("1,2"), listOf(2, 1)))
        assertEquals(2, negotiateVersion(emptyList(), listOf(2, 1)))
        assertEquals(2, negotiateVersion(listOf("5", "2"), listOf(2, 1)))
        assertNull(negotiateVersion(listOf("5"), listOf(2, 1)))
    }

    @Test
    fun `contribution ids must be valid and unique`() {
        assertFailsWith<IllegalArgumentException> {
            definition.copy(contributions = listOf(contribution, contribution))
        }
        assertFailsWith<IllegalArgumentException> {
            definition.copy(contributions = listOf(object : Contribution by contribution {
                override val id = "not valid"
            }))
        }
    }
}
