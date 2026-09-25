package com.mdeo.backend.git

import com.mdeo.backend.config.AppConfig
import com.mdeo.backend.config.CorsConfig
import com.mdeo.backend.config.DatabaseConfig
import com.mdeo.backend.config.DefaultAdminConfig
import com.mdeo.backend.config.FileDataConfig
import com.mdeo.backend.config.GitConfig
import com.mdeo.backend.config.JwtConfig
import com.mdeo.backend.config.PluginConfig
import com.mdeo.backend.config.SessionConfig
import com.mdeo.backend.database.DatabaseFactory
import com.mdeo.backend.database.ProjectsTable
import com.mdeo.backend.service.AuthRateLimiter
import com.mdeo.backend.service.ExecutionService
import com.mdeo.backend.service.FileDataService
import com.mdeo.backend.service.FileService
import com.mdeo.backend.service.InjectedServices
import com.mdeo.backend.service.JwtService
import com.mdeo.backend.service.LanguagePluginRequestService
import com.mdeo.backend.service.MetadataService
import com.mdeo.backend.service.OAuthCodeService
import com.mdeo.backend.service.PersonalAccessTokenService
import com.mdeo.backend.service.PluginService
import com.mdeo.backend.service.ProjectService
import com.mdeo.backend.service.RESERVED_PROJECT_FILE
import com.mdeo.backend.service.SshKeyService
import com.mdeo.backend.service.TokenBindingService
import com.mdeo.backend.service.UserService
import com.mdeo.backend.service.WebSocketNotificationService
import com.mdeo.common.model.ApiResult
import org.eclipse.jgit.dircache.DirCache
import org.eclipse.jgit.dircache.DirCacheEntry
import org.eclipse.jgit.lib.CommitBuilder
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.jetbrains.exposed.v1.jdbc.insert
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.uuid.toKotlinUuid

/**
 * Kotlin cannot instantiate Testcontainers' self-typed generic containers directly
 * (`PostgreSQLContainer<Nothing>` is as close as the type system gets and does not compile calls
 * that return `SELF`), so a trivially self-typed subclass is the standard way to use it from Kotlin.
 */
internal class KPostgreSQLContainer(image: DockerImageName) : PostgreSQLContainer<KPostgreSQLContainer>(image)

/**
 * Regression test for the race [GitRepositoryService.applyCommitToProject]'s per-file
 * `expectedVersion` guard closes: a workbench edit landing after this push's file-version
 * snapshot is read, but before its own write for that path runs, must be rejected rather than
 * silently overwritten.
 *
 * Needs a real Postgres, not a fake: the guard is a database-level compare-and-set, and it is
 * exactly Postgres's own READ COMMITTED semantics - a statement seeing another transaction's
 * commit that landed after this transaction began - that make the race possible at all, and make
 * the fix work. [GitRepositoryService.applyCommitToProject]'s `onFilesSnapshotted` parameter is a
 * test-only seam that makes the timing deterministic instead of relying on real concurrency.
 */
@Testcontainers
class GitRepositoryServiceConcurrencyTest {

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
                it[name] = "race-test-project"
                it[createdAt] = now
                it[updatedAt] = now
            }
        }
    }

    @AfterTest
    fun tearDown() {
        DatabaseFactory.close()
    }

    @Test
    fun `a workbench edit landing during a push's own write loop is not silently overwritten`() {
        val path = "race.txt"
        val created = fileService.writeFile(projectId, path, "original".toByteArray(), create = true, overwrite = true)
        assertTrue(created is ApiResult.Success, "test setup: creating the file should succeed")

        // Publishes current content as a commit and records the version a real client would have
        // computed its change against - exactly what happens on a real clone.
        val repository = gitRepositoryService.openRepository(projectId)
        val baseCommit = repository.resolve(gitRepositoryService.branch)
        assertNotNull(baseCommit, "test setup: opening the repository should publish a base commit")

        // The pushed commit: a client's local edit of race.txt, built from that same base -
        // exactly what `git push` would send after someone edited their clone.
        val pushedCommit = commitWithFile(repository, baseCommit, path, "pushed-content")

        var concurrentEditApplied = false
        val failure = gitRepositoryService.applyCommitToProject(
            repository, projectId, pushedCommit, callerIsProjectAdmin = true,
            onFilesSnapshotted = {
                // Fires after applyCommitToProject has read race.txt's version but before it
                // writes anything against it - the exact window a real concurrent workbench edit
                // depends on landing in to be at risk of being silently overwritten. Run on a
                // separate thread, with its own independent transaction, and joined before
                // returning: calling fileService.writeFile directly here would instead nest
                // inside applyCommitToProject's own still-open transaction on this thread, so it
                // would share that transaction's rollback rather than committing on its own the
                // way a genuinely concurrent request (its own thread, its own connection) does.
                val thread = Thread {
                    val result = fileService.writeFile(
                        projectId, path, "concurrent-workbench-edit".toByteArray(),
                        create = false, overwrite = true
                    )
                    concurrentEditApplied = result is ApiResult.Success
                }
                thread.start()
                thread.join()
            }
        )

        assertTrue(concurrentEditApplied, "test setup: the concurrent edit itself should have succeeded")
        assertNotNull(failure, "the push should have been rejected, not silently applied over the concurrent edit")

        val finalContent = fileService.readFile(projectId, path)
        assertTrue(finalContent is ApiResult.Success)
        assertEquals(
            "concurrent-workbench-edit",
            String(finalContent.value),
            "the concurrent edit must win - the push must not have overwritten it"
        )
    }

    @Test
    fun `a workbench create landing during a push's own write loop for a new file is not silently overwritten`() {
        val path = "new.txt"

        // Published with no files at all, so `path` is genuinely new to both the project and
        // this push - existing[path] is null, which is exactly the case the per-file version
        // guard could not cover before overwrite was conditioned on previous != null.
        val repository = gitRepositoryService.openRepository(projectId)
        val baseCommit = repository.resolve(gitRepositoryService.branch)
        assertNotNull(baseCommit, "test setup: opening the repository should publish a base commit")

        val pushedCommit = commitWithFile(repository, baseCommit, path, "pushed-content")

        var concurrentCreateApplied = false
        val failure = gitRepositoryService.applyCommitToProject(
            repository, projectId, pushedCommit, callerIsProjectAdmin = true,
            onFilesSnapshotted = {
                // A workbench user creates the very path this push is about to introduce, in the
                // gap between the push's snapshot (which saw no file at all here) and its own
                // write for that path. Same separate-thread reasoning as the edit-path test above.
                val thread = Thread {
                    val result = fileService.writeFile(
                        projectId, path, "concurrent-workbench-create".toByteArray(),
                        create = true, overwrite = false
                    )
                    concurrentCreateApplied = result is ApiResult.Success
                }
                thread.start()
                thread.join()
            }
        )

        assertTrue(concurrentCreateApplied, "test setup: the concurrent create itself should have succeeded")
        assertNotNull(failure, "the push should have been rejected, not silently applied over the concurrent create")

        val finalContent = fileService.readFile(projectId, path)
        assertTrue(finalContent is ApiResult.Success)
        assertEquals(
            "concurrent-workbench-create",
            String(finalContent.value),
            "the concurrent create must win - the push must not have overwritten it"
        )
    }

    /**
     * Builds a commit as a real `git push` would produce one: a tree with [path] set to
     * [content], plus [RESERVED_PROJECT_FILE] describing the project's current (here, empty)
     * plugin set exactly, so [GitRepositoryService.applyCommitToProject] does not also attempt an
     * unrelated plugin change while this test is exercising the version race.
     */
    private fun commitWithFile(
        repository: PostgresDfsRepository,
        parent: ObjectId,
        path: String,
        content: String
    ): ObjectId {
        repository.newObjectInserter().use { inserter ->
            val dirCache = DirCache.newInCore()
            val builder = dirCache.builder()

            val fileEntry = DirCacheEntry(path)
            fileEntry.fileMode = FileMode.REGULAR_FILE
            fileEntry.setObjectId(inserter.insert(Constants.OBJ_BLOB, content.toByteArray()))
            builder.add(fileEntry)

            val projectFileEntry = DirCacheEntry(RESERVED_PROJECT_FILE)
            projectFileEntry.fileMode = FileMode.REGULAR_FILE
            projectFileEntry.setObjectId(inserter.insert(Constants.OBJ_BLOB, GitProjectFile.serialize(emptyList())))
            builder.add(projectFileEntry)

            builder.finish()
            val treeId = dirCache.writeTree(inserter)

            val identity = PersonIdent("test", "test@test.invalid")
            val commit = CommitBuilder().apply {
                setTreeId(treeId)
                setParentId(parent)
                setAuthor(identity)
                setCommitter(identity)
                message = "test commit"
            }
            val commitId = inserter.insert(commit)
            inserter.flush()
            return commitId
        }
    }
}

/**
 * The minimal [InjectedServices] the git tests need: [FileService] and [PluginService] wired to a
 * real database, with every other service left to fail loudly if the code under test ever turns
 * out to depend on one - a signal a test has grown a dependency it does not declare, rather
 * than a silent no-op.
 */
internal class TestServices : InjectedServices {
    // Built by hand rather than via AppConfig.load(): that reads real environment variables,
    // several of which (e.g. PLUGIN_BASE_URL) it assigns into non-nullable fields with no
    // fallback, which is fine for the application's actual deployments (which always set them)
    // but not something a unit test should depend on having in its environment.
    override val config: AppConfig = AppConfig(
        serverPort = 8080,
        trustedProxyHops = 0,
        database = DatabaseConfig(url = "unused", user = "unused", password = "unused", maxPoolSize = 1),
        session = SessionConfig(
            maxIdleSeconds = 3600, maxAbsoluteSeconds = 3600, cookieSecure = true,
            encryptionKey = "test", sameSite = "Strict"
        ),
        cors = CorsConfig(allowedHosts = emptyList()),
        defaultAdmin = DefaultAdminConfig(username = "admin", password = "admin"),
        defaultNewUserCanCreateProject = false,
        plugin = PluginConfig(baseUrl = "http://localhost", internalBaseUrl = "http://localhost", forceHttp1 = false),
        jwt = JwtConfig(expirationSeconds = 3600, executionExpirationSeconds = 3600, issuer = "test"),
        fileData = FileDataConfig(computationTimeoutSeconds = 300),
        git = GitConfig(
            maxPushPackSizeBytes = 100L * 1024 * 1024, maxProjectStorageBytes = 2L * 1024 * 1024 * 1024,
            sshPort = 2222, oauthClientId = "test", oauthCodeTtlSeconds = 300,
            oauthAuthorizePath = "/oauth/authorize", oauthTokenPath = "/api/oauth/token"
        )
    )
    override val fileService: FileService by lazy { FileService(this) }
    override val pluginService: PluginService by lazy { PluginService(this) }
    override val userService: UserService get() = error("not needed by this test")
    override val projectService: ProjectService get() = error("not needed by this test")
    override val metadataService: MetadataService get() = error("not needed by this test")
    override val jwtService: JwtService get() = error("not needed by this test")
    override val tokenBindingService: TokenBindingService get() = error("not needed by this test")
    override val fileDataService: FileDataService get() = error("not needed by this test")
    override val executionService: ExecutionService get() = error("not needed by this test")
    override val webSocketNotificationService: WebSocketNotificationService get() = error("not needed by this test")
    override val languagePluginRequestService: LanguagePluginRequestService get() = error("not needed by this test")
    override val authRateLimiter: AuthRateLimiter get() = error("not needed by this test")
    override val personalAccessTokenService: PersonalAccessTokenService get() = error("not needed by this test")
    override val sshKeyService: SshKeyService get() = error("not needed by this test")
    override val oAuthCodeService: OAuthCodeService get() = error("not needed by this test")
}
