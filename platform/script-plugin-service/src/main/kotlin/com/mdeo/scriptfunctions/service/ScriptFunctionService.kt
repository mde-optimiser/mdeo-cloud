package com.mdeo.scriptfunctions.service

import com.mdeo.pluginservice.session.SessionContext
import com.mdeo.pluginservice.session.SessionHandler
import com.mdeo.pluginservice.session.SessionPeer
import com.mdeo.scriptfunctions.protocol.ClientMessage
import com.mdeo.scriptfunctions.protocol.ScriptFunctionsProtocol
import com.mdeo.scriptfunctions.protocol.ServiceMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/**
 * The session handler answering the `script-functions` protocol.
 *
 * Each connection gets its own [ScriptFunctionsServiceSession], so collections never leak from one
 * execution to another. Calls on one connection are answered one at a time, in the order they
 * arrive, and operations run on [Dispatchers.Default].
 *
 * [scriptContribution] creates and registers this for you. Use it directly only when building a
 * contribution payload by hand.
 *
 * @param operations The operations to answer, by the name an external implementation declares
 */
class ScriptFunctionService(private val operations: Map<String, ScriptFunctionOperation>) : SessionHandler {

    override fun open(context: SessionContext): SessionPeer {
        val state = ScriptFunctionsServiceSession(operations, context)
        return object : SessionPeer {
            // Every call must be answered, or closed on: the execution waits for its answer with
            // nothing else to wake it.
            override suspend fun onMessage(data: ByteArray) {
                val message = try {
                    ScriptFunctionsProtocol.decodeClient(data)
                } catch (e: Exception) {
                    logger.error("Unreadable ${ScriptFunctionsProtocol.NAME} message", e)
                    context.close("Unreadable ${ScriptFunctionsProtocol.NAME} message")
                    return
                }
                val answer = try {
                    withContext(Dispatchers.Default) { state.handle(message) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error("Failed to handle a ${ScriptFunctionsProtocol.NAME} message", e)
                    if (message !is ClientMessage.Call) {
                        context.close("The service failed on a message")
                        return
                    }
                    ServiceMessage.Failure(message.callId, "The service failed on this call")
                }
                if (answer == null) return
                try {
                    context.send(ScriptFunctionsProtocol.encodeService(answer))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error("Could not send a ${ScriptFunctionsProtocol.NAME} answer", e)
                    context.close("The service could not send its answer")
                }
            }

            override fun onClose(reason: String) {
                state.clear()
            }
        }
    }
}

private val logger = LoggerFactory.getLogger(ScriptFunctionService::class.java)
