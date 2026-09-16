package com.mdeo.scriptfunctions.service

import com.mdeo.pluginservice.session.SessionContext
import com.mdeo.pluginservice.session.SessionHandler
import com.mdeo.pluginservice.session.SessionPeer
import com.mdeo.scriptfunctions.protocol.ScriptFunctionsProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
            override suspend fun onMessage(data: ByteArray) {
                val answer = withContext(Dispatchers.Default) {
                    state.handle(ScriptFunctionsProtocol.decodeClient(data))
                }
                if (answer != null) {
                    context.send(ScriptFunctionsProtocol.encodeService(answer))
                }
            }

            override fun onClose(reason: String) {
                state.clear()
            }
        }
    }
}
