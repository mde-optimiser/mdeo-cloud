package com.mdeo.scriptfunctions.protocol

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The scalar mapping both sides of the protocol share.
 */
class WireScalarsTest {

    @Test
    fun `every scalar survives the trip, keeping its number type`() {
        val values = listOf(null, true, 1, 2L, 3.5f, 4.5, "s")
        for (value in values) {
            val wire = WireScalars.encode(value)!!
            assertTrue(WireScalars.isScalar(wire))
            val decoded = WireScalars.decode(wire)
            assertEquals(value, decoded)
            assertEquals<Class<*>?>(value?.javaClass, decoded?.javaClass)
        }
    }

    @Test
    fun `anything else is not a scalar`() {
        assertNull(WireScalars.encode(listOf(1)))
        val enumEntry = WireValue.EnumValue("Style", "Modern")
        assertFalse(WireScalars.isScalar(enumEntry))
        assertFailsWith<IllegalArgumentException> { WireScalars.decode(enumEntry) }
    }

    @Test
    fun `an enum entry is encoded by enum and entry name`() {
        val result = ServiceMessage.Result(1, value = WireValue.EnumValue("Style", "Modern"))
        val bytes = ScriptFunctionsProtocol.encodeService(result)
        assertEquals(result, ScriptFunctionsProtocol.decodeService(bytes))
        // ["result", {"callId": 1, "value": ["enum", {"enumName": "Style", "entry": "Modern"}]}]
        val definite = "8266726573756c74a26663616c6c4964016576616c7565" +
            "8264656e756da268656e756d4e616d65655374796c6565656e747279664d6f6465726e"
        @OptIn(ExperimentalStdlibApi::class)
        assertEquals(result, ScriptFunctionsProtocol.decodeService(definite.hexToByteArray()))
    }
}
