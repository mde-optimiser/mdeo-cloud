package com.mdeo.optimizer.guidance

import com.mdeo.optimizer.config.ObjectiveTendency
import com.mdeo.optimizer.solution.Solution
import com.mdeo.script.runtime.ExternalCallDispatcher
import com.mdeo.script.runtime.ScriptContext
import com.mdeo.script.runtime.SimpleScriptContext
import org.slf4j.LoggerFactory
import java.io.PrintStream
import java.lang.reflect.InvocationTargetException

/**
 * [GuidanceFunction] that evaluates a compiled script method via reflection.
 *
 * The script class is instantiated with a [SimpleScriptContext] wrapping the solution's
 * model graph, then the named method is invoked and its return value is coerced to [Double].
 * For [ObjectiveTendency.MAXIMIZE] objectives the raw value is negated so that MOEA Framework,
 * which minimises internally, receives the correct directional signal.
 *
 * @param clazz The compiled script class to instantiate.
 * @param jvmMethodName The no-arg method on [clazz] to call for the fitness value.
 * @param printStream Output stream made available to scripts via the [ScriptContext].
 * @param name Human-readable name of this guidance function.
 * @param tendency Whether to minimise or maximise the function value. Defaults to [ObjectiveTendency.MINIMIZE].
 * @param externalCalls Dispatcher for contributed functions implemented outside the platform.
 */
class ScriptGuidanceFunction(
    private val clazz: Class<*>,
    private val jvmMethodName: String,
    private val printStream: PrintStream,
    override val name: String,
    private val tendency: ObjectiveTendency = ObjectiveTendency.MINIMIZE,
    private val externalCalls: ExternalCallDispatcher = ExternalCallDispatcher.UNSUPPORTED
) : GuidanceFunction {

    private val logger = LoggerFactory.getLogger(ScriptGuidanceFunction::class.java)

    override fun computeFitness(solution: Solution): Double {
        val model = solution.modelGraph.toModel()
        val context = SimpleScriptContext(printStream, model, externalCalls)
        val instance = clazz.getDeclaredConstructor(ScriptContext::class.java).newInstance(context)
        val method = clazz.methods.find { it.name == jvmMethodName && it.parameterCount == 0 }
            ?: error("JVM method '$jvmMethodName' not found in class '${clazz.name}'")
        val result = try {
            method.invoke(instance)
        } catch (e: InvocationTargetException) {
            throw e.cause ?: e
        }
        return toDouble(result) * tendency.numericalDirection()
    }

    /**
     * Coerces a script return value to [Double].
     *
     * @param value The value returned by the script method.
     * @return The numeric representation.
     */
    private fun toDouble(value: Any?): Double {
        return when (value) {
            is Double -> value
            is Float -> value.toDouble()
            is Int -> value.toDouble()
            is Long -> value.toDouble()
            is Number -> value.toDouble()
            is Boolean -> if (value) 0.0 else 1.0
            null -> 0.0
            else -> {
                throw IllegalArgumentException("Unsupported return type from script method: ${value::class.java.name}")
            }
        }
    }
}
