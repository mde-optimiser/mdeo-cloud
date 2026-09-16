package com.mdeo.backend.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Decides when a plugin whose answers carry a new manifest fingerprint is fetched again.
 *
 * A refresh starts when an answer's fingerprint differs from the one recorded at the plugin's last
 * manifest fetch, or when none was recorded. At most one refresh per plugin runs at a time, and a
 * plugin is not tried again within [cooldownMillis] of the last attempt, so a plugin that cannot be
 * refreshed is not hammered by every request.
 *
 * @param recorded The fingerprint recorded for a plugin, if any
 * @param refresh Fetches a plugin's manifest again, recording its new fingerprint
 * @param scope Where refreshes run
 * @param cooldownMillis How long to wait before trying the same plugin again
 * @param clock The current time in milliseconds
 */
class ManifestWatcher(
    private val recorded: (UUID) -> String?,
    private val refresh: suspend (UUID) -> Unit,
    private val scope: CoroutineScope,
    private val cooldownMillis: Long = 60_000,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val refreshing: MutableSet<UUID> = ConcurrentHashMap.newKeySet()
    private val lastAttempt = ConcurrentHashMap<UUID, Long>()

    /**
     * Records the fingerprint a plugin answered with, refreshing the plugin when it changed.
     *
     * @param pluginId The plugin that answered
     * @param fingerprint The fingerprint on its answer, null when it sent none
     * @return Whether a refresh was started
     */
    fun observe(pluginId: UUID, fingerprint: String?): Boolean {
        if (fingerprint == null || recorded(pluginId) == fingerprint) return false

        val now = clock()
        val last = lastAttempt[pluginId]
        if (last != null && now - last < cooldownMillis) return false
        if (!refreshing.add(pluginId)) return false
        lastAttempt[pluginId] = now

        scope.launch {
            try {
                refresh(pluginId)
            } finally {
                refreshing.remove(pluginId)
            }
        }
        return true
    }
}
