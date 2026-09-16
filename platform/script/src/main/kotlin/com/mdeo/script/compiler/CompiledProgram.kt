package com.mdeo.script.compiler

import com.mdeo.expression.ast.types.ReturnType
import com.mdeo.metamodel.Metamodel

/**
 * Represents a compiled script program ready for class-loading and execution.
 *
 * All functions from all script files are compiled into a single JVM class ([SCRIPT_PROGRAM_BINARY_NAME]).
 * The [functionLookup] maps each (filePath, functionName) pair to the artificial JVM method name
 * (`fn0`, `fn1`, ...) assigned during compilation.
 *
 * All bytecodes (the script program class, generated lambda interfaces, and metamodel classes)
 * are stored in [allBytecodes] keyed by JVM binary class name (dot-separated).
 *
 * @param allBytecodes   All bytecodes keyed by JVM binary class name.
 * @param functionLookup Maps each script file path to a map of function name → JVM method name.
 * @param metamodel      The compiled metamodel, or null if no metamodel was used.
 * @param externalCalls  One entry per contributed signature whose implementation lives outside
 *                       the platform, keyed by the call id the generated stub passes to the
 *                       dispatcher. A program with no such signature has none.
 */
data class CompiledProgram(
    val allBytecodes: Map<String, ByteArray>,
    val functionLookup: Map<String, Map<String, String>> = emptyMap(),
    val metamodel: Metamodel? = null,
    val externalCalls: Map<String, ExternalCallSpec> = emptyMap()
) {
    companion object {
        /**
         * JVM binary class name (dot-separated) of the single generated script class. 
         */
        const val SCRIPT_PROGRAM_BINARY_NAME = "com.mdeo.script.generated.ScriptProgram"

        /**
         * JVM internal class name (slash-separated) of the single generated script class. 
         */
        const val SCRIPT_PROGRAM_INTERNAL_NAME = "com/mdeo/script/generated/ScriptProgram"
    }
}

/**
 * Everything a dispatcher needs to know about one compiled external call.
 *
 * The generated stub carries only the call id; the types it was compiled against live here, so
 * a dispatcher can encode arguments and decode a result without re-deriving anything from the
 * bytecode.
 *
 * @param callId        Identifies this signature. The stub passes it to
 *                      [com.mdeo.script.runtime.ExternalCallDispatcher.call].
 * @param functionName  The function name as the script sees it.
 * @param overloadKey   Which overload of that name this is.
 * @param operation     The operation name within the plugin's own protocol.
 * @param model         Whether the operation reads the model, and in what form. One of
 *                      [com.mdeo.script.ast.ExternalImplementation.MODEL_NONE] and
 *                      [com.mdeo.script.ast.ExternalImplementation.MODEL_VERSIONED].
 * @param parameterTypes Declared parameter types, in declaration order.
 * @param returnType    Declared return type.
 * @param contribution  Id of the contribution whose service answers the call.
 * @param session       Name of that contribution's `script-functions` session, or null when it
 *                      declares none.
 */
data class ExternalCallSpec(
    val callId: String,
    val functionName: String,
    val overloadKey: String,
    val operation: String,
    val model: String,
    val parameterTypes: List<ReturnType>,
    val returnType: ReturnType,
    val contribution: String = "",
    val session: String? = null
)
