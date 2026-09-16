package com.mdeo.script.external

import com.mdeo.scriptfunctions.protocol.ClientMessage
import com.mdeo.scriptfunctions.protocol.ScriptFunctionsProtocol
import com.mdeo.scriptfunctions.protocol.ServiceMessage
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
 * @param claimEverythingMutable Whether to tell the service every collection was sent as mutable,
 *        so that it lets an operation change a readonly argument. Tests use this to check that the
 *        client rejects such a result on its own.
 */
class Loopback(
    operations: Map<String, (List<Any?>) -> Any?>,
    private val claimEverythingMutable: Boolean = false
) : ScriptFunctionsTransport {

    /**
     * Every message the client sent, decoded, for tests to inspect.
     */
    val received = mutableListOf<ClientMessage>()

    /**
     * Every message this service answered with, for tests to inspect.
     */
    val answered = mutableListOf<ServiceMessage>()

    private val service = ScriptFunctionsServiceSession(
        operations.mapValues { (_, operation) -> ScriptFunctionOperation { call -> operation(call.arguments) } }
    )
    private val outbox = ArrayDeque<ByteArray>()

    override fun send(message: ByteArray) {
        val decoded = ScriptFunctionsProtocol.decodeClient(message)
        received += decoded
        val handed = if (claimEverythingMutable && decoded is ClientMessage.Call) {
            decoded.copy(objects = decoded.objects.map { it.copy(mutable = true) })
        } else {
            decoded
        }
        val answer = runBlocking { service.handle(handed) } ?: return
        answered += answer
        outbox.add(ScriptFunctionsProtocol.encodeService(answer))
    }

    override fun receive(): ByteArray = outbox.poll() ?: error("The loopback service has nothing to answer with")
}
