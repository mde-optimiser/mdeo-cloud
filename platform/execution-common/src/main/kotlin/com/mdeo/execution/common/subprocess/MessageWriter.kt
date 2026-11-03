package com.mdeo.execution.common.subprocess

import java.io.DataOutputStream
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Writes [SubprocessMessage] frames to a stream that several threads share.
 *
 * A frame is a length followed by its bytes, so two threads writing at once would interleave
 * them and corrupt the stream. Every frame is therefore written whole while holding [lock].
 *
 * @param output The stream the frames go to
 */
internal class MessageWriter(private val output: DataOutputStream) {

    private val lock = ReentrantLock()

    /**
     * Writes one frame and flushes it.
     *
     * @param message The message to write
     * @throws java.io.IOException if the stream is broken
     */
    fun write(message: SubprocessMessage) {
        lock.withLock {
            SubprocessMessage.write(output, message)
        }
    }
}
