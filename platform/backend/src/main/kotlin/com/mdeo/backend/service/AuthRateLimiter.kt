package com.mdeo.backend.service

import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Throttles failed password verification attempts, shared between the login
 * route and git's HTTP basic authentication.
 *
 * Every request against either carries a username and password, and nothing
 * in the application locks out a failing account, so both are equally a
 * bcrypt oracle: the CPU cost verifyPassword is deliberately configured to
 * spend on every guess is otherwise available to a caller in a loop, and
 * git's basic-auth flow makes that trivial to script against.
 *
 * Only *failures* count, in the long run. Correct credentials leave no trace
 * at all: [recordSuccess] releases exactly the one reservation [tryReserve]
 * made for that same attempt, undoing its own footprint without touching
 * whatever else the same username or address had already accumulated from
 * other, unrelated attempts. That distinction is the whole point: smart HTTP
 * authenticates twice per clone, fetch or push, so counting every attempt
 * permanently would lock a legitimate user out of their own repository
 * partway through their third git command in a minute, while doing nothing
 * extra against a guessing loop - which never verifies successfully, and so
 * is counted on every single try either way.
 *
 * Two independent limits apply to every reservation, so neither a single
 * username nor a single address has to be exhausted on its own: many
 * usernames tried from one address, and one username tried from many
 * addresses, are both slowed down.
 *
 * A caller reserves with [tryReserve] *before* verifying the password, and
 * reports a success afterwards with [recordSuccess]; a failure needs no
 * further call; the reservation already counts it. See [tryReserve]'s doc
 * comment for why reserving has to happen before verification rather than
 * being reported after, and [recordSuccess]'s for why it releases only its
 * own reservation rather than clearing a whole window.
 */
class AuthRateLimiter {
    private data class Window(var count: Int, var startedAt: Instant)

    private val byUsername = ConcurrentHashMap<String, Window>()
    private val byAddress = ConcurrentHashMap<String, Window>()

    /**
     * Reserves one attempt against both limits and reports whether the
     * attempt may proceed to password verification at all.
     *
     * Reserves unconditionally, even when a limit is already exhausted, and
     * *before* verification runs rather than checking a limit first and
     * reporting the outcome afterwards: verification (bcrypt) is slow
     * enough that several requests can arrive while one is still in
     * flight, so a plain check-then-report split would let all of them see
     * a limit not yet exhausted, all proceed to bcrypt, and all report
     * their failure only afterwards - a burst of concurrent guesses getting
     * through in numbers the limit was meant to cap at five. Reserving
     * first closes that gap: the increment concurrent callers race against
     * is the same one [isAllowed] would have read from, done atomically by
     * [reserve], so at most [MAX_FAILURES_PER_USERNAME] (or ..._ADDRESS)
     * callers can ever observe `true` before the rest start seeing `false`,
     * regardless of how many arrive at once.
     *
     * A caller whose verification then fails makes no further call:
     * the reservation already counts as this attempt's failure. One whose
     * verification succeeds calls [recordSuccess], which releases exactly
     * this reservation.
     *
     * @param username The username being authenticated as
     * @param remoteAddress The caller's address
     * @return true if the attempt may proceed, false if either limit was
     *   already exhausted at the moment this reservation was made
     */
    fun tryReserve(username: String, remoteAddress: String): Boolean {
        val usernameOk = reserve(byUsername, username.lowercase(), MAX_FAILURES_PER_USERNAME)
        val addressOk = reserve(byAddress, remoteAddress, MAX_FAILURES_PER_ADDRESS)
        return usernameOk && addressOk
    }

    /**
     * Releases the reservation [tryReserve] made for an attempt that turned
     * out to verify successfully, so it leaves no trace on either limit.
     *
     * Releases only this one reservation - decrementing each window by
     * exactly one - rather than clearing the whole window for [username] or
     * [remoteAddress]. That distinction matters most for the address
     * window: unlike a username, which one successful login proves the
     * caller actually controls, an address can carry many different
     * people's traffic (a shared office connection, or any proxied
     * deployment), so a caller who owns one valid account could otherwise
     * interleave correct logins on it with guesses against other usernames
     * from the same address, wiping the address counter clean each time and
     * defeating the protection that limit exists to provide. Releasing only
     * this reservation leaves every other attempt's contribution to the
     * window untouched, whichever key it is on.
     *
     * @param username The username that authenticated
     * @param remoteAddress The address it authenticated from
     */
    fun recordSuccess(username: String, remoteAddress: String) {
        release(byUsername, username.lowercase())
        release(byAddress, remoteAddress)
    }

    /**
     * Atomically checks and increments a key's count in one step, so the
     * value concurrent callers race against is the same one they increment
     * - see [tryReserve]'s doc comment for why this has to be one step
     * rather than two.
     *
     * @return true if the count was still under [limit] before this
     *   reservation's own increment, i.e. whether this attempt may proceed
     */
    private fun reserve(windows: ConcurrentHashMap<String, Window>, key: String, limit: Int): Boolean {
        var wasAllowed = false
        val now = Instant.now()
        windows.compute(key) { _, existing ->
            if (existing == null || Duration.between(existing.startedAt, now) > WINDOW) {
                wasAllowed = true
                Window(1, now)
            } else {
                wasAllowed = existing.count < limit
                existing.count += 1
                existing
            }
        }
        evictExpired(windows, now)
        return wasAllowed
    }

    /**
     * Undoes one reservation [reserve] made, leaving every other attempt's
     * contribution to the same window in place.
     *
     * A no-op if the window has already expired by the time this runs: a
     * fresh window started since (by a different, unrelated attempt racing
     * this one) is not this reservation's to decrement, and an expired one
     * with nobody counting against it yet needs no correction either way.
     * In practice this window is a few hundred milliseconds - the time
     * verification itself takes - which the one-minute [WINDOW] comfortably
     * outlives, so this guard is a correctness backstop rather than
     * something expected to trigger.
     */
    private fun release(windows: ConcurrentHashMap<String, Window>, key: String) {
        val now = Instant.now()
        windows.computeIfPresent(key) { _, existing ->
            if (Duration.between(existing.startedAt, now) > WINDOW) {
                existing
            } else if (existing.count <= 1) {
                null
            } else {
                existing.apply { count -= 1 }
            }
        }
    }

    /**
     * Drops windows that have already expired, once a map has grown past the
     * point where holding them is worth anything.
     *
     * Without this the maps only ever grow: an unauthenticated caller
     * supplying a fresh username on every request adds one permanently
     * retained entry each time. The size check keeps this a rare full pass
     * rather than a scan on every reservation, and expired entries are
     * exactly the ones that no longer affect any decision, so dropping them
     * loses nothing.
     *
     * @param windows The map to sweep
     * @param now The current time, reused from the caller
     */
    private fun evictExpired(windows: ConcurrentHashMap<String, Window>, now: Instant) {
        if (windows.size <= EVICTION_THRESHOLD) {
            return
        }
        windows.entries.removeIf { Duration.between(it.value.startedAt, now) > WINDOW }
    }

    companion object {
        private val WINDOW: Duration = Duration.ofMinutes(1)

        /**
         * Deliberately tighter than the per-address limit: this is the one
         * that actually protects a specific account against a distributed
         * guessing attempt.
         *
         * Accepted gap: nothing about this limit requires the caller to
         * authenticate as anyone in particular before it starts counting
         * against a username. An unauthenticated caller who simply knows a
         * real username can send [MAX_FAILURES_PER_USERNAME] wrong passwords
         * for it - from one address, no distributed effort needed - and lock
         * that account out of both login and git for as long as they keep
         * it up, since every limiter deployed this way (without a CAPTCHA,
         * a secondary channel, or otherwise proving the caller is a genuine
         * distributed attacker rather than one person with a target in
         * mind) makes the same trade: protect against credential-guessing,
         * or accept that anyone who can reach this endpoint can also use it
         * to deny a specific person service. Keying the limit on username
         * plus address instead would not close this - the same single
         * caller, from the same one address, could still exhaust it just as
         * easily. Left as a conscious, documented trade-off rather than a
         * partial mitigation that would not actually change the outcome.
         */
        private const val MAX_FAILURES_PER_USERNAME = 5

        /**
         * Looser than the per-username limit, since one address (a shared
         * office connection, or a reverse proxy whose forwarded-for header
         * is not trusted) can legitimately carry many different users'
         * traffic.
         */
        private const val MAX_FAILURES_PER_ADDRESS = 20

        /**
         * How many tracked keys one map may hold before a reservation also
         * sweeps it. Far above what any real deployment's genuine failures
         * reach in a minute, so ordinary operation never pays for the scan.
         */
        private const val EVICTION_THRESHOLD = 10_000
    }
}
