package com.mdeo.pluginservice.session

import com.mdeo.common.model.PluginTarget

/**
 * Close codes the platform itself uses on a session.
 *
 * These are the refusals that happen before any plugin code runs. Everything a plugin closes a
 * session with is its own business. The TypeScript services refuse with the same codes, and add
 * `4408` for a peer that stopped answering keepalives; here that is Ktor's own pong timeout, which
 * closes with a Ktor code.
 */
object SessionCloseCodes {
    /**
     * The token was missing, malformed, expired, or not for this session.
     */
    const val UNAUTHORIZED: Short = 4401

    /**
     * This service serves no such target or session.
     */
    const val NOT_FOUND: Short = 4404

    /**
     * No protocol version both sides can speak.
     */
    const val VERSION_MISMATCH: Short = 4409

    /**
     * The service could not open another session.
     */
    const val UNAVAILABLE: Short = 4503
}

/**
 * What a handler is given when a session opens.
 *
 * The platform owns everything here: who is calling, what was negotiated, and how to write to and
 * end the connection. What is written is a protocol the plugin defines. The platform imposes no
 * envelope, correlation scheme or error shape on it.
 */
interface SessionContext {
    /**
     * The project the session belongs to.
     */
    val projectId: String

    /**
     * The execution the session belongs to. A session never outlives its execution.
     */
    val executionId: String

    /**
     * The addressed target.
     */
    val target: PluginTarget

    /**
     * The session name, the last segment of the address.
     */
    val sessionName: String

    /**
     * The protocol version both sides agreed on.
     */
    val version: Int

    /**
     * The token the session was opened with.
     */
    val token: String

    /**
     * Sends one message to the caller.
     *
     * @param data The encoded message
     */
    suspend fun send(data: ByteArray)

    /**
     * Ends the session.
     *
     * @param reason Why, reported to the caller
     */
    suspend fun close(reason: String = "Closed by handler")
}

/**
 * The plugin's side of one open session.
 */
interface SessionPeer {
    /**
     * Handles one message from the caller.
     *
     * Messages are handed over one at a time, in the order they arrived: the next is not
     * delivered until this returns. A handler that throws has the error logged; the session
     * stays open.
     *
     * @param data The received bytes
     */
    suspend fun onMessage(data: ByteArray)

    /**
     * Handles the session ending, from either side. Release whatever the session held here.
     *
     * @param reason Why the session ended
     */
    fun onClose(reason: String) {}
}

/**
 * Serves one declared session type.
 */
fun interface SessionHandler {
    /**
     * Starts one session.
     *
     * @param context Everything the platform knows about this connection
     * @return The peer that handles its traffic
     */
    fun open(context: SessionContext): SessionPeer
}
