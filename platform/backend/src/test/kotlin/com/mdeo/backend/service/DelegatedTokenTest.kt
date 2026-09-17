package com.mdeo.backend.service

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A plugin request passes the caller's token on only when the caller delegates its work.
 */
class DelegatedTokenTest {

    @Test
    fun `a request that does not delegate forwards no token`() {
        assertNull(LanguagePluginRequestService.delegatedToken("Bearer run-token", null))
        assertNull(LanguagePluginRequestService.delegatedToken("Bearer run-token", "false"))
        assertNull(LanguagePluginRequestService.delegatedToken("Bearer run-token", "yes"))
    }

    @Test
    fun `a delegating request forwards the bearer token`() {
        assertEquals("run-token", LanguagePluginRequestService.delegatedToken("Bearer run-token", "true"))
    }

    @Test
    fun `a delegating request without a bearer token forwards none`() {
        assertNull(LanguagePluginRequestService.delegatedToken(null, "true"))
        assertNull(LanguagePluginRequestService.delegatedToken("Basic abc", "true"))
    }
}
