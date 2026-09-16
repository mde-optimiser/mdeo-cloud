package com.mdeo.backend.service

import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileDataComputationLogTest {
    private val project = UUID.randomUUID()

    @Test
    fun `overlapping computations of the same data are concurrent repeats`() {
        val log = FileDataComputationLog()
        val first = log.start(project, "/a.fn", "ast")
        val second = log.start(project, "/a.fn", "ast")
        assertNull(first.repeat)
        assertEquals("concurrent", second.repeat)
        first.finish(1, 1)
        second.finish(1, 1)
    }

    @Test
    fun `a computation soon after the last one is a repeat, a later one is not`() {
        val log = FileDataComputationLog(repeatWindow = Duration.ofMillis(200))
        log.start(project, "/a.fn", "ast").finish(1, 1)
        assertTrue(log.start(project, "/a.fn", "ast").also { it.finish(1, 1) }.repeat!!.startsWith("within-"))
        Thread.sleep(250)
        assertNull(log.start(project, "/a.fn", "ast").repeat)
    }

    @Test
    fun `different keys and paths are independent`() {
        val log = FileDataComputationLog()
        log.start(project, "/a.fn", "ast")
        assertNull(log.start(project, "/a.fn", "typed-ast").repeat)
        assertNull(log.start(project, "/b.fn", "ast").repeat)
    }
}
