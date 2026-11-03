package com.mdeo.pluginservice

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PluginServiceConfigTest {
    @Test
    fun `unset variables fall back to the defaults`() {
        assertEquals(PluginServiceConfig(), PluginServiceConfig.fromEnvironment(emptyMap()))
    }

    @Test
    fun `numeric variables are read`() {
        val config = PluginServiceConfig.fromEnvironment(mapOf("PORT" to "4000", "MAX_SESSIONS" to " 8 "))
        assertEquals(4000, config.port)
        assertEquals(8, config.maxSessions)
    }

    @Test
    fun `an invalid numeric variable stops the service`() {
        for ((name, value) in listOf("PORT" to "abc", "PORT" to "-1", "MAX_SESSIONS" to "0", "MAX_SESSIONS" to "1.5")) {
            val error = assertFailsWith<IllegalArgumentException>("$name=$value") {
                PluginServiceConfig.fromEnvironment(mapOf(name to value))
            }
            assertEquals("$name must be a whole number of at least ${if (name == "PORT") 0 else 1}, but is '$value'", error.message)
        }
    }
}
