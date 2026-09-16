package com.mdeo.script.compiler

import com.mdeo.metamodel.Model
import com.mdeo.expression.ast.expressions.TypedExtensionCallArgument
import com.mdeo.expression.ast.expressions.TypedExtensionCallExpression
import com.mdeo.expression.ast.expressions.TypedExpression
import com.mdeo.expression.ast.types.ClassTypeRef
import com.mdeo.expression.ast.types.ReturnType
import com.mdeo.expression.ast.types.VoidType
import com.mdeo.script.ast.ExternalImplementation
import com.mdeo.script.ast.TypedAst
import com.mdeo.script.ast.TypedParameter
import com.mdeo.script.ast.TypedPluginAst
import com.mdeo.script.ast.TypedPluginFunction
import com.mdeo.script.ast.TypedPluginFunctionSignature
import com.mdeo.script.runtime.ExecutionEnvironment
import com.mdeo.script.runtime.ExternalCallDispatcher
import com.mdeo.script.runtime.SimpleScriptContext
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the stubs the compiler emits for contributed signatures implemented outside the
 * platform.
 *
 * A stub must be indistinguishable from a locally implemented overload at the call site, so these
 * tests call it exactly as a script would — through an extension call — and check what reaches
 * the dispatcher: the call id, the arguments in declaration order, and each argument boxed from
 * whatever primitive the descriptor carried. The result must come back unboxed to the declared
 * return type.
 */
class ExternalCallCompilerTest {

    companion object {
        private const val FILE_PATH = "test://external-call-test.script"
        private const val TEST_FN = "testFunction"
    }

    private val compiler = ScriptCompiler()

    /**
     * Records every call it receives and answers with a fixed value.
     */
    private class RecordingDispatcher(private val answer: Any?) : ExternalCallDispatcher {
        val calls = mutableListOf<Pair<String, List<Any?>>>()

        override fun call(callId: String, arguments: Array<Any?>, model: Model?, classLoader: ClassLoader): Any? {
            calls += callId to arguments.toList()
            return answer
        }
    }

    private fun externalPluginAst(
        funcName: String,
        operation: String,
        parameters: List<Pair<String, ReturnType>>,
        returnType: ReturnType
    ): TypedPluginAst {
        val types = mutableListOf<ReturnType>()
        fun addType(type: ReturnType): Int {
            val existing = types.indexOf(type)
            if (existing >= 0) return existing
            types += type
            return types.size - 1
        }
        val params = parameters.map { (name, type) -> TypedParameter(name, addType(type)) }
        val returnIndex = addType(returnType)
        return TypedPluginAst(
            types = types,
            functions = listOf(
                TypedPluginFunction(
                    name = funcName,
                    signatures = mapOf(
                        "" to TypedPluginFunctionSignature(
                            parameters = params,
                            returnType = returnIndex,
                            external = ExternalImplementation(operation = operation)
                        )
                    )
                )
            )
        )
    }

    private fun extensionCall(
        name: String,
        args: List<Pair<String, TypedExpression>>,
        resultTypeIndex: Int
    ) = TypedExtensionCallExpression(
        evalType = resultTypeIndex,
        name = name,
        overload = "",
        arguments = args.map { (paramName, expr) -> TypedExtensionCallArgument(name = paramName, value = expr) }
    )

    private fun compileAndRun(ast: TypedAst, pluginAst: TypedPluginAst, dispatcher: ExternalCallDispatcher?): Pair<CompiledProgram, Any?> {
        val program = compiler.compile(CompilationInput(mapOf(FILE_PATH to ast), pluginAst))
        val context = if (dispatcher != null) {
            SimpleScriptContext(System.out, null, dispatcher)
        } else {
            SimpleScriptContext(System.out, null)
        }
        return program to ExecutionEnvironment(program).invoke(FILE_PATH, TEST_FN, context)
    }

    @Test
    fun `boxes primitive and reference arguments in declaration order`() {
        val pluginAst = externalPluginAst(
            funcName = "score",
            operation = "computeScore",
            parameters = listOf(
                "count" to ClassTypeRef("builtin", "int", false),
                "label" to ClassTypeRef("builtin", "string", false),
                "weight" to ClassTypeRef("builtin", "long", false),
                "strict" to ClassTypeRef("builtin", "boolean", false)
            ),
            returnType = ClassTypeRef("builtin", "Any", true)
        )

        val ast = buildTypedAst {
            val anyType = anyNullableType()
            function(
                name = TEST_FN,
                returnType = anyType,
                body = listOf(
                    returnStmt(
                        extensionCall(
                            "score",
                            listOf(
                                "count" to intLiteral(7, intType()),
                                "label" to stringLiteral("tasks", stringType()),
                                "weight" to longLiteral(9_000_000_000L, longType()),
                                "strict" to booleanLiteral(true, booleanType())
                            ),
                            anyType
                        )
                    )
                )
            )
        }

        val dispatcher = RecordingDispatcher(answer = "answered")
        val (program, result) = compileAndRun(ast, pluginAst, dispatcher)

        assertEquals("answered", result)
        assertEquals(1, dispatcher.calls.size)
        val (callId, arguments) = dispatcher.calls.single()
        assertEquals(listOf(7, "tasks", 9_000_000_000L, true), arguments)

        val spec = program.externalCalls[callId]
        assertTrue(spec != null, "the call id the stub passes must name a compiled spec")
        assertEquals("score", spec.functionName)
        assertEquals("computeScore", spec.operation)
        assertEquals(ExternalImplementation.MODEL_NONE, spec.model)
        assertEquals(4, spec.parameterTypes.size)
    }

    @Test
    fun `unboxes a primitive result to the declared return type`() {
        val pluginAst = externalPluginAst(
            funcName = "half",
            operation = "half",
            parameters = listOf("value" to ClassTypeRef("builtin", "double", false)),
            returnType = ClassTypeRef("builtin", "double", false)
        )

        val ast = buildTypedAst {
            val doubleType = doubleType()
            function(
                name = TEST_FN,
                returnType = doubleType,
                body = listOf(
                    returnStmt(
                        extensionCall("half", listOf("value" to doubleLiteral(3.0, doubleType)), doubleType)
                    )
                )
            )
        }

        val dispatcher = RecordingDispatcher(answer = 1.5)
        val (_, result) = compileAndRun(ast, pluginAst, dispatcher)

        assertEquals(1.5, result)
        assertEquals(listOf<Any?>(3.0), dispatcher.calls.single().second)
    }

    @Test
    fun `a void external call discards whatever the dispatcher returns`() {
        val pluginAst = externalPluginAst(
            funcName = "notify",
            operation = "notify",
            parameters = listOf("message" to ClassTypeRef("builtin", "string", false)),
            returnType = VoidType()
        )

        val ast = buildTypedAst {
            val voidType = voidType()
            function(
                name = TEST_FN,
                returnType = anyNullableType(),
                body = listOf(
                    exprStmt(extensionCall("notify", listOf("message" to stringLiteral("hi", stringType())), voidType)),
                    returnStmt(nullLiteral(anyNullableType()))
                )
            )
        }

        val dispatcher = RecordingDispatcher(answer = "ignored")
        val (_, result) = compileAndRun(ast, pluginAst, dispatcher)

        assertNull(result)
        assertEquals(listOf<Any?>("hi"), dispatcher.calls.single().second)
    }

    @Test
    fun `a context without a dispatcher fails with a message naming the call`() {
        val pluginAst = externalPluginAst(
            funcName = "remote",
            operation = "remote",
            parameters = emptyList(),
            returnType = ClassTypeRef("builtin", "Any", true)
        )

        val ast = buildTypedAst {
            val anyType = anyNullableType()
            function(
                name = TEST_FN,
                returnType = anyType,
                body = listOf(returnStmt(extensionCall("remote", emptyList(), anyType)))
            )
        }

        val error = assertFailsWith<UnsupportedOperationException> { compileAndRun(ast, pluginAst, null) }
        assertTrue(error.message!!.contains("cannot be called here"))
    }

    @Test
    fun `a signature must carry exactly one of a body and an external implementation`() {
        assertFailsWith<IllegalArgumentException> {
            TypedPluginFunctionSignature(parameters = emptyList(), returnType = 0)
        }
    }
}
