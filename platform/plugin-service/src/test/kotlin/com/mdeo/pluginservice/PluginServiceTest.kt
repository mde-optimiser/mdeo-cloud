package com.mdeo.pluginservice

import com.mdeo.common.model.SessionType
import com.mdeo.pluginservice.session.SessionCloseCodes
import com.mdeo.pluginservice.session.SessionPeer
import com.mdeo.pluginservice.session.SessionTokenClaims
import com.mdeo.pluginservice.session.SessionTokenVerifier
import com.mdeo.pluginservice.session.negotiateVersion
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import io.ktor.websocket.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

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
            "good" -> SessionTokenClaims("p", "e", listOf("session:connect"), "contrib:echoes", "echo")
            "other" -> SessionTokenClaims("p", "e", listOf("session:connect"), "contrib:other", "echo")
            "unserved" -> SessionTokenClaims("p", "e", listOf("session:connect"), "contrib:echoes", "nope")
            "unscoped" -> SessionTokenClaims("p", "e", listOf("execution:read"), "contrib:echoes", "echo")
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
        val manifest = Json.parseToJsonElement(client.get("/").bodyAsText()).jsonObject
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
    fun `a token in the query works too`() = service {
        val client = createClient { install(WebSockets) }
        var answer: ByteArray? = null
        client.webSocket("/ws/sessions/contrib/echoes/echo?token=good") {
            send(Frame.Binary(true, byteArrayOf(7)))
            answer = (incoming.receive() as Frame.Binary).readBytes()
        }
        assertEquals(listOf<Byte>(2, 7), answer!!.toList())
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
