package com.mdeo.execution.common.config

import com.mdeo.common.transport.MAX_SERVICE_WEBSOCKET_MESSAGE_BYTES
import com.mdeo.common.transport.installDeflate
import com.mdeo.execution.common.auth.WsTokenVerifier
import io.ktor.server.application.*
import io.ktor.server.websocket.*
import io.ktor.util.AttributeKey
import kotlin.time.Duration.Companion.seconds

/**
 * Holds the verifier the execution WebSocket endpoint authorizes individual requests with.
 *
 * Registered by [configureExecutionTransport]; its absence is what tells
 * `baseExecutionRoutes` that this service has not opted into the WebSocket endpoint.
 */
val ExecutionWsVerifierKey = AttributeKey<WsTokenVerifier>("ExecutionWsTokenVerifier")

/**
 * Enables the WebSocket transport for execution result access.
 *
 * Reading results over HTTP costs a request per file on every hop between the browser and
 * this service. This installs the WebSocket support that lets callers hold one connection
 * open across a burst of reads instead, and registers the verifier that authorizes each
 * request on it — the connection carries no credentials of its own.
 *
 * Services that already install [WebSockets] for their own use keep their configuration.
 *
 * @param backendUrl Base URL of the backend serving the JWKS used to verify tokens
 * @param issuer Expected token issuer
 */
fun Application.configureExecutionTransport(backendUrl: String, issuer: String) {
    if (pluginOrNull(WebSockets) == null) {
        install(WebSockets) {
            // Result files are sent whole in a single frame, and a large model can be tens of
            // megabytes, so the frame limit has to be well clear of any file a run produces.
            maxFrameSize = MAX_FRAME_SIZE_BYTES
            pingPeriod = 30.seconds
            timeout = 60.seconds
            extensions { installDeflate(MAX_FRAME_SIZE_BYTES) }
        }
    }
    attributes.put(ExecutionWsVerifierKey, WsTokenVerifier(backendUrl, issuer))
}

/**
 * Largest WebSocket frame accepted on the execution endpoint.
 */
const val MAX_FRAME_SIZE_BYTES: Long = MAX_SERVICE_WEBSOCKET_MESSAGE_BYTES
