package com.mdeo.script.external

import com.mdeo.scriptfunctions.protocol.ScriptFunctionsProtocol
import com.mdeo.script.ast.TypedPluginAst

/**
 * Checks, before a run starts, that every contribution with an external function can be reached.
 *
 * A misconfigured contribution would otherwise only fail when a script first calls into it —
 * possibly deep into a run, and with an error pointing at the script rather than at the
 * contribution. Checking up front turns that into a failure at execution start that names the
 * contribution and what to fix.
 */
object ExternalSessionCheck {

    /**
     * Finds the first reason an external function of [pluginAst] could not be answered.
     *
     * @param pluginAst The contribution AST of the run, or null when there is none
     * @param resolve Resolves one contribution's session, throwing when it cannot
     * @return A message naming the contribution and the problem, or null when every session resolves
     */
    suspend fun findProblem(
        pluginAst: TypedPluginAst?,
        resolve: suspend (contribution: String, session: String) -> Unit
    ): String? {
        val externals = pluginAst?.functions.orEmpty().flatMap { function ->
            function.signatures.values.mapNotNull { signature -> signature.external?.let { function.name to it } }
        }

        for ((functionName, external) in externals) {
            if (external.session == null) {
                return "Contribution '${external.contribution}' implements '$functionName' externally but " +
                        "declares no '${ScriptFunctionsProtocol.NAME}' session. Add one to the contribution's 'sessions'."
            }
        }

        for ((contribution, session) in externals.map { it.second.contribution to it.second.session!! }.distinct()) {
            try {
                resolve(contribution, session)
            } catch (e: Exception) {
                return "External functions of contribution '$contribution' are unavailable: ${e.message}"
            }
        }
        return null
    }
}
