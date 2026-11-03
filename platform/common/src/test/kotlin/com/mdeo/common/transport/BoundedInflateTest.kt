package com.mdeo.common.transport

import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BoundedInflateTest {

    private fun gzip(bytes: ByteArray): ByteArray =
        ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()

    @Test
    fun `a body within the bound inflates whole`() {
        val body = ByteArray(1000) { 7 }
        assertEquals(body.toList(), GZIPInputStream(ByteArrayInputStream(gzip(body))).readBounded(1000).toList())
    }

    @Test
    fun `a body inflating past the bound is refused`() {
        val compressed = gzip(ByteArray(1001))
        assertFailsWith<IOException> { GZIPInputStream(ByteArrayInputStream(compressed)).readBounded(1000) }
    }
}
