package com.mdeo.backend.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Makes concurrent requests for the same computation share one run of it.
 *
 * The first request for a key starts the computation; every request for that key arriving while it
 * runs waits for its result instead of computing the same thing again.
 *
 * The run belongs to none of the requests: it runs in [scope], so a request that stops waiting —
 * because its deadline passed or its caller went away — leaves the run to the others. That is also
 * why a run must not depend on anything one request brought along, such as its deadline.
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
 * @param scope Where runs execute; cancelling it cancels every run
 */
class ComputationFlights<K : Any, R>(private val scope: CoroutineScope) {

    private class Flight<R>(val computationId: UUID) {
        lateinit var result: Deferred<R>
    }

    private val flights = ConcurrentHashMap<K, Flight<R>>()

    /**
     * For every computation running in this process, the computation whose request started it.
     */
    private val parents = ConcurrentHashMap<UUID, Parent>()

    private data class Parent(val computationId: UUID?)

    /**
     * Runs [compute] for [key], or waits for the run already in progress.
     *
     * A run that fails with an exception is not shared: the request that started it gets the
     * exception, and every other request tries again. A run that returns a failure value shares it
     * like any other result.
     *
     * @param key The computation
     * @param caller The computation whose request this is, when it comes from one
     * @param compute Computes the result under the given computation id
     * @return The result, computed by the run this request started or joined
     */
    suspend fun run(key: K, caller: UUID?, compute: suspend (computationId: UUID) -> R): R {
        while (true) {
            val mine = Flight<R>(UUID.randomUUID())
            mine.result = scope.async(start = CoroutineStart.LAZY) {
                try {
                    runTracked(mine.computationId, caller, compute)
                } finally {
                    // Before the result is published, so nobody who sees it finds this flight again.
                    flights.remove(key, mine)
                }
            }
            val running = flights.putIfAbsent(key, mine)
            val started = running == null
            val flight = running ?: mine

            if (started) {
                mine.result.start()
            } else {
                mine.result.cancel()
                if (isWithin(flight.computationId, caller)) {
                    return runTracked(UUID.randomUUID(), caller, compute)
                }
            }

            try {
                return flight.result.await()
            } catch (e: CancellationException) {
                // This request was cancelled, or the run was: only the latter is worth a retry.
                currentCoroutineContext().ensureActive()
                if (started) throw IllegalStateException("The computation was cancelled", e)
            } catch (e: Exception) {
                if (started) throw e
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
