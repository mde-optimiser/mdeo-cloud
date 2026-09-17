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
import com.mdeo.script.runtime.ScriptOpaque
import com.mdeo.script.runtime.ScriptRecord
import com.mdeo.script.runtime.SimpleScriptContext
import com.mdeo.scriptfunctions.protocol.ClientMessage
import com.mdeo.scriptfunctions.service.RecordValue
import com.mdeo.scriptfunctions.service.ScriptFunctionCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Records and opaque classes, end to end from what the script frontend produces.
 *
 * The two ASTs are the typed ASTs the script service emits for a contribution `geo` declaring a
 * record `Point(x: double, label: string)` and an opaque class `Index`, and for this script:
 *
 * ```
 * fun total(): double { var p = nearest(); return p.x }
 * fun label(): string { return nearest().label }
 * fun same(): boolean { return nearest() == nearest() }
 * fun sumAll(n: int): double { var total = 0.0; for (p in points(n)) { total = total + p.x }; return total }
 * fun lookup(): int { var idx = buildIndex(); return query(idx, 3) + query(idx, 4) }
 * ```
 */
class ContributedClassesTest {

    private val json = Json {
        ignoreUnknownKeys = true
        serializersModule = SerializersModule {
            contextual(TypedExpression::class, TypedExpressionSerializer)
            contextual(TypedStatement::class, TypedStatementSerializer)
        }
    }

    private val scriptPath = "/p/test.fn"

    private fun resource(name: String) =
        javaClass.getResourceAsStream("/external/$name")!!.readBytes().decodeToString()

    private val program = ScriptCompiler().compile(
        CompilationInput(
            mapOf(scriptPath to json.decodeFromString<TypedAst>(resource("geo-script-ast.json"))),
            json.decodeFromString<TypedPluginAst>(resource("geo-plugin-ast.json"))
        )
    )

    private fun run(
        operations: Map<String, (ScriptFunctionCall) -> Any?>,
        function: String,
        vararg args: Any?,
        dropBeforeCall: Int? = null
    ): Pair<Any?, Loopback> {
        val loopback = Loopback(operations).also { it.dropBeforeCall = dropBeforeCall }
        val client = ScriptFunctionsClient(loopback, program.externalCalls, program.contributedClasses)
        val context = SimpleScriptContext(System.out, null, client)
        return ExecutionEnvironment(program).invoke(scriptPath, function, context, *args) to loopback
    }

    private val nearest: (ScriptFunctionCall) -> Any? = { RecordValue("Point", mapOf("x" to 2.5, "label" to "home")) }

    @Test
    fun `a script reads the fields of a returned record`() {
        assertEquals(2.5, run(mapOf("nearest" to nearest), "total").first)
        assertEquals("home", run(mapOf("nearest" to nearest), "label").first)
    }

    @Test
    fun `records are equal by content`() {
        assertEquals(true, run(mapOf("nearest" to nearest), "same").first)
    }

    @Test
    fun `a large list of records is read by property`() {
        val points: (ScriptFunctionCall) -> Any? = { call ->
            val n = call.argument<Int>(0)
            List(n) { RecordValue("Point", mapOf("x" to it.toDouble(), "label" to "p$it")) }
        }
        val n = 20_000
        val (total, _) = run(mapOf("points" to points), "sumAll", n)
        assertEquals((0 until n).sumOf { it.toDouble() }, total)
    }

    @Test
    fun `an opaque index built in one call is reused in later calls`() {
        class Index(val built: Long)
        val built = mutableListOf<Index>()
        val seen = mutableListOf<Any?>()
        lateinit var geoIndex: com.mdeo.scriptfunctions.service.OpaqueType
        val contribution = com.mdeo.scriptfunctions.service.scriptContribution("geo") {
            geoIndex = opaque("Index")
        }
        val operations = mapOf<String, (ScriptFunctionCall) -> Any?>(
            "buildIndex" to { geoIndex.wrap(Index(System.nanoTime()).also { built += it }) },
            "query" to { call -> seen += call.argument<Index>(0); call.argument<Int>(1) }
        )

        val (result, loopback) = run(operations, "lookup")

        assertEquals(7, result)
        assertEquals(1, built.size, "the index is built once")
        assertEquals(listOf<Any?>(built.single(), built.single()), seen, "both queries get the very same state")
        assertTrue(contribution.opaqueClasses.containsKey("Index"))
        assertTrue(loopback.received.filterIsInstance<ClientMessage.Call>().size == 3)
    }

    @Test
    fun `a handle whose state was lost with the connection is refused`() {
        class Index
        lateinit var geoIndex: com.mdeo.scriptfunctions.service.OpaqueType
        com.mdeo.scriptfunctions.service.scriptContribution("geo") { geoIndex = opaque("Index") }
        val operations = mapOf<String, (ScriptFunctionCall) -> Any?>(
            "buildIndex" to { geoIndex.wrap(Index()) },
            "query" to { call -> call.argument<Int>(1) }
        )

        // The first query reaches a service that never built the index.
        val failure = runCatching { run(operations, "lookup", dropBeforeCall = 2) }.exceptionOrNull()
        val messages = generateSequence(failure) { it.cause }.mapNotNull { it.message }.toList()
        assertTrue(messages.any { "was lost" in it }, "got: $messages")
    }

    @Test
    fun `generated classes keep records and handles apart`() {
        val point = program.contributedClasses.getValue("contrib/geo.Point")
        val index = program.contributedClasses.getValue("contrib/geo.Index")
        val loader = ExecutionEnvironment(program).classLoader
        assertTrue(ScriptRecord::class.java.isAssignableFrom(loader.loadClass(point.jvmClassName.replace('/', '.'))))
        assertTrue(ScriptOpaque::class.java.isAssignableFrom(loader.loadClass(index.jvmClassName.replace('/', '.'))))
    }

    @Test
    fun `a record the contribution does not define is rejected`() {
        val bogus: (ScriptFunctionCall) -> Any? = { RecordValue("Circle", mapOf("r" to 1.0)) }
        val error = assertFailsWith<Exception> { run(mapOf("nearest" to bogus), "total") }
        assertTrue(generateSequence<Throwable>(error) { it.cause }.any { it is ExternalCallException && "Circle" in it.message!! })
    }
}
