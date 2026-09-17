package com.mdeo.script.external

import com.mdeo.expression.ast.expressions.TypedExpression
import com.mdeo.expression.ast.statements.TypedStatement
import com.mdeo.script.ast.TypedAst
import com.mdeo.script.ast.TypedPluginAst
import com.mdeo.script.ast.expressions.TypedExpressionSerializer
import com.mdeo.script.ast.statements.TypedStatementSerializer
import com.mdeo.script.compiler.CompilationInput
import com.mdeo.script.compiler.ScriptCompiler
import com.mdeo.script.runtime.ExecutionEnvironment
import com.mdeo.script.runtime.SimpleScriptContext
import com.mdeo.scriptfunctions.service.RecordValue
import com.mdeo.scriptfunctions.service.ScriptFunctionCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Scripts constructing, changing and passing the records a contribution defines, end to end from
 * what the script frontend produces.
 *
 * The two ASTs are the typed ASTs the script service emits for a contribution `geo` declaring the
 * records `Point(x: double, label: string)` and `Route(name: string, stops: List<Point>)` with the
 * external functions `nearest(): Point`, `describe(p: Point): string`,
 * `relabel(point: Point, label: string): Point`, `summarize(route: Route): string`,
 * `extend(route: Route): int` and `reverse(route: Route): Route`, and for a script whose functions
 * the tests name. Arguments of external functions are in-only: an operation sees them readonly, and
 * a script never sees its values changed by a call.
 */
class ContributedRecordsTest {

    private val json = Json {
        ignoreUnknownKeys = true
        serializersModule = SerializersModule {
            contextual(TypedExpression::class, TypedExpressionSerializer)
            contextual(TypedStatement::class, TypedStatementSerializer)
        }
    }

    private val scriptPath = "/main.fn"

    private fun resource(name: String) =
        javaClass.getResourceAsStream("/external/$name")!!.readBytes().decodeToString()

    private val program = ScriptCompiler().compile(
        CompilationInput(
            mapOf(scriptPath to json.decodeFromString<TypedAst>(resource("geo-records-script-ast.json"))),
            json.decodeFromString<TypedPluginAst>(resource("geo-records-plugin-ast.json"))
        )
    )

    private fun run(operations: Map<String, (ScriptFunctionCall) -> Any?>, function: String): Any? {
        val client = ScriptFunctionsClient(Loopback(operations), program.externalCalls, program.contributedClasses)
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
