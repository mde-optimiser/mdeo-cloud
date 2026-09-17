package com.mdeo.script.language

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Named arguments and default values, end to end from `language/named-arguments.fn`.
 */
class NamedArgumentsTest : FrontendAstTest() {

    private val program = compile("named-arguments")

    @Test
    fun `arguments are passed by name and missing ones take their defaults`() {
        assertEquals("x1:2|y1:2|z2:3|x4:5", run(program, "calls"))
    }

    @Test
    fun `arguments are evaluated in the order they are written`() {
        assertEquals("c01:7,c,b,a", run(program, "order"))
    }

    @Test
    fun `a default lambda sees the parameters before it`() {
        assertEquals(904, run(program, "lambdas"))
    }

    @Test
    fun `defaults work with two-slot and nullable parameters`() {
        assertEquals(50506.5, run(program, "wides"))
    }

    @Test
    fun `a method takes named arguments`() {
        assertEquals(1, run(program, "members"))
    }
}
