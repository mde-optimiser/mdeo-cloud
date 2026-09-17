package com.mdeo.script.external

import com.mdeo.scriptfunctions.protocol.ClientMessage
import com.mdeo.scriptfunctions.protocol.ScriptFunctionsProtocol
import com.mdeo.scriptfunctions.protocol.ServiceMessage
import com.mdeo.scriptfunctions.service.ScriptFunctionCall
import com.mdeo.scriptfunctions.service.ScriptFunctionOperation
import com.mdeo.scriptfunctions.service.ScriptFunctionsServiceSession
import kotlinx.coroutines.runBlocking
import java.util.ArrayDeque

/**
 * The real `script-functions` service, in-process, for tests.
 *
 * Every byte still goes through the real encoding: the client's messages are decoded here from
 * CBOR, answered by [ScriptFunctionsServiceSession], and the answers encoded back.
 *
 * @param operations The operations this service answers, by name
 * @param rewriteAnswer Changes each answer before it is sent, to test what the client does with a
 *        service that breaks the contract
 */
class Loopback(
    operations: Map<String, (ScriptFunctionCall) -> Any?>,
    private val rewriteAnswer: (ServiceMessage) -> ServiceMessage = { it }
) : ScriptFunctionsTransport {

    companion object {
        /**
         * A loopback whose operations only look at their arguments.
         */
        fun ofArguments(operations: Map<String, (List<Any?>) -> Any?>) =
            Loopback(operations.mapValues { (_, op) -> { call: ScriptFunctionCall -> op(call.arguments) } })
    }

    /**
     * Every message the client sent, decoded, for tests to inspect.
     */
    val received = mutableListOf<ClientMessage>()

    /**
     * Every message this service answered with, for tests to inspect.
     */
    val answered = mutableListOf<ServiceMessage>()

    private val serviceOperations =
        operations.mapValues { (_, operation) -> ScriptFunctionOperation { call -> operation(call) } }

    /**
     * The service itself, for tests to inspect or to make it lose its state.
     */
    var service = ScriptFunctionsServiceSession(serviceOperations)
        private set
    private val outbox = ArrayDeque<ByteArray>()

    override var connection: Long = 1
        private set

    /**
     * When set, the connection drops just before the call with this number (counting from 1) is
     * handled, and a fresh service answers it, as after a reconnect inside a send.
     */
    var dropBeforeCall: Int? = null
    private var calls = 0

    override fun send(message: ByteArray) {
        val decoded = ScriptFunctionsProtocol.decodeClient(message)
        if (decoded is ClientMessage.Call && ++calls == dropBeforeCall) {
            service = ScriptFunctionsServiceSession(serviceOperations)
            connection++
        }
        received += decoded
        val answer = runBlocking { service.handle(decoded) }?.let(rewriteAnswer) ?: return
        answered += answer
        outbox.add(ScriptFunctionsProtocol.encodeService(answer))
    }

    override fun receive(): ByteArray = outbox.poll() ?: error("The loopback service has nothing to answer with")
}
