package com.mdeo.script.external

import com.mdeo.expression.ast.types.ClassTypeRef
import com.mdeo.metamodel.data.EnumData
import com.mdeo.metamodel.data.MetamodelData
import com.mdeo.script.compiler.CompilationInput
import com.mdeo.script.compiler.ScriptCompiler
import com.mdeo.script.compiler.TypedAstBuilder
import com.mdeo.script.compiler.binaryExpr
import com.mdeo.script.compiler.buildTypedAst
import com.mdeo.script.compiler.concat
import com.mdeo.script.compiler.functionCall
import com.mdeo.script.compiler.identifier
import com.mdeo.script.compiler.intLiteral
import com.mdeo.script.compiler.memberAccess
import com.mdeo.script.compiler.returnStmt
import com.mdeo.script.compiler.stringLiteral
import com.mdeo.script.compiler.varDecl
import com.mdeo.script.runtime.ExecutionEnvironment
import com.mdeo.script.runtime.SimpleScriptContext
import com.mdeo.scriptfunctions.service.RecordValue
import com.mdeo.scriptfunctions.service.ScriptEnumValue
import com.mdeo.scriptfunctions.service.ScriptFunctionCall
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Scripts passing enum values of their metamodel to external functions and getting them back.
 *
 * The metamodel declares `enum Style { Modern, Classic }`. The contribution `houses` declares the
 * record `Plan(style: Style, rooms: int)` with the external functions `restyle(plan: Plan): Plan`
 * and `favourite(styles: ReadonlyList<Style>): Style`. Scripts run without a model: enum values
 * need none.
 */
class ContributedEnumsTest {

    private val scriptPath = "/main.fn"
    private val metamodelPath = "/houses.mm"

    private val metamodelData = MetamodelData(
        path = metamodelPath,
        enums = listOf(EnumData(name = "Style", entries = listOf("Modern", "Classic")))
    )

    private val styleRef = ClassTypeRef("enum$metamodelPath", "Style", false)
    private val styleContainerRef = ClassTypeRef("enum-container$metamodelPath", "Style", false)
    private val intRef = ClassTypeRef("builtin", "int", false)
    private val planRef = ClassTypeRef("contrib/houses", "Plan", false)
    private val stylesRef = ClassTypeRef("builtin", "ReadonlyList", false, mapOf("T" to styleRef))

    private val plugin = pluginAst("houses") {
        record("Plan", "style" to styleRef, "rooms" to intRef)
        external("restyle", planRef, "plan" to planRef)
        external("favourite", styleRef, "styles" to stylesRef)
    }

    private fun TypedAstBuilder.style(entry: String) =
        memberAccess(identifier("Style", addType(styleContainerRef), scope = 1), entry, resultTypeIndex = addType(styleRef))

    private val script = buildTypedAst {
        val str = stringType()
        val bool = booleanType()
        val style = addType(styleRef)
        val plan = addType(planRef)
        val list = addType(ClassTypeRef("builtin", "List", false, mapOf("T" to styleRef)))

        // fun restyled(): string {
        //     val p = restyle(Plan(Style.Modern, 3))
        //     return "" + p.style + " " + (p.style == Style.Classic)
        // }
        val p = identifier("p", plan, 3)
        function(
            "restyled", str,
            body = listOf(
                varDecl("p", plan, functionCall("restyle", "", listOf(functionCall("Plan", "", listOf(style("Modern"), intLiteral(3, intType())), plan)), plan)),
                returnStmt(
                    concat(
                        str, stringLiteral("", str), memberAccess(p, "style", resultTypeIndex = style), stringLiteral(" ", str),
                        binaryExpr(memberAccess(p, "style", resultTypeIndex = style), "==", style("Classic"), bool)
                    )
                )
            )
        )

        // fun pick(): boolean {
        //     return favourite(listOf(Style.Modern, Style.Classic)) == Style.Classic
        // }
        function(
            "pick", bool,
            body = listOf(
                returnStmt(
                    binaryExpr(
                        functionCall("favourite", "", listOf(functionCall("listOf", "", listOf(style("Modern"), style("Classic")), list)), style),
                        "==", style("Classic"), bool
                    )
                )
            )
        )
    }

    private val program = ScriptCompiler().compile(CompilationInput(mapOf(scriptPath to script), plugin), metamodelData)

    private fun run(operations: Map<String, (ScriptFunctionCall) -> Any?>, function: String): Any? {
        val client = ScriptFunctionsClient(Loopback(operations), program.externalCalls, program.contributedClasses.values)
        return ExecutionEnvironment(program).invoke(scriptPath, function, SimpleScriptContext(System.out, null, client))
    }

    private val restyle: (ScriptFunctionCall) -> Any? = { call ->
        val plan = call.argument<RecordValue>(0)
        val style = plan["style"] as ScriptEnumValue
        RecordValue("Plan", plan.fields + ("style" to style.copy(entry = if (style.entry == "Modern") "Classic" else "Modern")))
    }

    @Test
    fun `an enum field of a record goes to the service and comes back as the script's entry`() {
        assertEquals("Classic true", run(mapOf("restyle" to restyle), "restyled"))
    }

    @Test
    fun `enum values in a list reach the service, and a returned one equals the script's`() {
        var seen: Any? = null
        val favourite: (ScriptFunctionCall) -> Any? = { call ->
            val styles = call.argument<List<ScriptEnumValue>>(0)
            seen = styles
            styles.last()
        }
        assertEquals(true, run(mapOf("favourite" to favourite), "pick"))
        assertEquals(listOf(ScriptEnumValue("Style", "Modern"), ScriptEnumValue("Style", "Classic")), seen)
    }

    @Test
    fun `an entry the metamodel does not declare is rejected`() {
        val error = assertFailsWith<Throwable> { run(mapOf("favourite" to { ScriptEnumValue("Style", "Gothic") }), "pick") }
        assertTrue(generateSequence(error) { it.cause }.any { it is ExternalCallException && "Style.Gothic" in it.message!! })
    }
}
