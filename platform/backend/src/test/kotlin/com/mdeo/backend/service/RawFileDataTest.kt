package com.mdeo.backend.service

import com.mdeo.common.model.FileDataResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class RawFileDataTest {

    @Test
    fun `the raw response has the shape of a file data response`() {
        val data = buildJsonObject { put("kind", "Script"); put("name", "a \"quoted\" name") }
        val parsed = Json.decodeFromString<FileDataResponse>(RawFileData(data.toString(), 7).toResponseJson())
        assertEquals(FileDataResponse(data, 7), parsed)

        val directory = Json.decodeFromString<FileDataResponse>(RawFileData("[1,2]", null).toResponseJson())
        assertEquals(null, directory.version)
        assertEquals(JsonPrimitive(2), (directory.data as kotlinx.serialization.json.JsonArray)[1])
    }
}
