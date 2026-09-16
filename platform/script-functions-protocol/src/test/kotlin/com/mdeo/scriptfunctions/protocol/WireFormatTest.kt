package com.mdeo.scriptfunctions.protocol

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Pins the bytes of the protocol, which services in other languages are written against. The
 * examples in the protocol documentation are these very messages.
 */
@OptIn(ExperimentalStdlibApi::class)
class WireFormatTest {

    @Test
    fun `a call is encoded as documented`() {
        val call = ClientMessage.Call(
            callId = 1,
            operation = "op",
            objects = listOf(HeapObject(1, HeapKind.LIST, 3, true, elements = listOf(WireValue.DoubleValue(2.0)))),
            args = listOf(WireValue.Ref(1), WireValue.Null)
        )
        assertEquals(
            "9f6463616c6cbf6663616c6c496401696f7065726174696f6e626f70676f626a656374739fbf62696401646b696e64646c69" +
                "73746776657273696f6e03676d757461626c65f568656c656d656e74739f9f66646f75626c65bf6576616c7565fb40000000" +
                "00000000ffffff67656e7472696573f6ffff64617267739f9f63726566bf62696401ffff9f646e756c6cbfffffff676d6f64" +
                "656c4964f6ffff",
            ScriptFunctionsProtocol.encodeClient(call).toHexString()
        )
    }

    @Test
    fun `definite lengths and omitted defaults are accepted`() {
        // ["result", {"callId": 1}]
        val bytes = "8266726573756c74a16663616c6c496401".hexToByteArray()
        assertEquals(ServiceMessage.Result(1), ScriptFunctionsProtocol.decodeService(bytes))
    }

    @Test
    fun `unknown fields are ignored`() {
        // ["failure", {"callId": 2, "message": "no", "hint": true}]
        val bytes = "82676661696c757265a36663616c6c49640267 6d657373616765626e6f6468696e74f5".replace(" ", "").hexToByteArray()
        assertEquals(ServiceMessage.Failure(2, "no"), ScriptFunctionsProtocol.decodeService(bytes))
    }
}
