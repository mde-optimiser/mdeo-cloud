package com.mdeo.script.external

import com.mdeo.expression.ast.expressions.TypedExpression
import com.mdeo.expression.ast.types.ClassTypeRef
import com.mdeo.script.compiler.CompilationInput
import com.mdeo.script.compiler.ScriptCompiler
import com.mdeo.script.compiler.TypedAstBuilder
import com.mdeo.script.compiler.arg
import com.mdeo.script.compiler.assignment
import com.mdeo.script.compiler.binaryExpr
import com.mdeo.script.compiler.buildTypedAst
import com.mdeo.script.compiler.concat
import com.mdeo.script.compiler.doubleLiteral
import com.mdeo.script.compiler.exprStmt
import com.mdeo.script.compiler.functionCall
import com.mdeo.script.compiler.functionCallWithArgs
import com.mdeo.script.compiler.identifier
import com.mdeo.script.compiler.memberAccess
import com.mdeo.script.compiler.memberCall
import com.mdeo.script.compiler.memberCallWithArgs
import com.mdeo.script.compiler.named
import com.mdeo.script.compiler.returnStmt
import com.mdeo.script.compiler.stringLiteral
import com.mdeo.script.compiler.varDecl
import com.mdeo.script.runtime.ExecutionEnvironment
import com.mdeo.script.runtime.SimpleScriptContext
import com.mdeo.scriptfunctions.service.RecordValue
import com.mdeo.scriptfunctions.service.ScriptFunctionCall
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Scripts constructing, changing and passing the records a contribution defines.
 *
 * The contribution `geo` declares the records `Point(x: double, label: string)` and
 * `Route(name: string, stops: List<Point>)` with the external functions `nearest(): Point`,
 * `describe(p: Point): string`, `relabel(point: Point, label: string): Point`,
 * `summarize(route: Route): string`, `extend(route: Route): int` and `reverse(route: Route): Route`.
 * Each script function is shown above the test that runs it. Arguments of external functions are
 * in-only: an operation sees them readonly, and a script never sees its values changed by a call.
 */
class ContributedRecordsTest {

    private val scriptPath = "/main.fn"

    private val doubleRef = ClassTypeRef("builtin", "double", false)
    private val stringRef = ClassTypeRef("builtin", "string", false)
    private val pointRef = ClassTypeRef("contrib/geo", "Point", false)
    private val routeRef = ClassTypeRef("contrib/geo", "Route", false)
    private val stopsRef = ClassTypeRef("builtin", "List", false, mapOf("T" to pointRef))

    private val plugin = pluginAst("geo") {
        record("Point", "x" to doubleRef, "label" to stringRef)
        record("Route", "name" to stringRef, "stops" to stopsRef)
        external("nearest", pointRef)
        external("describe", stringRef, "p" to pointRef)
        external("relabel", pointRef, "point" to pointRef, "label" to stringRef)
        external("summarize", stringRef, "route" to routeRef)
        external("extend", ClassTypeRef("builtin", "int", false), "route" to routeRef)
        external("reverse", routeRef, "route" to routeRef)
    }

    private fun TypedAstBuilder.newPoint(x: Double, label: String): TypedExpression =
        functionCall("Point", "", listOf(doubleLiteral(x, doubleType()), stringLiteral(label, stringType())), addType(pointRef))

    private fun TypedAstBuilder.newRoute(vararg stops: TypedExpression): TypedExpression {
        val stopsType = addType(stopsRef)
        return functionCall("Route", "", listOf(stringLiteral("r", stringType()), functionCall("listOf", "", stops.toList(), stopsType)), addType(routeRef))
    }

    private fun TypedAstBuilder.local(name: String, type: ClassTypeRef) = identifier(name, addType(type), 3)

    private fun TypedAstBuilder.field(target: TypedExpression, name: String, type: ClassTypeRef) =
        memberAccess(target, name, resultTypeIndex = addType(type))

    private fun TypedAstBuilder.stops(route: String) = field(local(route, routeRef), "stops", stopsRef)

    private val script = buildTypedAst {
        val str = stringType()
        val double = doubleType()
        val int = intType()
        val bool = booleanType()
        val point = addType(pointRef)
        val route = addType(routeRef)
        val stops = addType(stopsRef)
        fun addStop(routeName: String, stop: TypedExpression) =
            exprStmt(memberCall(stops(routeName), "add", "", listOf(stop), resultTypeIndex = bool))
        fun firstStop(routeName: String) = memberCall(stops(routeName), "first", "", emptyList(), resultTypeIndex = point)

        // fun build(): string {
        //     val p = Point(label = "a", x = 1.5)
        //     p.x = 2.5
        //     val q: Point = p.with(label = "b")
        //     return "" + p + " " + q
        // }
        function(
            "build", str,
            body = listOf(
                varDecl("p", point, functionCallWithArgs("Point", "", listOf(named(stringLiteral("a", str), 1), named(doubleLiteral(1.5, double), 0)), point)),
                assignment(field(local("p", pointRef), "x", doubleRef), doubleLiteral(2.5, double)),
                varDecl("q", point, memberCallWithArgs(local("p", pointRef), "with", "", listOf(named(stringLiteral("b", str), 1)), resultTypeIndex = point)),
                returnStmt(concat(str, stringLiteral("", str), local("p", pointRef), stringLiteral(" ", str), local("q", pointRef)))
            )
        )

        // fun mutateReturned(): double {
        //     val p = nearest()
        //     p.x = p.x + 1.0
        //     return p.x
        // }
        function(
            "mutateReturned", double,
            body = listOf(
                varDecl("p", point, functionCall("nearest", "", emptyList(), point)),
                assignment(field(local("p", pointRef), "x", doubleRef), binaryExpr(field(local("p", pointRef), "x", doubleRef), "+", doubleLiteral(1.0, double), double)),
                returnStmt(field(local("p", pointRef), "x", doubleRef))
            )
        )

        // fun passConstructed(): string {
        //     return describe(Point(3.0, "c"))
        // }
        function("passConstructed", str, body = listOf(returnStmt(functionCall("describe", "", listOf(newPoint(3.0, "c")), str))))

        // fun namedArgumentsToExternal(): string {
        //     return relabel(label = "renamed", point = Point(x = 1.0, label = "old")).label + describe(p = nearest())
        // }
        function(
            "namedArgumentsToExternal", str,
            body = listOf(
                returnStmt(
                    concat(
                        str,
                        field(
                            functionCallWithArgs("relabel", "", listOf(named(stringLiteral("renamed", str), 1), named(newPoint(1.0, "old"), 0)), point),
                            "label", stringRef
                        ),
                        functionCallWithArgs("describe", "", listOf(arg(functionCall("nearest", "", emptyList(), point))), str)
                    )
                )
            )
        )

        // fun argumentsStayUnchanged(): string {
        //     val p = Point(1.0, "kept")
        //     val q = relabel(p, "new")
        //     return p.label + " " + q.label + " " + (p === q)
        // }
        function(
            "argumentsStayUnchanged", str,
            body = listOf(
                varDecl("p", point, newPoint(1.0, "kept")),
                varDecl("q", point, functionCall("relabel", "", listOf(local("p", pointRef), stringLiteral("new", str)), point)),
                returnStmt(
                    concat(
                        str, field(local("p", pointRef), "label", stringRef), stringLiteral(" ", str), field(local("q", pointRef), "label", stringRef),
                        stringLiteral(" ", str), binaryExpr(local("p", pointRef), "===", local("q", pointRef), bool)
                    )
                )
            )
        )

        // fun equalContributedRecords(): string {
        //     val a = Point(1.0, "a")
        //     return "" + (a == Point(1.0, "a")) + (a == a.with(label = "b")) + setOf(a, a.with(), Point(2.0, "a")).size()
        // }
        val pointSet = addType(ClassTypeRef("builtin", "Set", false, mapOf("T" to pointRef)))
        function(
            "equalContributedRecords", str,
            body = listOf(
                varDecl("a", point, newPoint(1.0, "a")),
                returnStmt(
                    concat(
                        str, stringLiteral("", str),
                        binaryExpr(local("a", pointRef), "==", newPoint(1.0, "a"), bool),
                        binaryExpr(
                            local("a", pointRef), "==",
                            memberCallWithArgs(local("a", pointRef), "with", "", listOf(named(stringLiteral("b", str), 1)), resultTypeIndex = point),
                            bool
                        ),
                        memberCall(
                            functionCall(
                                "setOf", "",
                                listOf(local("a", pointRef), memberCall(local("a", pointRef), "with", "", emptyList(), resultTypeIndex = point), newPoint(2.0, "a")),
                                pointSet
                            ),
                            "size", "", emptyList(), resultTypeIndex = int
                        )
                    )
                )
            )
        )

        // fun routeWithStops(): string {
        //     val route = Route("r", listOf(Point(1.0, "a")))
        //     val before = summarize(route)
        //     route.stops.add(Point(2.0, "b"))
        //     route.stops.first().label = "A"
        //     return before + " " + summarize(route)
        // }
        function(
            "routeWithStops", str,
            body = listOf(
                varDecl("route", route, newRoute(newPoint(1.0, "a"))),
                varDecl("before", str, functionCall("summarize", "", listOf(local("route", routeRef)), str)),
                addStop("route", newPoint(2.0, "b")),
                assignment(field(firstStop("route"), "label", stringRef), stringLiteral("A", str)),
                returnStmt(concat(str, local("before", stringRef), stringLiteral(" ", str), functionCall("summarize", "", listOf(local("route", routeRef)), str)))
            )
        )

        // fun serviceCannotChangeStops(): int {
        //     return extend(Route("r", listOf(Point(1.0, "a"))))
        // }
        function("serviceCannotChangeStops", int, body = listOf(returnStmt(functionCall("extend", "", listOf(newRoute(newPoint(1.0, "a"))), int))))

        // fun returnedRouteIsUsable(): string {
        //     val route = Route("r", listOf(Point(1.0, "a"), Point(2.0, "b")))
        //     val reversed = reverse(route)
        //     reversed.stops.add(Point(3.0, "c"))
        //     reversed.name = "back"
        //     return reversed.name + reversed.stops.size() + reversed.stops.first().label + " " + route.stops.size() + route.stops.first().label
        // }
        fun size(routeName: String) = memberCall(stops(routeName), "size", "", emptyList(), resultTypeIndex = int)
        function(
            "returnedRouteIsUsable", str,
            body = listOf(
                varDecl("route", route, newRoute(newPoint(1.0, "a"), newPoint(2.0, "b"))),
                varDecl("reversed", route, functionCall("reverse", "", listOf(local("route", routeRef)), route)),
                addStop("reversed", newPoint(3.0, "c")),
                assignment(field(local("reversed", routeRef), "name", stringRef), stringLiteral("back", str)),
                returnStmt(
                    concat(
                        str, field(local("reversed", routeRef), "name", stringRef), size("reversed"), field(firstStop("reversed"), "label", stringRef),
                        stringLiteral(" ", str), size("route"), field(firstStop("route"), "label", stringRef)
                    )
                )
            )
        )

        // fun copiedRouteSharesStops(): int {
        //     val route = Route("r", listOf(Point(1.0, "a")))
        //     val copy = route.with(name = "copy")
        //     copy.stops.add(Point(2.0, "b"))
        //     return route.stops.size()
        // }
        function(
            "copiedRouteSharesStops", int,
            body = listOf(
                varDecl("route", route, newRoute(newPoint(1.0, "a"))),
                varDecl("copy", route, memberCallWithArgs(local("route", routeRef), "with", "", listOf(named(stringLiteral("copy", str), 0)), resultTypeIndex = route)),
                addStop("copy", newPoint(2.0, "b")),
                returnStmt(size("route"))
            )
        )
    }

    private val program = ScriptCompiler().compile(CompilationInput(mapOf(scriptPath to script), plugin))

    private fun run(operations: Map<String, (ScriptFunctionCall) -> Any?>, function: String): Any? {
        val client = ScriptFunctionsClient(Loopback(operations), program.externalCalls, program.contributedClasses.values)
        return ExecutionEnvironment(program).invoke(scriptPath, function, SimpleScriptContext(System.out, null, client))
    }

    @Test
    fun `a script constructs, changes and copies a contributed record`() {
        assertEquals("Point(x=2.5, label=a) Point(x=2.5, label=b)", run(emptyMap(), "build"))
    }

    @Test
    fun `a returned record can be changed`() {
        val nearest: (ScriptFunctionCall) -> Any? = { RecordValue("Point", mapOf("x" to 2.5, "label" to "home")) }
        assertEquals(3.5, run(mapOf("nearest" to nearest), "mutateReturned"))
    }

    @Test
    fun `a record the script constructed is passed to an external function`() {
        val describe: (ScriptFunctionCall) -> Any? = { call ->
            val point = call.argument<RecordValue>(0)
            "${point.fields["label"]}@${point.fields["x"]}"
        }
        assertEquals("c@3.0", run(mapOf("describe" to describe), "passConstructed"))
    }

    private val point: (Double, String) -> RecordValue = { x, label -> RecordValue("Point", mapOf("x" to x, "label" to label)) }

    private val relabel: (ScriptFunctionCall) -> Any? = { call ->
        val original = call.argument<RecordValue>(0)
        RecordValue("Point", original.fields + ("label" to call.argument<String>(1)))
    }

    private val summarize: (ScriptFunctionCall) -> Any? = { call ->
        val route = call.argument<RecordValue>(0)
        val stops = route["stops"] as List<*>
        "${route["name"]}:" + stops.joinToString(",") { (it as RecordValue)["label"].toString() }
    }

    @Test
    fun `named arguments are bound before an external call`() {
        val describe: (ScriptFunctionCall) -> Any? = { call -> "@" + call.argument<RecordValue>(0)["label"] }
        assertEquals(
            "renamed@home",
            run(mapOf("relabel" to relabel, "describe" to describe, "nearest" to { point(0.0, "home") }), "namedArgumentsToExternal")
        )
    }

    @Test
    fun `a record returned for an argument is a new record, and the argument stays unchanged`() {
        assertEquals("kept new false", run(mapOf("relabel" to relabel), "argumentsStayUnchanged"))
    }

    @Test
    fun `contributed records are equal by content`() {
        assertEquals("truefalse2", run(emptyMap(), "equalContributedRecords"))
    }

    @Test
    fun `a list inside a record reaches the service with the changes the script made since the last call`() {
        assertEquals("r:a r:A,b", run(mapOf("summarize" to summarize), "routeWithStops"))
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun `an operation cannot change a list inside a record it is given`() {
        val extend: (ScriptFunctionCall) -> Any? = { call ->
            val stops = call.argument<RecordValue>(0)["stops"] as MutableList<Any?>
            stops.add(point(9.0, "z"))
            stops.size
        }
        val error = assertFailsWith<Throwable> { run(mapOf("extend" to extend), "serviceCannotChangeStops") }
        val messages = generateSequence(error) { it.cause }.mapNotNull { it.message }.toList()
        assertTrue(messages.any { "readonly" in it }, messages.toString())
    }

    @Test
    fun `a record with a list the service built is usable and independent of the argument`() {
        val reverse: (ScriptFunctionCall) -> Any? = { call ->
            val route = call.argument<RecordValue>(0)
            RecordValue("Route", mapOf("name" to route["name"], "stops" to (route["stops"] as List<*>).reversed()))
        }
        assertEquals("back3b 2a", run(mapOf("reverse" to reverse), "returnedRouteIsUsable"))
    }

    @Test
    fun `a copy of a contributed record shares its list`() {
        assertEquals(2, run(emptyMap(), "copiedRouteSharesStops"))
    }
}
