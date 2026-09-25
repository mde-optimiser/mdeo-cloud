package com.mdeo.backend.service

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthRateLimiterTest {
    @Test
    fun `a successful attempt is never counted against the caller`() {
        val limiter = AuthRateLimiter()

        // Smart HTTP authenticates twice per clone, fetch or push, so a user
        // doing ordinary git work passes through here far more often than the
        // per-username limit would allow if success counted. Ten operations'
        // worth, well past that limit, all with correct credentials.
        repeat(20) {
            assertTrue(limiter.tryReserve("alice", "10.0.0.1"))
            limiter.recordSuccess("alice", "10.0.0.1")
        }

        assertTrue(limiter.tryReserve("alice", "10.0.0.1"))
    }

    @Test
    fun `failures for one username are refused once the limit is reached`() {
        val limiter = AuthRateLimiter()

        repeat(5) {
            assertTrue(limiter.tryReserve("alice", "10.0.0.$it"))
        }

        // Spread over five different addresses, so this is the per-username
        // limit talking and not the per-address one.
        assertFalse(limiter.tryReserve("alice", "10.0.0.99"))
    }

    @Test
    fun `failures from one address are refused once the limit is reached`() {
        val limiter = AuthRateLimiter()

        repeat(20) {
            limiter.tryReserve("user$it", "10.0.0.1")
        }

        // A username that has never failed, from the exhausted address.
        assertFalse(limiter.tryReserve("alice", "10.0.0.1"))
        // The same username from elsewhere is unaffected.
        assertTrue(limiter.tryReserve("alice", "10.0.0.2"))
    }

    @Test
    fun `a success releases only its own reservation, not failures the caller already accumulated`() {
        val limiter = AuthRateLimiter()

        // Four real failures.
        repeat(4) { assertTrue(limiter.tryReserve("alice", "10.0.0.1")) }

        // A fifth attempt that succeeds: reserved (taking the count to the
        // limit), then released on success.
        assertTrue(limiter.tryReserve("alice", "10.0.0.1"))
        limiter.recordSuccess("alice", "10.0.0.1")

        // The four real failures are still there - a success must not wipe
        // out failures it did not itself cause. One more genuine failure is
        // still tolerated (the fifth slot the release freed up)...
        assertTrue(limiter.tryReserve("alice", "10.0.0.1"))
        // ...but a sixth is not: the four original failures plus this one
        // now occupy every slot the limit allows.
        assertFalse(limiter.tryReserve("alice", "10.0.0.1"))
    }

    @Test
    fun `genuinely concurrent attempts cannot all pass before any of them is recorded`() {
        val limiter = AuthRateLimiter()
        val threadCount = 50
        val pool = Executors.newFixedThreadPool(threadCount)
        val start = CountDownLatch(1)
        val allowedCount = AtomicInteger(0)

        try {
            // Every thread blocks on the same latch and is released together, so their
            // tryReserve calls land as close to simultaneously as the JVM's scheduler allows -
            // this is what a plain check-then-report design (a separate isAllowed read, followed
            // later by a separate recordFailure write) would fail under: every thread's read
            // could land before any thread's write, letting all of them through regardless of
            // the limit. tryReserve folds the two into one atomic step precisely so this cannot
            // happen, however many callers race it.
            val futures = (1..threadCount).map { i ->
                pool.submit {
                    start.await()
                    if (limiter.tryReserve("alice", "10.0.0.$i")) {
                        allowedCount.incrementAndGet()
                    }
                }
            }
            start.countDown()
            futures.forEach { it.get(5, TimeUnit.SECONDS) }
        } finally {
            pool.shutdown()
        }

        // Exactly five of the fifty genuinely concurrent attempts may have passed - the
        // per-username limit, regardless of how the threads happened to interleave.
        assertEquals(5, allowedCount.get())
    }

    @Test
    fun `usernames are matched case insensitively`() {
        val limiter = AuthRateLimiter()

        repeat(5) { limiter.tryReserve("Alice", "10.0.0.$it") }

        assertFalse(limiter.tryReserve("alice", "10.0.0.99"))
    }

    @Test
    fun `a refused attempt does not push the window's start forward`() {
        val limiter = AuthRateLimiter()

        repeat(5) { limiter.tryReserve("alice", "10.0.0.1") }
        assertFalse(limiter.tryReserve("alice", "10.0.0.1"))

        // Asking repeatedly must not push the window's start forward, which
        // would let a caller lock an account out indefinitely just by
        // continuing to knock.
        repeat(100) { assertFalse(limiter.tryReserve("alice", "10.0.0.1")) }

        limiter.recordSuccess("alice", "10.0.0.1")
        // One reservation released; the other, genuine failures the caller
        // above racked up while locked out are still counted against them.
        assertFalse(limiter.tryReserve("alice", "10.0.0.1"))
    }

    @Test
    fun `releasing a reservation does not touch a different caller's window`() {
        val limiter = AuthRateLimiter()

        // Four real failures against bob from the same address alice will
        // authenticate from.
        repeat(4) { limiter.tryReserve("bob", "10.0.0.1") }

        // Alice authenticates successfully from that address. Her own
        // reservation and release must not disturb bob's separate, still
        //-accumulating username window.
        assertTrue(limiter.tryReserve("alice", "10.0.0.1"))
        limiter.recordSuccess("alice", "10.0.0.1")

        assertTrue(limiter.tryReserve("bob", "10.0.0.2"))
        assertFalse(limiter.tryReserve("bob", "10.0.0.3"))
    }
}
