package com.mdeo.pluginservice

import com.mdeo.common.model.PluginTarget
import com.mdeo.common.model.PluginTargetKind
import com.mdeo.pluginservice.request.RequestHandler
import io.ktor.client.request.*
import io.ktor.client.statement.*
import com.mdeo.common.transport.MAX_DECODED_REQUEST_BYTES
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The one-shot request endpoint of a Kotlin plugin service: a contribution answers the same
 * request/response channel a language plugin does, addressed at `contrib:<id>` instead of
 * `lang:<id>`.
 */
class RequestEndpointTest {

    private val contribution = object : Contribution {
        override val id = "echoes"
        override val languageId = "script"
        override val description = "Echoes"
        override val requestHandlers: Map<String, RequestHandler> = mapOf(
            "echo" to RequestHandler { context ->
                buildJsonObject {
                    put("project", context.projectId)
                    put("target", context.target.toString())
                    put("key", context.key)
                    put("said", context.body)
                }
            },
            "fails" to RequestHandler { error("broken on purpose at jdbc://internal-host") }
        )
        override fun payload() = JsonObject(emptyMap())
    }

    private val definition = PluginDefinition(
        id = "echo-service",
        name = "Echo",
        description = "Echoes",
        icon = icon("path" to mapOf("d" to "M0 0")),
        contributions = listOf(contribution)
    )

    /** Stands in for the backend: each token name maps to the claims a real token would carry. */
    private val verifier = PluginTokenVerifier { token ->
        when (token) {
            "good" -> PluginTokenClaims("p", null, listOf("plugin:request:send"), null, null)
            "other-project" -> PluginTokenClaims("q", null, listOf("plugin:request:send"), null, null)
            "unscoped" -> PluginTokenClaims("p", null, listOf("session:open"), null, null)
            else -> null
        }
    }

    private fun service(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { pluginService(definition, verifier) }
        block()
    }

    private suspend fun ApplicationTestBuilder.post(
        path: String,
        token: String?,
        project: String = "p",
        body: String = "{\"hello\":\"world\"}"
    ) = client.post(path) {
        token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        contentType(ContentType.Application.Json)
        setBody("{\"project\":\"$project\",\"body\":$body}")
    }

    private fun errorOf(body: String) = Json.parseToJsonElement(body).jsonObject["error"]!!.jsonObject

    @Test
    fun `an authorized request reaches the contribution's handler`() = service {
        val response = post("/request/contrib/echoes/echo", "good")

        assertEquals(HttpStatusCode.OK, response.status)
        val data = Json.parseToJsonElement(response.bodyAsText()).jsonObject["data"]!!.jsonObject
        assertEquals("p", data["project"]!!.jsonPrimitive.content)
        assertEquals("contrib:echoes", data["target"]!!.jsonPrimitive.content)
        assertEquals("echo", data["key"]!!.jsonPrimitive.content)
        assertEquals("world", data["said"]!!.jsonObject["hello"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the address names the kind, so a language id does not reach a contribution of the same name`() = service {
        val response = post("/request/lang/echoes/echo", "good")

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertTrue(
            "lang:echoes" in errorOf(response.bodyAsText())["message"]!!.jsonPrimitive.content,
            "the refusal names what was addressed"
        )
    }

    @Test
    fun `an unusable address and an unserved key are both refused`() = service {
        assertEquals(HttpStatusCode.NotFound, post("/request/nonsense/echoes/echo", "good").status)
        assertEquals(HttpStatusCode.NotFound, post("/request/contrib/echoes/nope", "good").status)
    }

    @Test
    fun `a missing, invalid, unscoped or misdirected token is refused`() = service {
        assertEquals(HttpStatusCode.Unauthorized, post("/request/contrib/echoes/echo", null).status)
        assertEquals(HttpStatusCode.Unauthorized, post("/request/contrib/echoes/echo", "forged").status)
        assertEquals(HttpStatusCode.Forbidden, post("/request/contrib/echoes/echo", "unscoped").status)
        // The token speaks for project q, the body claims p: the two have to agree.
        assertEquals(HttpStatusCode.Forbidden, post("/request/contrib/echoes/echo", "other-project").status)
    }

    @Test
    fun `an unreadable body is refused, and a handler that throws says nothing internal`() = service {
        val unreadable = client.post("/request/contrib/echoes/echo") {
            header(HttpHeaders.Authorization, "Bearer good")
            contentType(ContentType.Application.Json)
            setBody("not json")
        }
        assertEquals(HttpStatusCode.BadRequest, unreadable.status)

        val failed = post("/request/contrib/echoes/fails", "good")
        assertEquals(HttpStatusCode.InternalServerError, failed.status)
        val message = errorOf(failed.bodyAsText())["message"]!!.jsonPrimitive.content
        assertEquals("The request could not be answered", message)
        assertTrue("internal-host" !in message, "nothing internal reaches the caller")
    }

    @Test
    fun `a body larger than a service accepts is refused before it is read whole`() = service {
        val tooLarge = post("/request/contrib/echoes/echo", "good", body = "\"" + "x".repeat(MAX_DECODED_REQUEST_BYTES.toInt()) + "\"")
        assertEquals(HttpStatusCode.PayloadTooLarge, tooLarge.status)
    }
}
