package com.mdeo.script.external

import com.mdeo.common.model.PluginTarget
import com.mdeo.common.model.PluginTargetKind
import com.mdeo.common.transport.SessionConnection
import com.mdeo.expression.ast.types.ClassTypeRef
import com.mdeo.pluginservice.PluginDefinition
import com.mdeo.pluginservice.icon
import com.mdeo.pluginservice.pluginService
import com.mdeo.pluginservice.session.SessionTokenClaims
import com.mdeo.pluginservice.session.SessionTokenVerifier
import com.mdeo.script.compiler.ExternalCallSpec
import com.mdeo.script.stdlib.impl.collections.ListImpl
import com.mdeo.scriptfunctions.service.scriptContribution
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * An execution calling a Kotlin plugin service over a real WebSocket: the dispatcher the script
 * runtime uses, the session endpoint of `:plugin-service`, and the service of
 * `:script-plugin-service`. Only the backend is stood in for, by a fixed connection and token.
 */
class PluginServiceEndToEndTest {

    private val double = ClassTypeRef("builtin", "double", false)
    private val listOfDouble = ClassTypeRef("builtin", "List", false, mapOf("T" to double))
    private val readonlyListOfDouble = ClassTypeRef("builtin", "ReadonlyList", false, mapOf("T" to double))

    private val contribution = scriptContribution("stats") {
        function("normalize") {
            parameter("values", readonlyListOfDouble)
            returns(listOfDouble)
            implementation { call ->
                val values = call.argument<List<Double>>(0)
                val total = values.sum()
                values.map { it / total }
            }
        }
        function("fail") {
            implementation { error("service says no") }
        }
    }

    private val verifier = SessionTokenVerifier { token ->
        if (token == "run-token") SessionTokenClaims("p", "e", listOf("plugin:session:connect"), PluginTarget.of(PluginTargetKind.CONTRIBUTION, "stats"), "functions") else null
    }

    private lateinit var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>
    private var port = 0

    @BeforeEach
    fun start() {
        val definition = PluginDefinition("stats-service", "Stats", "Statistics", icon(), listOf(contribution))
        server = embeddedServer(Netty, port = 0, host = "127.0.0.1") { pluginService(definition, verifier) }.start()
        port = runBlocking { server.engine.resolvedConnectors().first().port }
    }

    @AfterEach
    fun stop() {
        server.stop(100, 1000)
    }

    private fun spec(operation: String, returnType: ClassTypeRef, vararg params: ClassTypeRef) = ExternalCallSpec(
        callId = operation, functionName = operation, overloadKey = "", operation = operation, model = "none",
        parameterTypes = params.toList(), returnType = returnType, contribution = "stats", session = "functions"
    )

    private fun dispatcher(token: String) = SessionDispatcher(
        mapOf("normalize" to spec("normalize", listOfDouble, readonlyListOfDouble), "fail" to spec("fail", double))
    ) { contributionId, session ->
        val target = PluginTarget.of(PluginTargetKind.CONTRIBUTION, contributionId)
        SessionConnection(
            url = "ws://127.0.0.1:$port/ws/sessions/${target.kind.wire}/${target.id}/$session",
            protocol = "script-functions",
            versions = listOf(1),
            token = token,
            expiresAt = Long.MAX_VALUE
        )
    }

    @Test
    fun `a call returns a new list and leaves the argument as it was`() {
        dispatcher("run-token").use { dispatcher ->
            val values = ListImpl(listOf(1.0, 3.0))
            val normalized = dispatcher.call("normalize", arrayOf(values), null, javaClass.classLoader) as ListImpl<*>

            assertEquals(listOf(0.25, 0.75), normalized.heapSnapshot())
            assertEquals(listOf(1.0, 3.0), values.heapSnapshot())

            // A second call on the same session reuses what the service holds.
            values.add(4.0)
            val again = dispatcher.call("normalize", arrayOf(values), null, javaClass.classLoader) as ListImpl<*>
            assertEquals(listOf(0.125, 0.375, 0.5), again.heapSnapshot())
        }
    }

    @Test
    fun `a failing operation reaches the script as an error`() {
        dispatcher("run-token").use { dispatcher ->
            val error = assertFailsWith<ExternalCallException> { dispatcher.call("fail", arrayOf(), null, javaClass.classLoader) }
            assertTrue(error.message!!.contains("service says no"))
        }
    }

    @Test
    fun `a refused token fails the call`() {
        dispatcher("forged").use { dispatcher ->
            val error = assertFailsWith<ExternalCallException> {
                dispatcher.call("normalize", arrayOf(ListImpl(listOf(1.0))), null, javaClass.classLoader)
            }
            // The plugin's close code and reason are what tells the author why.
            assertTrue(error.message!!.contains("4401"), error.message)
            assertTrue(error.message!!.contains("Missing or invalid token"), error.message)
        }
    }
}
