package com.mdeo.backend.service

import com.mdeo.common.model.ExecutionState
import com.mdeo.backend.database.ExecutionsTable
import com.mdeo.backend.database.FilesTable
import com.mdeo.backend.database.FileVersionCountersTable
import com.mdeo.common.model.*
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Instant
import java.util.*
import java.util.Base64
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid

/**
 * File extension reserved for MDEO's own generated project metadata.
 *
 * The reservation is on the extension rather than on one exact path,
 * because that is the thing the platform can actually keep free: every
 * project file belongs to a registered language plugin and so carries that
 * plugin's extension, and [PluginService] refuses to register a plugin
 * claiming this one. Nothing a user can legitimately create ends in
 * `.mdeo`, so anything MDEO generates under it is collision-free by
 * construction - including against the directory a path like
 * `something.mdeo/file.txt` would otherwise have created.
 */
const val RESERVED_FILE_EXTENSION = ".mdeo"

/**
 * The single file that extension is currently used for: a project's
 * metadata as served over git, carrying its enabled plugins.
 *
 * Generated fresh by [com.mdeo.backend.git.GitRepositoryService] on every
 * fetch rather than ever stored as an ordinary file, and never writable
 * through the file API; see [FileService.checkNotReserved].
 */
const val RESERVED_PROJECT_FILE = "project$RESERVED_FILE_EXTENSION"

/**
 * Service for managing files and directories within projects.
 *
 * @param services The injected services providing access to configuration and other services
 */
class FileService(services: InjectedServices) : BaseService(), InjectedServices by services {

    /**
     * Returns a failure result if [path] uses the reserved
     * [RESERVED_FILE_EXTENSION], which a project may not write to directly.
     *
     * Reserving this here, not just skipping the generated file when a git
     * commit is applied, is what actually keeps it out of collision with
     * real project content: without it, an old client that had the file
     * locally, a `git add .`, or the file explorer's own UI could create a
     * real row at that path, and every later git operation on the project
     * would then fail with two entries claiming the same path in one tree.
     *
     * Every segment is checked, not just the last one, so `report.mdeo/x.txt`
     * is refused too. That path would otherwise have [ensureParentDirectories]
     * create a `report.mdeo` *directory*, which collides in exactly the same
     * way with a generated file of that name - the reservation has to cover
     * the whole namespace the extension could ever name, not only files.
     *
     * @param path The already-normalized path to check
     * @return ApiResult.Failure if the path is reserved, null otherwise
     */
    private fun checkNotReserved(path: String): ApiResult.Failure? {
        val reserved = path.split('/').firstOrNull {
            it.endsWith(RESERVED_FILE_EXTENSION, ignoreCase = true)
        } ?: return null

        return ApiResult.Failure(
            ApiError(
                ErrorCodes.RESERVED_PATH,
                "'$reserved' uses the reserved '$RESERVED_FILE_EXTENSION' extension and cannot be written to directly"
            )
        )
    }

    /**
     * Returns the version number the file that will now exist at [path]
     * should carry, guaranteed higher than any version that path has ever
     * used before, even across a delete in between.
     *
     * Every write that changes what lives at a path - creating it, editing
     * it, or renaming another file onto it - must go through this rather
     * than incrementing the row's own `version` column in place: a plain
     * per-row counter, reset to 1 by [FilesTable.insert] and merely bumped
     * by an in-place `version + 1` otherwise, would let a deleted-then-
     * recreated (or renamed-away-and-back) file collide with a stale cache
     * entry elsewhere that recorded a dependency on the old file at that
     * same version number. [FileVersionCountersTable] tracks the next
     * version separately, in a row this method never deletes, so it
     * survives the file's own row being deleted or renamed and keeps
     * counting up from there.
     *
     * The insert and the conflict-driven increment are one atomic statement
     * so two concurrent writers at the same path can never be handed the
     * same version.
     *
     * @param projectId The project the file belongs to
     * @param path The already-normalized path the new version is for
     * @return A version number never used before for this exact path
     */
    private fun JdbcTransaction.nextVersion(projectId: UUID, path: String): Int {
        return exec(
            """
            INSERT INTO file_version_counters (project_id, path, next_version)
            VALUES (?, ?, 1)
            ON CONFLICT (project_id, path)
            DO UPDATE SET next_version = file_version_counters.next_version + 1
            RETURNING next_version
            """.trimIndent(),
            listOf(
                FileVersionCountersTable.projectId.columnType to projectId.toKotlinUuid(),
                FileVersionCountersTable.path.columnType to path
            ),
            // Not StatementType.INSERT: Exposed executes that via JDBC's
            // executeUpdate(), which expects an update count and throws on
            // a statement that actually returns rows. This one does,
            // because of RETURNING, so it needs the query path instead.
            StatementType.SELECT
        ) { rs ->
            check(rs.next()) { "INSERT ... RETURNING produced no row" }
            rs.getInt(1)
        }!!
    }

    /**
     * Checks if a project is locked due to an execution in initializing state.
     *
     * @param projectId The UUID of the project
     * @return true if the project is locked, false otherwise
     */
    private fun isProjectLocked(projectId: UUID): Boolean {
        return ExecutionsTable.selectAll()
            .where {
                (ExecutionsTable.projectId eq projectId.toKotlinUuid()) and
                (ExecutionsTable.state eq ExecutionState.INITIALIZING)
            }
            .count() > 0
    }
    
    /**
     * Returns a failure result if the project is locked.
     *
     * @param projectId The UUID of the project
     * @return ApiResult.Failure if locked, null otherwise
     */
    private fun checkProjectLock(projectId: UUID): ApiResult.Failure? {
        return if (isProjectLocked(projectId)) {
            ApiResult.Failure(
                ApiError(
                    ErrorCodes.PROJECT_LOCKED,
                    "Project is locked: an execution is initializing. File modifications are not allowed."
                )
            )
        } else {
            null
        }
    }
    
    /**
     * Reads the contents of a file.
     *
     * @param projectId The UUID of the project
     * @param path The path to the file
     * @return ApiResult containing the file contents as a byte array, or an error
     */
    fun readFile(projectId: UUID, path: String): ApiResult<ByteArray> {
        val normalizedPath = normalizePath(path)
        
        return transaction {
            val row = FilesTable.selectAll()
                .where { (FilesTable.projectId eq projectId.toKotlinUuid()) and (FilesTable.path eq normalizedPath) }
                .firstOrNull()
            
            if (row == null) {
                return@transaction fileSystemFailure(ErrorCodes.FILE_NOT_FOUND, "File not found: $path")
            }
            
            if (row[FilesTable.fileType] == FileType.DIRECTORY) {
                return@transaction fileSystemFailure(ErrorCodes.FILE_IS_A_DIRECTORY, "Is a directory: $path")
            }
            
            val contentText = row[FilesTable.content] ?: ""
            val contentBytes = if (contentText.isEmpty()) ByteArray(0) else Base64.getDecoder().decode(contentText)
            success(contentBytes)
        }
    }
    
    /**
     * Writes content to a file, optionally creating or overwriting it.
     * Will fail if the project is locked due to an execution in initializing state.
     *
     * @param projectId The UUID of the project
     * @param path The path to the file
     * @param content The content to write as a byte array
     * @param create Whether to create the file if it doesn't exist
     * @param overwrite Whether to overwrite the file if it already exists
     * @param expectedVersion When set, the write is rejected unless the file's current version
     *   matches exactly, so a caller editing a version it already knows is stale (for instance a
     *   workbench tab that has been open since before a git push moved the file forward) fails
     *   loudly instead of silently overwriting content it never saw. Not checked when the file
     *   does not exist yet, since there is nothing for it to be stale against.
     * @return ApiResult indicating success or containing an error
     */
    fun writeFile(
        projectId: UUID,
        path: String,
        content: ByteArray,
        create: Boolean,
        overwrite: Boolean,
        expectedVersion: Int? = null
    ): ApiResult<Unit> {
        val normalizedPath = normalizePath(path)
        val now = Instant.now()

        return transaction {
            checkProjectLock(projectId)?.let { return@transaction it }
            checkNotReserved(normalizedPath)?.let { return@transaction it }
            // FOR UPDATE, not a plain SELECT: without it, another transaction could change this
            // row's version between this read and the version check below, then commit before
            // this transaction's own UPDATE runs. The version check would have already passed
            // against a value that was, by the time it mattered, stale - and nextVersion() would
            // already have consumed a version number for a write that was about to be rejected as
            // a conflict anyway. Locking the row for the rest of this transaction is what makes
            // "read the version, decide, write" one atomic step instead of three separate ones a
            // concurrent writer could land inside of.
            val existing = FilesTable.selectAll()
                .where { (FilesTable.projectId eq projectId.toKotlinUuid()) and (FilesTable.path eq normalizedPath) }
                .forUpdate()
                .firstOrNull()

            if (existing != null) {
                if (existing[FilesTable.fileType] == FileType.DIRECTORY) {
                    return@transaction fileSystemFailure(ErrorCodes.FILE_IS_A_DIRECTORY, "Is a directory: $path")
                }
                if (!overwrite) {
                    return@transaction fileSystemFailure(ErrorCodes.FILE_EXISTS, "File already exists: $path")
                }
                val currentVersion = existing[FilesTable.version]
                if (expectedVersion != null && currentVersion != expectedVersion) {
                    return@transaction fileSystemFailure(
                        ErrorCodes.VERSION_CONFLICT,
                        "File $path is at version $currentVersion, expected $expectedVersion"
                    )
                }

                // Only reached once the version check above has passed under the row lock taken
                // above, so nextVersion() here can never be "spent" on a write that turns out to
                // lose a race - there is no longer a race left to lose by this point.
                val contentText = Base64.getEncoder().encodeToString(content)
                val newVersion = nextVersion(projectId, normalizedPath)
                if (expectedVersion != null) {
                    // Folded into the UPDATE's own WHERE clause too, as a second, independent
                    // enforcement of the same invariant the row lock above already guarantees -
                    // belt and braces, not load-bearing on its own.
                    val updatedRows = FilesTable.update({
                        (FilesTable.projectId eq projectId.toKotlinUuid()) and
                        (FilesTable.path eq normalizedPath) and
                        (FilesTable.version eq expectedVersion)
                    }) {
                        it[FilesTable.content] = contentText
                        it[version] = newVersion
                        it[updatedAt] = now
                    }
                    if (updatedRows == 0) {
                        return@transaction fileSystemFailure(
                            ErrorCodes.VERSION_CONFLICT,
                            "File $path was modified concurrently, please retry"
                        )
                    }
                } else {
                    FilesTable.update({
                        (FilesTable.projectId eq projectId.toKotlinUuid()) and (FilesTable.path eq normalizedPath)
                    }) {
                        it[FilesTable.content] = contentText
                        it[version] = newVersion
                        it[updatedAt] = now
                    }
                }
            } else {
                if (!create) {
                    return@transaction fileSystemFailure(ErrorCodes.FILE_NOT_FOUND, "File not found: $path")
                }

                ensureParentDirectories(projectId, normalizedPath, now)?.let { return@transaction it }

                val parentPath = getParentPath(normalizedPath)

                val contentText = Base64.getEncoder().encodeToString(content)
                FilesTable.insert {
                    it[FilesTable.projectId] = projectId.toKotlinUuid()
                    it[FilesTable.path] = normalizedPath
                    it[FilesTable.parentPath] = parentPath
                    it[fileType] = FileType.FILE
                    it[FilesTable.content] = contentText
                    it[version] = nextVersion(projectId, normalizedPath)
                    it[createdAt] = now
                    it[updatedAt] = now
                }
            }
            
            success(Unit)
        }
    }
    
    /**
     * Creates a directory at the specified path.
     * Will fail if the project is locked due to an execution in initializing state.
     *
     * @param projectId The UUID of the project
     * @param path The path where the directory should be created
     * @return ApiResult indicating success or containing an error
     */
    fun mkdir(projectId: UUID, path: String): ApiResult<Unit> {
        val normalizedPath = normalizePath(path)
        val now = Instant.now()
        
        return transaction {
            checkProjectLock(projectId)?.let { return@transaction it }
            checkNotReserved(normalizedPath)?.let { return@transaction it }

            val existing = FilesTable.selectAll()
                .where { (FilesTable.projectId eq projectId.toKotlinUuid()) and (FilesTable.path eq normalizedPath) }
                .firstOrNull()

            if (existing != null) {
                if (existing[FilesTable.fileType] == FileType.DIRECTORY) {
                    return@transaction success(Unit)
                }
                return@transaction fileSystemFailure(ErrorCodes.FILE_EXISTS, "File already exists: $path")
            }

            ensureParentDirectories(projectId, normalizedPath, now)?.let { return@transaction it }

            val parentPath = getParentPath(normalizedPath)

            FilesTable.insert {
                it[FilesTable.projectId] = projectId.toKotlinUuid()
                it[FilesTable.path] = normalizedPath
                it[FilesTable.parentPath] = parentPath
                it[fileType] = FileType.DIRECTORY
                it[content] = null
                it[createdAt] = now
                it[updatedAt] = now
            }
            
            success(Unit)
        }
    }
    
    /**
     * Lists the contents of a directory.
     *
     * @param projectId The UUID of the project
     * @param path The path to the directory
     * @return ApiResult containing a list of file entries, or an error
     */
    fun readdir(projectId: UUID, path: String): ApiResult<List<FileEntry>> {
        val normalizedPath = normalizePath(path)
        
        return transaction {
            val row = FilesTable.selectAll()
                .where { (FilesTable.projectId eq projectId.toKotlinUuid()) and (FilesTable.path eq normalizedPath) }
                .firstOrNull()
            
            if (row == null) {
                return@transaction fileSystemFailure(ErrorCodes.FILE_NOT_FOUND, "Directory not found: $path")
            }
            
            if (row[FilesTable.fileType] != FileType.DIRECTORY) {
                return@transaction fileSystemFailure(ErrorCodes.FILE_NOT_A_DIRECTORY, "Not a directory: $path")
            }
            
            val result = FilesTable.selectAll()
                .where { (FilesTable.projectId eq projectId.toKotlinUuid()) and (FilesTable.parentPath eq normalizedPath) }
                .map { childRow ->
                    FileEntry(getBasename(childRow[FilesTable.path]), childRow[FilesTable.fileType])
                }
            
            success(result)
        }
    }
    
    /**
     * Gets the file type (file or directory) of a path.
     *
     * @param projectId The UUID of the project
     * @param path The path to check
     * @return ApiResult containing the file type as an integer or null if not found, or an error
     */
    fun stat(projectId: UUID, path: String): ApiResult<Int?> {
        val normalizedPath = normalizePath(path)
        
        return transaction {
            val row = FilesTable.selectAll()
                .where { (FilesTable.projectId eq projectId.toKotlinUuid()) and (FilesTable.path eq normalizedPath) }
                .firstOrNull()
            
            if (row == null) {
                success(null)
            } else {
                success(row[FilesTable.fileType])
            }
        }
    }
    
    /**
     * Gets the version of a file.
     *
     * @param projectId The UUID of the project
     * @param path The path to the file
     * @return ApiResult containing the file version as an integer, or an error
     */
    fun getFileVersion(projectId: UUID, path: String): ApiResult<Int> {
        val normalizedPath = normalizePath(path)
        
        return transaction {
            val row = FilesTable.selectAll()
                .where { (FilesTable.projectId eq projectId.toKotlinUuid()) and (FilesTable.path eq normalizedPath) }
                .firstOrNull()
            
            if (row == null) {
                return@transaction fileSystemFailure(ErrorCodes.FILE_NOT_FOUND, "File not found: $path")
            }
            
            if (row[FilesTable.fileType] == FileType.DIRECTORY) {
                return@transaction fileSystemFailure(ErrorCodes.FILE_IS_A_DIRECTORY, "Is a directory: $path")
            }
            
            success(row[FilesTable.version])
        }
    }

    /**
     * Returns all file and directory entries for a project as a flat list of (path, type) pairs.
     * Used for the bulk project load over WebSocket.
     *
     * @param projectId The UUID of the project
     * @return List of (path, fileType) pairs for every entry in the project
     */
    fun getAllEntries(projectId: UUID): List<Pair<String, Int>> {
        return transaction {
            FilesTable
                .select(FilesTable.path, FilesTable.fileType)
                .where { FilesTable.projectId eq projectId.toKotlinUuid() }
                .map { row -> row[FilesTable.path] to row[FilesTable.fileType] }
        }
    }
    
    /**
     * Deletes a file or directory.
     * Will fail if the project is locked due to an execution in initializing state.
     *
     * @param projectId The UUID of the project
     * @param path The path to delete
     * @param recursive Whether to recursively delete directory contents
     * @param expectedVersion When set, the delete is rejected unless the file's current version
     *   matches exactly - the same compare-and-set [writeFile] offers, for a caller (a git push
     *   replacing the project's tree) that read the file's version earlier and needs to know
     *   nothing else changed it before removing it. Not checked when the path is a directory,
     *   which carries no meaningful version, or when null.
     * @return ApiResult indicating success or containing an error
     */
    fun delete(projectId: UUID, path: String, recursive: Boolean, expectedVersion: Int? = null): ApiResult<Unit> {
        val normalizedPath = normalizePath(path)

        return transaction {
            checkProjectLock(projectId)?.let { return@transaction it }
            checkNotReserved(normalizedPath)?.let { return@transaction it }

            val row = FilesTable.selectAll()
                .where { (FilesTable.projectId eq projectId.toKotlinUuid()) and (FilesTable.path eq normalizedPath) }
                .firstOrNull()

            if (row == null) {
                return@transaction fileSystemFailure(ErrorCodes.FILE_NOT_FOUND, "File or directory not found: $path")
            }

            if (row[FilesTable.fileType] == FileType.DIRECTORY) {
                val childrenCount = FilesTable.selectAll()
                    .where { (FilesTable.projectId eq projectId.toKotlinUuid()) and (FilesTable.parentPath eq normalizedPath) }
                    .count()

                if (childrenCount > 0 && !recursive) {
                    return@transaction fileSystemFailure(ErrorCodes.DIRECTORY_NOT_EMPTY, "Directory not empty: $path")
                }

                FilesTable.deleteWhere {
                    (FilesTable.projectId eq projectId.toKotlinUuid()) and (FilesTable.path eq normalizedPath)
                }
            } else if (expectedVersion != null) {
                // Same reasoning as writeFile's compare-and-set: folding the version into the
                // DELETE's own WHERE clause is what makes the check atomic against a writer
                // that commits between an earlier SELECT of this version and this statement.
                val deletedRows = FilesTable.deleteWhere {
                    (FilesTable.projectId eq projectId.toKotlinUuid()) and
                    (FilesTable.path eq normalizedPath) and
                    (FilesTable.version eq expectedVersion)
                }
                if (deletedRows == 0) {
                    return@transaction fileSystemFailure(
                        ErrorCodes.VERSION_CONFLICT,
                        "File $path was modified concurrently, please retry"
                    )
                }
            } else {
                FilesTable.deleteWhere {
                    (FilesTable.projectId eq projectId.toKotlinUuid()) and (FilesTable.path eq normalizedPath)
                }
            }

            success(Unit)
        }
    }
    
    /**
     * Renames or moves a file or directory.
     * Will fail if the project is locked due to an execution in initializing state.
     *
     * @param projectId The UUID of the project
     * @param from The current path
     * @param to The new path
     * @param overwrite Whether to overwrite the destination if it exists
     * @return ApiResult indicating success or containing an error
     */
    fun rename(projectId: UUID, from: String, to: String, overwrite: Boolean): ApiResult<Unit> {
        val normalizedFrom = normalizePath(from)
        val normalizedTo = normalizePath(to)
        val now = Instant.now()
        
        return transaction {
            checkProjectLock(projectId)?.let { return@transaction it }
            checkNotReserved(normalizedFrom)?.let { return@transaction it }
            checkNotReserved(normalizedTo)?.let { return@transaction it }

            val sourceRow = FilesTable.selectAll()
                .where { (FilesTable.projectId eq projectId.toKotlinUuid()) and (FilesTable.path eq normalizedFrom) }
                .firstOrNull()
            
            if (sourceRow == null) {
                return@transaction fileSystemFailure(ErrorCodes.FILE_NOT_FOUND, "Source not found: $from")
            }
            
            val destExists = FilesTable.selectAll()
                .where { (FilesTable.projectId eq projectId.toKotlinUuid()) and (FilesTable.path eq normalizedTo) }
                .count() > 0
            
            if (destExists && !overwrite) {
                return@transaction fileSystemFailure(ErrorCodes.FILE_EXISTS, "Destination already exists: $to")
            }
            
            if (destExists) {
                FilesTable.deleteWhere { 
                    (FilesTable.projectId eq projectId.toKotlinUuid()) and (FilesTable.path eq normalizedTo) 
                }
            }
            
            ensureParentDirectories(projectId, normalizedTo, now)?.let { return@transaction it }

            val oldParent = getParentPath(normalizedFrom)
            val newParent = getParentPath(normalizedTo)
            
            if (sourceRow[FilesTable.fileType] == FileType.DIRECTORY) {
                renameDirectoryChildren(projectId, normalizedFrom, normalizedTo)
            }
            
            val renamedVersion = nextVersion(projectId, normalizedTo)
            FilesTable.update({
                (FilesTable.projectId eq projectId.toKotlinUuid()) and (FilesTable.path eq normalizedFrom)
            }) {
                it[path] = normalizedTo
                it[parentPath] = newParent
                it[updatedAt] = now
                it[version] = renamedVersion
            }
            
            success(Unit)
        }
    }
    
    /**
     * Gets the parent path of a given path.
     *
     * @param path The input path
     * @return The parent path, or null if the input is empty
     */
    private fun getParentPath(path: String): String? {
        if (path.isEmpty()) return null
        val lastSlash = path.lastIndexOf('/')
        return if (lastSlash == -1) "" else path.substring(0, lastSlash)
    }
    
    /**
     * Gets the basename (last segment) of a path.
     *
     * @param path The input path
     * @return The basename of the path
     */
    private fun getBasename(path: String): String {
        val lastSlash = path.lastIndexOf('/')
        return if (lastSlash == -1) path else path.substring(lastSlash + 1)
    }
    
    /**
     * Ensures that all parent directories exist for a given path.
     *
     * @param projectId The UUID of the project
     * @param path The path whose parent directories should be created
     * @param now The timestamp to use for creation
     * @return An ApiResult failure if an existing row on the parent chain is a file rather than a
     *   directory, null otherwise
     */
    private fun ensureParentDirectories(projectId: UUID, path: String, now: Instant): ApiResult<Nothing>? {
        val parentPath = getParentPath(path) ?: return null

        val parent = FilesTable.selectAll()
            .where { (FilesTable.projectId eq projectId.toKotlinUuid()) and (FilesTable.path eq parentPath) }
            .firstOrNull()

        if (parent == null) {
            ensureParentDirectories(projectId, parentPath, now)?.let { return it }

            val grandparentPath = getParentPath(parentPath)

            FilesTable.insert {
                it[FilesTable.projectId] = projectId.toKotlinUuid()
                it[FilesTable.path] = parentPath
                it[FilesTable.parentPath] = grandparentPath
                it[fileType] = FileType.DIRECTORY
                it[content] = null
                it[createdAt] = now
                it[updatedAt] = now
            }
        } else if (parent[FilesTable.fileType] != FileType.DIRECTORY) {
            // A row already exists at this exact path, but it is a file, not a directory: without
            // this check, the caller below would go on to insert a child row underneath it
            // (parentPath pointing at a path that names a file), which git happily writes and
            // reads back, but which the workbench's own directory listing can never reach, since
            // readdir only ever looks for children of an actual directory row. This is exactly
            // what a push that replaces a file with a directory at the same path does if writes
            // and deletes are not carefully ordered around it; see GitRepositoryService's
            // applyCommitToProject for the other half of that fix.
            return fileSystemFailure(
                ErrorCodes.FILE_NOT_A_DIRECTORY,
                "Not a directory: $parentPath"
            )
        }
        return null
    }
    
    /**
     * Renames all children of a directory when the directory is renamed.
     *
     * @param projectId The UUID of the project
     * @param oldPath The old directory path
     * @param newPath The new directory path
     */
    private fun JdbcTransaction.renameDirectoryChildren(projectId: UUID, oldPath: String, newPath: String) {
        val prefix = if (oldPath.isEmpty()) "" else "$oldPath/"
        val newPrefix = if (newPath.isEmpty()) "" else "$newPath/"

        FilesTable.selectAll()
            .where {
                (FilesTable.projectId eq projectId.toKotlinUuid()) and
                (FilesTable.path like "$prefix%")
            }
            .forEach { row ->
                val oldChildPath = row[FilesTable.path]
                val newChildPath = newPrefix + oldChildPath.substring(prefix.length)
                val newChildVersion = nextVersion(projectId, newChildPath)

                FilesTable.update({
                    (FilesTable.projectId eq projectId.toKotlinUuid()) and (FilesTable.path eq oldChildPath)
                }) {
                    it[path] = newChildPath
                    it[updatedAt] = Instant.now()
                    it[version] = newChildVersion
                }
            }
    }
}
