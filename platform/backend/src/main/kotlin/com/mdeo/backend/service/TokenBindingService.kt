package com.mdeo.backend.service

import com.auth0.jwt.interfaces.Payload
import com.mdeo.backend.database.ExecutionsTable
import com.mdeo.backend.database.FileDataComputationsTable
import com.mdeo.common.model.ExecutionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.*
import kotlin.uuid.toKotlinUuid

/**
 * Checks that a token is still backed by the work it was issued for.
 *
 * Service tokens are handed out for a specific piece of work - running an execution, computing file
 * data - but their expiry has to cover the worst-case duration of that work, which leaves a valid
 * token lying around long after the work is done. A bound token additionally requires that work to
 * still be in progress, so it becomes useless the moment the execution reaches a terminal state or
 * the computation finishes.
 *
 * Tokens without a [JwtService.CLAIM_BINDING] claim are unaffected and remain valid until they
 * expire.
 *
 * @param services The injected services providing access to configuration and other services
 */
class TokenBindingService(services: InjectedServices) : BaseService(), InjectedServices by services {
    private val logger = LoggerFactory.getLogger(TokenBindingService::class.java)

    companion object {
        /**
         * Extra time a file data computation row stays valid beyond the configured computation
         * timeout, so that a plugin request issued just before the timeout is not rejected by a
         * clock race with the timeout itself.
         */
        private const val COMPUTATION_BINDING_GRACE_SECONDS = 60L
    }

    /**
     * Checks whether the work a token is bound to is still in progress.
     *
     * @param payload The verified JWT payload
     * @return True if the token carries no binding or its binding is still satisfied, false if the
     *   token is bound to work that has finished, vanished, or that cannot be identified
     */
    suspend fun isBindingSatisfied(payload: Payload): Boolean {
        return when (val binding = payload.getClaim(JwtService.CLAIM_BINDING)?.asString()) {
            null -> true
            JwtService.BINDING_ACTIVE_EXECUTION -> isExecutionActive(payload)
            JwtService.BINDING_FILE_DATA_COMPUTATION -> isComputationRunning(payload)
            else -> {
                logger.warn("Rejecting token with unknown binding: $binding")
                false
            }
        }
    }

    /**
     * Whether the execution named by the token still exists and has not finished.
     */
    private suspend fun isExecutionActive(payload: Payload): Boolean {
        val executionId = payload.uuidClaim(JwtService.CLAIM_EXECUTION_ID)
        if (executionId == null) {
            logger.warn("Rejecting execution-bound token without a valid execution ID claim")
            return false
        }

        val state = withContext(Dispatchers.IO) {
            transaction {
                ExecutionsTable
                    .selectAll()
                    .where { ExecutionsTable.id eq executionId.toKotlinUuid() }
                    .firstOrNull()
                    ?.get(ExecutionsTable.state)
            }
        }

        if (state == null) {
            logger.debug("Rejecting token for unknown execution $executionId")
            return false
        }

        val active = state !in TERMINAL_STATES
        if (!active) {
            logger.debug("Rejecting token for execution $executionId in terminal state $state")
        }
        return active
    }

    /**
     * Whether the file data computation named by the token is still recorded as running. A row that
     * outlived the configured computation binding is treated as abandoned by a crashed request.
     */
    private suspend fun isComputationRunning(payload: Payload): Boolean {
        val computationId = payload.uuidClaim(JwtService.CLAIM_COMPUTATION_ID)
        if (computationId == null) {
            logger.warn("Rejecting computation-bound token without a valid computation ID claim")
            return false
        }

        val cutoff = Instant.now()
            .minusSeconds(config.fileData.computationBindingSeconds + COMPUTATION_BINDING_GRACE_SECONDS)

        val running = withContext(Dispatchers.IO) {
            transaction {
                FileDataComputationsTable
                    .selectAll()
                    .where {
                        (FileDataComputationsTable.id eq computationId.toKotlinUuid()) and
                            (FileDataComputationsTable.startedAt greaterEq cutoff)
                    }
                    .any()
            }
        }

        if (!running) {
            logger.debug("Rejecting token for file data computation $computationId that is no longer running")
        }
        return running
    }

    /**
     * Reads a claim as a UUID, returning null when it is absent or malformed.
     */
    private fun Payload.uuidClaim(name: String): UUID? {
        return getClaim(name)?.asString()?.let {
            try {
                UUID.fromString(it)
            } catch (e: IllegalArgumentException) {
                null
            }
        }
    }
}

/**
 * States in which an execution is finished and no longer needs its token.
 */
private val TERMINAL_STATES = setOf(
    ExecutionState.COMPLETED,
    ExecutionState.CANCELLED,
    ExecutionState.FAILED
)
