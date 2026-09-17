package com.mdeo.script.language

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Lambdas capture the outer variables they use in every kind of expression, end to end from
 * `language/lambda-captures.fn`.
 */
class LambdaCaptureTest : FrontendAstTest() {

    private val program = compile("lambda-captures")

    @Test
    fun `a lambda calls an outer lambda`() {
        assertEquals(12, run(program, "callsOuterLambda"))
    }

    @Test
    fun `a lambda asserts an outer variable is not null`() {
        assertEquals(6, run(program, "assertsOuterNonNull"))
    }

    @Test
    fun `a lambda checks the type of an outer variable`() {
        assertEquals(true, run(program, "checksOuterType"))
    }

    @Test
    fun `a lambda passed to a method calls an outer lambda`() {
        assertEquals(9.0, run(program, "nestedLambdaCallsOuterLambda"))
    }

    @Test
    fun `a lambda in the iterable of a for loop captures outer variables`() {
        assertEquals("123", run(program, "lambdaInForIterable"))
    }

    @Test
    fun `a lambda in the iterable of a nested for loop captures the outer loop variable`() {
        assertEquals(90, run(program, "lambdaInNestedForIterable"))
    }
}
