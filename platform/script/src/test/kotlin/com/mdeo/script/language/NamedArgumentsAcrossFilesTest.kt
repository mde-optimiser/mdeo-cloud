package com.mdeo.script.language

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Named arguments and default values beyond single-file functions, end to end from
 * `language/named-arguments-imports.fn`, which imports from `language/named-arguments-lib.fn`
 * saved as `/lib.fn`.
 */
class NamedArgumentsAcrossFilesTest : FrontendAstTest() {

    private val program = compile(mapOf(SCRIPT_PATH to "named-arguments-imports", "/lib.fn" to "named-arguments-lib"))

    @Test
    fun `an imported and renamed function takes named arguments and its defaults`() {
        assertEquals("Hello, Ada! Hello, Bob? Hi, Cy!", run(program, "imported"))
    }

    @Test
    fun `the defaults of an imported function see its earlier parameters`() {
        assertEquals(
            "Money(amount=90, currency=EUR) Money(amount=45, currency=USD) Money(amount=20, currency=EUR)",
            run(program, "importedDefaultsSeeParameters")
        )
    }

    @Test
    fun `argument names choose between the overloads of a method`() {
        assertEquals("ef|bc|cd", run(program, "overloadsByName"))
    }

    @Test
    fun `a lambda passed by name to a method is inferred from its parameter`() {
        assertEquals(true, run(program, "namedLambdaArgument"))
        assertEquals("1USD2EUR3EUR", run(program, "namedLambdaOnRecords"))
    }

    @Test
    fun `a default value is evaluated only when its parameter is left out`() {
        assertEquals("aBab:3", run(program, "defaultsOnlyRunWhenLeftOut"))
    }

    @Test
    fun `a recursive call passes an argument by name`() {
        assertEquals(55, run(program, "recursion"))
    }

    @Test
    fun `wide and nullable arguments are passed out of order`() {
        assertEquals("3.0/2/null 1.5/1/4.0 1.0/1/null", run(program, "wideAndNullableOutOfOrder"))
    }

    @Test
    fun `null is passed by name to a parameter with a default`() {
        assertEquals("none1none2p0", run(program, "nulls"))
    }

    @Test
    fun `a lambda body calls with named arguments and captured values`() {
        assertEquals("Hello, Eve!", run(program, "insideLambda"))
    }

    @Test
    fun `a default lambda calls the lambda parameter before it`() {
        assertEquals(309, run(program, "defaultLambdaUsesEarlierLambda"))
    }

    @Test
    fun `parameters between given ones take their defaults`() {
        assertEquals("1,20,30,4;1,20,3,40;1,2,3,4", run(program, "skipsMiddleParameters"))
    }

    @Test
    fun `named arguments are evaluated in the order they are written, not the parameter order`() {
        assertEquals("dacc:1,20,2,1", run(program, "argumentOrder"))
    }
}
