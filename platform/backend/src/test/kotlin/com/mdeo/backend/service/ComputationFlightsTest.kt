package com.mdeo.backend.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ComputationFlightsTest {

    /** Runs flights like the backend does: a failed run must not take the requests down with it. */
    private fun CoroutineScope.supervisor() = CoroutineScope(coroutineContext.minusKey(Job) + SupervisorJob())

    @Test
    fun `concurrent requests for the same key share one run`() = runBlocking {
        val flights = ComputationFlights<String, Int>(supervisor())
        val runs = AtomicInteger()
        val release = CompletableDeferred<Unit>()

        val results = (1..5).map {
            async {
                flights.run("ast:/a.fn", caller = null) {
                    runs.incrementAndGet()
                    release.await()
                    42
                }
            }
        }
        repeat(10) { yield() }
        release.complete(Unit)

        assertEquals(List(5) { 42 }, results.awaitAll())
        assertEquals(1, runs.get())
    }

    @Test
    fun `different keys run independently`() = runBlocking {
        val flights = ComputationFlights<String, String>(supervisor())
        val results = listOf("a", "b").map { key -> async { flights.run(key, null) { key.uppercase() } } }
        assertEquals(listOf("A", "B"), results.awaitAll())
    }

    @Test
    fun `a run that throws is retried by a waiter`() = runBlocking {
        val flights = ComputationFlights<String, Int>(supervisor())
        val runs = AtomicInteger()
        val release = CompletableDeferred<Unit>()

        val failing = async {
            runCatching {
                flights.run("k", null) {
                    runs.incrementAndGet()
                    release.await()
                    error("plugin went away")
                }
            }
        }
        repeat(10) { yield() }
        val waiting = async { flights.run("k", null) { runs.incrementAndGet(); 7 } }
        repeat(10) { yield() }
        release.complete(Unit)

        assertFailsWith<IllegalStateException> { failing.await().getOrThrow() }
        assertEquals(7, waiting.await())
        assertEquals(2, runs.get())
    }

    @Test
    fun `a request that stops waiting leaves the run to the others`() = runBlocking {
        val flights = ComputationFlights<String, Int>(supervisor())
        val runs = AtomicInteger()
        val release = CompletableDeferred<Unit>()

        // The request that starts the run gives up long before the run is done.
        val impatient = async {
            withTimeoutOrNull(50) {
                flights.run("k", null) {
                    runs.incrementAndGet()
                    release.await()
                    42
                }
            }
        }
        repeat(10) { yield() }
        val patient = async { flights.run("k", null) { runs.incrementAndGet(); -1 } }

        assertNull(impatient.await())
        release.complete(Unit)
        assertEquals(42, patient.await())
        assertEquals(1, runs.get())
    }

    @Test
    fun `a failure value is shared like any other result`() = runBlocking {
        val flights = ComputationFlights<String, Result<Int>>(supervisor())
        val runs = AtomicInteger()
        val release = CompletableDeferred<Unit>()
        val failure = Result.failure<Int>(IllegalStateException("plugin failed"))

        val results = (1..3).map {
            async {
                flights.run("k", null) {
                    runs.incrementAndGet()
                    release.await()
                    failure
                }
            }
        }
        repeat(10) { yield() }
        release.complete(Unit)

        assertEquals(List(3) { failure }, results.awaitAll())
        assertEquals(1, runs.get())
    }

    @Test
    fun `a computation requesting data that its own caller is computing does not wait for itself`() = runBlocking {
        val flights = ComputationFlights<String, String>(supervisor())

        // Computing A requests B, and computing B requests A again: waiting would never finish.
        val result = withTimeout(5_000) {
            flights.run("A", caller = null) { a ->
                flights.run("B", caller = a) { b ->
                    flights.run("A", caller = b) { "inner A" }
                }
            }
        }
        assertEquals("inner A", result)
    }
}
