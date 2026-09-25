package com.mdeo.backend.git

import org.eclipse.jgit.internal.storage.dfs.ReadableChannel
import java.nio.ByteBuffer
import kotlin.math.min

/**
 * Random access reads over a pack file already loaded from the database.
 *
 * JGit seeks around pack files rather than reading them front to back, so the
 * channel it is given has to support positioning. Pack files here are whole
 * byte arrays fetched from Postgres, so seeking is just moving an offset.
 *
 * @param bytes The file contents
 */
class ByteArrayReadableChannel(private val bytes: ByteArray) : ReadableChannel {
    private var position = 0L
    private var open = true

    override fun read(dst: ByteBuffer): Int {
        if (position >= bytes.size) {
            return -1
        }
        val available = min(dst.remaining(), bytes.size - position.toInt())
        dst.put(bytes, position.toInt(), available)
        position += available
        return available
    }

    override fun isOpen(): Boolean = open

    override fun close() {
        open = false
    }

    override fun position(): Long = position

    override fun position(newPosition: Long) {
        position = newPosition
    }

    override fun size(): Long = bytes.size.toLong()

    /**
     * The whole file is already in memory, so there is no benefit in JGit
     * reading it in smaller pieces. Zero lets JGit pick its own default.
     */
    override fun blockSize(): Int = 0

    override fun setReadAheadBytes(bufferSize: Int) {
        // Nothing to prefetch, the content is already resident.
    }
}
