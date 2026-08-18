package com.mdeo.backend.git

import com.mdeo.backend.database.FilesTable
import com.mdeo.backend.service.FileService
import com.mdeo.backend.service.PluginService
import com.mdeo.backend.service.RESERVED_PLUGINS_PATH
import com.mdeo.common.model.ApiResult
import com.mdeo.common.model.FileType
import com.mdeo.backend.database.GitPacksTable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.eclipse.jgit.dircache.DirCache
import org.eclipse.jgit.dircache.DirCacheEntry
import org.eclipse.jgit.internal.storage.dfs.DfsGarbageCollector
import org.eclipse.jgit.lib.CommitBuilder
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.NullProgressMonitor
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.RefUpdate
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.TreeWalk
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.toKotlinUuid

/**
 * Exposes projects as git repositories.
 *
 * A project's files live in Postgres and are edited live, so there is no commit
 * history to draw on. History is instead appended when someone looks: before a
 * repository is served, the current file state is written as a commit if it
 * differs from what the branch already points at. That gives an append only
 * history with a boundary nobody has to choose, and it means identical content
 * never produces a second commit.
 *
 * @param fileService Used to apply a push through the same validation, version
 *   bump and project locking as a workbench edit
 * @param pluginService Used to read and replace a project's enabled plugins
 * @param maxPushPackSizeBytes Also used as the cap on a pushed tree's total
 *   *decompressed* size; see [applyCommitToProject]
 * @param maxProjectStorageBytes Largest total git object storage one project
 *   may hold; see [applyCommitToProject]
 */
class GitRepositoryService(
    private val fileService: FileService,
    private val pluginService: PluginService,
    private val maxPushPackSizeBytes: Long,
    private val maxProjectStorageBytes: Long
) {
    private val logger = LoggerFactory.getLogger(GitRepositoryService::class.java)

    /**
     * Path of the file carrying a project's enabled plugins.
     *
     * A single reserved dotfile rather than a directory, deliberately: with
     * only ever one such file, a bare name with no extension is easy to keep
     * out of collision with real project content (which always belongs to a
     * registered language plugin, and so always has a meaningful extension),
     * and reserving one exact path is simpler than reserving a whole prefix.
     * Diagram layout was considered too (see issue #29) but left out: it is
     * purely representational, changes on practically every diagram
     * interaction, and would turn the commit-on-fetch boundary into a commit
     * on every look rather than one on real change.
     */
    val pluginsPath = RESERVED_PLUGINS_PATH

    /**
     * Branch a project's files are published on, and the only one a push may
     * target. Anything else would have no meaning: the project has one set of
     * files, not one per branch.
     */
    val branch = "refs/heads/main"

    /**
     * Identity recorded on generated commits.
     *
     * These commits are made by the server on behalf of everyone who edited the
     * project, not by one person, so attributing them to an individual would be
     * misleading.
     */
    private val author = PersonIdent("MDEO Cloud", "mdeo-cloud@localhost")

    /**
     * One lock per project that has been accessed over git, held for the
     * duration of one request.
     *
     * A single, per-process, in-memory lock is correct only when exactly one
     * backend instance is running: `infra/k8s/mdeo/services.tf` pins the
     * backend deployment's `replicas` to 1 specifically because this class
     * depends on it. A second replica would reintroduce the exact races this
     * lock exists to close, since neither instance's lock would know about
     * requests held by the other. Moving to a database-level lock
     * (`pg_advisory_xact_lock`, keyed on the project id) would remove that
     * dependency, at the cost of holding the lock across a real network
     * round trip instead of an in-process one; not done here, but worth
     * doing before this ever runs behind more than one replica.
     *
     * Never removed: a project is accessed over git rarely enough, and the
     * process lives long enough, that this is not worth the complexity of
     * eviction.
     */
    private val projectLocks = ConcurrentHashMap<UUID, Mutex>()

    /**
     * Serializes git handling for one project, across both reads and writes.
     *
     * A push takes this lock for the reason its own history explains: a
     * fresh `ReceivePack` reads the ref it treats as "current" itself, live,
     * at the start of handling its own request rather than from an earlier
     * advertisement, so two racing pushes on the same stale head could both
     * pass validation and both run [applyCommitToProject] before JGit's own
     * ref update decides which one actually wins. The loser is reported to
     * its client as rejected, but by then its files were already written.
     *
     * A fetch is not read-only, though: [openRepository] publishes a commit
     * whenever the branch does not already match the project's current
     * files. Without this same lock covering fetches too, a fetch racing a
     * push could read the files the push just committed (inside
     * [applyCommitToProject]'s own transaction) but the *old* ref (since the
     * push has not reached JGit's ref update yet), publish its own commit
     * with that content, and move the ref there first. The push's own ref
     * update then loses a race against a commit that describes exactly what
     * it was trying to write, and is rejected even though its content
     * landed. Serializing both against the same lock means a fetch's read
     * only ever happens strictly before or strictly after a push, never
     * during one.
     *
     * @param projectId The project being accessed
     * @param block The git handling to run with that project serialized
     */
    suspend fun <T> withProjectLock(projectId: UUID, block: suspend () -> T): T =
        projectLocks.computeIfAbsent(projectId) { Mutex() }.withLock { block() }

    /**
     * Opens a project's repository, creating it if this is the first access.
     *
     * @param projectId The project to open
     * @return The repository, with its files published on [branch]
     */
    fun openRepository(projectId: UUID): PostgresDfsRepository {
        val repository = PostgresDfsRepository(projectId)
        if (!repository.objectDatabase.exists()) {
            repository.create(true)
            // JGit's create() links HEAD to refs/heads/master, but this
            // service only ever publishes on [branch]. Left alone, HEAD
            // would point at a ref that never exists, so a clone sees an
            // unborn remote HEAD and may not check anything out.
            val headUpdate = repository.updateRef(Constants.HEAD)
            headUpdate.disableRefLog()
            headUpdate.link(branch)
        }
        publishCurrentFiles(repository, projectId)
        return repository
    }

    /**
     * Writes the project's current files as a commit, unless the branch already
     * points at exactly this content.
     *
     * Always includes [pluginsPath], even for a project with no ordinary
     * files yet: a project's plugin selection is real content the moment it
     * has one, not something that only starts existing once a file does, and
     * the alternative (skipping publication entirely while `files` is empty)
     * would silently drop it from a clone of a brand new project.
     *
     * @param repository The project's repository
     * @param projectId The project whose files should be published
     * @return The commit now at the head of [branch]
     */
    private fun publishCurrentFiles(repository: PostgresDfsRepository, projectId: UUID): ObjectId {
        val currentHead: ObjectId? = repository.resolve(branch)
        // Defensive: FileService rejects writes to pluginsPath (see
        // applyCommitToProject's doc comment on why this cannot actually
        // happen through the ordinary write path), but filtering it out
        // here too means a stray row from before that reservation existed
        // cannot collide with the generated entry added below.
        val files = readProjectFiles(projectId).filter { it.first != pluginsPath }
        val pluginsContent = pluginsFileContent(repository, currentHead, projectId)

        repository.newObjectInserter().use { inserter ->
            // DirCache builds the nested trees from flat paths, so directories
            // in the files table do not need to be walked. Git infers them.
            val dirCache = DirCache.newInCore()
            val builder = dirCache.builder()
            for ((path, content) in files + (pluginsPath to pluginsContent)) {
                val entry = DirCacheEntry(path)
                entry.fileMode = FileMode.REGULAR_FILE
                entry.setObjectId(inserter.insert(Constants.OBJ_BLOB, content))
                builder.add(entry)
            }
            builder.finish()
            val treeId = dirCache.writeTree(inserter)

            if (currentHead != null && treeOf(repository, currentHead) == treeId) {
                // Nothing changed since the last time anyone looked.
                return currentHead
            }

            // Built before entering the builder, because inside `apply` an
            // unqualified `author` would bind to CommitBuilder's own property
            // rather than to this service's.
            val identity = PersonIdent(author, Instant.now())
            val commit = CommitBuilder().apply {
                setTreeId(treeId)
                if (currentHead != null) {
                    setParentId(currentHead)
                }
                setAuthor(identity)
                setCommitter(identity)
                message = if (currentHead == null) {
                    "Initial project contents\n"
                } else {
                    "Project contents as of this fetch\n"
                }
            }
            val commitId = inserter.insert(commit)
            inserter.flush()

            val update = repository.updateRef(branch)
            update.setNewObjectId(commitId)
            update.setExpectedOldObjectId(currentHead ?: ObjectId.zeroId())
            val result = update.update()
            if (result != RefUpdate.Result.NEW && result != RefUpdate.Result.FAST_FORWARD) {
                // Another request published first. Its commit is just as valid,
                // so take whatever is there now rather than retrying.
                logger.debug("Ref update for project {} returned {}", projectId, result)
                return repository.resolve(branch) ?: commitId
            }
            return commitId
        }
    }

    /**
     * Writes the contents of a pushed commit back into the project's files.
     *
     * Every path in the commit, except [pluginsPath], is written through the
     * ordinary file service, so a push goes through exactly the same
     * validation, version bump and project locking as an edit made in the
     * workbench. Files the commit no longer contains are deleted, which is
     * what makes a push replace the project rather than only add to it.
     * [pluginsPath] is handled separately: it replaces the project's enabled
     * plugins rather than being written as a file, and its absence from the
     * pushed tree leaves plugins untouched rather than clearing them. Since
     * [pluginsPath] is always removed from the pushed tree before any of it
     * reaches [FileService], and [FileService] independently refuses to
     * write there itself, a project file can never end up at that exact
     * path through either route.
     *
     * Changing a project's plugins is normally gated on project admin
     * permission (see `PluginRoutes.kt`), not merely write access, so a
     * pushed [pluginsPath] is held to the same bar: [callerIsProjectAdmin]
     * must be true, or the whole push is rejected rather than silently
     * dropping just that part of it.
     *
     * Note that clients with the project open are not told about this. The
     * platform has no file change broadcast at all today, so an edit made in
     * one browser tab is equally invisible to another. That is a gap worth
     * closing, but it is not specific to pushing.
     *
     * @param repository The project's repository, holding the pushed objects
     * @param projectId The project to update
     * @param commitId The commit whose contents should become the project
     * @param callerIsProjectAdmin Whether the pushing user has admin permission
     *   on this project
     * @return null on success, or a message describing why the push cannot be
     *   applied
     */
    fun applyCommitToProject(
        repository: PostgresDfsRepository,
        projectId: UUID,
        commitId: ObjectId,
        callerIsProjectAdmin: Boolean
    ): String? {
        // Nothing else reclaims storage a *rejected* push already wrote to
        // Postgres (JGit unpacks and commits it before this hook even runs),
        // so without this a write-capable user could grow the database
        // without bound just by pushing a large pack in a loop and letting
        // it be rejected every time. Checked against what is already stored
        // before this push is processed at all, not against what this push
        // would add, so a push that only removes content is never blocked
        // by a project that is already over the cap.
        val currentStorageBytes = projectStorageBytes(projectId)
        if (currentStorageBytes > maxProjectStorageBytes) {
            return "project has exceeded its git storage limit " +
                "($currentStorageBytes of $maxProjectStorageBytes bytes); contact an administrator"
        }

        val pushed = mutableMapOf<String, ByteArray>()
        var totalInflatedBytes = 0L
        try {
            RevWalk(repository).use { walk ->
                val tree = walk.parseCommit(commitId).tree
                TreeWalk(repository).use { treeWalk ->
                    treeWalk.addTree(tree)
                    treeWalk.isRecursive = true
                    while (treeWalk.next()) {
                        val path = treeWalk.pathString
                        if (!isPathSafe(path)) {
                            return "refusing to write unsafe path: $path"
                        }

                        val loader = repository.open(treeWalk.getObjectId(0))
                        // The pack size limit JGit enforces while unpacking
                        // bounds the *compressed* pack, not what the objects
                        // in it expand to. A small, highly compressible pack
                        // can otherwise inflate to far more than that limit
                        // once every blob here is materialized below, so the
                        // running decompressed total is capped too, using
                        // the same limit as a conservative proxy for it.
                        totalInflatedBytes += loader.size
                        if (totalInflatedBytes > maxPushPackSizeBytes) {
                            return "pushed content exceeds the size limit once decompressed"
                        }

                        pushed[path] = loader.bytes
                    }
                }
            }
        } catch (e: Exception) {
            logger.warn("Could not read pushed commit {} for project {}", commitId.name, projectId, e)
            return "could not read the pushed commit"
        }

        // Every push's tree walk covers the whole commit, not a diff against
        // the previous one, so pluginsPath is in `pushed` on every push that
        // has ever touched it, whether or not this particular push actually
        // changes what it says. Comparing against the project's current
        // plugins (the same comparison pluginsFileContent already makes) is
        // what tells a push that merely round-trips the file apart from one
        // that really is trying to change it, so an ordinary write-access
        // push is not held to the admin bar for content it never modified.
        val pushedPlugins = pushed.remove(pluginsPath)
        val pluginsChanged = pushedPlugins != null &&
            !GitPluginsFile.describes(pushedPlugins, pluginService.getProjectPluginUrls(projectId))
        if (pluginsChanged && !callerIsProjectAdmin) {
            return "changing a project's plugins ($pluginsPath) requires project admin permission"
        }

        // A single outer transaction, so a failure partway through (one path
        // rejected, an unreadable plugins file) rolls every write and delete
        // already made by this push back out rather than leaving the project
        // part old, part new. FileService's own `transaction {}` calls join
        // this one on the same thread instead of committing separately.
        return try {
            transaction {
                val existing = readProjectFiles(projectId)

                for ((path, content) in pushed) {
                    val unchanged = existing.any { (existingPath, existingContent) ->
                        existingPath == path && existingContent.contentEquals(content)
                    }
                    if (unchanged) {
                        // Writing back exactly what is already stored would
                        // still bump the file's version and invalidate every
                        // computed AST that depends on it, for a change that
                        // never happened. A push touches every file in the
                        // tree, changed or not, so this is common rather
                        // than an edge case: a one-line fix in an otherwise
                        // large project should not invalidate the rest of it.
                        continue
                    }
                    val result = fileService.writeFile(projectId, path, content, create = true, overwrite = true)
                    if (result is ApiResult.Failure) {
                        throw GitPushRejected("could not write $path: ${result.error.message}")
                    }
                }

                for (path in existing.map { it.first }.toSet() - pushed.keys) {
                    val result = fileService.delete(projectId, path, recursive = false)
                    if (result is ApiResult.Failure) {
                        throw GitPushRejected("could not delete $path: ${result.error.message}")
                    }
                }

                // Git records no empty directories, so a directory a delete
                // above just emptied has nothing left to recreate it. Left
                // alone it would just sit there, and the workbench tree
                // would slowly accumulate folders that never came back from
                // anywhere.
                removeEmptyDirectories(projectId)

                // Absent rather than empty is treated as "not managed by this
                // push", so a client that never touched pluginsPath cannot
                // silently clear every plugin a project has enabled. Applied
                // only when it actually changed (not merely present, which
                // it is on every push): setProjectPlugins invalidates every
                // computed AST for the whole project, so running it on a
                // push that only round-trips the same plugin set unchanged
                // would cost a full recompute for nothing.
                if (pluginsChanged) {
                    val urls = GitPluginsFile.parse(pushedPlugins)
                        ?: throw GitPushRejected("could not read $pluginsPath: not a JSON array of urls")
                    val unknown = pluginService.setProjectPlugins(projectId, urls)
                    if (unknown.isNotEmpty()) {
                        logger.info(
                            "Project {} push referenced unregistered plugin urls, skipped: {}",
                            projectId,
                            unknown
                        )
                    }
                }

                null
            }
        } catch (e: GitPushRejected) {
            e.message
        } catch (e: Exception) {
            // Anything other than GitPushRejected (a path over the column
            // length limit, a connection blip) would otherwise escape after
            // the response stream is already open, so the client sees a
            // broken transfer instead of the clean git-level rejection this
            // is designed to produce for every other failure.
            logger.warn("Unexpected failure applying push for project {}", projectId, e)
            "could not apply the push: an unexpected error occurred"
        }
    }

    /**
     * Builds the contents of [pluginsPath]: the project's enabled plugins, as
     * their registered urls.
     *
     * A pushed [pluginsPath] can be formatted however the client wrote it, so
     * regenerating a fresh, canonically formatted array every time would make
     * the very next fetch look like a change even when the enabled plugins
     * are exactly what was just pushed. To avoid that, the current head's own
     * bytes are reused whenever they already describe the same set of urls,
     * and a fresh array is only built the first time or after a real change
     * (for instance through the workbench's plugin settings, not git).
     *
     * @param repository The project's repository
     * @param currentHead The branch's current head, or null before any commit
     * @param projectId The project to read enabled plugins for
     * @return The JSON array content, encoded as bytes
     */
    private fun pluginsFileContent(repository: PostgresDfsRepository, currentHead: ObjectId?, projectId: UUID): ByteArray {
        val urls = pluginService.getProjectPluginUrls(projectId)
        val existing = currentHead?.let { readPathAt(repository, it, pluginsPath) }
        if (existing != null && GitPluginsFile.describes(existing, urls)) {
            return existing
        }
        return GitPluginsFile.serialize(urls)
    }

    /**
     * Reads one file's bytes out of the tree a commit points at.
     *
     * @param repository The repository to read from
     * @param commitId The commit whose tree should be searched
     * @param path The path to read
     * @return The file's bytes, or null if the commit or path cannot be read
     */
    private fun readPathAt(repository: PostgresDfsRepository, commitId: ObjectId, path: String): ByteArray? =
        try {
            RevWalk(repository).use { walk ->
                val tree = walk.parseCommit(commitId).tree
                TreeWalk.forPath(repository, path, tree)?.use { treeWalk ->
                    repository.open(treeWalk.getObjectId(0)).bytes
                }
            }
        } catch (_: Exception) {
            null
        }

    /**
     * Reads the tree a commit points at.
     *
     * @param repository The repository to read from
     * @param commitId The commit to inspect
     * @return The commit's tree id, or null if the commit cannot be read
     */
    private fun treeOf(repository: PostgresDfsRepository, commitId: ObjectId): ObjectId? =
        try {
            RevWalk(repository).use { walk -> walk.parseCommit(commitId).tree.id }
        } catch (_: Exception) {
            null
        }

    /**
     * Reads every file of a project, with its decoded contents.
     *
     * Directories are skipped: git records only files, and infers the
     * directories from their paths. File contents are held base64 encoded in a
     * text column, so they are decoded back to bytes here, which also means
     * binary files survive the round trip.
     *
     * @param projectId The project to read
     * @return Path to contents, for every file in the project
     */
    private fun readProjectFiles(projectId: UUID): List<Pair<String, ByteArray>> {
        val project = projectId.toKotlinUuid()
        return transaction {
            FilesTable
                .selectAll()
                .where {
                    (FilesTable.projectId eq project) and (FilesTable.fileType eq FileType.FILE)
                }
                .mapNotNull { row ->
                    val path = row[FilesTable.path].trimStart('/')
                    if (path.isEmpty()) {
                        return@mapNotNull null
                    }
                    val encoded = row[FilesTable.content] ?: ""
                    val bytes = if (encoded.isEmpty()) {
                        ByteArray(0)
                    } else {
                        Base64.getDecoder().decode(encoded)
                    }
                    path to bytes
                }
        }
    }

    /**
     * Deletes directory rows that have no children left, repeating until a
     * pass removes nothing (since removing a childless leaf can leave its
     * own parent childless too). The project root (`path == ""`) is never a
     * candidate, so an entirely empty project still has somewhere for new
     * files to attach to.
     *
     * @param projectId The project to clean up
     */
    private fun removeEmptyDirectories(projectId: UUID) {
        val project = projectId.toKotlinUuid()
        while (true) {
            val emptyDirs = FilesTable
                .selectAll()
                .where { (FilesTable.projectId eq project) and (FilesTable.fileType eq FileType.DIRECTORY) }
                .map { it[FilesTable.path] }
                .filter { path ->
                    path.isNotEmpty() &&
                        FilesTable.selectAll()
                            .where { (FilesTable.projectId eq project) and (FilesTable.parentPath eq path) }
                            .empty()
                }

            if (emptyDirs.isEmpty()) {
                return
            }

            for (path in emptyDirs) {
                FilesTable.deleteWhere { (FilesTable.projectId eq project) and (FilesTable.path eq path) }
            }
        }
    }

    /**
     * Sums the estimated size of every pack a project's git storage holds.
     *
     * @param projectId The project to measure
     * @return The total estimated pack size, in bytes
     */
    private fun projectStorageBytes(projectId: UUID): Long {
        val project = projectId.toKotlinUuid()
        return transaction {
            GitPacksTable
                .select(GitPacksTable.estimatedPackSize)
                .where { GitPacksTable.projectId eq project }
                .sumOf { it[GitPacksTable.estimatedPackSize] }
        }
    }

    /**
     * Reclaims storage a rejected push already wrote.
     *
     * JGit unpacks and commits an incoming pack's objects to storage before
     * the pre-receive hook decides whether to accept it, so a push this
     * service ultimately rejects still leaves its objects behind: nothing
     * references them from [branch] or any other ref, but nothing removes
     * them either. [org.eclipse.jgit.internal.storage.dfs.DfsGarbageCollector]
     * is JGit's own reachability walk over a DFS repository: run here, it
     * finds exactly those objects (unreachable from every ref) and prunes
     * the packs holding them through this database's own [PostgresDfsObjDatabase.commitPackImpl],
     * the same method an ordinary push's packs are committed through.
     *
     * Deliberately not run after every push, accepted or not: a push that
     * succeeds needs nothing pruned (every pack it added is reachable from
     * the ref it just moved), and running a full reachability walk on that
     * critical, common path would cost real latency for no benefit. Not run
     * as a background sweep either, for lack of anywhere to schedule one
     * yet; run synchronously, but only on the rarer path where a push was
     * just rejected, so the specific abuse this exists for (growing storage
     * by pushing large rejected packs in a loop) is closed without slowing
     * down every ordinary push.
     *
     * A failure here is only logged, never surfaced to the pushing client:
     * whether stale packs got swept is not part of what a push's result
     * means, and the client already received its rejection before this runs.
     *
     * @param repository The project's repository
     * @param projectId The project whose storage should be reclaimed
     */
    fun reclaimRejectedPushGarbage(repository: PostgresDfsRepository, projectId: UUID) {
        try {
            DfsGarbageCollector(repository).pack(NullProgressMonitor.INSTANCE)
        } catch (e: Exception) {
            logger.warn("Could not reclaim git storage for project {} after a rejected push", projectId, e)
        }
    }
}

/**
 * Whether a path from a pushed tree is safe to write through the ordinary
 * file service.
 *
 * A git client is not necessarily the git CLI: nothing stops one from
 * constructing a tree entry like `../evil`, `.git/config`, or `a//b`
 * directly, and `FileService`'s own path normalization only strips a
 * leading slash, not `.`/`..` segments. Writing one of those in verbatim
 * would not escape onto a filesystem (execution nodes receive content over
 * the websocket, not a checkout), but it would corrupt the files table and
 * break every later git operation on the project the moment JGit tries to
 * build a tree from it again, so it is rejected outright instead.
 *
 * @param path A path from a pushed tree, as `TreeWalk.getPathString()` returns it
 * @return true if the path is safe to write
 */
private fun isPathSafe(path: String): Boolean {
    if (path.isEmpty()) {
        return false
    }
    val segments = path.split("/")
    if (segments.any { it.isEmpty() || it == "." || it == ".." }) {
        return false
    }
    return segments.first() != ".git"
}

/**
 * Thrown from inside [GitRepositoryService.applyCommitToProject]'s transaction
 * to abort and roll back every write and delete already made by the push in
 * progress, rather than letting a mid-push failure land partially.
 */
private class GitPushRejected(message: String) : Exception(message)
