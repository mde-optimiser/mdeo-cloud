package com.mdeo.backend.service

import com.mdeo.backend.config.*
import com.mdeo.common.auth.Scopes
import com.mdeo.common.model.PluginTarget
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.util.*
import kotlin.test.assertEquals

class TokenScopesTest {

    private val config = AppConfig(
        serverPort = 0,
        database = DatabaseConfig("", "", "", 1),
        session = SessionConfig(0, 0, false, "", ""),
        cors = CorsConfig(emptyList()),
        defaultAdmin = DefaultAdminConfig("", ""),
        defaultNewUserCanCreateProject = false,
        plugin = PluginConfig("", "", false),
        jwt = JwtConfig(expirationSeconds = 60, executionExpirationSeconds = 60, issuer = "test"),
        fileData = FileDataConfig(60),
        timeouts = TimeoutConfig.load(emptyMap())
    )

    private val jwtService = JwtService(
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(InjectedServices::class.java)) { _, method, _ ->
            if (method.name == "getConfig") config else error("${method.name} is not needed here")
        } as InjectedServices
    ).also { it.init() }

    private val project = UUID.randomUUID()
    private val execution = UUID.randomUUID()

    private fun scopesOf(token: String): Set<String> =
        jwtService.getVerifier().verify(token).getClaim(JwtService.CLAIM_SCOPE).asList(String::class.java).toSet()

    @Test
    fun `a plugin request may read the project and pass the request on`() {
        assertEquals(
            setOf(Scopes.PLUGIN_REQUEST_SEND, Scopes.FILES_READ, Scopes.FILE_DATA_READ),
            scopesOf(jwtService.generatePluginRequestToken(project))
        )
    }

    @Test
    fun `a plugin request made while computing file data stays bound to that computation`() {
        val computation = UUID.randomUUID()
        val token = jwtService.getVerifier().verify(jwtService.generatePluginRequestToken(project, computation))
        assertEquals(computation.toString(), token.getClaim(JwtService.CLAIM_COMPUTATION_ID).asString())
        assertEquals(JwtService.BINDING_FILE_DATA_COMPUTATION, token.getClaim(JwtService.CLAIM_BINDING).asString())
        assertEquals(setOf(Scopes.PLUGIN_REQUEST_SEND, Scopes.FILES_READ, Scopes.FILE_DATA_READ), scopesOf(token.token))
    }

    @Test
    fun `a file data computation may compute, read the project and ask other plugins`() {
        assertEquals(
            setOf(Scopes.PLUGIN_FILE_DATA_COMPUTE, Scopes.FILES_READ, Scopes.FILE_DATA_READ, Scopes.PLUGIN_REQUEST_SEND),
            scopesOf(jwtService.generateFileDataComputationToken(project, UUID.randomUUID()))
        )
    }

    @Test
    fun `only the run token starts an execution, reports its state and opens sessions`() {
        assertEquals(
            setOf(
                Scopes.PLUGIN_EXECUTION_START,
                Scopes.FILES_READ,
                Scopes.FILE_DATA_READ,
                Scopes.PLUGIN_REQUEST_SEND,
                Scopes.SESSION_OPEN,
                Scopes.EXECUTION_WRITE
            ),
            scopesOf(jwtService.generateExecutionRunToken(project, execution, 60))
        )
    }

    @Test
    fun `a call about an execution carries the one scope it needs`() {
        for (scope in listOf(Scopes.PLUGIN_EXECUTION_READ, Scopes.PLUGIN_EXECUTION_CANCEL, Scopes.PLUGIN_EXECUTION_DELETE)) {
            assertEquals(
                setOf(scope, Scopes.FILES_READ, Scopes.FILE_DATA_READ, Scopes.PLUGIN_REQUEST_SEND),
                scopesOf(jwtService.generatePluginExecutionToken(project, execution, scope))
            )
        }
    }

    @Test
    fun `a session token connects, and only a language session reads its contributions`() {
        assertEquals(
            setOf(Scopes.PLUGIN_SESSION_CONNECT),
            scopesOf(jwtService.generateSessionConnectToken(project, execution, PluginTarget.parse("contrib:stats"), "functions"))
        )
        assertEquals(
            setOf(Scopes.PLUGIN_SESSION_CONNECT, Scopes.SESSION_CONTRIBUTIONS_READ),
            scopesOf(jwtService.generateSessionConnectToken(project, execution, PluginTarget.parse("lang:script"), "functions"))
        )
    }
}
