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
