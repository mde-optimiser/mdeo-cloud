package com.mdeo.backend.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ManifestWatcherTest {
    private val plugin = UUID.randomUUID()

    @Test
    fun `a changed or unrecorded fingerprint refreshes, an unchanged or missing one does not`() {
        val scope = TestScope(StandardTestDispatcher())
        var recorded: String? = "v1"
        val refreshed = mutableListOf<UUID>()
        var now = 0L
        val watcher = ManifestWatcher({ recorded }, { refreshed += it; recorded = "v2" }, scope, cooldownMillis = 1_000, clock = { now })

        assertFalse(watcher.observe(plugin, "v1"))
        assertFalse(watcher.observe(plugin, null))
        assertTrue(watcher.observe(plugin, "v2"))
        scope.runCurrent()
        assertEquals(listOf(plugin), refreshed)
        assertFalse(watcher.observe(plugin, "v2"), "the refresh recorded the new fingerprint")

        recorded = null
        now = 5_000
        assertTrue(watcher.observe(plugin, "v3"), "a plugin without a recorded fingerprint is refreshed")
    }

    @Test
    fun `one refresh at a time, and none again within the cooldown`() {
        val scope = TestScope(StandardTestDispatcher())
        val release = CompletableDeferred<Unit>()
        var refreshes = 0
        var now = 0L
        val watcher = ManifestWatcher({ "old" }, { refreshes++; release.await() }, scope, cooldownMillis = 1_000, clock = { now })

        assertTrue(watcher.observe(plugin, "new"))
        scope.runCurrent()
        assertFalse(watcher.observe(plugin, "new"), "already refreshing")
        release.complete(Unit)
        scope.runCurrent()
        now = 500
        assertFalse(watcher.observe(plugin, "new"), "within the cooldown")
        now = 1_500
        assertTrue(watcher.observe(plugin, "new"))
        scope.runCurrent()
        assertEquals(2, refreshes)
    }
}
