package com.mdeo.script.language

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * A function calling a lambda parameter of a type no lambda expression before it has, end to end
 * from `language/lambda-parameter-call.fn`.
 */
class LambdaParameterCallTest : FrontendAstTest() {

    @Test
    fun `the functional interface exists when the call is compiled before any lambda of its type`() {
        assertEquals(6, run(compile("lambda-parameter-call"), "t"))
    }
}
