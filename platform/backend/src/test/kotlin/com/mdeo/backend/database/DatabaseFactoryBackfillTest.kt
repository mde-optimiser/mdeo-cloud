package com.mdeo.backend.database

import com.mdeo.backend.config.DatabaseConfig
import com.mdeo.common.model.FileType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.time.Instant
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.toKotlinUuid

/**
 * See [GitRepositoryServiceConcurrencyTest][com.mdeo.backend.git.GitRepositoryServiceConcurrencyTest]
 * for why this is a Testcontainers self-typed subclass rather than the generic container directly.
 */
private class KPostgreSQLContainer(image: DockerImageName) : PostgreSQLContainer<KPostgreSQLContainer>(image)

/**
 * Regression test for the version-counter backfill [DatabaseFactory.init] runs after creating the
 * schema: a file that already existed - with real content and a real version - before
 * [FileVersionCountersTable] was introduced must not have its version silently reset the next
 * time something edits it.
 *
 * Needs a real Postgres to exercise the actual `INSERT ... SELECT ... ON CONFLICT DO NOTHING`
 * this backfill runs, not a fake standing in for it.
 */
@Testcontainers
class DatabaseFactoryBackfillTest {

    @Container
    private val postgres = KPostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))

    @AfterTest
    fun tearDown() {
        DatabaseFactory.close()
    }

    @Test
    fun `a file that existed before the version counter table does not regress to version 1 on its next edit`() {
        val projectId = UUID.randomUUID()
        val path = "existing.txt"
        val now = Instant.now()

        DatabaseFactory.init(
            DatabaseConfig(url = postgres.jdbcUrl, user = postgres.username, password = postgres.password, maxPoolSize = 4)
        )

        // Simulates data that genuinely predates FileVersionCountersTable: written directly to
        // FilesTable, exactly as FileService.writeFile's own INSERT does, but with no
        // corresponding row in the counter table - impossible to produce any other way today,
        // since every real write path now goes through nextVersion(), which is exactly why this
        // has to be reproduced by hand instead of via FileService.
        transaction {
            ProjectsTable.insert {
                it[id] = projectId.toKotlinUuid()
                it[name] = "backfill-test-project"
                it[createdAt] = now
                it[updatedAt] = now
            }
            // The project root, which FileService.ensureParentDirectories's own foreign key
            // (every file's parentPath must reference a real path in this table) requires to
            // exist before a root-level file can be inserted - mirrors what
            // ProjectService.createProject's own fileService.mkdir(projectId, "") call does.
            FilesTable.insert {
                it[FilesTable.projectId] = projectId.toKotlinUuid()
                it[FilesTable.path] = ""
                it[parentPath] = null
                it[fileType] = FileType.DIRECTORY
                it[content] = null
                it[createdAt] = now
                it[updatedAt] = now
            }
            FilesTable.insert {
                it[FilesTable.projectId] = projectId.toKotlinUuid()
                it[FilesTable.path] = path
                it[parentPath] = ""
                it[fileType] = FileType.FILE
                it[content] = "b2xk"
                it[version] = 7
                it[createdAt] = now
                it[updatedAt] = now
            }
        }

        // A second init(), simulating the backend restarting after this upgrade: SchemaUtils.create
        // is a no-op against tables that already exist, and the backfill's ON CONFLICT DO NOTHING
        // means running it again is exactly as safe as running it the first time.
        DatabaseFactory.init(
            DatabaseConfig(url = postgres.jdbcUrl, user = postgres.username, password = postgres.password, maxPoolSize = 4)
        )

        val seededNextVersion = transaction {
            FileVersionCountersTable
                .selectAll()
                .where { (FileVersionCountersTable.projectId eq projectId.toKotlinUuid()) and (FileVersionCountersTable.path eq path) }
                .single()[FileVersionCountersTable.nextVersion]
        }
        assertEquals(
            7,
            seededNextVersion,
            "the counter must be seeded from the file's real, already-stored version, not start from zero"
        )

        // The next edit's version comes from the same nextVersion() every other write path uses -
        // exercised directly here, rather than through FileService, to isolate the backfill itself
        // from FileService.writeFile's own behavior (already covered by its own tests).
        val nextVersion = transaction {
            exec(
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
                StatementType.SELECT
            ) { rs ->
                check(rs.next())
                rs.getInt(1)
            }
        }

        assertEquals(8, nextVersion, "the file's first edit after the upgrade must continue from its real version, not reset to 1")
    }

    @Test
    fun `backfill never lowers a counter that has already moved past a file's stored version`() {
        val projectId = UUID.randomUUID()
        val path = "already-counted.txt"
        val now = Instant.now()

        DatabaseFactory.init(
            DatabaseConfig(url = postgres.jdbcUrl, user = postgres.username, password = postgres.password, maxPoolSize = 4)
        )

        transaction {
            ProjectsTable.insert {
                it[id] = projectId.toKotlinUuid()
                it[name] = "backfill-test-project-2"
                it[createdAt] = now
                it[updatedAt] = now
            }
            FilesTable.insert {
                it[FilesTable.projectId] = projectId.toKotlinUuid()
                it[FilesTable.path] = ""
                it[parentPath] = null
                it[fileType] = FileType.DIRECTORY
                it[content] = null
                it[createdAt] = now
                it[updatedAt] = now
            }
            FilesTable.insert {
                it[FilesTable.projectId] = projectId.toKotlinUuid()
                it[FilesTable.path] = path
                it[parentPath] = ""
                it[fileType] = FileType.FILE
                it[content] = "b2xk"
                it[version] = 2
                it[createdAt] = now
                it[updatedAt] = now
            }
            // A counter already ahead of the file's stored version - e.g. the file was deleted
            // and recreated since, which bumps the counter but resets the row's own version only
            // up to what a fresh create hands out.
            FileVersionCountersTable.insert {
                it[FileVersionCountersTable.projectId] = projectId.toKotlinUuid()
                it[FileVersionCountersTable.path] = path
                it[nextVersion] = 40
            }
        }

        DatabaseFactory.init(
            DatabaseConfig(url = postgres.jdbcUrl, user = postgres.username, password = postgres.password, maxPoolSize = 4)
        )

        val seededNextVersion = transaction {
            FileVersionCountersTable
                .selectAll()
                .where { (FileVersionCountersTable.projectId eq projectId.toKotlinUuid()) and (FileVersionCountersTable.path eq path) }
                .single()[FileVersionCountersTable.nextVersion]
        }
        assertEquals(
            40,
            seededNextVersion,
            "ON CONFLICT DO NOTHING must leave an existing counter untouched, never overwrite it backwards"
        )
    }
}
