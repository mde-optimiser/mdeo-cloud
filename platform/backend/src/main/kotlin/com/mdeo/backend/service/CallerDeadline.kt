package com.mdeo.backend.service

import java.time.Duration

/**
 * How long a caller is still willing to wait for an answer.
 *
 * A caller sends the milliseconds it has left in [HEADER]. The backend then waits no longer than
 * that for whatever it calls on the caller's behalf, and passes what remains on to it, so a plugin
 * knows when nobody is waiting for its answer any more. A caller that sends nothing gets the
 * configured maximum.
 */
class CallerDeadline private constructor(private val expiresAtNanos: Long) {

    /**
     * The time left, never negative.
     */
    fun remaining(): Duration = Duration.ofNanos(maxOf(0, expiresAtNanos - System.nanoTime()))

    /**
     * Whether no time is left.
     */
    val isExpired: Boolean get() = remaining().isZero

    companion object {
        /**
         * The header a caller sends its remaining time in, and the backend forwards it in.
         */
        const val HEADER = "X-Mdeo-Timeout-Ms"

        /**
         * Reads a deadline from the header value.
         *
         * @param value The header value, in milliseconds
         * @return The deadline, or null when the caller sent none or nothing usable
         */
        fun fromHeader(value: String?): CallerDeadline? =
            value?.trim()?.toLongOrNull()?.takeIf { it > 0 }?.let { CallerDeadline(System.nanoTime() + it * 1_000_000) }

        /**
         * Creates a deadline a given time from now.
         *
         * @param timeout The time left
         * @return The deadline
         */
        fun after(timeout: Duration): CallerDeadline = CallerDeadline(System.nanoTime() + timeout.toNanos())

        /**
         * The time to wait: the configured maximum, or less when the caller will not wait that long.
         *
         * @param deadline The caller's deadline, if it sent one
         * @param maximum The configured maximum
         * @return The time to wait
         */
        fun effective(deadline: CallerDeadline?, maximum: Duration): Duration =
            deadline?.remaining()?.let { minOf(it, maximum) } ?: maximum

        /**
         * The header value to forward for a wait of [timeout].
         *
         * @param timeout The time the callee has
         * @return Milliseconds, at least 1
         */
        fun headerValue(timeout: Duration): String = maxOf(1, timeout.toMillis()).toString()
    }
}

/**
 * Raised when a caller's deadline passed before the backend could answer.
 */
class DeadlineExceededException(message: String) : RuntimeException(message)
