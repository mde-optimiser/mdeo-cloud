package com.mdeo.script.language

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * `val` and definite initialization at runtime, end to end from `language/vals.fn`.
 */
class ValTest : FrontendAstTest() {

    private val program = compile("vals")

    @Test
    fun `a val is assigned later on every branch of an else-if chain`() {
        assertEquals("negative", run(program, "lateOnEveryBranch", -1))
        assertEquals("zero", run(program, "lateOnEveryBranch", 0))
        assertEquals("positive", run(program, "lateOnEveryBranch", 5))
    }

    @Test
    fun `a branch that returns need not assign the val`() {
        assertEquals(-1, run(program, "lateWithReturn", 11))
        assertEquals(8, run(program, "lateWithReturn", 4))
    }

    @Test
    fun `branches that break, continue or return need not assign the val`() {
        assertEquals("non-negative4", run(program, "readAfterJumpingBranches", 2))
        assertEquals("near", run(program, "readAfterJumpingBranches", -5))
        assertEquals("far", run(program, "readAfterJumpingBranches", -20))
    }

    @Test
    fun `a lambda captures a val`() {
        assertEquals(42, run(program, "capturedByLambda"))
    }

    @Test
    fun `a val declared in a loop body is a new variable in every iteration`() {
        assertEquals(60, run(program, "freshInEveryIteration"))
        assertEquals(48, run(program, "lateInLoopBody"))
    }

    @Test
    fun `late long and double vals keep their values`() {
        assertEquals(5.0E9, run(program, "wideLateValues", true))
        assertEquals(2.0, run(program, "wideLateValues", false))
    }

    @Test
    fun `a var initialized before a loop is reassigned inside it`() {
        assertEquals(154, run(program, "varReassignedInLoop"))
    }

    @Test
    fun `nullable numbers are concatenated to strings`() {
        assertEquals("1.5null3", run(program, "nullableConcat"))
    }
}
