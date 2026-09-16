package com.mdeo.pluginservice

import com.mdeo.common.transport.installDeflate
import com.mdeo.pluginservice.session.JwksSessionTokenVerifier
import com.mdeo.pluginservice.session.SessionTokenVerifier
import com.mdeo.pluginservice.session.sessionEndpoint
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import java.security.MessageDigest
import kotlin.time.Duration.Companion.seconds

/**
 * How often a keepalive is sent on a session. Sessions have no request timeout, so this is what
 * keeps a reverse proxy from dropping a connection that is busy computing.
 */
const val SESSION_PING_PERIOD_SECONDS = 30L

/**
 * How long a peer may go without answering a keepalive before its session is closed.
 */
const val SESSION_PONG_TIMEOUT_SECONDS = 90L

/**
 * Largest message accepted on a session.
 */
const val SESSION_MAX_FRAME_BYTES = 512L * 1024 * 1024

/**
 * Header every answer of a plugin service carries: a fingerprint of its manifest, by which the
 * backend notices that the plugin was redeployed with a changed manifest.
 */
const val MANIFEST_FINGERPRINT_HEADER = "X-Mdeo-Manifest-Fingerprint"

/**
 * Installs everything a plugin service serves: the manifest at `GET /` and the session endpoint.
 *
 * Use this to add a plugin service to an application you configure yourself, for example to add
 * routes of your own. [runPluginService] starts a server with nothing else.
 *
 * @param definition What the plugin offers
 * @param verifier Verifies session tokens; by default against the backend's published keys
 */
fun Application.pluginService(
    definition: PluginDefinition,
    verifier: SessionTokenVerifier
) {
    val manifest = definition.manifest().toString()
    val fingerprint = MessageDigest.getInstance("SHA-256").digest(manifest.toByteArray())
        .joinToString("") { "%02x".format(it) }

    installSessionWebSockets()

    // Sent with every answer, so the backend notices a redeployed plugin and fetches its manifest again.
    intercept(ApplicationCallPipeline.Plugins) {
        call.response.header(MANIFEST_FINGERPRINT_HEADER, fingerprint)
    }

    routing {
        get("/") {
            call.respondText(manifest, ContentType.Application.Json)
        }
        sessionEndpoint(definition.sessions, verifier)
    }
}

/**
 * Installs the WebSockets plugin with the platform's keepalive settings, unless it is installed
 * already.
 *
 * A peer that has not answered a ping within [SESSION_PONG_TIMEOUT_SECONDS] is disconnected.
 */
fun Application.installSessionWebSockets() {
    if (pluginOrNull(WebSockets) != null) return
    install(WebSockets) {
        pingPeriod = SESSION_PING_PERIOD_SECONDS.seconds
        timeout = SESSION_PONG_TIMEOUT_SECONDS.seconds
        maxFrameSize = SESSION_MAX_FRAME_BYTES
        masking = false
        extensions { installDeflate() }
    }
}

/**
 * Starts a plugin service.
 *
 * ```kotlin
 * fun main() {
 *     runPluginService(PluginDefinition(id = "routing-service", …, contributions = listOf(routing)))
 * }
 * ```
 *
 * @param definition What the plugin offers
 * @param config Where to listen and how to reach the backend; read from the environment by default
 * @param wait Whether to block until the server stops
 * @return The running server
 */
fun runPluginService(
    definition: PluginDefinition,
    config: PluginServiceConfig = PluginServiceConfig.fromEnvironment(),
    wait: Boolean = true
): EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> {
    val verifier = JwksSessionTokenVerifier(config.backendApiUrl, config.jwtIssuer)
    val server = embeddedServer(Netty, port = config.port, host = config.host) {
        pluginService(definition, verifier)
    }
    return server.start(wait = wait)
}
