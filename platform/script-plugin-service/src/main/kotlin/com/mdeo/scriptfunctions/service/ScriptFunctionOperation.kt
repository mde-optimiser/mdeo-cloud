package com.mdeo.scriptfunctions.service

import com.mdeo.pluginservice.session.SessionContext

/**
 * One operation a contribution's service answers.
 *
 * Arguments arrive as plain Kotlin values:
 *
 * | Script type | Kotlin value |
 * | --- | --- |
 * | `int`, `long`, `float`, `double` | [Int], [Long], [Float], [Double] |
 * | `boolean`, `string` | [Boolean], [String] |
 * | `null` | `null` |
 * | `List`, `Bag` and their readonly types | [List], readonly |
 * | `Set`, `OrderedSet` and their readonly types | [Set], readonly, iterating in insertion order |
 * | `Map`, `ReadonlyMap` | [Map], readonly, iterating in insertion order |
 * | a model class | [ScriptModelInstance] |
 * | an enum of the metamodel | [ScriptEnumValue] |
 * | a record the contribution defines | [RecordValue] |
 * | an opaque class the contribution defines | the state its handle stands for; return it wrapped with [OpaqueType.wrap] |
 *
 * The same collection passed twice is the same object twice, and a collection that contains itself
 * does so here as well.
 *
 * Every argument is *in*: nothing an operation does to it reaches the script. Collections arrive as
 * readonly views, and trying to change one fails the call. Throwing fails the call as well; the
 * script sees the error.
 *
 * Return any of the values above. A returned collection may be a new one or one of the arguments;
 * returning an argument returns that same collection to the script.
 */
fun interface ScriptFunctionOperation {
    /**
     * Answers one call.
     *
     * Runs on [kotlinx.coroutines.Dispatchers.Default]. Wrap blocking I/O in
     * `withContext(Dispatchers.IO)`.
     *
     * @param call The arguments and where the call comes from
     * @return The return value; `null` for a void function
     */
    suspend fun invoke(call: ScriptFunctionCall): Any?
}

/**
 * One call of an operation.
 *
 * @property operation The operation name, as the external implementation declares it
 * @property arguments The arguments, in declaration order
 * @property session The session the call arrived on; null when the service is used without one,
 *           as in tests
 * @property model The model the call works on, when the function reads the model or is passed
 *           instances of it; null otherwise
 */
class ScriptFunctionCall(
    val operation: String,
    val arguments: List<Any?>,
    val session: SessionContext?,
    val model: ScriptModel? = null
) {
    /**
     * Returns one argument as the type it was declared with.
     *
     * ```kotlin
     * val stops = call.argument<List<String>>(0)
     * ```
     *
     * @param index The parameter position
     * @return The argument
     * @throws IllegalArgumentException when the argument is not of type [T]
     */
    inline fun <reified T> argument(index: Int): T {
        require(index in arguments.indices) {
            "Operation '$operation' was called with ${arguments.size} arguments, so there is no argument $index"
        }
        val value = arguments[index]
        require(value is T) {
            "Argument $index of operation '$operation' is ${value?.let { it::class.simpleName } ?: "null"}, " +
                    "not ${T::class.simpleName}"
        }
        return value
    }
}
