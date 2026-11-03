package com.mdeo.script.external

import com.mdeo.expression.ast.types.ClassTypeRef
import com.mdeo.script.ast.TypeKey
import com.mdeo.script.compiler.CompilationInput
import com.mdeo.script.compiler.ScriptCompiler
import com.mdeo.script.compiler.binaryExpr
import com.mdeo.script.compiler.buildTypedAst
import com.mdeo.script.compiler.doubleLiteral
import com.mdeo.script.compiler.forStmt
import com.mdeo.script.compiler.functionCall
import com.mdeo.script.compiler.identifier
import com.mdeo.script.compiler.intLiteral
import com.mdeo.script.compiler.memberAccess
import com.mdeo.script.compiler.assignment
import com.mdeo.script.compiler.param
import com.mdeo.script.compiler.returnStmt
import com.mdeo.script.compiler.varDecl
import com.mdeo.script.runtime.ExecutionEnvironment
import com.mdeo.script.runtime.ScriptOpaque
import com.mdeo.script.runtime.ScriptRecord
import com.mdeo.script.runtime.SimpleScriptContext
import com.mdeo.scriptfunctions.protocol.ClientMessage
import com.mdeo.scriptfunctions.service.RecordValue
import com.mdeo.scriptfunctions.service.ScriptFunctionCall
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Records and opaque classes a contribution defines, returned by its external functions.
 *
 * The contribution `geo` declares a record `Point(x: double, label: string)`, an opaque class
 * `Index` and the functions `nearest(): Point`, `points(n: int): ReadonlyList<Point>`,
 * `buildIndex(): Index` and `query(i: Index, k: int): int`, and the script is:
 *
 * ```
 * fun total(): double { val p = nearest(); return p.x }
 * fun label(): string { return nearest().label }
 * fun same(): boolean { return nearest() == nearest() }
 * fun sumAll(n: int): double { var total = 0.0; for (p in points(n)) { total = total + p.x }; return total }
 * fun lookup(): int { val idx = buildIndex(); return query(idx, 3) + query(idx, 4) }
 * ```
 */
class ContributedClassesTest {

    private val scriptPath = "/p/test.fn"

    private val double = ClassTypeRef("builtin", "double", false)
    private val int = ClassTypeRef("builtin", "int", false)

    private val plugin = pluginAst("geo") {
        record("Point", "x" to double, "label" to ClassTypeRef("builtin", "string", false))
        opaque("Index")
        external("nearest", classType("Point"))
        external("points", ClassTypeRef("builtin", "ReadonlyList", false, mapOf("T" to classType("Point"))), "n" to int)
        external("buildIndex", classType("Index"))
        external("query", int, "i" to classType("Index"), "k" to int)
    }

    private val script = buildTypedAst {
        val doubleType = doubleType()
        val stringType = stringType()
        val intType = intType()
        val point = addType(ClassTypeRef("contrib/geo", "Point", false))
        val index = addType(ClassTypeRef("contrib/geo", "Index", false))
        val points = addType(ClassTypeRef("builtin", "ReadonlyList", false, mapOf("T" to ClassTypeRef("contrib/geo", "Point", false))))
        fun nearest() = functionCall("nearest", "", emptyList(), point)
        function(
            "total", doubleType,
            body = listOf(varDecl("p", point, nearest()), returnStmt(memberAccess(identifier("p", point, 3), "x", resultTypeIndex = doubleType)))
        )
        function("label", stringType, body = listOf(returnStmt(memberAccess(nearest(), "label", resultTypeIndex = stringType))))
        function("same", booleanType(), body = listOf(returnStmt(binaryExpr(nearest(), "==", nearest(), booleanType()))))
        function(
            "sumAll", doubleType, listOf(param("n", intType)),
            listOf(
                varDecl("total", doubleType, doubleLiteral(0.0, doubleType)),
                forStmt(
                    "p", point, functionCall("points", "", listOf(identifier("n", intType, 2)), points),
                    listOf(
                        assignment(
                            identifier("total", doubleType, 3),
                            binaryExpr(identifier("total", doubleType, 3), "+", memberAccess(identifier("p", point, 4), "x", resultTypeIndex = doubleType), doubleType)
                        )
                    )
                ),
                returnStmt(identifier("total", doubleType, 3))
            )
        )
        fun query(k: Int) = functionCall("query", "", listOf(identifier("idx", index, 3), intLiteral(k, intType)), intType)
        function(
            "lookup", intType,
            body = listOf(
                varDecl("idx", index, functionCall("buildIndex", "", emptyList(), index)),
                returnStmt(binaryExpr(query(3), "+", query(4), intType))
            )
        )
    }

    private val program = ScriptCompiler().compile(CompilationInput(mapOf(scriptPath to script), plugin))

    private fun run(
        operations: Map<String, (ScriptFunctionCall) -> Any?>,
        function: String,
        vararg args: Any?,
        dropBeforeCall: Int? = null
    ): Pair<Any?, Loopback> {
        val loopback = Loopback(operations).also { it.dropBeforeCall = dropBeforeCall }
        val client = ScriptFunctionsClient(loopback, program.externalCalls, program.contributedClasses.values)
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
        val point = program.contributedClasses.getValue(TypeKey("contrib/geo", "Point"))
        val index = program.contributedClasses.getValue(TypeKey("contrib/geo", "Index"))
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
