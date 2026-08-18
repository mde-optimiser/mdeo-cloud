package com.mdeo.backend.database

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import com.mdeo.common.model.ExecutionState
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * Users table schema for storing user accounts and authentication data.
 */
object UsersTable : Table("users") {
    val id = uuid("id")
    val username = varchar("username", 255).uniqueIndex()
    val passwordHash = varchar("password_hash", 255)
    val roles = text("roles")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}

/**
 * Projects table schema for storing project metadata.
 */
object ProjectsTable : Table("projects") {
    val id = uuid("id")
    val name = varchar("name", 255)
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}

/**
 * Project user membership table schema for managing project-level permissions.
 */
object ProjectOwnersTable : Table("project_owners") {
    val projectId = uuid("project_id").references(ProjectsTable.id, onDelete = ReferenceOption.CASCADE)
    val userId = uuid("user_id").references(UsersTable.id, onDelete = ReferenceOption.CASCADE)
    val isAdmin = bool("is_admin").default(false)
    val canExecute = bool("can_execute").default(false)
    val canWrite = bool("can_write").default(false)

    override val primaryKey = PrimaryKey(projectId, userId)
}

/**
 * Files table schema for storing project files and directory structures.
 */
object FilesTable : Table("files") {
    val projectId = uuid("project_id").references(ProjectsTable.id, onDelete = ReferenceOption.CASCADE)
    val path = varchar("path", 1024)
    val parentPath = varchar("parent_path", 1024).nullable()
    val fileType = integer("file_type")
    val content = text("content").nullable()
    val version = integer("version").default(1)
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(projectId, path)

    init {
        foreignKey(
            projectId,
            parentPath,
            target = primaryKey,
            onDelete = ReferenceOption.CASCADE,
            onUpdate = ReferenceOption.CASCADE
        )
        index(false, projectId, parentPath)
    }
}

/**
 * Tracks the next version number to use for a path, surviving that path's
 * own row in [FilesTable] being deleted.
 *
 * A file's version resets to 1 on every insert, since [FilesTable] itself
 * has nowhere to remember what it was before a delete. A cache entry
 * elsewhere that recorded a dependency on that path at version 1 (the
 * common case: a file created and never edited again) would then pass an
 * equality check against a completely different later file at the same
 * path, purely because both happen to be at version 1. This table exists
 * so a path's version keeps counting up across a delete, not just within
 * one row's lifetime, which is what actually makes "the same version" mean
 * "the same content" again.
 */
object FileVersionCountersTable : Table("file_version_counters") {
    val projectId = uuid("project_id").references(ProjectsTable.id, onDelete = ReferenceOption.CASCADE)
    val path = varchar("path", 1024)
    val nextVersion = integer("next_version").default(1)

    override val primaryKey = PrimaryKey(projectId, path)
}

/**
 * File metadata table schema for storing additional metadata associated with files.
 */
object FileMetadataTable : Table("file_metadata") {
    val projectId = uuid("project_id")
    val path = varchar("path", 1024)
    val metadata = json<JsonObject>("metadata", Json)
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    init {
        foreignKey(
            projectId,
            path,
            target = FilesTable.primaryKey,
            onDelete = ReferenceOption.CASCADE,
            onUpdate = ReferenceOption.CASCADE
        )
    }

    override val primaryKey = PrimaryKey(projectId, path)
}

/**
 * Plugins table schema for storing registered plugins.
 */
object PluginsTable : Table("plugins") {
    val id = uuid("id")
    val url = varchar("url", 2048).uniqueIndex()
    val name = varchar("name", 255)
    val description = text("description")
    val icon = text("icon")
    val default = bool("default").default(false)
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}

/**
 * Project plugins table schema for managing plugin associations with projects.
 */
object ProjectPluginsTable : Table("project_plugins") {
    val projectId = uuid("project_id").references(ProjectsTable.id, onDelete = ReferenceOption.CASCADE)
    val pluginId = uuid("plugin_id").references(PluginsTable.id, onDelete = ReferenceOption.CASCADE)

    override val primaryKey = PrimaryKey(projectId, pluginId)
}

/**
 * Language plugins table schema for storing language plugin data from plugin manifests.
 */
object LanguagePluginsTable : Table("language_plugins") {
    val id = varchar("id", 255)
    val pluginId = uuid("plugin_id").references(PluginsTable.id, onDelete = ReferenceOption.CASCADE)
    val name = varchar("name", 255)
    val extension = varchar("extension", 64).nullable()
    val newFileAction = bool("new_file_action").default(false)
    val serverPluginImport = varchar("server_plugin_import", 2048)
    val graphicalEditorPluginImport = varchar("graphical_editor_plugin_import", 2048).nullable()
    val graphicalEditorStylesUrl = varchar("graphical_editor_styles_url", 2048).nullable()
    val graphicalEditorStylesCls = varchar("graphical_editor_styles_cls", 255).nullable()
    val textualEditorLanguageConfiguration = text("textual_editor_language_configuration").nullable()
    val textualEditorMonarchTokensProvider = text("textual_editor_monarch_tokens_provider").nullable()
    val icon = text("icon")
    val isGenerated = bool("is_generated").default(false)
    val documentationUrl = varchar("documentation_url", 2048).nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(pluginId, id)
}

/**
 * Contribution plugins table schema for storing contribution plugins from plugin manifests.
 * Contribution plugins provide additional functionality to existing languages.
 */
object ContributionPluginsTable : Table("contribution_plugins") {
    val id = uuid("id")
    val pluginId = uuid("plugin_id").references(PluginsTable.id, onDelete = ReferenceOption.CASCADE)
    val languageId = varchar("language_id", 255)
    val description = text("description")
    val additionalKeywords = text("additional_keywords")
    val serverContributionPlugins = text("server_contribution_plugins")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}

/**
 * File data table schema for caching computed file data (e.g., AST).
 */
object FileDataTable : Table("file_data") {
    val projectId = uuid("project_id")
    val path = varchar("path", 1024)
    val dataKey = varchar("data_key", 255)
    val data = json<JsonElement>("data", Json)
    val sourceVersion = integer("source_version")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    init {
        foreignKey(
            projectId,
            path,
            target = FilesTable.primaryKey,
            onDelete = ReferenceOption.CASCADE,
            onUpdate = ReferenceOption.CASCADE
        )
    }

    override val primaryKey = PrimaryKey(projectId, path, dataKey)
}

/**
 * In-flight file data computations.
 *
 * A row exists only while a plugin is computing data for a file, and is what the token handed to
 * that plugin is bound to: once the computation ends the row is gone and the token stops being
 * accepted, even though it has not expired yet. Rows left behind by a crash are ignored once they
 * are older than the configured computation timeout, and purged when the next computation starts.
 */
object FileDataComputationsTable : Table("file_data_computations") {
    val id = uuid("id")
    val projectId = uuid("project_id")
    val path = varchar("path", 1024)
    val dataKey = varchar("data_key", 255)
    val startedAt = timestamp("started_at")

    init {
        foreignKey(
            projectId,
            path,
            target = FilesTable.primaryKey,
            onDelete = ReferenceOption.CASCADE,
            onUpdate = ReferenceOption.CASCADE
        )
        index(false, startedAt)
    }

    override val primaryKey = PrimaryKey(id)
}

/**
 * File dependencies table schema for tracking file-to-file dependencies.
 */
object FileDependenciesTable : Table("file_dependencies") {
    val projectId = uuid("project_id")
    val path = varchar("path", 1024)
    val dataKey = varchar("data_key", 255)

    val dependencyPath = varchar("dependency_path", 1024)
    val dependencyVersion = integer("dependency_version")

    init {
        foreignKey(
            projectId,
            path,
            dataKey,
            target = FileDataTable.primaryKey,
            onDelete = ReferenceOption.CASCADE,
            onUpdate = ReferenceOption.CASCADE
        )

        index(
            isUnique = true,
            projectId,
            path,
            dataKey,
            dependencyPath
        )
    }
}


/**
 * Data dependencies table schema for tracking file data-to-file data dependencies.
 */
object DataDependenciesTable : Table("data_dependencies") {
    val projectId = uuid("project_id")
    val path = varchar("path", 1024)
    val dataKey = varchar("data_key", 255)

    val dependencyPath = varchar("dependency_path", 1024)
    val dependencyKey = varchar("dependency_key", 255)
    val dependencyVersion = integer("dependency_version")

    init {
        foreignKey(
            projectId,
            path,
            dataKey,
            target = FileDataTable.primaryKey,
            onDelete = ReferenceOption.CASCADE,
            onUpdate = ReferenceOption.CASCADE
        )

        index(
            isUnique = true,
            projectId,
            path,
            dataKey,
            dependencyPath,
            dependencyKey
        )
    }
}

/**
 * Executions table schema for storing execution metadata.
 * Files are not cascade deleted (executions persist after source file deletion),
 * but project deletion cascades to remove all executions.
 */
object ExecutionsTable : Table("executions") {
    val id = uuid("id")
    val projectId = uuid("project_id").references(ProjectsTable.id, onDelete = ReferenceOption.CASCADE)
    val name = varchar("name", 255)
    val state = varchar("state", 32).default(ExecutionState.SUBMITTED)
    val progressText = text("progress_text").nullable()
    val metadata = json<JsonObject>("metadata", Json).nullable()
    val filePath = varchar("file_path", 1024)
    val languageId = varchar("language_id", 255)
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    /**
     * Timestamp when the execution started running (transitioned to running state) 
     */
    val startedAt = timestamp("started_at").nullable()
    /**
     * Timestamp when the execution finished (completed, cancelled, or failed) 
     */
    val finishedAt = timestamp("finished_at").nullable()

    override val primaryKey = PrimaryKey(id)

    init {
        index(false, projectId)
    }
}

/**
 * Execution file metadata table schema for storing additional metadata associated with execution result files.
 */
object ExecutionFileMetadataTable : Table("execution_file_metadata") {
    val executionId = uuid("execution_id")
    val path = varchar("path", 1024)
    val metadata = json<JsonObject>("metadata", Json)
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    init {
        foreignKey(
            executionId,
            target = ExecutionsTable.primaryKey,
            onDelete = ReferenceOption.CASCADE
        )
    }

    override val primaryKey = PrimaryKey(executionId, path)
}

/**
 * Git pack metadata for a project's repository.
 *
 * JGit's DFS storage layer keeps objects in pack files rather than loose, and
 * hands the storage backend whole packs to write and read back. This table
 * holds the state needed to reconstruct a `DfsPackDescription` on listPacks,
 * while the pack bytes themselves live in [GitPackFilesTable], one row per
 * file extension of the pack.
 */
object GitPacksTable : Table("git_packs") {
    val projectId = uuid("project_id").references(ProjectsTable.id, onDelete = ReferenceOption.CASCADE)

    /**
     * Pack name as generated by JGit, unique within the repository.
     */
    val packName = varchar("pack_name", 255)

    /**
     * Name of the `DfsObjDatabase.PackSource` this pack came from. JGit orders
     * packs by source when searching for objects, so it has to survive a reload.
     */
    val packSource = varchar("pack_source", 32)

    val objectCount = long("object_count").default(0)
    val deltaCount = long("delta_count").default(0)
    val indexVersion = integer("index_version").default(0)
    val minUpdateIndex = long("min_update_index").default(0)
    val maxUpdateIndex = long("max_update_index").default(0)
    val estimatedPackSize = long("estimated_pack_size").default(0)
    val lastModified = long("last_modified").default(0)
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(projectId, packName)

    init {
        index(false, projectId)
    }
}

/**
 * The bytes of one file belonging to a git pack, for example the `.pack`
 * itself or its `.idx` index.
 *
 * Kept separate from [GitPacksTable] because a pack has several files with
 * different extensions, and because JGit reads them as random access streams,
 * so the payload is fetched only when a read actually reaches it.
 */
object GitPackFilesTable : Table("git_pack_files") {
    val projectId = uuid("project_id")
    val packName = varchar("pack_name", 255)

    /**
     * `PackExt` name, for example PACK or INDEX.
     */
    val ext = varchar("ext", 32)

    val data = binary("data")
    val size = long("size")

    /**
     * Block size JGit should read this file in. Zero means let JGit decide.
     */
    val blockSize = integer("block_size").default(0)

    override val primaryKey = PrimaryKey(projectId, packName, ext)

    init {
        foreignKey(
            projectId,
            packName,
            target = GitPacksTable.primaryKey,
            onDelete = ReferenceOption.CASCADE,
            onUpdate = ReferenceOption.CASCADE
        )
    }
}

/**
 * Refs of a project's repository.
 *
 * A ref points either at an object or, when it is symbolic, at another ref.
 * `HEAD` is normally the latter. JGit updates these through a compare and set,
 * so writes check the previous value rather than overwriting blindly.
 */
object GitRefsTable : Table("git_refs") {
    val projectId = uuid("project_id").references(ProjectsTable.id, onDelete = ReferenceOption.CASCADE)

    /**
     * Full ref name, for example `refs/heads/main`.
     */
    val name = varchar("name", 512)

    /**
     * Object this ref resolves to, or null when the ref is symbolic.
     */
    val objectId = varchar("object_id", 40).nullable()

    /**
     * Ref this one points at when symbolic, otherwise null.
     */
    val symTarget = varchar("sym_target", 512).nullable()

    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(projectId, name)

    init {
        index(false, projectId)
    }
}
