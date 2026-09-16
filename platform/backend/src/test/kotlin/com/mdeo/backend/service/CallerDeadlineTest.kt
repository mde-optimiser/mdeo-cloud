package com.mdeo.backend.service

import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CallerDeadlineTest {

    @Test
    fun `a caller without a deadline gets the configured maximum`() {
        assertNull(CallerDeadline.fromHeader(null))
        assertNull(CallerDeadline.fromHeader("soon"))
        assertNull(CallerDeadline.fromHeader("0"))
        assertEquals(Duration.ofMinutes(5), CallerDeadline.effective(null, Duration.ofMinutes(5)))
    }

    @Test
    fun `a caller with less time than the maximum shortens the wait`() {
        val deadline = CallerDeadline.fromHeader("2000")!!
        val effective = CallerDeadline.effective(deadline, Duration.ofMinutes(5))
        assertTrue(effective <= Duration.ofSeconds(2) && effective > Duration.ofSeconds(1))
        assertEquals(Duration.ofSeconds(1), CallerDeadline.effective(CallerDeadline.fromHeader("600000"), Duration.ofSeconds(1)))
    }

    @Test
    fun `a passed deadline has nothing left, and the forwarded value never reaches zero`() {
        val deadline = CallerDeadline.after(Duration.ofMillis(1))
        Thread.sleep(5)
        assertTrue(deadline.isExpired)
        assertEquals("1", CallerDeadline.headerValue(Duration.ZERO))
        assertEquals("1500", CallerDeadline.headerValue(Duration.ofMillis(1500)))
    }
}
