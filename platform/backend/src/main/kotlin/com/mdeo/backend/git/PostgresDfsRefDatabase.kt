package com.mdeo.backend.git

import com.mdeo.backend.database.GitRefsTable
import org.eclipse.jgit.internal.storage.dfs.DfsRefDatabase
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.ObjectIdRef
import org.eclipse.jgit.lib.Ref
import org.eclipse.jgit.lib.ReflogReader
import org.eclipse.jgit.lib.SymbolicRef
import org.eclipse.jgit.util.RefList
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Instant
import java.util.UUID
import kotlin.uuid.toKotlinUuid

/**
 * Refs of a project's repository, stored in Postgres.
 *
 * JGit updates refs through a compare and set so concurrent pushes cannot lose
 * each other's writes. That maps onto conditional SQL: a statement that also
 * matches on the previous value and reports whether it changed a row.
 *
 * Note that the project id is resolved before entering any statement builder.
 * Those take the table as their receiver, so an unqualified `projectId` inside
 * them would bind to the table's own column rather than to this project.
 *
 * @param projectId The project whose repository this is
 * @param repository The repository these refs belong to
 */
class PostgresDfsRefDatabase(
    private val projectId: UUID,
    repository: PostgresDfsRepository
) : DfsRefDatabase(repository) {

    override fun scanAllRefs(): RefCache {
        val project = projectId.toKotlinUuid()
        return transaction {
            val ids = RefList.Builder<Ref>()
            val symbolic = RefList.Builder<Ref>()

            val rows = GitRefsTable
                .selectAll()
                .where { GitRefsTable.projectId eq project }
                .associate {
                    it[GitRefsTable.name] to (it[GitRefsTable.objectId] to it[GitRefsTable.symTarget])
                }

            // Object refs are built first so a symbolic ref can point at the
            // resolved target rather than a placeholder.
            val resolved = mutableMapOf<String, Ref>()
            for ((name, value) in rows) {
                val (objectId, symTarget) = value
                if (symTarget == null && objectId != null) {
                    val ref = ObjectIdRef.PeeledNonTag(
                        Ref.Storage.PACKED,
                        name,
                        ObjectId.fromString(objectId)
                    )
                    resolved[name] = ref
                    ids.add(ref)
                }
            }

            for ((name, value) in rows) {
                val symTarget = value.second
                if (symTarget != null) {
                    val target = resolved[symTarget]
                        ?: ObjectIdRef.Unpeeled(Ref.Storage.NEW, symTarget, null)
                    symbolic.add(SymbolicRef(name, target))
                }
            }

            ids.sort()
            symbolic.sort()
            RefCache(ids.toRefList(), symbolic.toRefList())
        }
    }

    override fun compareAndPut(oldRef: Ref?, newRef: Ref): Boolean {
        val project = projectId.toKotlinUuid()
        val name = newRef.name
        val newObjectId = if (newRef.isSymbolic) null else newRef.objectId?.name
        val newSymTarget = if (newRef.isSymbolic) newRef.target.name else null

        return transaction {
            if (oldRef == null || oldRef.storage == Ref.Storage.NEW) {
                // The caller expects this ref not to exist yet. A plain
                // existence check followed by an insert is not itself a
                // compare-and-set: two first writers can both observe no
                // row, after which one would fail with a primary key
                // violation instead of correctly losing the race. Letting
                // Postgres do the check atomically (a conflicting row is
                // simply not inserted) is what actually makes this a CAS.
                val inserted = GitRefsTable.insertIgnore {
                    it[GitRefsTable.projectId] = project
                    it[GitRefsTable.name] = name
                    it[GitRefsTable.objectId] = newObjectId
                    it[GitRefsTable.symTarget] = newSymTarget
                    it[GitRefsTable.updatedAt] = Instant.now()
                }
                return@transaction inserted.insertedCount > 0
            }

            val expectedObjectId = if (oldRef.isSymbolic) null else oldRef.objectId?.name
            val expectedSymTarget = if (oldRef.isSymbolic) oldRef.target.name else null

            val updated = GitRefsTable.update({
                (GitRefsTable.projectId eq project) and
                    (GitRefsTable.name eq name) and
                    (GitRefsTable.objectId eq expectedObjectId) and
                    (GitRefsTable.symTarget eq expectedSymTarget)
            }) {
                it[GitRefsTable.objectId] = newObjectId
                it[GitRefsTable.symTarget] = newSymTarget
                it[GitRefsTable.updatedAt] = Instant.now()
            }
            updated > 0
        }
    }

    override fun compareAndRemove(oldRef: Ref): Boolean {
        val project = projectId.toKotlinUuid()
        val expectedObjectId = if (oldRef.isSymbolic) null else oldRef.objectId?.name
        val expectedSymTarget = if (oldRef.isSymbolic) oldRef.target.name else null

        return transaction {
            val deleted = GitRefsTable.deleteWhere {
                (GitRefsTable.projectId eq project) and
                    (GitRefsTable.name eq oldRef.name) and
                    (GitRefsTable.objectId eq expectedObjectId) and
                    (GitRefsTable.symTarget eq expectedSymTarget)
            }
            deleted > 0
        }
    }

    /**
     * Reflogs are not kept. Nothing here rewrites or resets refs behind a
     * user's back, so there is no local history for a reflog to recover, and
     * JGit treats a null reader as "no reflog available".
     */
    override fun getReflogReader(ref: Ref): ReflogReader? = null
}
