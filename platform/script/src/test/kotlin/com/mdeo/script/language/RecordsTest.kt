package com.mdeo.script.language

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Records and `val`, end to end from `language/records.fn`, which imports a record from
 * `language/records-geo.fn` saved as `/geo.fn`.
 */
class RecordsTest : FrontendAstTest() {

    private val program = compile(mapOf(SCRIPT_PATH to "records", "/geo.fn" to "records-geo"))

    @Test
    fun `a record is constructed with its defaults and prints its fields`() {
        assertEquals("Point(x=1.0, y=1.0, label=)", run(program, "construct"))
    }

    @Test
    fun `fields are read and written, also through nested records`() {
        assertEquals(36.0, run(program, "fields"))
    }

    @Test
    fun `with copies a record and changes the fields it is passed`() {
        assertEquals("Point(x=1.0, y=2.0, label=a) Point(x=5.0, y=2.0, label=b)", run(program, "copies"))
    }

    @Test
    fun `records are equal by content and identical only to themselves`() {
        assertEquals("truefalsefalse", run(program, "equality"))
    }

    @Test
    fun `equal records are one element of a set`() {
        assertEquals(2, run(program, "sets"))
    }

    @Test
    fun `a copy shares the collections of the original`() {
        assertEquals(1, run(program, "sharedTags"))
    }

    @Test
    fun `primitive, long, nullable and lambda fields keep their values`() {
        assertEquals("5,3,0.5,null", run(program, "counters"))
    }

    @Test
    fun `a val is assigned once on each branch`() {
        assertEquals(1, run(program, "vals", true))
        assertEquals(2, run(program, "vals", false))
    }

    @Test
    fun `null-safe access works on records`() {
        assertEquals(2.0, run(program, "nullable"))
    }

    @Test
    fun `type checks tell records apart`() {
        assertEquals(true, run(program, "typeCheck"))
    }

    @Test
    fun `records are cast like other classes`() {
        assertEquals(4.5, run(program, "casts"))
    }
}
