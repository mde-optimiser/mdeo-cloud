package com.mdeo.common.transport

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ErrorResponsesTest {

    @Test
    fun `a reported message is passed on`() {
        assertEquals(
            "status 400: File has errors",
            describeErrorResponse(400, """{"error":{"code":"BadRequest","message":"File has errors"}}""")
        )
    }

    @Test
    fun `a body that is not in the error shape is not passed on`() {
        assertEquals("status 500", describeErrorResponse(500, "java.lang.IllegalStateException at db-host:5432"))
        assertEquals("status 502", describeErrorResponse(502, """{"error":"text"}"""))
        assertEquals("status 404", describeErrorResponse(404, ""))
    }
}
