package com.mdeo.backend.git

import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ByteArrayReadableChannelTest {
    private val content = "0123456789".toByteArray()

    @Test
    fun `size reports the full content length`() {
        val channel = ByteArrayReadableChannel(content)

        assertEquals(10L, channel.size())
    }

    @Test
    fun `position starts at zero`() {
        val channel = ByteArrayReadableChannel(content)

        assertEquals(0L, channel.position())
    }

    @Test
    fun `read fills the buffer and advances position`() {
        val channel = ByteArrayReadableChannel(content)
        val buffer = ByteBuffer.allocate(4)

        val read = channel.read(buffer)

        assertEquals(4, read)
        assertEquals(4L, channel.position())
        assertEquals("0123", String(buffer.array(), 0, 4))
    }

    @Test
    fun `read past the end returns only what remains`() {
        val channel = ByteArrayReadableChannel(content)
        channel.position(8L)
        val buffer = ByteBuffer.allocate(10)

        val read = channel.read(buffer)

        assertEquals(2, read)
        assertEquals("89", String(buffer.array(), 0, 2))
    }

    @Test
    fun `read at end of stream returns minus one`() {
        val channel = ByteArrayReadableChannel(content)
        channel.position(10L)
        val buffer = ByteBuffer.allocate(4)

        assertEquals(-1, channel.read(buffer))
    }

    @Test
    fun `read past the end of stream also returns minus one`() {
        val channel = ByteArrayReadableChannel(content)
        channel.position(50L)
        val buffer = ByteBuffer.allocate(4)

        assertEquals(-1, channel.read(buffer))
    }

    @Test
    fun `seeking allows re-reading earlier content`() {
        val channel = ByteArrayReadableChannel(content)
        val buffer = ByteBuffer.allocate(3)
        channel.read(buffer)

        channel.position(0L)
        buffer.clear()
        val read = channel.read(buffer)

        assertEquals(3, read)
        assertEquals("012", String(buffer.array(), 0, 3))
    }

    @Test
    fun `channel is open until closed`() {
        val channel = ByteArrayReadableChannel(content)

        assertTrue(channel.isOpen)
        channel.close()
        assertFalse(channel.isOpen)
    }

    @Test
    fun `blockSize is zero since content is already fully resident`() {
        val channel = ByteArrayReadableChannel(content)

        assertEquals(0, channel.blockSize())
    }
}
