package com.mdeo.backend.service

import com.mdeo.backend.config.FileDataConfig
import com.mdeo.backend.config.TimeoutConfig
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class TimeoutConfigTest {

    @Test
    fun `defaults are the timeouts the backend used before they were configurable`() {
        val timeouts = TimeoutConfig.load(emptyMap())
        assertEquals(TimeoutConfig(300, 300, 60, 30, 10), timeouts)
    }

    @Test
    fun `every timeout can be set, and nonsense falls back to the default`() {
        val timeouts = TimeoutConfig.load(
            mapOf(
                "PLUGIN_REQUEST_TIMEOUT_SECONDS" to "900",
                "EXECUTION_START_TIMEOUT_SECONDS" to "600",
                "EXECUTION_READ_TIMEOUT_SECONDS" to "120",
                "PLUGIN_MANIFEST_TIMEOUT_SECONDS" to "0",
                "SERVICE_CONNECT_TIMEOUT_SECONDS" to "five"
            )
        )
        assertEquals(TimeoutConfig(900, 600, 120, 30, 10), timeouts)
    }

    @Test
    fun `the computation binding follows the computation timeout unless set on its own`() {
        assertEquals(FileDataConfig(300, 300), FileDataConfig.load(emptyMap()))
        assertEquals(FileDataConfig(120, 120), FileDataConfig.load(mapOf("FILE_DATA_COMPUTATION_TIMEOUT_SECONDS" to "120")))
        assertEquals(
            FileDataConfig(120, 900),
            FileDataConfig.load(mapOf("FILE_DATA_COMPUTATION_TIMEOUT_SECONDS" to "120", "FILE_DATA_COMPUTATION_BINDING_SECONDS" to "900"))
        )
    }
}
