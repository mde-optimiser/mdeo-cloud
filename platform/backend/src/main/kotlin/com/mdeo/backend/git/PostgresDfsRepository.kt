package com.mdeo.backend.git

import org.eclipse.jgit.internal.storage.dfs.DfsRepository
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryBuilder
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription
import org.eclipse.jgit.lib.RefDatabase
import java.util.UUID

/**
 * A project exposed as a git repository, stored entirely in Postgres.
 *
 * There is one of these per project. Objects and refs live in the database
 * rather than on disk, which keeps the backend free of persistent state outside
 * Postgres, the property it has today.
 *
 * @param projectId The project this repository represents
 */
class PostgresDfsRepository(
    val projectId: UUID
) : DfsRepository(Builder(projectId)) {

    private val objectDatabase = PostgresDfsObjDatabase(projectId, this)
    private val refDatabase = PostgresDfsRefDatabase(projectId, this)

    /**
     * The project's file versions, by path, as of the moment
     * [GitRepositoryService.openRepository] last published the branch on this
     * instance.
     *
     * A client computes its push against exactly this state, so
     * [GitRepositoryService.applyCommitToProject] checks the project still
     * matches it before applying anything. Recorded here, on the per-request
     * repository, rather than passed through the routes, because the two
     * points that need it are the two ends of one request's work with this
     * object.
     *
     * The workbench's own writes go through [com.mdeo.backend.service.FileService]
     * without taking the per-project git lock, so an edit made in a browser
     * between publication and application is the one thing that lock cannot
     * rule out; without this check the push would silently revert it.
     */
    var publishedFileVersions: Map<String, Int> = emptyMap()

    override fun getObjectDatabase(): PostgresDfsObjDatabase = objectDatabase

    override fun getRefDatabase(): RefDatabase = refDatabase

    /**
     * Builder that names the repository after its project.
     *
     * @param projectId The project this repository represents
     */
    private class Builder(projectId: UUID) :
        DfsRepositoryBuilder<Builder, PostgresDfsRepository>() {

        init {
            setRepositoryDescription(DfsRepositoryDescription(projectId.toString()))
        }

        override fun build(): PostgresDfsRepository =
            throw UnsupportedOperationException("Construct PostgresDfsRepository directly")
    }
}
