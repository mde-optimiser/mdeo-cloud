package com.mdeo.backend.git

import com.mdeo.backend.database.GitPackFilesTable
import org.eclipse.jgit.internal.storage.dfs.ReadableChannel
import org.eclipse.jgit.internal.storage.pack.PackExt
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.nio.ByteBuffer
import java.util.UUID
import kotlin.math.min
import kotlin.uuid.toKotlinUuid

/**
 * Random access reads over a pack file, fetching only the bytes a read
 * actually needs from Postgres rather than the whole pack.
 *
 * [ByteArrayReadableChannel] is what JGit is handed after a pack's bytes are
 * already resident, which is fine for a pack just written by this process.
 * This channel is for the far more common case, reading a pack somebody else
 * wrote: the cost of touching one object should be the size of the block
 * read, not the size of the pack containing it. Postgres does the same work
 * server-side either way, but only the requested range crosses the wire and
 * only that much ever sits in this process' heap.
 *
 * @param projectId The project the pack belongs to
 * @param packName The pack's name
 * @param ext The file extension being read
 * @param size The file's total size, already known from [com.mdeo.backend.database.GitPackFilesTable]
 */
class PostgresPackReadableChannel(
    private val projectId: UUID,
    private val packName: String,
    private val ext: PackExt,
    private val size: Long
) : ReadableChannel {
    private var position = 0L
    private var open = true

    override fun read(dst: ByteBuffer): Int {
        if (position >= size) {
            return -1
        }

        // A read is never allowed to run past the end of the file, so the
        // requested length is clamped before it ever reaches the query.
        val length = min(dst.remaining().toLong(), size - position).toInt()
        val project = projectId.toKotlinUuid()

        // Postgres' substring is 1-based and, unlike a byte array slice,
        // does the trimming server-side, so only the requested range is
        // read off disk and sent back rather than the whole column value.
        val range = CustomFunction<ByteArray>(
            "substring",
            GitPackFilesTable.data.columnType,
            GitPackFilesTable.data,
            intLiteral((position + 1).toInt()),
            intLiteral(length)
        )

        val bytes = transaction {
            GitPackFilesTable
                .select(range)
                .where {
                    (GitPackFilesTable.projectId eq project) and
                        (GitPackFilesTable.packName eq packName) and
                        (GitPackFilesTable.ext eq ext.name)
                }
                .singleOrNull()
                ?.get(range)
        } ?: return -1

        dst.put(bytes)
        position += bytes.size
        return bytes.size
    }

    override fun isOpen(): Boolean = open

    override fun close() {
        open = false
    }

    override fun position(): Long = position

    override fun position(newPosition: Long) {
        position = newPosition
    }

    override fun size(): Long = size

    /**
     * Reports a real block size so JGit's own DfsBlockCache batches reads
     * into blocks of this size instead of issuing one query per small read.
     */
    override fun blockSize(): Int = BLOCK_SIZE

    override fun setReadAheadBytes(bufferSize: Int) {
        // JGit's block cache already batches reads to blockSize(); nothing
        // further to prefetch here.
    }

    companion object {
        /**
         * Matches JGit's own default DFS block size, so reads through the
         * block cache align with what a "normal" (filesystem-backed) DFS
         * database would report.
         */
        private const val BLOCK_SIZE = 64 * 1024
    }
}
