package com.mdeo.backend.git

import com.mdeo.backend.database.FilesTable
import com.mdeo.backend.service.FileService
import com.mdeo.backend.service.PluginService
import com.mdeo.backend.service.RESERVED_FILE_EXTENSION
import com.mdeo.backend.service.RESERVED_PROJECT_FILE
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
import java.util.concurrent.TimeUnit
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
     * Path of the generated file carrying a project's metadata, currently its
     * enabled plugins.
     *
     * One file rather than a `.mdeo/` directory, deliberately. What the
     * platform can actually keep free is the `.mdeo` *extension*: every
     * project file belongs to a registered language plugin and so carries
     * that plugin's extension, and `PluginService` refuses to register a
     * plugin claiming this one, so nothing a user can legitimately create
     * ever ends that way. Reserving a directory name instead would take a
     * whole prefix out of the project's namespace for no more safety.
     *
     * Diagram layout was considered for this file too (see issue #29) but
     * left out: it is purely representational, changes on practically every
     * diagram interaction, and would turn the commit-on-fetch boundary into a
     * commit on every look rather than one on real change.
     */
    val projectFilePath = RESERVED_PROJECT_FILE

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
     * Opens a project's repository, establishing HEAD if it does not have one yet.
     *
     * @param projectId The project to open
     * @return The repository, with HEAD pointing at [branch] and its files published there
     */
    fun openRepository(projectId: UUID): PostgresDfsRepository {
        val repository = PostgresDfsRepository(projectId)
        // Keyed on HEAD itself rather than on whether the object database
        // exists: `ObjectDatabase.exists()` is a hardcoded `return true` that
        // no DFS subclass overrides, so a guard on it never fires and the
        // link below never ran. HEAD is what actually has to be established
        // here, so asking after HEAD is both the honest condition and one
        // that repairs a repository created before this was fixed.
        //
        // There is nothing to create(): a DFS repository has no on-disk
        // layout to lay down, and create() would in fact throw "Repository
        // already exists" for the same reason the old guard never fired.
        //
        // Without the link, this service publishes only on [branch] while
        // HEAD stays unborn, and an unborn remote HEAD is not a cosmetic
        // detail: a clone that has to choose one branch for itself
        // (`--single-branch`, which `--depth` implies) finds no branch to
        // choose and silently produces an empty repository.
        if (repository.exactRef(Constants.HEAD) == null) {
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
     * Always includes [projectFilePath], even for a project with no ordinary
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
        val files = readProjectFiles(projectId)
        // Recorded before the early return below, so a fetch that publishes
        // nothing new still leaves a push in the same request checking
        // against the state it actually advertised.
        repository.publishedFileVersions = files.associate { it.path to it.version }
        val projectFileContent = projectFileContent(repository, currentHead, projectId)

        repository.newObjectInserter().use { inserter ->
            // DirCache builds the nested trees from flat paths, so directories
            // in the files table do not need to be walked. Git infers them.
            val dirCache = DirCache.newInCore()
            val builder = dirCache.builder()
            for ((path, content) in files.map { it.path to it.content } + (projectFilePath to projectFileContent)) {
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
     * Every path in the commit, except [projectFilePath], is written through
     * the ordinary file service, so a push goes through exactly the same
     * validation, version bump and project locking as an edit made in the
     * workbench. Files the commit no longer contains are deleted, which is
     * what makes a push replace the project rather than only add to it.
     * [projectFilePath] is handled separately: it replaces the project's
     * enabled plugins rather than being written as a file, and its absence
     * from the pushed tree leaves plugins untouched rather than clearing
     * them. Since [projectFilePath] is always removed from the pushed tree
     * before any of it reaches [FileService], and [FileService] independently
     * refuses to write anything using the reserved extension itself, a
     * project file can never end up at that path through either route.
     *
     * Changing a project's plugins is normally gated on project admin
     * permission (see `PluginRoutes.kt`), not merely write access, so a
     * pushed [projectFilePath] is held to the same bar: [callerIsProjectAdmin]
     * must be true, or the whole push is rejected rather than silently
     * dropping just that part of it.
     *
     * The push is applied only if the project still holds exactly the files
     * that were published when this request opened the repository. The
     * per-project git lock keeps other git operations out, but the
     * workbench's writes do not take it, so a browser edit landing while a
     * push is being unpacked would otherwise be silently reverted by a push
     * that never saw it. Rejecting is the right answer rather than merging:
     * a pushed tree replaces the project wholesale, so any concurrent edit
     * genuinely conflicts with it, and the user fetches and pushes again.
     *
     * @param repository The project's repository, holding the pushed objects
     * @param projectId The project to update
     * @param commitId The commit whose contents should become the project
     * @param callerIsProjectAdmin Whether the pushing user has admin permission
     *   on this project
     * @param onFilesSnapshotted Test seam only: called once this push's per-file version
     *   snapshot has been read, before anything is written against it. Lets a test commit a
     *   concurrent edit in exactly that window - the one a real race depends on landing at the
     *   right moment - so it can be exercised deterministically instead of by timing. A no-op in
     *   production.
     * @return null on success, or a message describing why the push cannot be
     *   applied
     */
    fun applyCommitToProject(
        repository: PostgresDfsRepository,
        projectId: UUID,
        commitId: ObjectId,
        callerIsProjectAdmin: Boolean,
        onFilesSnapshotted: () -> Unit = {}
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
        // the previous one, so projectFilePath is in `pushed` on every push
        // that has ever touched it, whether or not this particular push
        // actually changes what it says. Comparing against the project's
        // current plugins (the same comparison projectFileContent already
        // makes) is what tells a push that merely round-trips the file apart
        // from one that really is trying to change it, so an ordinary
        // write-access push is not held to the admin bar for content it never
        // modified.
        //
        // Parsed here rather than where it is applied below, so a malformed
        // file is refused before the push has written anything at all.
        val pushedProjectFile = pushed.remove(projectFilePath)
        val pushedPlugins = if (pushedProjectFile == null) {
            null
        } else {
            val contents = GitProjectFile.parse(pushedProjectFile)
                ?: return "could not read $projectFilePath: not a JSON object with a plugins array of urls"
            contents.plugins
        }

        // A single outer transaction, so a failure partway through (one path
        // rejected, an unreadable plugins file) rolls every write and delete
        // already made by this push back out rather than leaving the project
        // part old, part new. FileService's own `transaction {}` calls join
        // this one on the same thread instead of committing separately.
        return try {
            transaction {
                // Indexed by path rather than scanned per pushed file: a push
                // carries the whole tree, so a linear search here would make
                // applying one O(files squared), and nothing bounds a
                // project's file count (the pack limit bounds bytes).
                val existing = readProjectFiles(projectId).associateBy { it.path }
                onFilesSnapshotted()

                if (existing.mapValues { it.value.version } != repository.publishedFileVersions) {
                    throw GitPushRejected(
                        "the project changed while this push was being applied; fetch, merge and push again"
                    )
                }

                // Read inside this transaction, not before it: a plugin change made from the
                // workbench while this push was still being unpacked would otherwise be compared
                // against here using a value read before the transaction even started, and an
                // admin push that never touched the project file itself could go on to overwrite
                // that intervening change once setProjectPlugins runs below. Reading it here,
                // alongside the file-version snapshot above, is what makes both checks see the
                // same, single consistent moment.
                //
                // Null when the push asks for no plugin change at all: either it did not carry
                // the file, or carried one describing exactly what the project already has.
                val changedPlugins = pushedPlugins
                    ?.takeIf { it != pluginService.getProjectPluginUrls(projectId).sorted() }
                if (changedPlugins != null && !callerIsProjectAdmin) {
                    throw GitPushRejected(
                        "changing a project's plugins ($projectFilePath) requires project admin permission"
                    )
                }

                // Deletes run before writes, not the other order: a push that replaces a file
                // with a directory at the same path (foo the file becomes foo/ containing foo/bar)
                // has "foo" in existing.keys - pushed.keys and "foo/bar" in pushed. Writing
                // "foo/bar" first would try to create it under a parent that, at that point, is
                // still the old file row at "foo" - FileService now refuses that outright rather
                // than silently nesting a child under a non-directory - and only the delete of
                // "foo" below actually clears the way. Running deletes first means the write loop
                // never has to contend with a parent path the push's own tree is about to replace.
                for (path in existing.keys - pushed.keys) {
                    // existing[path] is always present here (path is drawn from existing.keys),
                    // so this guards a delete against a concurrent edit to the very file the push
                    // is about to remove.
                    val result = fileService.delete(
                        projectId, path, recursive = false,
                        expectedVersion = existing[path]?.version
                    )
                    if (result is ApiResult.Failure) {
                        throw GitPushRejected("could not delete $path: ${result.error.message}")
                    }
                }

                for ((path, content) in pushed) {
                    val previous = existing[path]
                    val unchanged = previous?.content?.contentEquals(content) == true
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
                    // Guarded by the version this same transaction just read for path, not only
                    // by the whole-push snapshot check above: that check runs once, before this
                    // loop, so a workbench edit committing in the gap between it and this
                    // particular write would otherwise land here with no expectedVersion at all
                    // and be silently overwritten. Passing it folds the check into this write's
                    // own UPDATE, so a collision in that gap is caught here instead.
                    //
                    // overwrite is conditioned on whether this path was already in the snapshot,
                    // not hardcoded true: for a path pushed's tree introduces fresh (previous ==
                    // null), overwrite = false means a file a concurrent workbench edit created at
                    // this exact path, after the snapshot was taken, causes this write to fail
                    // with FILE_EXISTS instead of silently clobbering it - expectedVersion alone
                    // cannot catch this case, since there was no version to expect yet.
                    val result = fileService.writeFile(
                        projectId, path, content, create = true, overwrite = previous != null,
                        expectedVersion = previous?.version
                    )
                    if (result is ApiResult.Failure) {
                        throw GitPushRejected("could not write $path: ${result.error.message}")
                    }
                }

                // Git records no empty directories, so a directory a delete
                // above just emptied has nothing left to recreate it. Left
                // alone it would just sit there, and the workbench tree
                // would slowly accumulate folders that never came back from
                // anywhere.
                removeEmptyDirectories(projectId)

                // A missing file, or one with no `plugins` key, is treated as
                // "not managed by this push", so a client that never touched
                // projectFilePath cannot silently clear every plugin a
                // project has enabled. Applied only when it actually changed
                // (not merely present, which it is on every push):
                // setProjectPlugins invalidates every computed AST for the
                // whole project, so running it on a push that only
                // round-trips the same plugin set unchanged would cost a full
                // recompute for nothing.
                if (changedPlugins != null) {
                    val unknown = pluginService.setProjectPlugins(projectId, changedPlugins)
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
     * Builds the contents of [projectFilePath]: the project's metadata, which
     * today is its enabled plugins as their registered urls.
     *
     * A pushed [projectFilePath] can be formatted however the client wrote
     * it, so regenerating a fresh, canonically formatted document every time
     * would make the very next fetch look like a change even when the enabled
     * plugins are exactly what was just pushed. To avoid that, the current
     * head's own bytes are reused whenever they already describe the same set
     * of urls, and a fresh document is only built the first time or after a
     * real change (for instance through the workbench's plugin settings, not
     * git).
     *
     * @param repository The project's repository
     * @param currentHead The branch's current head, or null before any commit
     * @param projectId The project to read enabled plugins for
     * @return The JSON content, encoded as bytes
     */
    private fun projectFileContent(repository: PostgresDfsRepository, currentHead: ObjectId?, projectId: UUID): ByteArray {
        val urls = pluginService.getProjectPluginUrls(projectId)
        val existing = currentHead?.let { readPathAt(repository, it, projectFilePath) }
        if (existing != null && GitProjectFile.describes(existing, urls)) {
            return existing
        }
        return GitProjectFile.serialize(urls)
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
     * One file of a project, as git needs to see it.
     *
     * @property path The file's path, relative to the project root
     * @property content The decoded file content
     * @property version The file's current version, used to tell whether the
     *   project still holds what a push was computed against; see
     *   [PostgresDfsRepository.publishedFileVersions]
     */
    private class ProjectFile(val path: String, val content: ByteArray, val version: Int)

    /**
     * Reads every file of a project git should see, with its decoded
     * contents and version.
     *
     * Directories are skipped: git records only files, and infers the
     * directories from their paths. File contents are held base64 encoded in a
     * text column, so they are decoded back to bytes here, which also means
     * binary files survive the round trip.
     *
     * Anything using the reserved extension is skipped as well. [FileService]
     * refuses to write such a path, so this is defensive, but a stray row
     * from before that reservation existed would otherwise collide with the
     * generated [projectFilePath] entry in the same tree - and, because both
     * callers here have to agree on what "the project's files" means, it has
     * to be skipped in one place rather than filtered at each of them.
     *
     * @param projectId The project to read
     * @return Every file in the project git should see
     */
    private fun readProjectFiles(projectId: UUID): List<ProjectFile> {
        val project = projectId.toKotlinUuid()
        return transaction {
            FilesTable
                .selectAll()
                .where {
                    (FilesTable.projectId eq project) and (FilesTable.fileType eq FileType.FILE)
                }
                .mapNotNull { row ->
                    val path = row[FilesTable.path].trimStart('/')
                    // Every segment is checked, not just the path's own suffix, matching
                    // FileService.checkNotReserved: a stray "project.mdeo/child.txt" row left
                    // over from before this reservation existed does not itself end in
                    // RESERVED_FILE_EXTENSION, but "project.mdeo" - the segment naming its
                    // directory - does, and it would otherwise collide with the generated
                    // projectFilePath entry in the same tree exactly as a bare stray file would.
                    val reserved = path.split('/').any { it.endsWith(RESERVED_FILE_EXTENSION, ignoreCase = true) }
                    if (path.isEmpty() || reserved) {
                        return@mapNotNull null
                    }
                    val encoded = row[FilesTable.content] ?: ""
                    val bytes = if (encoded.isEmpty()) {
                        ByteArray(0)
                    } else {
                        Base64.getDecoder().decode(encoded)
                    }
                    ProjectFile(path, bytes, row[FilesTable.version])
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
     * The garbage TTL is what makes that pruning actually happen. Left at its
     * default the collector still identifies the unreachable objects, but only
     * moves them into an `UNREACHABLE_GARBAGE` pack and keeps it, so storage
     * grew by roughly one rejected pack per attempt and never shrank - the
     * abuse this function exists to close stayed wide open, and a project
     * driven past [maxProjectStorageBytes] that way stayed over the limit
     * permanently, refusing every later push. An expiry of one millisecond
     * means "garbage found by this run is garbage now": safe here because
     * withProjectLock holds the project for the whole of the push that just
     * failed, so there is no concurrent push whose freshly written, not yet
     * referenced objects could be swept out from under it.
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
            DfsGarbageCollector(repository)
                .setGarbageTtl(1, TimeUnit.MILLISECONDS)
                .pack(NullProgressMonitor.INSTANCE)
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
