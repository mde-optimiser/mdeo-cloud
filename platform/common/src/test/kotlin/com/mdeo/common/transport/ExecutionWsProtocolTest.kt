package com.mdeo.common.transport

import com.mdeo.common.model.ApiError
import com.mdeo.common.model.ErrorCodes
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * The protocol is spoken by three implementations in two languages, and a message that fails
 * to decode is indistinguishable from a peer that never answered: the request simply hangs
 * until it times out. These pin the wire format both directions.
 */
class ExecutionWsProtocolTest {

    @Test
    fun `decodes the request the workbench sends, which omits every optional field`() {
        val fromBrowser = """
            {"messageType":"exec/files","requestId":"req-1","context":{"executionId":"e1","projectId":"p1"},"paths":null}
        """.trimIndent()

        val decoded = ExecutionWsProtocol.decode(fromBrowser)

        assertIs<ExecutionFilesWsRequest>(decoded)
        assertEquals("req-1", decoded.requestId)
        assertEquals("e1", decoded.context.executionId)
        assertEquals("p1", decoded.context.projectId)
        assertNull(decoded.context.auth)
        assertNull(decoded.paths)
    }

    @Test
    fun `decodes a request with no paths field at all`() {
        val decoded = ExecutionWsProtocol.decode(
            """{"messageType":"exec/files","requestId":"r","context":{"executionId":"e"}}"""
        )

        assertIs<ExecutionFilesWsRequest>(decoded)
        assertNull(decoded.paths)
    }

    @Test
    fun `encodes messages with the discriminator both other implementations switch on`() {
        val encoded = ExecutionWsProtocol.encode(
            ExecutionFileDataMessage("req-1", "results/a.model", "content")
        )

        val json = ExecutionWsProtocol.json.parseToJsonElement(encoded).jsonObject
        assertEquals("exec/fileData", json["messageType"]?.jsonPrimitive?.content)
        assertEquals("req-1", json["requestId"]?.jsonPrimitive?.content)
        assertEquals("results/a.model", json["path"]?.jsonPrimitive?.content)
    }

    @Test
    fun `round-trips every request type`() {
        val context = ExecutionWsContext("e1", auth = "token", projectId = "p1", languageId = "mdeo")
        val messages = listOf(
            ExecutionSummaryWsRequest("r", context),
            ExecutionFileTreeWsRequest("r", context),
            ExecutionFileWsRequest("r", context, "results/a.model"),
            ExecutionFilesWsRequest("r", context, listOf("results/a.model")),
            ExecutionCancelWsRequest("r", context),
            ExecutionDeleteWsRequest("r", context)
        )

        for (message in messages) {
            assertEquals(message, ExecutionWsProtocol.decode(ExecutionWsProtocol.encode(message)))
        }
    }

    @Test
    fun `round-trips responses including the empty payload`() {
        val error = ExecutionWsError("r", ApiError(ErrorCodes.NOT_FOUND, "gone"))
        assertEquals(error, ExecutionWsProtocol.decode(ExecutionWsProtocol.encode(error)))

        val empty = ExecutionWsResponse("r", null)
        assertEquals(empty, ExecutionWsProtocol.decode(ExecutionWsProtocol.encode(empty)))
    }
}
