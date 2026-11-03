package com.mdeo.script.compiler

import com.mdeo.expression.ast.expressions.TypedExpression
import com.mdeo.expression.ast.statements.TypedStatement
import com.mdeo.expression.ast.types.ClassTypeRef
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Tests for the records scripts declare: construction, field access, `with`, equality and their use
 * as ordinary values.
 *
 * A record is constructed by a call to its name, with the fields as parameters, and `with` is a
 * member call whose arguments are bound to fields like named arguments.
 */
class RecordCompilerTest {

    private val helper = CompilerTestHelper()

    private val stringRef = ClassTypeRef("builtin", "string", false)

    /**
     * `record Point(x: double, y: double = x, label: string = "")`
     */
    private fun TypedAstBuilder.point(file: String = CompilerTestHelper.TEST_FILE_PATH): Int {
        val double = doubleType()
        val str = stringType()
        return record(
            "Point",
            listOf(param("x", double), param("y", double, identifier("x", double, 2)), param("label", str, stringLiteral("", str))),
            file
        )
    }

    private fun TypedAstBuilder.newPoint(vararg values: Double, file: String = CompilerTestHelper.TEST_FILE_PATH): TypedExpression =
        functionCall("Point", "", values.map { doubleLiteral(it, doubleType()) }, recordType("Point", file))

    private fun TypedAstBuilder.local(name: String, type: Int) = identifier(name, type, 3)

    private fun TypedAstBuilder.field(target: TypedExpression, name: String, type: Int, nullSafe: Boolean = false) =
        memberAccess(target, name, nullSafe, type)

    private fun run(returnType: TypedAstBuilder.() -> Int, body: TypedAstBuilder.() -> List<TypedStatement>): Any? =
        helper.compileAndInvoke(buildTypedAst { function("testFunction", returnType(), body = body()) })

    /**
     * ```
     * fun testFunction(): string {
     *     return "" + Point(1.0) + " " + Point(1.0, 2.0, "a")
     * }
     * ```
     */
    @Test
    fun `a record takes its defaults, which see earlier fields, and prints its fields`() {
        val result = run({ stringType() }) {
            point()
            val str = stringType()
            val p = recordType("Point")
            listOf(
                returnStmt(
                    concat(
                        str, stringLiteral("", str), newPoint(1.0), stringLiteral(" ", str),
                        functionCall("Point", "", listOf(doubleLiteral(1.0, doubleType()), doubleLiteral(2.0, doubleType()), stringLiteral("a", str)), p)
                    )
                )
            )
        }
        assertEquals("Point(x=1.0, y=1.0, label=) Point(x=1.0, y=2.0, label=a)", result)
    }

    /**
     * ```
     * record Segment(start: Point, end: Point)
     *
     * fun testFunction(): double {
     *     val s = Segment(end = Point(3.0, 4.0), start = Point(0.0))
     *     s.end.y = 6.0
     *     s.start = s.end
     *     return s.start.x * 10.0 + s.end.y
     * }
     * ```
     */
    @Test
    fun `fields are read and assigned, also through nested records`() {
        val result = run({ doubleType() }) {
            val double = doubleType()
            val p = point()
            val segment = record("Segment", listOf(param("start", p), param("end", p)))
            fun s() = local("s", segment)
            listOf(
                varDecl("s", segment, functionCallWithArgs("Segment", "", listOf(named(newPoint(3.0, 4.0), 1), named(newPoint(0.0), 0)), segment)),
                assignment(field(field(s(), "end", p), "y", double), doubleLiteral(6.0, double)),
                assignment(field(s(), "start", p), field(s(), "end", p)),
                returnStmt(
                    binaryExpr(
                        binaryExpr(field(field(s(), "start", p), "x", double), "*", doubleLiteral(10.0, double), double),
                        "+",
                        field(field(s(), "end", p), "y", double),
                        double
                    )
                )
            )
        }
        assertEquals(36.0, result)
    }

    /**
     * ```
     * fun testFunction(): string {
     *     val a = Point(1.0, 2.0, "a")
     *     val b = a.with(label = "b", x = 5.0)
     *     val c = a.with()
     *     return "" + a + " " + b + " " + (a == c) + (a === c)
     * }
     * ```
     */
    @Test
    fun `with copies a record with the fields it is given changed`() {
        val result = run({ stringType() }) {
            val str = stringType()
            val bool = booleanType()
            val p = point()
            listOf(
                varDecl("a", p, functionCall("Point", "", listOf(doubleLiteral(1.0, doubleType()), doubleLiteral(2.0, doubleType()), stringLiteral("a", str)), p)),
                varDecl(
                    "b", p,
                    memberCallWithArgs(local("a", p), "with", "", listOf(named(stringLiteral("b", str), 2), named(doubleLiteral(5.0, doubleType()), 0)), resultTypeIndex = p)
                ),
                varDecl("c", p, memberCall(local("a", p), "with", "", emptyList(), resultTypeIndex = p)),
                returnStmt(
                    concat(
                        str, stringLiteral("", str), local("a", p), stringLiteral(" ", str), local("b", p), stringLiteral(" ", str),
                        binaryExpr(local("a", p), "==", local("c", p), bool), binaryExpr(local("a", p), "===", local("c", p), bool)
                    )
                )
            )
        }
        assertEquals("Point(x=1.0, y=2.0, label=a) Point(x=5.0, y=2.0, label=b) truefalse", result)
    }

    /**
     * ```
     * record Tag(a: string, b: string, c: string)
     *
     * fun make(log: List<string>): Tag {
     *     log.add("receiver")
     *     return Tag("1", "2", "3")
     * }
     *
     * fun note(log: List<string>, entry: string): string {
     *     log.add(entry)
     *     return entry
     * }
     *
     * fun testFunction(): string {
     *     val log = emptyList<string>()
     *     val tag = make(log).with(c = note(log, "c"), a = note(log, "a"))
     *     return "" + tag + " " + log.concat(",")
     * }
     * ```
     */
    @Test
    fun `with evaluates its receiver once, then its arguments in the order they are written`() {
        val result = helper.compileAndInvoke(buildTypedAst {
            val str = stringType()
            val bool = booleanType()
            val list = collectionType("List", stringRef)
            val tag = record("Tag", listOf(param("a", str), param("b", str), param("c", str)))
            fun add(entry: TypedExpression) = exprStmt(memberCall(identifier("log", list, 2), "add", "", listOf(entry), resultTypeIndex = bool))
            function(
                "make", tag, listOf(param("log", list)),
                listOf(
                    add(stringLiteral("receiver", str)),
                    returnStmt(functionCall("Tag", "", listOf("1", "2", "3").map { stringLiteral(it, str) }, tag))
                )
            )
            function(
                "note", str, listOf(param("log", list), param("entry", str)),
                listOf(add(identifier("entry", str, 2)), returnStmt(identifier("entry", str, 2)))
            )
            fun note(entry: String) = functionCall("note", "", listOf(local("log", list), stringLiteral(entry, str)), str)
            function(
                "testFunction", str,
                body = listOf(
                    varDecl("log", list, functionCall("emptyList", "", emptyList(), list)),
                    varDecl(
                        "tag", tag,
                        memberCallWithArgs(
                            functionCall("make", "", listOf(local("log", list)), tag), "with", "",
                            listOf(named(note("c"), 2), named(note("a"), 0)),
                            resultTypeIndex = tag
                        )
                    ),
                    returnStmt(
                        concat(
                            str, stringLiteral("", str), local("tag", tag), stringLiteral(" ", str),
                            memberCall(local("log", list), "concat", "sep", listOf(stringLiteral(",", str)), resultTypeIndex = str)
                        )
                    )
                )
            )
        })
        assertEquals("Tag(a=a, b=2, c=c) receiver,c,a", result)
    }

    /**
     * ```
     * record Bag(items: List<string> = emptyList<string>())
     *
     * fun testFunction(): string {
     *     val a = Bag()
     *     val b = Bag()
     *     val copy = a.with()
     *     copy.items.add("x")
     *     return "" + a.items.size() + b.items.size() + (a == copy) + (a == b)
     * }
     * ```
     */
    @Test
    fun `a default is evaluated for every record, and a copy shares the collections of the original`() {
        val result = run({ stringType() }) {
            val str = stringType()
            val int = intType()
            val bool = booleanType()
            val list = collectionType("List", stringRef)
            val bag = record("Bag", listOf(param("items", list, functionCall("emptyList", "", emptyList(), list))))
            fun size(name: String) = memberCall(field(local(name, bag), "items", list), "size", "", emptyList(), resultTypeIndex = int)
            listOf(
                varDecl("a", bag, functionCall("Bag", "", emptyList(), bag)),
                varDecl("b", bag, functionCall("Bag", "", emptyList(), bag)),
                varDecl("copy", bag, memberCall(local("a", bag), "with", "", emptyList(), resultTypeIndex = bag)),
                exprStmt(memberCall(field(local("copy", bag), "items", list), "add", "", listOf(stringLiteral("x", str)), resultTypeIndex = bool)),
                returnStmt(
                    concat(
                        str, stringLiteral("", str), size("a"), size("b"),
                        binaryExpr(local("a", bag), "==", local("copy", bag), bool),
                        binaryExpr(local("a", bag), "==", local("b", bag), bool)
                    )
                )
            )
        }
        assertEquals("10truefalse", result)
    }

    /**
     * ```
     * record Pt(x: double)
     * record Vec(x: double)
     * record Empty()
     *
     * fun testFunction(): string {
     *     val a: Any = Pt(1.0)
     *     val b: Any = Vec(1.0)
     *     return "" + (Pt(1.0) == Pt(1.0)) + (Pt(1.0) === Pt(1.0)) + (a == b) + (Empty() == Empty()) + Empty()
     * }
     * ```
     */
    @Test
    fun `records are equal by record and content, and identical only to themselves`() {
        val result = run({ stringType() }) {
            val str = stringType()
            val bool = booleanType()
            val double = doubleType()
            val any = addType(ClassTypeRef("builtin", "Any", false))
            val pt = record("Pt", listOf(param("x", double)))
            val vec = record("Vec", listOf(param("x", double)))
            val empty = record("Empty", emptyList())
            fun pt() = functionCall("Pt", "", listOf(doubleLiteral(1.0, double)), pt)
            fun empty() = functionCall("Empty", "", emptyList(), empty)
            listOf(
                varDecl("a", any, pt()),
                varDecl("b", any, functionCall("Vec", "", listOf(doubleLiteral(1.0, double)), vec)),
                returnStmt(
                    concat(
                        str, stringLiteral("", str),
                        binaryExpr(pt(), "==", pt(), bool),
                        binaryExpr(pt(), "===", pt(), bool),
                        binaryExpr(local("a", any), "==", local("b", any), bool),
                        binaryExpr(empty(), "==", empty(), bool),
                        empty()
                    )
                )
            )
        }
        assertEquals("truefalsefalsetrueEmpty()", result)
    }

    /**
     * ```
     * record Bucket(items: List<int>)
     *
     * fun testFunction(): string {
     *     val a = Bucket(listOf(1, 2))
     *     val b = Bucket(listOf(1, 2))
     *     val before = a == b
     *     b.items.add(3)
     *     val set = setOf(a, b, Bucket(listOf(1, 2)))
     *     return "" + before + (a == b) + set.size() + a
     * }
     * ```
     */
    @Test
    fun `records with collection fields compare the collections by their content at the time`() {
        val result = run({ stringType() }) {
            val str = stringType()
            val int = intType()
            val bool = booleanType()
            val intRef = ClassTypeRef("builtin", "int", false)
            val list = collectionType("List", intRef)
            val bucket = record("Bucket", listOf(param("items", list)))
            val set = collectionType("Set", recordTypeRef("Bucket"))
            fun bucket() = functionCall("Bucket", "", listOf(functionCall("listOf", "", listOf(intLiteral(1, int), intLiteral(2, int)), list)), bucket)
            listOf(
                varDecl("a", bucket, bucket()),
                varDecl("b", bucket, bucket()),
                varDecl("before", bool, binaryExpr(local("a", bucket), "==", local("b", bucket), bool)),
                exprStmt(memberCall(field(local("b", bucket), "items", list), "add", "", listOf(intLiteral(3, int)), resultTypeIndex = bool)),
                varDecl("set", set, functionCall("setOf", "", listOf(local("a", bucket), local("b", bucket), bucket()), set)),
                returnStmt(
                    concat(
                        str, stringLiteral("", str), local("before", bool),
                        binaryExpr(local("a", bucket), "==", local("b", bucket), bool),
                        memberCall(local("set", set), "size", "", emptyList(), resultTypeIndex = int),
                        local("a", bucket)
                    )
                )
            )
        }
        assertEquals("truefalse2Bucket(items=[1, 2])", result)
    }

    /**
     * ```
     * record Mixed(i: int, f: float, l: long, b: boolean, s: string?, d: double?, any: Any?)
     *
     * fun testFunction(): string {
     *     val m = Mixed(1, 2.5f, 3L, true, null, 4.5, "x")
     *     m.i = m.i + 1
     *     m.f = m.f * 2.0f
     *     m.l = m.l * 1000000000000L
     *     m.b = !m.b
     *     m.s = "s"
     *     val n = m.with(d = null, any = 7, s = null)
     *     return "" + m + " " + n + " d=" + m.d + " " + (n.d ?? -1.0)
     * }
     * ```
     */
    @Test
    fun `fields of every primitive, nullable and Any type keep their values when written and copied`() {
        val result = run({ stringType() }) {
            val str = stringType()
            val int = intType()
            val float = floatType()
            val long = longType()
            val bool = booleanType()
            val nullableStr = stringNullableType()
            val nullableDouble = doubleNullableType()
            val nullableAny = anyNullableType()
            val double = doubleType()
            val mixed = record(
                "Mixed",
                listOf(param("i", int), param("f", float), param("l", long), param("b", bool), param("s", nullableStr), param("d", nullableDouble), param("any", nullableAny))
            )
            fun m(name: String, type: Int) = field(local("m", mixed), name, type)
            listOf(
                varDecl(
                    "m", mixed,
                    functionCallWithArgs(
                        "Mixed", "",
                        listOf(
                            arg(intLiteral(1, int)), arg(floatLiteral(2.5f, float)), arg(longLiteral(3L, long)), arg(booleanLiteral(true, bool)),
                            arg(nullLiteral(nullableAny), nullableStr), arg(doubleLiteral(4.5, double), nullableDouble), arg(stringLiteral("x", str), nullableAny)
                        ),
                        mixed
                    )
                ),
                assignment(m("i", int), binaryExpr(m("i", int), "+", intLiteral(1, int), int)),
                assignment(m("f", float), binaryExpr(m("f", float), "*", floatLiteral(2.0f, float), float)),
                assignment(m("l", long), binaryExpr(m("l", long), "*", longLiteral(1_000_000_000_000L, long), long)),
                assignment(m("b", bool), unaryExpr("!", m("b", bool), bool)),
                assignment(m("s", nullableStr), stringLiteral("s", str)),
                varDecl(
                    "n", mixed,
                    memberCallWithArgs(
                        local("m", mixed), "with", "",
                        listOf(named(nullLiteral(nullableAny), 5, nullableDouble), named(intLiteral(7, int), 6, nullableAny), named(nullLiteral(nullableAny), 4, nullableStr)),
                        resultTypeIndex = mixed
                    )
                ),
                returnStmt(
                    concat(
                        str, stringLiteral("", str), local("m", mixed), stringLiteral(" ", str), local("n", mixed),
                        stringLiteral(" d=", str), m("d", nullableDouble), stringLiteral(" ", str),
                        binaryExpr(field(local("n", mixed), "d", nullableDouble), "??", doubleLiteral(-1.0, double), double)
                    )
                )
            )
        }
        assertEquals(
            "Mixed(i=2, f=5.0, l=3000000000000, b=false, s=s, d=4.5, any=x) " +
                "Mixed(i=2, f=5.0, l=3000000000000, b=false, s=null, d=null, any=7) d=4.5 -1.0",
            result
        )
    }

    /**
     * ```
     * record Odd(copy: int, field: string, fields: int, hashCode: int, recordType: string, values: int)
     *
     * fun testFunction(): string {
     *     val o = Odd(1, "f", 2, 3, "r", 4)
     *     o.copy = o.copy + 10
     *     return "" + o.with(values = 40)
     * }
     * ```
     */
    @Test
    fun `fields may have the names of members of the runtime record class`() {
        val result = run({ stringType() }) {
            val str = stringType()
            val int = intType()
            val odd = record(
                "Odd",
                listOf(param("copy", int), param("field", str), param("fields", int), param("hashCode", int), param("recordType", str), param("values", int))
            )
            listOf(
                varDecl(
                    "o", odd,
                    functionCall("Odd", "", listOf(intLiteral(1, int), stringLiteral("f", str), intLiteral(2, int), intLiteral(3, int), stringLiteral("r", str), intLiteral(4, int)), odd)
                ),
                assignment(field(local("o", odd), "copy", int), binaryExpr(field(local("o", odd), "copy", int), "+", intLiteral(10, int), int)),
                returnStmt(
                    concat(
                        str, stringLiteral("", str),
                        memberCallWithArgs(local("o", odd), "with", "", listOf(named(intLiteral(40, int), 5)), resultTypeIndex = odd)
                    )
                )
            )
        }
        assertEquals("Odd(copy=11, field=f, fields=2, hashCode=3, recordType=r, values=40)", result)
    }

    /**
     * ```
     * record Node(value: int, next: Node? = null)
     *
     * fun testFunction(): string {
     *     val list = Node(1, Node(2, Node(3)))
     *     var sum = 0
     *     var current: Node? = list
     *     while (current != null) {
     *         sum = sum + current!!.value
     *         current = current!!.next
     *     }
     *     return "" + sum + " " + list
     * }
     * ```
     */
    @Test
    fun `a record refers to its own record`() {
        val result = run({ stringType() }) {
            val str = stringType()
            val int = intType()
            val bool = booleanType()
            val nullableNode = recordType("Node", nullable = true)
            val node = record("Node", listOf(param("value", int), param("next", nullableNode, nullLiteral(anyNullableType()))))
            fun newNode(value: Int, next: TypedExpression?) =
                functionCallWithArgs("Node", "", listOfNotNull(arg(intLiteral(value, int)), next?.let { arg(it, nullableNode) }), node)
            fun current() = local("current", nullableNode)
            listOf(
                varDecl("list", node, newNode(1, newNode(2, newNode(3, null)))),
                varDecl("sum", int, intLiteral(0, int)),
                varDecl("current", nullableNode, local("list", node)),
                whileStmt(
                    binaryExpr(current(), "!=", nullLiteral(anyNullableType()), bool),
                    listOf(
                        assignment(identifier("sum", int, 3), binaryExpr(identifier("sum", int, 3), "+", field(assertNonNull(current(), node), "value", int), int)),
                        assignment(identifier("current", nullableNode, 3), field(assertNonNull(current(), node), "next", nullableNode))
                    )
                ),
                returnStmt(concat(str, stringLiteral("", str), local("sum", int), stringLiteral(" ", str), local("list", node)))
            )
        }
        assertEquals("6 Node(value=1, next=Node(value=2, next=Node(value=3, next=null)))", result)
    }

    /**
     * ```
     * record Counter(count: int = 0, apply: (int) => int = (v) => v + count)
     *
     * fun testFunction(): int {
     *     val c = Counter(count = 3)
     *     c.count = 10
     *     return c.apply(5)
     * }
     * ```
     *
     * The default lambda captures `count` as the constructor saw it.
     */
    @Test
    fun `a lambda field default captures an earlier field when the record is constructed`() {
        val result = run({ intType() }) {
            val int = intType()
            val intRef = ClassTypeRef("builtin", "int", false)
            val intToInt = lambdaType(intRef, "param0" to intRef)
            val counter = record(
                "Counter",
                listOf(
                    param("count", int, intLiteral(0, int)),
                    param(
                        "apply", intToInt,
                        lambdaExpr(listOf("v"), listOf(returnStmt(binaryExpr(identifier("v", int, 3), "+", identifier("count", int, 2), int))), intToInt, hasBlockBody = false)
                    )
                )
            )
            listOf(
                varDecl("c", counter, functionCall("Counter", "", listOf(intLiteral(3, int)), counter)),
                assignment(field(local("c", counter), "count", int), intLiteral(10, int)),
                returnStmt(expressionCall(field(local("c", counter), "apply", intToInt), listOf(intLiteral(5, int)), int))
            )
        }
        assertEquals(8, result)
    }

    /**
     * ```
     * fun testFunction(): string {
     *     val a: Any = Point(4.0)
     *     val p: Point? = null
     *     return "" + (a is Point) + (a is Segment) + (a as Point).x + ((a as? Segment) == null) + (p?.x ?? -1.0)
     * }
     * ```
     */
    @Test
    fun `records are checked, cast and accessed null-safely like other classes`() {
        val result = run({ stringType() }) {
            val str = stringType()
            val bool = booleanType()
            val double = doubleType()
            val any = addType(ClassTypeRef("builtin", "Any", false))
            val p = point()
            val nullablePoint = recordType("Point", nullable = true)
            val segment = record("Segment", listOf(param("start", p)))
            val nullableSegment = recordType("Segment", nullable = true)
            listOf(
                varDecl("a", any, newPoint(4.0)),
                varDecl("p", nullablePoint, nullLiteral(anyNullableType())),
                returnStmt(
                    concat(
                        str, stringLiteral("", str),
                        typeCheck(local("a", any), p, bool),
                        typeCheck(local("a", any), segment, bool),
                        field(typeCast(local("a", any), p, p), "x", double),
                        binaryExpr(typeCast(local("a", any), segment, nullableSegment, isSafe = true), "==", nullLiteral(anyNullableType()), bool),
                        binaryExpr(field(local("p", nullablePoint), "x", addType(ClassTypeRef("builtin", "double", true)), nullSafe = true), "??", doubleLiteral(-1.0, double), double)
                    )
                )
            )
        }
        assertEquals("truefalse4.0true-1.0", result)
    }

    /**
     * `/shapes.fn`:
     * ```
     * record Point(x: double, y: double = x, label: string = "")
     * ```
     *
     * `/main.fn`:
     * ```
     * import { Point } from "./shapes.fn"
     *
     * fun testFunction(): string {
     *     val p = Point(2.0)
     *     return "" + p.with(label = "copy") + " " + p
     * }
     * ```
     */
    @Test
    fun `a record imported from another script is constructed with its defaults and copied`() {
        val shapes = buildTypedAst { point(file = "/shapes.fn") }
        val main = buildTypedAst {
            import("Point", "Point", "/shapes.fn")
            val str = stringType()
            val p = recordType("Point", "/shapes.fn")
            function(
                "testFunction", str,
                body = listOf(
                    varDecl("p", p, newPoint(2.0, file = "/shapes.fn")),
                    returnStmt(
                        concat(
                            str, stringLiteral("", str),
                            memberCallWithArgs(local("p", p), "with", "", listOf(named(stringLiteral("copy", str), 2)), resultTypeIndex = p),
                            stringLiteral(" ", str), local("p", p)
                        )
                    )
                )
            )
        }
        val program = helper.compileFiles(mapOf("/shapes.fn" to shapes, "/main.fn" to main))
        assertEquals("Point(x=2.0, y=2.0, label=copy) Point(x=2.0, y=2.0, label=)", helper.invoke(program, "testFunction", "/main.fn"))
    }

    /**
     * Two scripts each declare a record `Point`; the main script imports both under other names.
     * ```
     * import { Point as A } from "/a.fn"
     * import { Point as B } from "/b.fn"
     *
     * fun testFunction(): string {
     *     val a: A = A(1.0)
     *     val b: B = B(2.0, 3.0)
     *     return "" + a + " " + b.with(label = "b") + " " + (a == A(1.0)) + (a == B(1.0))
     * }
     * ```
     */
    @Test
    fun `records with the same name imported under other names stay apart`() {
        val a = buildTypedAst { point(file = "/a.fn") }
        val b = buildTypedAst { point(file = "/b.fn") }
        val main = buildTypedAst {
            import("A", "Point", "/a.fn")
            import("B", "Point", "/b.fn")
            val str = stringType()
            val bool = booleanType()
            val double = doubleType()
            val aType = recordType("Point", "/a.fn")
            val bType = recordType("Point", "/b.fn")
            fun construct(name: String, type: Int, vararg values: Double) =
                functionCall(name, "", values.map { doubleLiteral(it, double) }, type)
            function(
                "testFunction", str,
                body = listOf(
                    varDecl("a", aType, construct("A", aType, 1.0)),
                    varDecl("b", bType, construct("B", bType, 2.0, 3.0)),
                    returnStmt(
                        concat(
                            str, stringLiteral("", str), local("a", aType), stringLiteral(" ", str),
                            memberCallWithArgs(local("b", bType), "with", "", listOf(named(stringLiteral("b", str), 2)), resultTypeIndex = bType),
                            stringLiteral(" ", str),
                            binaryExpr(local("a", aType), "==", construct("A", aType, 1.0), bool),
                            binaryExpr(local("a", aType), "==", construct("B", bType, 1.0), bool)
                        )
                    )
                )
            )
        }
        val program = helper.compileFiles(mapOf("/a.fn" to a, "/b.fn" to b, "/main.fn" to main))
        assertEquals(
            "Point(x=1.0, y=1.0, label=) Point(x=2.0, y=3.0, label=b) truefalse",
            helper.invoke(program, "testFunction", "/main.fn")
        )
    }

    /**
     * ```
     * fun scale(p: Point, factor: double = 2.0): Point {
     *     p.x = p.x * factor
     *     return p
     * }
     *
     * fun testFunction(): string {
     *     val points = listOf(Point(3.0), Point(1.0), Point(2.0))
     *     val same = scale(points.first())
     *     val sorted = points.map((p) => p.with(y = 0.0)).sortedBy((p) => p.x)
     *     return "" + points.first().x + (same === points.first()) + sorted.first().x + points.includes(Point(1.0))
     * }
     * ```
     */
    @Test
    fun `records are passed by reference and used in collection lambdas`() {
        val result = run({ stringType() }) {
            val str = stringType()
            val bool = booleanType()
            val double = doubleType()
            val p = point()
            val pointRef = recordTypeRef("Point")
            val list = collectionType("List", pointRef)
            val ordered = collectionType("ReadonlyOrderedCollection", pointRef)
            val pointToPoint = lambdaType(pointRef, "param0" to pointRef)
            val pointToDouble = lambdaType(ClassTypeRef("builtin", "double", false), "param0" to pointRef)
            function(
                "scale", p, listOf(param("p", p), param("factor", double, doubleLiteral(2.0, double))),
                listOf(
                    assignment(field(identifier("p", p, 2), "x", double), binaryExpr(field(identifier("p", p, 2), "x", double), "*", identifier("factor", double, 2), double)),
                    returnStmt(identifier("p", p, 2))
                )
            )
            fun firstOf(collection: TypedExpression) = memberCall(collection, "first", "", emptyList(), resultTypeIndex = p)
            listOf(
                varDecl("points", list, functionCall("listOf", "", listOf(newPoint(3.0), newPoint(1.0), newPoint(2.0)), list)),
                varDecl("same", p, functionCall("scale", "", listOf(firstOf(local("points", list))), p)),
                varDecl(
                    "sorted", ordered,
                    memberCall(
                        memberCall(
                            local("points", list), "map", "",
                            listOf(
                                lambdaExpr(
                                    listOf("p"),
                                    listOf(returnStmt(memberCallWithArgs(identifier("p", p, 4), "with", "", listOf(named(doubleLiteral(0.0, double), 1)), resultTypeIndex = p))),
                                    pointToPoint, hasBlockBody = false
                                )
                            ),
                            resultTypeIndex = list
                        ),
                        "sortedBy", "",
                        listOf(lambdaExpr(listOf("p"), listOf(returnStmt(field(identifier("p", p, 4), "x", double))), pointToDouble, hasBlockBody = false)),
                        resultTypeIndex = ordered
                    )
                ),
                returnStmt(
                    concat(
                        str, stringLiteral("", str),
                        field(firstOf(local("points", list)), "x", double),
                        binaryExpr(local("same", p), "===", firstOf(local("points", list)), bool),
                        field(firstOf(local("sorted", ordered)), "x", double),
                        memberCall(local("points", list), "includes", "", listOf(newPoint(1.0)), resultTypeIndex = bool)
                    )
                )
            )
        }
        assertEquals("6.0true1.0true", result)
    }
}
