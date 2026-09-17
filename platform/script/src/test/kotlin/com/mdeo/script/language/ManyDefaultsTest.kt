package com.mdeo.script.language

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * A function and a record with 31 parameters that all have default values, the most the compiler
 * supports, end to end from `language/many-defaults.fn`. Parameter `pN` defaults to `N`.
 */
class ManyDefaultsTest : FrontendAstTest() {

    private val program = compile("many-defaults")

    @Test
    fun `every parameter takes its default`() {
        assertEquals((0..30).sum(), run(program, "allDefaults"))
    }

    @Test
    fun `the last parameter is given by name`() {
        assertEquals((0..29).sum() + 1000, run(program, "lastGiven"))
    }

    @Test
    fun `the first parameter is given by position and a late one by name`() {
        assertEquals((0..30).sum() - 1000 - 29, run(program, "firstGiven"))
    }

    @Test
    fun `a record with 31 defaulted fields is constructed and copied`() {
        assertEquals("99,7,99,0", run(program, "wideRecord"))
    }
}
