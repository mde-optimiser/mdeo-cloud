package com.mdeo.backend.git

import com.mdeo.backend.config.DatabaseConfig
import com.mdeo.backend.database.DatabaseFactory
import com.mdeo.backend.database.GitPackFilesTable
import com.mdeo.backend.database.GitRefsTable
import com.mdeo.backend.database.ProjectsTable
import com.mdeo.backend.service.FileService
import com.mdeo.common.model.ApiResult
import org.eclipse.jgit.lib.Constants
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.time.Instant
import java.util.UUID
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.uuid.toKotlinUuid

/**
 * Covers the state a project's repository has to be left in for ordinary git clients to work
 * against it: that HEAD exists and is advertised, and that storage a rejected push wrote is
 * genuinely reclaimed rather than merely identified.
 *
 * Needs a real Postgres for the same reason the concurrency test does - both properties are
 * about what is actually written to this database's ref and pack tables, which no fake would
 * exercise.
 */
@Testcontainers
class GitRepositoryStateTest {

    @Container
    private val postgres = KPostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))

    private lateinit var fileService: FileService
    private lateinit var gitRepositoryService: GitRepositoryService
    private val projectId = UUID.randomUUID()

    @BeforeTest
    fun setUp() {
        DatabaseFactory.init(
            DatabaseConfig(
                url = postgres.jdbcUrl,
                user = postgres.username,
                password = postgres.password,
                maxPoolSize = 4
            )
        )

        val services = TestServices()
        fileService = services.fileService
        gitRepositoryService = GitRepositoryService(
            fileService = services.fileService,
            pluginService = services.pluginService,
            maxPushPackSizeBytes = 100L * 1024 * 1024,
            maxProjectStorageBytes = 2L * 1024 * 1024 * 1024
        )

        val now = Instant.now()
        transaction {
            ProjectsTable.insert {
                it[id] = projectId.toKotlinUuid()
                it[name] = "repository-state-test-project"
                it[createdAt] = now
                it[updatedAt] = now
            }
        }
    }

    @AfterTest
    fun tearDown() {
        DatabaseFactory.close()
    }

    /**
     * Without HEAD, git can still clone by taking every ref it is offered, but a client that has
     * to pick one branch for itself has nothing to pick from: `git clone --single-branch` (which
     * `--depth` implies) reports "you appear to have cloned an empty repository" and exits 0,
     * leaving the user with an empty directory and no error to go on.
     */
    @Test
    fun `opening a repository leaves HEAD pointing at the project branch`() {
        val written = fileService.writeFile(projectId, "a.txt", "hello".toByteArray(), create = true, overwrite = true)
        assertTrue(written is ApiResult.Success, "test setup: creating the file should succeed")

        val repository = gitRepositoryService.openRepository(projectId)
        val branchCommit = repository.resolve(gitRepositoryService.branch)
        assertNotNull(branchCommit, "test setup: opening the repository should publish a commit on the branch")

        val head = repository.exactRef(Constants.HEAD)
        assertNotNull(head, "HEAD must exist, or a single-branch clone finds no branch to check out")
        assertTrue(head.isSymbolic, "HEAD must be symbolic so clients learn which branch is the default one")
        assertEquals(gitRepositoryService.branch, head.target.name, "HEAD must point at the branch this service publishes")
        assertEquals(branchCommit, head.objectId, "HEAD must resolve to the branch's current commit")
    }

    /**
     * A repository that predates HEAD being established has to gain one on its next access,
     * rather than only new projects getting it right.
     */
    @Test
    fun `a repository whose HEAD was never linked gains one on the next open`() {
        gitRepositoryService.openRepository(projectId)

        val project = projectId.toKotlinUuid()
        transaction {
            GitRefsTable.deleteWhere { (GitRefsTable.projectId eq project) and (GitRefsTable.name eq Constants.HEAD) }
        }

        val reopened = gitRepositoryService.openRepository(projectId)
        val head = reopened.exactRef(Constants.HEAD)
        assertNotNull(head, "an existing repository missing HEAD must have it restored, not only new ones")
        assertEquals(gitRepositoryService.branch, head.target.name)
    }

    /**
     * Listing refs is the first thing every clone, fetch and push does. A symbolic ref recorded
     * only as symbolic - and not also among the repository's refs - makes JGit's own RefMap throw
     * while merging the two, which surfaces as a 500 on every git request rather than as anything
     * a client could act on.
     */
    @Test
    fun `refs can be listed while a symbolic HEAD exists`() {
        val repository = gitRepositoryService.openRepository(projectId)

        val refs = repository.refDatabase.getRefsByPrefix("")

        assertTrue(refs.any { it.name == Constants.HEAD }, "HEAD must be among the advertised refs")
        assertTrue(
            refs.any { it.name == gitRepositoryService.branch },
            "the project branch must still be advertised alongside HEAD"
        )
    }

    /**
     * JGit commits an incoming pack's objects before the pre-receive hook decides anything, so a
     * rejected push leaves them behind unreferenced. Identifying them is not enough: left at the
     * collector's default expiry they are only moved into an `UNREACHABLE_GARBAGE` pack and kept
     * forever, so every rejected push added its whole pack to the project's stored size and
     * nothing ever gave it back - which is what let a loop of rejected pushes grow the database
     * without bound and strand a project permanently over its storage limit.
     *
     * What the sweep guarantees is that the total stays bounded, not that it returns to zero: a
     * sweep prunes the garbage parked by the previous one and parks its own, so a project carries
     * at most about one rejected push's worth rather than accumulating one per attempt. Two rounds
     * are therefore the smallest test that can tell the two behaviours apart.
     */
    @Test
    fun `repeatedly rejected pushes do not accumulate storage`() {
        val written = fileService.writeFile(projectId, "kept.txt", "keep me".toByteArray(), create = true, overwrite = true)
        assertTrue(written is ApiResult.Success, "test setup: creating the file should succeed")

        val repository = gitRepositoryService.openRepository(projectId)
        val branchCommit = repository.resolve(gitRepositoryService.branch)
        assertNotNull(branchCommit, "test setup: opening the repository should publish a commit on the branch")

        val afterFirstRound = rejectedPushRound(repository, seed = 1)
        val afterSecondRound = rejectedPushRound(repository, seed = 2)

        assertTrue(
            afterSecondRound < afterFirstRound + GARBAGE_BYTES,
            "a second rejected push must not add its pack on top of the first one's; " +
                "stored size was $afterFirstRound after one round and $afterSecondRound after two"
        )

        // The sweep must take only the garbage with it.
        assertEquals(
            branchCommit,
            gitRepositoryService.openRepository(projectId).resolve(gitRepositoryService.branch),
            "reclaiming must leave the branch and its reachable content intact"
        )
        val kept = fileService.readFile(projectId, "kept.txt")
        assertTrue(kept is ApiResult.Success)
        assertEquals("keep me", String(kept.value))
    }

    /**
     * Stands in for one rejected push: objects written into a pack that no ref ever comes to
     * reference, followed by the sweep that push would trigger.
     *
     * @param repository The project's repository
     * @param seed Makes each round's content distinct, so a later round cannot be deduplicated
     *   against an earlier one and appear free
     * @return The project's stored pack size once the sweep has run
     */
    private fun rejectedPushRound(repository: PostgresDfsRepository, seed: Int): Long {
        // Random rather than repetitive, so the pack cannot be compressed down to a size too
        // small to tell apart from the project's own content.
        val garbage = Random(seed).nextBytes(GARBAGE_BYTES)
        repository.newObjectInserter().use { inserter ->
            inserter.insert(Constants.OBJ_BLOB, garbage)
            inserter.flush()
        }
        assertTrue(
            packStorageBytes() > GARBAGE_BYTES,
            "test setup: the unreferenced objects should have grown the project's stored size"
        )

        // The collector's expiry is wall-clock, so the previous round's parked garbage has to be
        // older than it to count as expired. A real rejected push is separated from the next one
        // by far longer than this; without it the whole test runs inside a single millisecond.
        Thread.sleep(50)

        gitRepositoryService.reclaimRejectedPushGarbage(repository, projectId)
        return packStorageBytes()
    }

    /**
     * What the project's packs actually occupy in this database, which is what the storage limit
     * is meant to bound.
     */
    private fun packStorageBytes(): Long = transaction {
        GitPackFilesTable
            .select(GitPackFilesTable.size)
            .where { GitPackFilesTable.projectId eq projectId.toKotlinUuid() }
            .sumOf { it[GitPackFilesTable.size] }
    }

    private companion object {
        /**
         * Large enough that a round's garbage dwarfs the project's own few hundred bytes of
         * content, so the assertions are comparing rounds rather than noise.
         */
        const val GARBAGE_BYTES = 2 * 1024 * 1024
    }
}
