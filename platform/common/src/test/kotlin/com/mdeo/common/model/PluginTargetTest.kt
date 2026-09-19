package com.mdeo.common.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * The address is written by one side and read by the other — a Kotlin execution node formats it
 * into a URL, a TypeScript service parses it back out of one — so what these check is that the
 * two halves cannot drift apart: every address this produces is one it also accepts, and the
 * shapes it refuses are refused rather than silently reinterpreted.
 */
class PluginTargetTest {

    @Test
    fun `formats both kinds with their wire prefix`() {
        assertEquals("lang:script", PluginTarget.of(PluginTargetKind.LANGUAGE, "script").toString())
        assertEquals("contrib:script-functions", PluginTarget.of(PluginTargetKind.CONTRIBUTION, "script-functions").toString())
    }

    @Test
    fun `parses back what it formatted`() {
        for (target in listOf(
            PluginTarget.of(PluginTargetKind.LANGUAGE, "config-mdeo"),
            PluginTarget.of(PluginTargetKind.CONTRIBUTION, "config-mdeo"),
            PluginTarget.of(PluginTargetKind.CONTRIBUTION, "a.b_c-1")
        )) {
            assertEquals(target, PluginTarget.parse(target.toString()))
        }
    }

    @Test
    fun `tells the two kinds apart for one shared id`() {
        val language = PluginTarget.parse("lang:config-mdeo")
        val contribution = PluginTarget.parse("contrib:config-mdeo")

        assertEquals(PluginTargetKind.LANGUAGE, language.kind)
        assertEquals(PluginTargetKind.CONTRIBUTION, contribution.kind)
        assertEquals(language.id, contribution.id)
    }

    @Test
    fun `refuses addresses that are not addresses`() {
        for (address in listOf("script", "other:script", "lang:", "lang:/etc/passwd", "lang:a b", "lang:-leading")) {
            assertNull(PluginTarget.parseOrNull(address), "expected '$address' to be refused")
            assertFailsWith<IllegalArgumentException> { PluginTarget.parse(address) }
        }
    }

    @Test
    fun `refuses an id carrying a separator of its own`() {
        assertNull(PluginTarget.parseOrNull("lang:a:b"))
    }

    @Test
    fun `builds a target from its parts as they arrive in separate segments`() {
        assertEquals(PluginTarget(PluginTargetKind.CONTRIBUTION, "geo"), PluginTarget.ofOrNull("contrib", "geo"))
        assertNull(PluginTarget.ofOrNull("other", "geo"))
        assertNull(PluginTarget.ofOrNull("lang", "a:b"))
        assertNull(PluginTarget.ofOrNull(PluginTargetKind.LANGUAGE, ""))
    }

    @Test
    fun `refuses an unusable id at construction`() {
        assertFailsWith<IllegalArgumentException> { PluginTarget.of(PluginTargetKind.LANGUAGE, "with space") }
    }
}
