package com.mdeo.script.runtime

import com.mdeo.metamodel.Model
import java.io.PrintStream

/**
 * Context for script execution, providing access to the output stream and optional model.
 *
 * Each compiled script class receives a [ScriptContext] instance through its constructor,
 * replacing the previous thread-local [ExecutionContext] approach. This enables safe
 * concurrent execution of scripts with different contexts.
 */
interface ScriptContext {

    /**
     * The output stream for `println` and other print functions.
     */
    val printStream: PrintStream

    /**
     * The model for metamodel-aware scripts, or null when no metamodel is used.
     */
    val model: Model?

    /**
     * Answers calls to contributed functions implemented outside the platform.
     *
     * Defaults to [ExternalCallDispatcher.UNSUPPORTED], so an execution path that cannot reach
     * a plugin's service still compiles and runs every script that does not call one.
     */
    val externalCalls: ExternalCallDispatcher
        get() = ExternalCallDispatcher.UNSUPPORTED
}

/**
 * Simple data-class implementation of [ScriptContext].
 *
 * @param printStream The output stream for print functions.
 * @param model The model for metamodel-aware scripts, or null.
 * @param externalCalls Dispatcher for contributed functions implemented outside the platform.
 */
class SimpleScriptContext(
    override val printStream: PrintStream,
    override val model: Model?,
    override val externalCalls: ExternalCallDispatcher = ExternalCallDispatcher.UNSUPPORTED
) : ScriptContext
