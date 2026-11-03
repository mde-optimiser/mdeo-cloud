package com.mdeo.script.external

import com.mdeo.expression.ast.types.ClassTypeRef
import com.mdeo.script.compiler.CompilationInput
import com.mdeo.script.compiler.ScriptCompiler
import com.mdeo.script.compiler.buildTypedAst
import com.mdeo.script.compiler.concat
import com.mdeo.script.compiler.doubleLiteral
import com.mdeo.script.compiler.functionCall
import com.mdeo.script.compiler.functionCallWithArgs
import com.mdeo.script.compiler.identifier
import com.mdeo.script.compiler.memberAccess
import com.mdeo.script.compiler.named
import com.mdeo.script.compiler.returnStmt
import com.mdeo.script.compiler.stringLiteral
import com.mdeo.script.runtime.ExecutionEnvironment
import com.mdeo.script.runtime.SimpleScriptContext
import com.mdeo.scriptfunctions.service.ScriptFunctionCall
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Scripts leaving out parameters of contributed functions and fields of contributed records.
 *
 * The contribution `geo` declares `area(w: double, h: double = 2.0): double`,
 * `scale(x: double, y: double = x): double` and the record `Tag(name: string, weight: double = 5.0)`.
 * The defaults are evaluated in the script, so the service is always sent every argument.
 */
class ContributedDefaultsTest {

    private val scriptPath = "/main.fn"
    private val doubleRef = ClassTypeRef("builtin", "double", false)
    private val stringRef = ClassTypeRef("builtin", "string", false)
    private val tagRef = ClassTypeRef("contrib/geo", "Tag", false)

    private val plugin = pluginAst("geo") {
        external("area", doubleRef, listOf(plain("w", doubleRef), defaulted("h", doubleRef) { doubleLiteral(2.0, it) }))
        external("scale", doubleRef, listOf(plain("x", doubleRef), defaulted("y", doubleRef) { identifier("x", it, 2) }))
        record("Tag", listOf(plain("name", stringRef), defaulted("weight", doubleRef) { doubleLiteral(5.0, it) }))
    }

    /**
     * ```
     * fun testFunction(): string {
     *     return "" + area(3.0) + " " + area(h = 4.0, w = 3.0) + " " + scale(1.5) + " " + Tag("a").weight
     * }
     * ```
     */
    private val script = buildTypedAst {
        val str = stringType()
        val double = doubleType()
        val tag = addType(tagRef)
        function(
            "testFunction", str,
            body = listOf(
                returnStmt(
                    concat(
                        str, stringLiteral("", str),
                        functionCall("area", "", listOf(doubleLiteral(3.0, double)), double),
                        stringLiteral(" ", str),
                        functionCallWithArgs("area", "", listOf(named(doubleLiteral(4.0, double), 1), named(doubleLiteral(3.0, double), 0)), double),
                        stringLiteral(" ", str),
                        functionCall("scale", "", listOf(doubleLiteral(1.5, double)), double),
                        stringLiteral(" ", str),
                        memberAccess(functionCall("Tag", "", listOf(stringLiteral("a", str)), tag), "weight", resultTypeIndex = double)
                    )
                )
            )
        )
    }

    @Test
    fun `left-out parameters and fields take their defaults, and the service gets every argument`() {
        val program = ScriptCompiler().compile(CompilationInput(mapOf(scriptPath to script), plugin))
        val received = mutableListOf<List<Any?>>()
        val operations = mapOf<String, (ScriptFunctionCall) -> Any?>(
            "area" to { call -> received += call.arguments.toList(); call.argument<Double>(0) * call.argument<Double>(1) },
            "scale" to { call -> received += call.arguments.toList(); call.argument<Double>(0) * call.argument<Double>(1) }
        )
        val client = ScriptFunctionsClient(Loopback(operations), program.externalCalls, program.contributedClasses.values)
        val result = ExecutionEnvironment(program).invoke(scriptPath, "testFunction", SimpleScriptContext(System.out, null, client))
        assertEquals<Any?>("6.0 12.0 2.25 5.0", result)
        assertEquals<List<List<Any?>>>(listOf(listOf(3.0, 2.0), listOf(3.0, 4.0), listOf(1.5, 1.5)), received)
    }
}
