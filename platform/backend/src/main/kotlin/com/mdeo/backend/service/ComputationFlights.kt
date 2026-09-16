package com.mdeo.backend.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Makes concurrent requests for the same computation share one run of it.
 *
 * The first request for a key computes; every request for that key arriving while it runs waits
 * for its result instead of computing the same thing again.
 *
 * Computations nest: a plugin computing one piece of data may request another, with a token naming
 * the computation it belongs to. A request that waited on a computation which is itself waiting on
 * that request would never finish, so a request whose chain of calling computations includes the
 * one it would wait on computes on its own instead.
 *
 * Only this process is covered, which is all of it: the backend runs as a single replica.
 *
 * @param K What identifies one computation
 * @param R Its result
 */
class ComputationFlights<K : Any, R> {

    private class Flight<R>(val computationId: UUID, val result: CompletableDeferred<R>)

    private val flights = ConcurrentHashMap<K, Flight<R>>()

    /**
     * For every computation running in this process, the computation whose request started it.
     */
    private val parents = ConcurrentHashMap<UUID, Parent>()

    private data class Parent(val computationId: UUID?)

    /**
     * Runs [compute] for [key], or waits for the run already in progress.
     *
     * A run that fails with an exception is not shared: waiters try again, and one of them runs it.
     * A run that returns a failure value shares it like any other result.
     *
     * @param key The computation
     * @param caller The computation whose request this is, when it comes from one
     * @param compute Computes the result under the given computation id
     * @return The result, computed here or by the run that was already in progress
     */
    suspend fun run(key: K, caller: UUID?, compute: suspend (computationId: UUID) -> R): R {
        while (true) {
            val mine = Flight<R>(UUID.randomUUID(), CompletableDeferred())
            val running = flights.putIfAbsent(key, mine)

            if (running == null) {
                return try {
                    runTracked(mine.computationId, caller, compute).also { mine.result.complete(it) }
                } catch (e: Throwable) {
                    mine.result.completeExceptionally(e)
                    throw e
                } finally {
                    flights.remove(key, mine)
                }
            }

            if (isWithin(running.computationId, caller)) {
                return runTracked(UUID.randomUUID(), caller, compute)
            }

            try {
                return running.result.await()
            } catch (e: CancellationException) {
                if (!currentCoroutineContext().isActive) throw e
            } catch (e: Exception) {
                // The run failed without a result; try again.
            }
        }
    }

    private suspend fun runTracked(computationId: UUID, caller: UUID?, compute: suspend (UUID) -> R): R {
        parents[computationId] = Parent(caller)
        try {
            return compute(computationId)
        } finally {
            parents.remove(computationId)
        }
    }

    /**
     * Whether [computationId] is [caller] or one of the computations that, directly or through
     * others, requested it.
     */
    private fun isWithin(computationId: UUID, caller: UUID?): Boolean {
        var current = caller
        val seen = HashSet<UUID>()
        while (current != null && seen.add(current)) {
            if (current == computationId) return true
            current = parents[current]?.computationId
        }
        return false
    }
}
