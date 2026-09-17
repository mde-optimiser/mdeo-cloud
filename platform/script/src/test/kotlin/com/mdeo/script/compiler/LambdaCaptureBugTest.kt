package com.mdeo.script.compiler

import com.mdeo.expression.ast.types.ClassTypeRef
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Regression tests for lambdas that use outer variables in less common places.
 *
 * The compiler finds the outer variables a lambda captures by walking the expressions of its body,
 * and it gives every lambda a scope level that has to match the `scope` the language server
 * annotated identifiers with. Before the fixes, the walk skipped the callee of a lambda call, `!!`,
 * `is` and list literals, and it put a lambda in the iterable of a `for` loop one level too high,
 * where the language server puts it inside the loop's own scope. Both failed with:
 *
 * ```
 * java.lang.IllegalStateException: Variable 'first' at scope level 2 is not accessible in
 *   lambda scope at level 3. All outer variables must be captured.
 * ```
 */
class LambdaCaptureBugTest {

    private val helper = CompilerTestHelper()

    private val intRef = ClassTypeRef("builtin", "int", false)

    /**
     * ```
     * fun testFunction(): int {
     *     val twice: (int) => int = (v) => v * 2
     *     val quad: (int) => int = (v) => twice(twice(v))
     *     return quad(3)
     * }
     * ```
     */
    @Test
    fun `a lambda calls an outer lambda`() {
        val ast = buildTypedAst {
            val int = intType()
            val intToInt = lambdaType(intRef, "param0" to intRef)
            fun twice() = identifier("twice", intToInt, 3)
            function(
                "testFunction", int,
                body = listOf(
                    varDecl(
                        "twice", intToInt,
                        lambdaExpr(listOf("v"), listOf(returnStmt(binaryExpr(identifier("v", int, 4), "*", intLiteral(2, int), int))), intToInt, hasBlockBody = false)
                    ),
                    varDecl(
                        "quad", intToInt,
                        lambdaExpr(
                            listOf("v"),
                            listOf(returnStmt(expressionCall(twice(), listOf(expressionCall(twice(), listOf(identifier("v", int, 4)), int)), int))),
                            intToInt, hasBlockBody = false
                        )
                    ),
                    returnStmt(expressionCall(identifier("quad", intToInt, 3), listOf(intLiteral(3, int)), int))
                )
            )
        }
        assertEquals(12, helper.compileAndInvoke(ast))
    }

    /**
     * ```
     * fun testFunction(): string {
     *     val maybe: int? = 5
     *     val value: Any = "text"
     *     val read: (int) => string = (v) => "" + (v + maybe!!) + (value is string) + listOf(maybe)
     *     return read(1)
     * }
     * ```
     */
    @Test
    fun `a lambda uses outer variables in a non-null assertion, a type check and a list literal`() {
        val ast = buildTypedAst {
            val int = intType()
            val str = stringType()
            val bool = booleanType()
            val nullableInt = intNullableType()
            val any = addType(ClassTypeRef("builtin", "Any", false))
            val list = collectionType("List", ClassTypeRef("builtin", "int", true))
            val intToString = lambdaType(ClassTypeRef("builtin", "string", false), "param0" to intRef)
            function(
                "testFunction", str,
                body = listOf(
                    varDecl("maybe", nullableInt, intLiteral(5, int)),
                    varDecl("value", any, stringLiteral("text", str)),
                    varDecl(
                        "read", intToString,
                        lambdaExpr(
                            listOf("v"),
                            listOf(
                                returnStmt(
                                    concat(
                                        str, stringLiteral("", str),
                                        binaryExpr(identifier("v", int, 4), "+", assertNonNull(identifier("maybe", nullableInt, 3), int), int),
                                        typeCheck(identifier("value", any, 3), str, bool),
                                        listLiteral(listOf(identifier("maybe", nullableInt, 3)), list)
                                    )
                                )
                            ),
                            intToString, hasBlockBody = false
                        )
                    ),
                    returnStmt(expressionCall(identifier("read", intToString, 3), listOf(intLiteral(1, int)), str))
                )
            )
        }
        assertEquals("6true[5]", helper.compileAndInvoke(ast))
    }

    /**
     * ```
     * fun testFunction(): string {
     *     val offset = 10
     *     var text = ""
     *     for (value in listOf(3, 1, 2).sortedBy((it) => it + offset)) {
     *         text = text + value
     *     }
     *     return text
     * }
     * ```
     *
     * The loop variable is at level 4, so the language server declares `it` at level 5.
     */
    @Test
    fun `a lambda in the iterable of a for loop captures outer variables`() {
        val ast = buildTypedAst {
            val int = intType()
            val str = stringType()
            val list = collectionType("List", intRef)
            val ordered = collectionType("ReadonlyOrderedCollection", intRef)
            val intToInt = lambdaType(intRef, "param0" to intRef)
            function(
                "testFunction", str,
                body = listOf(
                    varDecl("offset", int, intLiteral(10, int)),
                    varDecl("text", str, stringLiteral("", str)),
                    forStmt(
                        "value", int,
                        memberCall(
                            functionCall("listOf", "", listOf(intLiteral(3, int), intLiteral(1, int), intLiteral(2, int)), list),
                            "sortedBy", "",
                            listOf(
                                lambdaExpr(
                                    listOf("it"),
                                    listOf(returnStmt(binaryExpr(identifier("it", int, 5), "+", identifier("offset", int, 3), int))),
                                    intToInt, hasBlockBody = false
                                )
                            ),
                            resultTypeIndex = ordered
                        ),
                        listOf(assignment(identifier("text", str, 3), binaryExpr(identifier("text", str, 3), "+", identifier("value", int, 4), str)))
                    ),
                    returnStmt(identifier("text", str, 3))
                )
            )
        }
        assertEquals("123", helper.compileAndInvoke(ast))
    }

    /**
     * ```
     * fun testFunction(): int {
     *     var total = 0
     *     for (outer in listOf(1, 2)) {
     *         for (inner in listOf(10, 20).map((it) => it * outer)) {
     *             total = total + inner
     *         }
     *     }
     *     return total
     * }
     * ```
     */
    @Test
    fun `a lambda in the iterable of a nested for loop captures the outer loop variable`() {
        val ast = buildTypedAst {
            val int = intType()
            val list = collectionType("List", intRef)
            val intToInt = lambdaType(intRef, "param0" to intRef)
            fun listOf(vararg values: Int) = functionCall("listOf", "", values.map { intLiteral(it, int) }, list)
            function(
                "testFunction", int,
                body = listOf(
                    varDecl("total", int, intLiteral(0, int)),
                    forStmt(
                        "outer", int, listOf(1, 2),
                        listOf(
                            forStmt(
                                "inner", int,
                                memberCall(
                                    listOf(10, 20), "map", "",
                                    listOf(
                                        lambdaExpr(
                                            listOf("it"),
                                            listOf(returnStmt(binaryExpr(identifier("it", int, 7), "*", identifier("outer", int, 4), int))),
                                            intToInt, hasBlockBody = false
                                        )
                                    ),
                                    resultTypeIndex = list
                                ),
                                listOf(assignment(identifier("total", int, 3), binaryExpr(identifier("total", int, 3), "+", identifier("inner", int, 6), int)))
                            )
                        )
                    ),
                    returnStmt(identifier("total", int, 3))
                )
            )
        }
        assertEquals(90, helper.compileAndInvoke(ast))
    }

    /**
     * ```
     * fun apply(n: int, op: (int) => int): int {
     *     return op(n)
     * }
     *
     * fun testFunction(): int {
     *     return apply(3, (v) => v * 2)
     * }
     * ```
     *
     * `apply` is compiled first, so its call of `op` is the first use of the lambda type. The
     * compiler used to look up the functional interface without generating it, and the call
     * failed with `NoClassDefFoundError: Lambda$0`.
     */
    @Test
    fun `a lambda parameter is called before any lambda of its type is compiled`() {
        val ast = buildTypedAst {
            val int = intType()
            val intToInt = lambdaType(intRef, "param0" to intRef)
            function(
                "apply", int, listOf(param("n", int), param("op", intToInt)),
                listOf(returnStmt(expressionCall(identifier("op", intToInt, 2), listOf(identifier("n", int, 2)), int)))
            )
            function(
                "testFunction", int,
                body = listOf(
                    returnStmt(
                        functionCall(
                            "apply", "",
                            listOf(
                                intLiteral(3, int),
                                lambdaExpr(listOf("v"), listOf(returnStmt(binaryExpr(identifier("v", int, 4), "*", intLiteral(2, int), int))), intToInt, hasBlockBody = false)
                            ),
                            int
                        )
                    )
                )
            )
        }
        assertEquals(6, helper.compileAndInvoke(ast))
    }
}
