package com.mdeo.backend.service

import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Records every file data computation a plugin performs, so its cost can be read from the logs.
 *
 * One line per computation says how large the request and the answer were, how long it took, and
 * whether it repeated work: whether the same file and key was being computed at the same moment,
 * or had started computing less than [repeatWindow] before.
 *
 * @param repeatWindow How close together two computations of the same data count as repeated work
 */
class FileDataComputationLog(private val repeatWindow: Duration = Duration.ofSeconds(1)) {
    private val logger = LoggerFactory.getLogger(FileDataComputationLog::class.java)

    internal data class Target(val projectId: UUID, val path: String, val key: String)

    private class Activity(var running: Int, var lastStartNanos: Long)

    private val activity = ConcurrentHashMap<Target, Activity>()

    /**
     * One computation in progress, returned by [start] and closed with [finish] or [fail].
     */
    inner class Computation internal constructor(
        private val target: Target,
        private val startNanos: Long,
        /**
         * How this computation repeats earlier work: `concurrent`, `within-<n>ms`, or null.
         */
        val repeat: String?
    ) {
        /**
         * Records the computation as done.
         *
         * @param requestBytes Size of the request sent to the plugin
         * @param responseBytes Size of the plugin's answer
         */
        fun finish(requestBytes: Int, responseBytes: Int) {
            end()
            logger.info(
                "file-data computed key={} path={} project={} requestBytes={} responseBytes={} ms={}{}",
                target.key, target.path, target.projectId, requestBytes, responseBytes, elapsedMillis(),
                repeat?.let { " repeat=$it" } ?: ""
            )
        }

        /**
         * Records the computation as failed.
         */
        fun fail() {
            end()
            logger.info(
                "file-data failed key={} path={} project={} ms={}{}",
                target.key, target.path, target.projectId, elapsedMillis(), repeat?.let { " repeat=$it" } ?: ""
            )
        }

        private fun end() {
            activity.computeIfPresent(target) { _, current -> current.also { it.running-- } }
        }

        private fun elapsedMillis() = (System.nanoTime() - startNanos) / 1_000_000
    }

    /**
     * Records that a computation starts.
     *
     * @param projectId The project
     * @param path The file path
     * @param key The data key
     * @return The computation, to be finished or failed
     */
    fun start(projectId: UUID, path: String, key: String): Computation {
        val target = Target(projectId, path, key)
        val now = System.nanoTime()
        var repeat: String? = null
        activity.compute(target) { _, current ->
            if (current == null) {
                Activity(1, now)
            } else {
                val sinceLast = Duration.ofNanos(now - current.lastStartNanos)
                repeat = when {
                    current.running > 0 -> "concurrent"
                    sinceLast < repeatWindow -> "within-${sinceLast.toMillis()}ms"
                    else -> null
                }
                current.running++
                current.lastStartNanos = now
                current
            }
        }
        if (activity.size > MAX_TRACKED) {
            activity.entries.removeIf { it.value.running == 0 && Duration.ofNanos(now - it.value.lastStartNanos) > repeatWindow }
        }
        return Computation(target, now, repeat)
    }

    private companion object {
        const val MAX_TRACKED = 10_000
    }
}
