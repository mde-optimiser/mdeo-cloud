package com.mdeo.common.transport

import com.mdeo.common.model.ErrorCodes
import io.ktor.http.HttpStatusCode
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

    @Test
    fun `an upstream failure is answered with its gateway status`() {
        assertEquals(HttpStatusCode.GatewayTimeout, failureStatusFor(ErrorCodes.DEADLINE_EXCEEDED))
        assertEquals(HttpStatusCode.ServiceUnavailable, failureStatusFor(ErrorCodes.UNAVAILABLE))
        assertEquals(HttpStatusCode.BadRequest, failureStatusFor(ErrorCodes.PLUGIN_NOT_FOUND))
    }
}
