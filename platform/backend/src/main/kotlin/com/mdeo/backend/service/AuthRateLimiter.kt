package com.mdeo.backend.service

import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Throttles password verification attempts, shared between the login route
 * and git's HTTP basic authentication.
 *
 * Every request against either carries a username and password, and nothing
 * in the application rate limits or locks out a failing account, so both are
 * equally a bcrypt oracle: the CPU cost verifyPassword is deliberately
 * configured to spend on every guess is otherwise available to a caller in
 * a loop, and git's basic-auth flow makes that trivial to script against.
 *
 * Two independent limits apply to every attempt, so neither a single
 * username nor a single address has to be exhausted on its own: many
 * usernames tried from one address, and one username tried from many
 * addresses, are both slowed down.
 */
class AuthRateLimiter {
    private data class Window(var count: Int, var startedAt: Instant)

    private val byUsername = ConcurrentHashMap<String, Window>()
    private val byAddress = ConcurrentHashMap<String, Window>()

    /**
     * Records one authentication attempt and reports whether it may proceed.
     *
     * Called before the password is verified, so a caller already over
     * either limit never reaches bcrypt at all for this attempt.
     *
     * @param username The username being authenticated as
     * @param remoteAddress The caller's address
     * @return true if the attempt is allowed, false if either limit is exceeded
     */
    fun tryAcquire(username: String, remoteAddress: String): Boolean {
        // Both counters are always recorded, even once one side has already
        // failed, so a caller alternating between two exhausted keys cannot
        // use the short-circuit to dodge one side's window entirely.
        val usernameOk = tryAcquire(byUsername, username.lowercase(), MAX_ATTEMPTS_PER_USERNAME)
        val addressOk = tryAcquire(byAddress, remoteAddress, MAX_ATTEMPTS_PER_ADDRESS)
        return usernameOk && addressOk
    }

    private fun tryAcquire(windows: ConcurrentHashMap<String, Window>, key: String, limit: Int): Boolean {
        val now = Instant.now()
        val window = windows.compute(key) { _, existing ->
            if (existing == null || Duration.between(existing.startedAt, now) > WINDOW) {
                Window(1, now)
            } else {
                existing.count += 1
                existing
            }
        }!!
        return window.count <= limit
    }

    companion object {
        private val WINDOW: Duration = Duration.ofMinutes(1)

        /**
         * Deliberately tighter than the per-address limit: this is the one
         * that actually protects a specific account against a distributed
         * guessing attempt.
         */
        private const val MAX_ATTEMPTS_PER_USERNAME = 5

        /**
         * Looser than the per-username limit, since one address (for
         * instance a shared office connection, or nginx itself if a
         * forwarded-for header is not yet trusted) can legitimately carry
         * many different users' traffic.
         */
        private const val MAX_ATTEMPTS_PER_ADDRESS = 20
    }
}
