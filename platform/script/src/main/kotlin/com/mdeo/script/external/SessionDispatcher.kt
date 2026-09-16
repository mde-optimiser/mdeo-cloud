package com.mdeo.script.external

import com.mdeo.metamodel.Model
import com.mdeo.scriptfunctions.protocol.ScriptFunctionsProtocol
import com.mdeo.common.transport.SessionClient
import com.mdeo.common.transport.SessionConnection
import com.mdeo.script.compiler.ExternalCallSpec
import com.mdeo.script.runtime.ExternalCallDispatcher
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue

/**
 * Answers a program's external calls over platform sessions, one per contribution.
 *
 * A session to a contribution is opened the first time one of its functions is called, and kept
 * for the rest of the execution. Each session carries its own [ScriptFunctionsClient], so the
 * heap of one contribution's service never sees another's collections.
 *
 * When a connection drops, [SessionClient] brings the transport back; the service on the other
 * side has lost its copies by then, answers the next call with an unknown-object failure, and the
 * client sends that call again in full. Nothing has to be re-established by hand.
 *
 * @param specs The external calls of the compiled program, keyed by call id
 * @param resolve Resolves one contribution's session to something that can be dialled, with a
 *        fresh token each time it is asked
 */
class SessionDispatcher(
    private val specs: Map<String, ExternalCallSpec>,
    private val resolve: suspend (contribution: String, session: String) -> SessionConnection
) : ExternalCallDispatcher, AutoCloseable {

    private class Connection(val session: SessionClient, val client: ScriptFunctionsClient)

    private val connections = ConcurrentHashMap<String, Connection>()

    override fun call(callId: String, arguments: Array<Any?>, model: Model?): Any? {
        val spec = specs[callId] ?: throw ExternalCallException("No external call '$callId' was compiled")
        val session = spec.session ?: throw ExternalCallException(
            "External function '${spec.functionName}' cannot be called: contribution " +
                    "'${spec.contribution}' declares no '${ScriptFunctionsProtocol.NAME}' session"
        )
        val connection = connections.computeIfAbsent(spec.contribution) { open(it, session) }
        return connection.client.call(callId, arguments, model)
    }

    private fun open(contribution: String, sessionName: String): Connection {
        val inbox = LinkedBlockingQueue<Result<ByteArray>>()
        val session = SessionClient(
            resolve = { resolve(contribution, sessionName) },
            versions = listOf(ScriptFunctionsProtocol.VERSION),
            onMessage = { inbox.put(Result.success(it)) },
            onClosed = { reason -> inbox.put(Result.failure(ExternalCallException("Session to '$contribution' closed: $reason"))) }
        )
        try {
            runBlocking { session.open() }
        } catch (e: Exception) {
            session.close()
            throw ExternalCallException(
                "Could not open the '${ScriptFunctionsProtocol.NAME}' session of contribution " +
                        "'$contribution': ${e.message}"
            )
        }

        val transport = object : ScriptFunctionsTransport {
            override fun send(message: ByteArray) {
                // A drop reported between calls concerns no call still waiting; the send below
                // reconnects, so the report must not be mistaken for this call's answer.
                inbox.removeIf { it.isFailure }
                try {
                    runBlocking { session.send(message) }
                } catch (e: Exception) {
                    throw ExternalCallException("Session to '$contribution' failed: ${e.message}")
                }
            }
            override fun receive(): ByteArray = inbox.take().getOrThrow()
        }
        val ownSpecs = specs.filterValues { it.contribution == contribution }
        return Connection(session, ScriptFunctionsClient(transport, ownSpecs))
    }

    /**
     * Closes every session this dispatcher opened.
     */
    override fun close() {
        connections.values.forEach { runCatching { it.session.close() } }
        connections.clear()
    }
}
