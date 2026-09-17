package com.mdeo.script.language

import com.mdeo.expression.ast.expressions.TypedExpression
import com.mdeo.expression.ast.statements.TypedStatement
import com.mdeo.script.ast.TypedAst
import com.mdeo.script.ast.TypedPluginAst
import com.mdeo.script.ast.expressions.TypedExpressionSerializer
import com.mdeo.script.ast.statements.TypedStatementSerializer
import com.mdeo.script.compiler.CompilationInput
import com.mdeo.script.compiler.CompiledProgram
import com.mdeo.script.compiler.ScriptCompiler
import com.mdeo.script.runtime.ExecutionEnvironment
import com.mdeo.script.runtime.SimpleScriptContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule

/**
 * Compiles typed ASTs the script frontend produced and runs their functions.
 *
 * Each test resource `language/<name>.json` is the typed AST the script service emits for the
 * script `language/<name>.fn` next to it, saved as `/main.fn` unless a test says otherwise.
 */
abstract class FrontendAstTest {

    private val json = Json {
        ignoreUnknownKeys = true
        serializersModule = SerializersModule {
            contextual(TypedExpression::class, TypedExpressionSerializer)
            contextual(TypedStatement::class, TypedStatementSerializer)
        }
    }

    /**
     * Loads and compiles a script.
     *
     * @param name The resource name, without extension
     * @param pluginAst The typed AST of the contributions the script was checked against
     * @return The compiled program, with the script at [SCRIPT_PATH]
     */
    protected fun compile(name: String, pluginAst: TypedPluginAst? = null): CompiledProgram =
        compile(mapOf(SCRIPT_PATH to name), pluginAst)

    /**
     * Loads and compiles scripts that import each other.
     *
     * @param files The resource name of each script, without extension, by the path it was saved as
     * @param pluginAst The typed AST of the contributions the scripts were checked against
     * @return The compiled program
     */
    protected fun compile(files: Map<String, String>, pluginAst: TypedPluginAst? = null): CompiledProgram =
        ScriptCompiler().compile(
            CompilationInput(
                files.mapValues { (_, name) -> json.decodeFromString<TypedAst>(resource("$name.json")) },
                pluginAst
            )
        )

    /**
     * Decodes a plugin AST resource.
     *
     * @param name The resource name, without extension
     * @return The plugin AST
     */
    protected fun pluginAst(name: String): TypedPluginAst = json.decodeFromString(resource("$name.json"))

    /**
     * Runs a function of a compiled script.
     *
     * @param program The compiled program
     * @param function The function name
     * @param args The arguments
     * @return What the function returns
     */
    protected fun run(program: CompiledProgram, function: String, vararg args: Any?): Any? =
        runIn(program, SCRIPT_PATH, function, *args)

    /**
     * Runs a function of one of several compiled scripts.
     *
     * @param program The compiled program
     * @param path The path of the script declaring the function
     * @param function The function name
     * @param args The arguments
     * @return What the function returns
     */
    protected fun runIn(program: CompiledProgram, path: String, function: String, vararg args: Any?): Any? =
        ExecutionEnvironment(program).invoke(path, function, SimpleScriptContext(System.out, null), *args)

    private fun resource(name: String) =
        javaClass.getResourceAsStream("/language/$name")!!.readBytes().decodeToString()

    companion object {
        /**
         * The path the scripts were checked under.
         */
        const val SCRIPT_PATH = "/main.fn"
    }
}
