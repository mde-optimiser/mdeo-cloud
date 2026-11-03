package com.mdeo.script.compiler

import com.mdeo.expression.ast.expressions.TypedCallArgument
import com.mdeo.expression.ast.expressions.TypedExpression
import com.mdeo.expression.ast.types.ClassTypeRef
import com.mdeo.script.ast.TypedAst
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for calls that pass arguments by name and leave out parameters with default values.
 *
 * The script service binds every argument to a parameter: a [TypedCallArgument] names its
 * parameter when that differs from its position, and parameters without an argument take their
 * default value. The compiler evaluates the arguments in the order they are written, and a
 * function with default values gets a companion method that evaluates the defaults of the
 * parameters a call leaves out.
 */
class NamedArgumentCompilerTest {

    private val helper = CompilerTestHelper()

    private val string = ClassTypeRef("builtin", "string", false)

    /**
     * ```
     * fun f(a: int, b: int = a + 1, c: string = "x"): string {
     *     return c + a + ":" + b
     * }
     * ```
     */
    private fun TypedAstBuilder.defineF() {
        val int = intType()
        val str = stringType()
        function(
            name = "f",
            returnType = str,
            parameters = listOf(
                param("a", int),
                param("b", int, binaryExpr(identifier("a", int, 2), "+", intLiteral(1, int), int)),
                param("c", str, stringLiteral("x", str))
            ),
            body = listOf(
                returnStmt(
                    concat(str, identifier("c", str, 2), identifier("a", int, 2), stringLiteral(":", str), identifier("b", int, 2))
                )
            )
        )
    }

    /**
     * ```
     * fun note(log: List<string>, entry: string): string {
     *     log.add(entry)
     *     return entry
     * }
     * ```
     */
    private fun TypedAstBuilder.defineNote() {
        val str = stringType()
        val list = collectionType("List", string)
        function(
            name = "note",
            returnType = str,
            parameters = listOf(param("log", list), param("entry", str)),
            body = listOf(
                exprStmt(memberCall(identifier("log", list, 2), "add", "", listOf(identifier("entry", str, 2)), resultTypeIndex = booleanType())),
                returnStmt(identifier("entry", str, 2))
            )
        )
    }

    private fun TypedAstBuilder.note(entry: String, logScope: Int = 3): TypedExpression =
        functionCall("note", "", listOf(identifier("log", collectionType("List", string), logScope), stringLiteral(entry, stringType())), stringType())

    /**
     * `log.concat(",")`, the entries of `log` in the order they were added.
     */
    private fun TypedAstBuilder.joinedLog(): TypedExpression =
        memberCall(identifier("log", collectionType("List", string), 3), "concat", "sep", listOf(stringLiteral(",", stringType())), resultTypeIndex = stringType())

    private fun callF(arguments: TypedAstBuilder.() -> List<TypedCallArgument>): Any? {
        val ast = buildTypedAst {
            defineF()
            val str = stringType()
            function("testFunction", str, body = listOf(returnStmt(functionCallWithArgs("f", "", arguments(), str))))
        }
        return helper.compileAndInvoke(ast)
    }

    @Test
    fun `a call leaves out parameters and they take their defaults`() {
        // f(1)
        assertEquals("x1:2", callF { listOf(arg(intLiteral(1, intType()))) })
    }

    @Test
    fun `a default refers to the parameters before it`() {
        // f(4, c = "y")
        assertEquals("y4:5", callF { listOf(arg(intLiteral(4, intType())), named(stringLiteral("y", stringType()), 2)) })
    }

    @Test
    fun `every argument is passed by name, in any order`() {
        // f(c = "z", a = 2, b = 3)
        assertEquals("z2:3", callF {
            listOf(named(stringLiteral("z", stringType()), 2), named(intLiteral(2, intType()), 0), named(intLiteral(3, intType()), 1))
        })
    }

    @Test
    fun `a parameter between given ones takes its default`() {
        // f(1, c = "m")
        assertEquals("m1:2", callF { listOf(arg(intLiteral(1, intType())), named(stringLiteral("m", stringType()), 2)) })
        // f(b = 5, a = 4)
        assertEquals("x4:5", callF { listOf(named(intLiteral(5, intType()), 1), named(intLiteral(4, intType()), 0)) })
    }

    /**
     * ```
     * fun testFunction(): string {
     *     val log = emptyList<string>()
     *     val result = f(c = note(log, "c"), b = note(log, "b").length(), a = note(log, "a").length())
     *     return result + " " + log.concat(",")
     * }
     * ```
     */
    @Test
    fun `named arguments are evaluated in the order they are written`() {
        val ast = buildTypedAst {
            defineF()
            defineNote()
            val str = stringType()
            val int = intType()
            val list = collectionType("List", string)
            fun length(expression: TypedExpression) = memberCall(expression, "length", "", emptyList(), resultTypeIndex = int)
            function(
                name = "testFunction",
                returnType = str,
                body = listOf(
                    varDecl("log", list, functionCall("emptyList", "", emptyList(), list)),
                    varDecl(
                        "result", str,
                        functionCallWithArgs(
                            "f", "",
                            listOf(named(note("c"), 2), named(length(note("b")), 1), named(length(note("a")), 0)),
                            str
                        )
                    ),
                    returnStmt(concat(str, identifier("result", str, 3), stringLiteral(" ", str), joinedLog()))
                )
            )
        }
        assertEquals("c1:1 c,b,a", helper.compileAndInvoke(ast))
    }

    /**
     * ```
     * fun counted(log: List<string>, a: string = note(log, "a"), b: string = note(log, "b")): string {
     *     return a + b
     * }
     *
     * fun testFunction(): string {
     *     val log = emptyList<string>()
     *     return counted(log, b = "B") + counted(log) + " " + log.concat(",")
     * }
     * ```
     */
    @Test
    fun `a default value is evaluated only when its parameter is left out`() {
        val ast = buildTypedAst {
            defineNote()
            val str = stringType()
            val list = collectionType("List", string)
            function(
                name = "counted",
                returnType = str,
                parameters = listOf(param("log", list), param("a", str, note("a", logScope = 2)), param("b", str, note("b", logScope = 2))),
                body = listOf(returnStmt(concat(str, identifier("a", str, 2), identifier("b", str, 2))))
            )
            function(
                name = "testFunction",
                returnType = str,
                body = listOf(
                    varDecl("log", list, functionCall("emptyList", "", emptyList(), list)),
                    returnStmt(
                        concat(
                            str,
                            functionCallWithArgs("counted", "", listOf(arg(identifier("log", list, 3)), named(stringLiteral("B", str), 2)), str),
                            functionCall("counted", "", listOf(identifier("log", list, 3)), str),
                            stringLiteral(" ", str),
                            joinedLog()
                        )
                    )
                )
            )
        }
        assertEquals("aBab a,a,b", helper.compileAndInvoke(ast))
    }

    /**
     * `/lib.fn`:
     * ```
     * fun price(amount: long, currency: string = "EUR", discount: long = amount / 10L): string {
     *     return currency + (amount - discount)
     * }
     * ```
     *
     * `/main.fn`:
     * ```
     * import { price as cost } from "./lib.fn"
     *
     * fun testFunction(): string {
     *     return cost(100L) + " " + cost(currency = "USD", amount = 50L) + " " + cost(20L, discount = 0L)
     * }
     * ```
     */
    @Test
    fun `an imported and renamed function takes named arguments and evaluates its own defaults`() {
        val lib = buildTypedAst {
            val str = stringType()
            val long = longType()
            function(
                name = "price",
                returnType = str,
                parameters = listOf(
                    param("amount", long),
                    param("currency", str, stringLiteral("EUR", str)),
                    param("discount", long, binaryExpr(identifier("amount", long, 2), "/", longLiteral(10L, long), long))
                ),
                body = listOf(
                    returnStmt(
                        concat(str, identifier("currency", str, 2), binaryExpr(identifier("amount", long, 2), "-", identifier("discount", long, 2), long))
                    )
                )
            )
        }
        val main = buildTypedAst {
            val str = stringType()
            val long = longType()
            import("cost", "price", "/lib.fn")
            function(
                name = "testFunction",
                returnType = str,
                body = listOf(
                    returnStmt(
                        concat(
                            str,
                            functionCall("cost", "", listOf(longLiteral(100L, long)), str),
                            stringLiteral(" ", str),
                            functionCallWithArgs("cost", "", listOf(named(stringLiteral("USD", str), 1), named(longLiteral(50L, long), 0)), str),
                            stringLiteral(" ", str),
                            functionCallWithArgs("cost", "", listOf(arg(longLiteral(20L, long)), named(longLiteral(0L, long), 2)), str)
                        )
                    )
                )
            )
        }
        val program = helper.compileFiles(mapOf("/lib.fn" to lib, "/main.fn" to main))
        assertEquals("EUR90 USD45 EUR20", helper.invoke(program, "testFunction", "/main.fn"))
    }

    /**
     * ```
     * fun widen(a: double, b: long = 1L, c: double? = null, d: int = 7): string {
     *     return "" + a + "/" + b + "/" + c + "/" + d
     * }
     *
     * fun testFunction(): string {
     *     return widen(d = 9, c = 4.5, b = 2L, a = 3.0) + " " + widen(c = 4.0, a = 1.5)
     * }
     * ```
     *
     * Arguments passed out of order are kept in temporary slots, which two-slot and boxed values
     * have to fit.
     */
    @Test
    fun `two-slot and nullable arguments are passed out of order`() {
        val ast = buildTypedAst {
            val str = stringType()
            val double = doubleType()
            val nullableDouble = doubleNullableType()
            val long = longType()
            val int = intType()
            function(
                name = "widen",
                returnType = str,
                parameters = listOf(
                    param("a", double),
                    param("b", long, longLiteral(1L, long)),
                    param("c", nullableDouble, nullLiteral(anyNullableType())),
                    param("d", int, intLiteral(7, int))
                ),
                body = listOf(
                    returnStmt(
                        concat(
                            str, stringLiteral("", str), identifier("a", double, 2), stringLiteral("/", str), identifier("b", long, 2),
                            stringLiteral("/", str), identifier("c", nullableDouble, 2), stringLiteral("/", str), identifier("d", int, 2)
                        )
                    )
                )
            )
            function(
                name = "testFunction",
                returnType = str,
                body = listOf(
                    returnStmt(
                        concat(
                            str,
                            functionCallWithArgs(
                                "widen", "",
                                listOf(
                                    named(intLiteral(9, int), 3), named(doubleLiteral(4.5, double), 2, nullableDouble),
                                    named(longLiteral(2L, long), 1), named(doubleLiteral(3.0, double), 0)
                                ),
                                str
                            ),
                            stringLiteral(" ", str),
                            functionCallWithArgs(
                                "widen", "",
                                listOf(named(doubleLiteral(4.0, double), 2, nullableDouble), named(doubleLiteral(1.5, double), 0)),
                                str
                            )
                        )
                    )
                )
            )
        }
        assertEquals("3.0/2/4.5/9 1.5/1/4.0/7", helper.compileAndInvoke(ast))
    }

    /**
     * ```
     * fun compose(value: int, first: (int) => int = (v) => v + 1, second: (int) => int = (v) => first(first(v))): int {
     *     return second(value)
     * }
     *
     * fun testFunction(): int {
     *     return compose(1) * 100 + compose(1, first = (v) => v * 3)
     * }
     * ```
     */
    @Test
    fun `a default lambda captures an earlier parameter and calls it`() {
        val ast = buildTypedAst {
            val int = intType()
            val intToInt = lambdaType(ClassTypeRef("builtin", "int", false), "param0" to ClassTypeRef("builtin", "int", false))
            fun lambda(body: TypedExpression, paramScope: Int) = lambdaExpr(listOf("v"), listOf(returnStmt(body)), intToInt, hasBlockBody = false)
            val first = lambda(binaryExpr(identifier("v", int, 3), "+", intLiteral(1, int), int), 3)
            val second = lambda(
                expressionCall(identifier("first", intToInt, 2), listOf(expressionCall(identifier("first", intToInt, 2), listOf(identifier("v", int, 3)), int)), int),
                3
            )
            function(
                name = "compose",
                returnType = int,
                parameters = listOf(param("value", int), param("first", intToInt, first), param("second", intToInt, second)),
                body = listOf(returnStmt(expressionCall(identifier("second", intToInt, 2), listOf(identifier("value", int, 2)), int)))
            )
            val triple = lambdaExpr(listOf("v"), listOf(returnStmt(binaryExpr(identifier("v", int, 4), "*", intLiteral(3, int), int))), intToInt, hasBlockBody = false)
            function(
                name = "testFunction",
                returnType = int,
                body = listOf(
                    returnStmt(
                        binaryExpr(
                            binaryExpr(functionCall("compose", "", listOf(intLiteral(1, int)), int), "*", intLiteral(100, int), int),
                            "+",
                            functionCallWithArgs("compose", "", listOf(arg(intLiteral(1, int)), named(triple, 1)), int),
                            int
                        )
                    )
                )
            )
        }
        assertEquals(309, helper.compileAndInvoke(ast))
    }

    /**
     * ```
     * fun greet(name: string, greeting: string = "Hello", punctuation: string = "!"): string {
     *     return greeting + ", " + name + punctuation
     * }
     *
     * fun testFunction(): string {
     *     val suffix = "?"
     *     val make: (string) => string = (name) => greet(punctuation = suffix, name = name)
     *     return make("Eve")
     * }
     * ```
     */
    @Test
    fun `a lambda body passes captured values by name`() {
        val ast = buildTypedAst {
            val str = stringType()
            val strToStr = lambdaType(string, "param0" to string)
            function(
                name = "greet",
                returnType = str,
                parameters = listOf(param("name", str), param("greeting", str, stringLiteral("Hello", str)), param("punctuation", str, stringLiteral("!", str))),
                body = listOf(
                    returnStmt(
                        concat(str, identifier("greeting", str, 2), stringLiteral(", ", str), identifier("name", str, 2), identifier("punctuation", str, 2))
                    )
                )
            )
            function(
                name = "testFunction",
                returnType = str,
                body = listOf(
                    varDecl("suffix", str, stringLiteral("?", str)),
                    varDecl(
                        "make", strToStr,
                        lambdaExpr(
                            listOf("name"),
                            listOf(
                                returnStmt(
                                    functionCallWithArgs(
                                        "greet", "",
                                        listOf(named(identifier("suffix", str, 3), 2), named(identifier("name", str, 4), 0)),
                                        str
                                    )
                                )
                            ),
                            strToStr,
                            hasBlockBody = false
                        )
                    ),
                    returnStmt(expressionCall(identifier("make", strToStr, 3), listOf(stringLiteral("Eve", str)), str))
                )
            )
        }
        assertEquals("Hello, Eve?", helper.compileAndInvoke(ast))
    }

    /**
     * ```
     * fun sumTo(n: int, acc: int = 0): int {
     *     if (n == 0) {
     *         return acc
     *     }
     *     return sumTo(acc = acc + n, n = n - 1)
     * }
     *
     * fun testFunction(): int {
     *     return sumTo(n = 10)
     * }
     * ```
     */
    @Test
    fun `a recursive call passes its arguments by name`() {
        val ast = buildTypedAst {
            val int = intType()
            val bool = booleanType()
            function(
                name = "sumTo",
                returnType = int,
                parameters = listOf(param("n", int), param("acc", int, intLiteral(0, int))),
                body = listOf(
                    ifStmt(
                        binaryExpr(identifier("n", int, 2), "==", intLiteral(0, int), bool),
                        listOf(returnStmt(identifier("acc", int, 2)))
                    ),
                    returnStmt(
                        functionCallWithArgs(
                            "sumTo", "",
                            listOf(
                                named(binaryExpr(identifier("acc", int, 2), "+", identifier("n", int, 2), int), 1),
                                named(binaryExpr(identifier("n", int, 2), "-", intLiteral(1, int), int), 0)
                            ),
                            int
                        )
                    )
                )
            )
            function("testFunction", int, body = listOf(returnStmt(functionCallWithArgs("sumTo", "", listOf(arg(intLiteral(10, int))), int))))
        }
        assertEquals(55, helper.compileAndInvoke(ast))
    }

    /**
     * ```
     * fun testFunction(): string {
     *     val s = "abcdef"
     *     return s.substring(endIndex = 3, startIndex = 1) + s.substring(index = 4)
     * }
     * ```
     *
     * The service picks the overload by the argument names, `"2"` and `"1"`.
     */
    @Test
    fun `a stdlib method takes its arguments by name`() {
        val ast = buildTypedAst {
            val str = stringType()
            val int = intType()
            function(
                name = "testFunction",
                returnType = str,
                body = listOf(
                    varDecl("s", str, stringLiteral("abcdef", str)),
                    returnStmt(
                        concat(
                            str,
                            memberCallWithArgs(
                                identifier("s", str, 3), "substring", "2",
                                listOf(named(intLiteral(3, int), 1), named(intLiteral(1, int), 0)),
                                resultTypeIndex = str
                            ),
                            memberCall(identifier("s", str, 3), "substring", "1", listOf(intLiteral(4, int)), resultTypeIndex = str)
                        )
                    )
                )
            )
        }
        assertEquals("bcef", helper.compileAndInvoke(ast))
    }

    /**
     * ```
     * fun testFunction(): boolean {
     *     return listOf(1, 2, 3, 4).nMatch(n = 2, iterator = (it) => it > 2)
     * }
     * ```
     */
    @Test
    fun `a lambda is passed by name after a later parameter`() {
        val ast = buildTypedAst {
            val int = intType()
            val bool = booleanType()
            val list = collectionType("List", ClassTypeRef("builtin", "int", false))
            val predicate = lambdaType(ClassTypeRef("builtin", "boolean", false), "param0" to ClassTypeRef("builtin", "int", false))
            val lambda = lambdaExpr(
                listOf("it"),
                listOf(returnStmt(binaryExpr(identifier("it", int, 4), ">", intLiteral(2, int), bool))),
                predicate,
                hasBlockBody = false
            )
            function(
                name = "testFunction",
                returnType = bool,
                body = listOf(
                    returnStmt(
                        memberCallWithArgs(
                            functionCall("listOf", "", (1..4).map { intLiteral(it, int) }, list),
                            "nMatch", "",
                            listOf(named(intLiteral(2, int), 1), named(lambda, 0)),
                            resultTypeIndex = bool
                        )
                    )
                )
            )
        }
        assertEquals(true, helper.compileAndInvoke(ast))
    }

    /**
     * `fun many(p0: int = 0, ..., pN: int = N): int { return p0 + ... + pN }`, and a test function
     * returning the call built by [call].
     */
    private fun manyDefaults(count: Int, call: TypedAstBuilder.() -> List<TypedCallArgument>): TypedAst = buildTypedAst {
        val int = intType()
        function(
            name = "many",
            returnType = int,
            parameters = (0 until count).map { param("p$it", int, intLiteral(it, int)) },
            body = listOf(
                returnStmt((0 until count).map<Int, TypedExpression> { identifier("p$it", int, 2) }.reduce { a, b -> binaryExpr(a, "+", b, int) })
            )
        )
        function("testFunction", int, body = listOf(returnStmt(functionCallWithArgs("many", "", call(), int))))
    }

    @Test
    fun `every parameter of a function with more than one mask takes its default`() {
        assertEquals((0..69).sum(), helper.compileAndInvoke(manyDefaults(70) { emptyList() }))
    }

    @Test
    fun `parameters on the edges of the masks are left out or given by name`() {
        // many(p31 = 1000, p32 = 2000, p64 = 3000): bit 31 of the first mask, bit 0 of the second,
        // bit 0 of the third
        val ast = manyDefaults(70) {
            val int = intType()
            listOf(named(intLiteral(1000, int), 31), named(intLiteral(2000, int), 32), named(intLiteral(3000, int), 64))
        }
        assertEquals((0..69).sum() - 31 - 32 - 64 + 1000 + 2000 + 3000, helper.compileAndInvoke(ast))
    }

    @Test
    fun `a function with exactly 32 parameters needs a single mask`() {
        assertEquals((0..30).sum() + 7, helper.compileAndInvoke(manyDefaults(32) { listOf(named(intLiteral(7, intType()), 31)) }))
    }
}
