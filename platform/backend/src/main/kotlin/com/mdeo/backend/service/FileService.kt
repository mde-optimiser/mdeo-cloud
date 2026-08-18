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
 * Path of the file carrying a project's enabled plugins when served over
 * git. Reserved here, and generated fresh by
 * [com.mdeo.backend.git.GitRepositoryService] rather than ever written
 * through the ordinary file API; see [FileService.checkNotReserved].
 */
const val RESERVED_PLUGINS_PATH = ".mdeo"

/**
 * Service for managing files and directories within projects.
 *
 * @param services The injected services providing access to configuration and other services
 */
class FileService(services: InjectedServices) : BaseService(), InjectedServices by services {

    /**
     * Returns a failure result if [path] is a reserved path a project may not
     * write to directly.
     *
     * `.mdeo` carries a project's enabled plugins when served over git (see
     * [com.mdeo.backend.git.GitRepositoryService]), generated from the
     * database rather than stored as an ordinary file. Reserving the name
     * here, not just skipping it when a git commit is applied, is what
     * actually keeps it out of collision with real project content: without
     * this, an old client that had it locally, a `git add .`, or the file
     * explorer's own UI could create a real row at that exact path, which
     * would then make every later git operation on the project fail (two
     * entries claiming the same path in one tree).
     *
     * @param path The already-normalized path to check
     * @return ApiResult.Failure if the path is reserved, null otherwise
     */
    private fun checkNotReserved(path: String): ApiResult.Failure? {
        return if (path == RESERVED_PLUGINS_PATH) {
            ApiResult.Failure(
                ApiError(ErrorCodes.RESERVED_PATH, "'$path' is reserved and cannot be written to directly")
            )
        } else {
            null
        }
    }

    /**
     * Returns the version number a newly (re)created file at [path] should
     * start at, guaranteed higher than any version that path has ever used
     * before, even across a delete in between.
     *
     * A plain per-row counter, reset by [FilesTable.insert] to 1 every time,
     * would let a deleted-then-recreated file collide with a stale cache
     * entry elsewhere that recorded a dependency on the old file at that
     * same version 1 (the common case for a file that was created and never
     * edited). [FileVersionCountersTable] tracks the next version
     * separately, in a row this method never deletes, so it survives the
     * file's own row being deleted and keeps counting up from there.
     *
     * The insert and the conflict-driven increment are one atomic statement
     * so two concurrent creations at the same path can never be handed the
     * same version.
     *
     * @param projectId The project the file belongs to
     * @param path The already-normalized path being (re)created
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
            val existing = FilesTable.selectAll()
                .where { (FilesTable.projectId eq projectId.toKotlinUuid()) and (FilesTable.path eq normalizedPath) }
                .firstOrNull()

            if (existing != null) {
                if (existing[FilesTable.fileType] == FileType.DIRECTORY) {
                    return@transaction fileSystemFailure(ErrorCodes.FILE_IS_A_DIRECTORY, "Is a directory: $path")
                }
                if (!overwrite) {
                    return@transaction fileSystemFailure(ErrorCodes.FILE_EXISTS, "File already exists: $path")
                }
                if (expectedVersion != null && existing[FilesTable.version] != expectedVersion) {
                    return@transaction fileSystemFailure(
                        ErrorCodes.VERSION_CONFLICT,
                        "File $path is at version ${existing[FilesTable.version]}, expected $expectedVersion"
                    )
                }

                val currentVersion = existing[FilesTable.version]
                val contentText = Base64.getEncoder().encodeToString(content)
                FilesTable.update({ (FilesTable.projectId eq projectId.toKotlinUuid()) and (FilesTable.path eq normalizedPath) }) {
                    it[FilesTable.content] = contentText
                    it[version] = currentVersion + 1
                    it[updatedAt] = now
                }
            } else {
                if (!create) {
                    return@transaction fileSystemFailure(ErrorCodes.FILE_NOT_FOUND, "File not found: $path")
                }
                
                ensureParentDirectories(projectId, normalizedPath, now)
                
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

            ensureParentDirectories(projectId, normalizedPath, now)
            
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
     * @return ApiResult indicating success or containing an error
     */
    fun delete(projectId: UUID, path: String, recursive: Boolean): ApiResult<Unit> {
        val normalizedPath = normalizePath(path)
        
        return transaction {
            checkProjectLock(projectId)?.let { return@transaction it }
            
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
                
            }
            
            FilesTable.deleteWhere { 
                (FilesTable.projectId eq projectId.toKotlinUuid()) and (FilesTable.path eq normalizedPath) 
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
            
            ensureParentDirectories(projectId, normalizedTo, now)
            
            val oldParent = getParentPath(normalizedFrom)
            val newParent = getParentPath(normalizedTo)
            
            if (sourceRow[FilesTable.fileType] == FileType.DIRECTORY) {
                renameDirectoryChildren(projectId, normalizedFrom, normalizedTo)
            }
            
            FilesTable.update({ 
                (FilesTable.projectId eq projectId.toKotlinUuid()) and (FilesTable.path eq normalizedFrom) 
            }) {
                it[path] = normalizedTo
                it[parentPath] = newParent
                it[updatedAt] = now
                it[version] = version + 1
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
     */
    private fun ensureParentDirectories(projectId: UUID, path: String, now: Instant) {
        val parentPath = getParentPath(path) ?: return
        
        val parent = FilesTable.selectAll()
            .where { (FilesTable.projectId eq projectId.toKotlinUuid()) and (FilesTable.path eq parentPath) }
            .firstOrNull()
        
        if (parent == null) {
            ensureParentDirectories(projectId, parentPath, now)
            
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
        }
    }
    
    /**
     * Renames all children of a directory when the directory is renamed.
     *
     * @param projectId The UUID of the project
     * @param oldPath The old directory path
     * @param newPath The new directory path
     */
    private fun renameDirectoryChildren(projectId: UUID, oldPath: String, newPath: String) {
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
                
                FilesTable.update({ 
                    (FilesTable.projectId eq projectId.toKotlinUuid()) and (FilesTable.path eq oldChildPath) 
                }) {
                    it[path] = newChildPath
                    it[updatedAt] = Instant.now()
                    it[version] = version + 1
                }
            }
    }
}
