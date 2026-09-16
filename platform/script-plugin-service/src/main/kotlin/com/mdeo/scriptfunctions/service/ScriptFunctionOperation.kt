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
 * | `List`, `Bag` | [MutableList] |
 * | `Set`, `OrderedSet` | [MutableSet], iterating in insertion order |
 * | `Map` | [MutableMap], iterating in insertion order |
 *
 * The same collection passed twice is the same object twice, and a collection that contains itself
 * does so here as well.
 *
 * Change the collections the signature declares as mutable, and return the result. Only what
 * actually changed is sent back. Changing a collection declared readonly fails the call, and so
 * does throwing: in both cases the script sees an error and none of the changes is applied.
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
 */
class ScriptFunctionCall(
    val operation: String,
    val arguments: List<Any?>,
    val session: SessionContext?
) {
    /**
     * Returns one argument as the type it was declared with.
     *
     * ```kotlin
     * val stops = call.argument<MutableList<String>>(0)
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
