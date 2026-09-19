package com.mdeo.execution.common.external

import com.mdeo.common.model.PluginTarget
import com.mdeo.common.model.PluginTargetKind
import com.mdeo.common.transport.SessionClient
import com.mdeo.common.transport.SessionConnection
import com.mdeo.execution.common.api.SessionResolver
import com.mdeo.script.ast.TypedPluginAst
import com.mdeo.script.compiler.CompiledProgram
import com.mdeo.script.external.ExternalSessionCheck
import com.mdeo.script.external.SessionDispatcher
import com.mdeo.script.runtime.ExternalCallDispatcher
import kotlinx.serialization.Serializable

/**
 * What a process needs to open sessions for external calls.
 *
 * The process where the calls happen is the one that dials, so this travels from the service
 * that accepted the run to the subprocess that runs the scripts. It asks the backend for each
 * session with the run token, exactly as the service would, and gets a fresh session token every
 * time a connection has to be reopened.
 *
 * @param backendApiUrl Base URL of the backend API
 * @param projectId The project the execution belongs to
 * @param runToken The token the execution holds for the run
 * @param connectTimeoutMillis How long dialling a session may take before it counts as unreachable
 */
@Serializable
data class SessionAccess(
    val backendApiUrl: String,
    val projectId: String,
    val runToken: String,
    val connectTimeoutMillis: Long = SessionClient.DEFAULT_CONNECT_TIMEOUT_MILLIS
)

/**
 * The sessions to contributions one execution may open, and the resolver they ask.
 *
 * Serves both steps that concern them: the check before a run starts that every contribution
 * with an external function can be reached, and the dispatcher that answers the program's
 * external calls while it runs. Closing it closes every session it opened.
 *
 * @param access How to reach the backend for the sessions
 */
class ExternalSessions(private val access: SessionAccess) : AutoCloseable {

    private val resolverDelegate = lazy { SessionResolver(access.backendApiUrl) }
    private val resolver by resolverDelegate

    private val dispatchers = mutableListOf<SessionDispatcher>()

    /**
     * Finds the first reason an external function of [pluginAst] could not be answered.
     * See [ExternalSessionCheck].
     *
     * @param pluginAst The contribution AST of the run, or null when there is none
     * @return A message naming the contribution and the problem, or null when every session resolves
     */
    suspend fun findProblem(pluginAst: TypedPluginAst?): String? {
        return ExternalSessionCheck.findProblem(pluginAst) { contribution, session -> resolve(contribution, session) }
    }

    /**
     * Builds the dispatcher that answers [program]'s external calls over sessions.
     *
     * The dispatcher is closed together with this.
     *
     * @param program The compiled program whose calls to answer
     * @return The dispatcher, or [ExternalCallDispatcher.UNSUPPORTED] when the program makes no
     *         external call
     */
    fun createDispatcher(program: CompiledProgram): ExternalCallDispatcher {
        if (program.externalCalls.isEmpty()) {
            return ExternalCallDispatcher.UNSUPPORTED
        }
        val dispatcher = SessionDispatcher(
            program.externalCalls,
            program.contributedClasses,
            access.connectTimeoutMillis
        ) { contribution, session ->
            resolve(contribution, session)
        }
        dispatchers += dispatcher
        return dispatcher
    }

    /**
     * Resolves the session of one contribution.
     */
    private suspend fun resolve(contribution: String, session: String): SessionConnection {
        return resolver.resolve(
            access.projectId,
            PluginTarget.of(PluginTargetKind.CONTRIBUTION, contribution),
            session,
            access.runToken
        )
    }

    /**
     * Closes every session opened through this, and the resolver.
     */
    override fun close() {
        dispatchers.forEach { runCatching { it.close() } }
        dispatchers.clear()
        if (resolverDelegate.isInitialized()) {
            resolver.close()
        }
    }
}
