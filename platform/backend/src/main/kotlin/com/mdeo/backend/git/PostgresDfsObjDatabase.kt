package com.mdeo.backend.git

import com.mdeo.backend.database.GitPackFilesTable
import com.mdeo.backend.database.GitPacksTable
import org.eclipse.jgit.internal.storage.dfs.DfsObjDatabase
import org.eclipse.jgit.internal.storage.dfs.DfsOutputStream
import org.eclipse.jgit.internal.storage.dfs.DfsPackDescription
import org.eclipse.jgit.internal.storage.dfs.DfsReaderOptions
import org.eclipse.jgit.internal.storage.dfs.ReadableChannel
import org.eclipse.jgit.internal.storage.pack.PackExt
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.time.Instant
import java.util.UUID
import kotlin.math.min
import kotlin.uuid.toKotlinUuid

/**
 * The name a pack is stored under.
 *
 * [DfsPackDescription] takes a name but exposes no getter for it, so every
 * description this database produces is a [NamedPackDescription] that keeps it.
 * JGit only ever hands back descriptions that came from `newPack` or
 * `listPacks`, so anything else here means a pack from another repository.
 */
private val DfsPackDescription.storedName: String
    get() = (this as? NamedPackDescription)?.name
        ?: error("Pack description did not originate from this database: $this")

/**
 * Object storage for a project's repository, backed by Postgres.
 *
 * JGit's DFS layer never asks for individual objects. It writes and reads whole
 * pack files, which is why this only has to move byte streams around and keep
 * enough metadata to rebuild a [DfsPackDescription]. That is what makes storing
 * repositories in the database practical: no filesystem is involved, and the
 * backend keeps its property of holding no persistent state outside Postgres.
 *
 * @param projectId The project whose repository this is
 * @param repository The repository these objects belong to
 */
class PostgresDfsObjDatabase(
    private val projectId: UUID,
    repository: PostgresDfsRepository
) : DfsObjDatabase(repository, DfsReaderOptions()) {

    override fun newPack(source: PackSource): DfsPackDescription {
        // A random UUID rather than a timestamp+counter: this database is
        // constructed fresh per openRepository call, so a counter restarts
        // at 0 every time. Two operations on the same project within the
        // same millisecond would otherwise both produce pack-<ms>-0, and
        // since commitPackImpl upserts on (projectId, packName), the second
        // one silently overwrites the first's bytes and metadata.
        val name = "pack-${UUID.randomUUID()}"
        return NamedPackDescription(repository.description, name, source)
    }

    /**
     * Used by JGit only to size internal maps, so the recorded pack counts are
     * a good enough estimate.
     */
    override fun getApproximateObjectCount(): Long {
        val project = projectId.toKotlinUuid()
        return transaction {
            GitPacksTable
                .selectAll()
                .where { GitPacksTable.projectId eq project }
                .sumOf { it[GitPacksTable.objectCount] }
        }
    }

    override fun listPacks(): MutableList<DfsPackDescription> {
        val project = projectId.toKotlinUuid()
        return transaction {
            // One query for every pack's file metadata, grouped by pack name,
            // rather than one query per pack: pack count grows with
            // repository activity, so a per-pack query means every listPacks
            // call (which JGit makes on essentially every object lookup)
            // does linearly more database round trips. The `data` column
            // itself is not selected here; openFile fetches it lazily, only
            // for the pack a read actually reaches.
            val filesByPack = GitPackFilesTable
                .select(
                    GitPackFilesTable.packName,
                    GitPackFilesTable.ext,
                    GitPackFilesTable.size,
                    GitPackFilesTable.blockSize
                )
                .where { GitPackFilesTable.projectId eq project }
                .groupBy { it[GitPackFilesTable.packName] }

            GitPacksTable
                .selectAll()
                .where { GitPacksTable.projectId eq project }
                .map { row ->
                    val name = row[GitPacksTable.packName]
                    val description = NamedPackDescription(
                        repository.description,
                        name,
                        PackSource.valueOf(row[GitPacksTable.packSource])
                    )
                    description.objectCount = row[GitPacksTable.objectCount]
                    description.deltaCount = row[GitPacksTable.deltaCount]
                    description.indexVersion = row[GitPacksTable.indexVersion]
                    description.minUpdateIndex = row[GitPacksTable.minUpdateIndex]
                    description.maxUpdateIndex = row[GitPacksTable.maxUpdateIndex]
                    description.estimatedPackSize = row[GitPacksTable.estimatedPackSize]
                    description.lastModified = row[GitPacksTable.lastModified]

                    // Which extensions exist, and how big each is, has to be
                    // restored too, or JGit will not know the index is there.
                    filesByPack[name]?.forEach { fileRow ->
                        val ext = PackExt.valueOf(fileRow[GitPackFilesTable.ext])
                        description.addFileExt(ext)
                        description.setFileSize(ext, fileRow[GitPackFilesTable.size])
                        val block = fileRow[GitPackFilesTable.blockSize]
                        if (block > 0) {
                            description.setBlockSize(ext, block)
                        }
                    }
                    description as DfsPackDescription
                }
                .toMutableList()
        }
    }

    override fun commitPackImpl(
        add: Collection<DfsPackDescription>,
        remove: Collection<DfsPackDescription>?
    ) {
        val project = projectId.toKotlinUuid()
        transaction {
            for (description in add) {
                val name = description.storedName
                GitPacksTable.upsert(GitPacksTable.projectId, GitPacksTable.packName) {
                    it[GitPacksTable.projectId] = project
                    it[GitPacksTable.packName] = name
                    it[GitPacksTable.packSource] = description.packSource.name
                    it[GitPacksTable.objectCount] = description.objectCount
                    it[GitPacksTable.deltaCount] = description.deltaCount
                    it[GitPacksTable.indexVersion] = description.indexVersion
                    it[GitPacksTable.minUpdateIndex] = description.minUpdateIndex
                    it[GitPacksTable.maxUpdateIndex] = description.maxUpdateIndex
                    it[GitPacksTable.estimatedPackSize] = description.estimatedPackSize
                    it[GitPacksTable.lastModified] = description.lastModified
                    it[GitPacksTable.createdAt] = Instant.now()
                }

                // Bytes buffered while the pack was written become visible only
                // now, so a pack is never half readable.
                pendingWrites.remove(name)?.forEach { (ext, bytes) ->
                    GitPackFilesTable.upsert(
                        GitPackFilesTable.projectId,
                        GitPackFilesTable.packName,
                        GitPackFilesTable.ext
                    ) {
                        it[GitPackFilesTable.projectId] = project
                        it[GitPackFilesTable.packName] = name
                        it[GitPackFilesTable.ext] = ext.name
                        it[GitPackFilesTable.data] = bytes
                        it[GitPackFilesTable.size] = bytes.size.toLong()
                        it[GitPackFilesTable.blockSize] = description.getBlockSize(ext)
                    }
                }
            }

            remove?.forEach { description ->
                val name = description.storedName
                GitPacksTable.deleteWhere {
                    (GitPacksTable.projectId eq project) and (GitPacksTable.packName eq name)
                }
            }
        }
    }

    override fun rollbackPack(desc: Collection<DfsPackDescription>?) {
        desc?.forEach { pendingWrites.remove(it.storedName) }
    }

    override fun openFile(desc: DfsPackDescription, ext: PackExt): ReadableChannel {
        val project = projectId.toKotlinUuid()
        val name = desc.storedName

        // A pack still buffered by this same database instance (just
        // written, not yet committed) is read from memory: it is not in
        // Postgres yet for a range read to reach. Anything already
        // committed is read lazily instead, a block at a time, rather than
        // loading the whole pack just to serve one object out of it.
        pendingWrites[name]?.get(ext)?.let { return ByteArrayReadableChannel(it) }

        val size = transaction {
            GitPackFilesTable
                .select(GitPackFilesTable.size)
                .where {
                    (GitPackFilesTable.projectId eq project) and
                        (GitPackFilesTable.packName eq name) and
                        (GitPackFilesTable.ext eq ext.name)
                }
                .singleOrNull()
                ?.get(GitPackFilesTable.size)
        } ?: throw FileNotFoundException("$name.${ext.getExtension()}")

        return PostgresPackReadableChannel(projectId, name, ext, size)
    }

    override fun writeFile(desc: DfsPackDescription, ext: PackExt): DfsOutputStream =
        BufferedPackOutputStream(desc.storedName, ext)

    /**
     * Pack files written but not yet committed, keyed by pack name.
     *
     * JGit writes a pack's files first and commits the description afterwards,
     * so the bytes wait here until [commitPackImpl] stores them. A rolled back
     * pack is simply dropped.
     */
    private val pendingWrites = mutableMapOf<String, MutableMap<PackExt, ByteArray>>()

    /**
     * Collects one pack file in memory and hands it to the pending set on close.
     *
     * @param packName Name of the pack being written
     * @param ext The file extension being written
     */
    private inner class BufferedPackOutputStream(
        private val packName: String,
        private val ext: PackExt
    ) : DfsOutputStream() {
        private val buffer = ByteArrayOutputStream()

        override fun write(b: ByteArray, off: Int, len: Int) {
            buffer.write(b, off, len)
        }

        override fun read(position: Long, buf: ByteBuffer): Int {
            val bytes = buffer.toByteArray()
            if (position >= bytes.size) {
                return -1
            }
            val available = min(buf.remaining(), bytes.size - position.toInt())
            buf.put(bytes, position.toInt(), available)
            return available
        }

        override fun close() {
            pendingWrites.getOrPut(packName) { mutableMapOf() }[ext] = buffer.toByteArray()
            super.close()
        }
    }
}
