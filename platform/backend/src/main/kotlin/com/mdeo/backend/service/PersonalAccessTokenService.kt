package com.mdeo.backend.service

import com.mdeo.backend.database.PersonalAccessTokenProjectsTable
import com.mdeo.backend.database.PersonalAccessTokensTable
import com.mdeo.backend.database.ProjectsTable
import com.mdeo.backend.database.UsersTable
import com.mdeo.common.model.PersonalAccessTokenCreated
import com.mdeo.common.model.PersonalAccessTokenInfo
import com.mdeo.common.model.TokenProjectScope
import com.mdeo.common.model.User
import com.mdeo.common.model.UserRoles
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid

/**
 * A prefix on every generated token, matching GitHub's own convention for
 * personal access tokens. Lets a caller (in particular [com.mdeo.backend.routes.gitRoutes])
 * cheaply decide whether an HTTP basic password looks like a token before
 * doing a database lookup, and gives anyone reading logs or support
 * requests an unambiguous way to recognize one.
 */
private const val TOKEN_PREFIX = "mdeo_pat_"

/**
 * Manages personal access tokens: an alternative to the account password
 * for git's HTTP basic auth that is independently revocable and does not
 * expose the account password itself.
 *
 * @param services The injected services providing access to configuration and other services
 */
class PersonalAccessTokenService(services: InjectedServices) : BaseService(), InjectedServices by services {
    private val logger = LoggerFactory.getLogger(PersonalAccessTokenService::class.java)
    private val secureRandom = SecureRandom()

    /**
     * Creates a new token for a user.
     *
     * @param userId The token's owner
     * @param name A label the user chooses to tell tokens apart later
     * @param expiresAt When the token stops working, or null for no expiry
     * @return The created token's metadata plus the raw value, which is
     *   never stored and cannot be recovered after this call returns
     */
    fun createToken(
        userId: UUID,
        name: String,
        expiresAt: Instant?,
        projectIds: List<UUID> = emptyList()
    ): PersonalAccessTokenCreated? {
        val rawToken = generateToken()
        val id = UUID.randomUUID()
        val now = Instant.now()
        val scope = projectIds.distinct()

        // A scope may only ever narrow what its owner can already reach, so
        // a project the owner cannot read is refused outright rather than
        // silently dropped - a token that quietly covers less than the
        // caller asked for is worse than one they are told to fix.
        //
        // Existence is checked separately from permission because
        // hasProjectPermission short-circuits to true for a global admin
        // without ever looking the project up, so for them an id that
        // matches no project at all would otherwise pass the permission
        // check and fail later against the foreign key.
        val isGlobalAdmin = userService.findById(userId)?.roles?.contains(UserRoles.ADMIN) == true
        if (scope.isNotEmpty()) {
            val existing = transaction {
                ProjectsTable
                    .select(ProjectsTable.id)
                    .where { ProjectsTable.id inList scope.map { it.toKotlinUuid() } }
                    .map { it[ProjectsTable.id].toJavaUuid() }
                    .toSet()
            }
            if (scope.any {
                    it !in existing ||
                        !projectService.hasProjectPermission(it, userId, isGlobalAdmin, ProjectPermission.READ)
                }
            ) {
                return null
            }
        }

        val scopeNames = transaction {
            PersonalAccessTokensTable.insert {
                it[PersonalAccessTokensTable.id] = id.toKotlinUuid()
                it[PersonalAccessTokensTable.userId] = userId.toKotlinUuid()
                it[PersonalAccessTokensTable.name] = name
                it[tokenHash] = hashToken(rawToken)
                it[tokenPrefix] = rawToken.take(TOKEN_PREFIX.length + 4)
                it[createdAt] = now
                it[PersonalAccessTokensTable.expiresAt] = expiresAt
                it[PersonalAccessTokensTable.scoped] = scope.isNotEmpty()
            }

            if (scope.isNotEmpty()) {
                PersonalAccessTokenProjectsTable.batchInsert(scope) { projectId ->
                    this[PersonalAccessTokenProjectsTable.tokenId] = id.toKotlinUuid()
                    this[PersonalAccessTokenProjectsTable.projectId] = projectId.toKotlinUuid()
                }
            }

            scopesFor(setOf(id))[id].orEmpty()
        }

        logger.info(
            "Created personal access token '{}' for user {}, scoped to {} project(s)",
            name,
            userId,
            if (scope.isEmpty()) "all" else scope.size.toString()
        )
        return PersonalAccessTokenCreated(
            id = id.toString(),
            name = name,
            token = rawToken,
            createdAt = now.toString(),
            expiresAt = expiresAt?.toString(),
            projects = scopeNames
        )
    }

    /**
     * Lists a user's own tokens. Never includes the raw value or its hash.
     */
    fun listTokens(userId: UUID): List<PersonalAccessTokenInfo> {
        return transaction {
            val rows = PersonalAccessTokensTable.selectAll()
                .where { PersonalAccessTokensTable.userId eq userId.toKotlinUuid() }
                .toList()
            // Resolved in one query for the whole page rather than per token,
            // so listing stays a constant number of round trips however many
            // tokens a user has accumulated.
            val scopes = scopesFor(rows.map { it[PersonalAccessTokensTable.id].toJavaUuid() }.toSet())
            rows.map { it.toInfo(scopes[it[PersonalAccessTokensTable.id].toJavaUuid()].orEmpty()) }
        }
    }

    /**
     * Resolves the project scope of each of [tokenIds] in a single query.
     *
     * @param tokenIds The tokens whose scopes are wanted
     * @return Scoped projects by token id; a token absent from the map, or
     *   mapped to an empty list, is unscoped
     */
    private fun scopesFor(tokenIds: Set<UUID>): Map<UUID, List<TokenProjectScope>> {
        if (tokenIds.isEmpty()) {
            return emptyMap()
        }
        val kotlinIds = tokenIds.map { it.toKotlinUuid() }
        return PersonalAccessTokenProjectsTable
            .join(ProjectsTable, JoinType.INNER, PersonalAccessTokenProjectsTable.projectId, ProjectsTable.id)
            .select(PersonalAccessTokenProjectsTable.tokenId, ProjectsTable.id, ProjectsTable.name)
            .where { PersonalAccessTokenProjectsTable.tokenId inList kotlinIds }
            .groupBy({ it[PersonalAccessTokenProjectsTable.tokenId].toJavaUuid() }) {
                TokenProjectScope(it[ProjectsTable.id].toJavaUuid().toString(), it[ProjectsTable.name])
            }
    }

    /**
     * Revokes a token, scoped to the caller's own tokens so one user cannot
     * revoke another's by guessing an id.
     *
     * @return true if a token was deleted, false if it did not exist or did not belong to [userId]
     */
    fun revokeToken(userId: UUID, tokenId: UUID): Boolean {
        return transaction {
            val deleted = PersonalAccessTokensTable.deleteWhere {
                (PersonalAccessTokensTable.id eq tokenId.toKotlinUuid()) and
                    (PersonalAccessTokensTable.userId eq userId.toKotlinUuid())
            }
            deleted > 0
        }
    }

    /**
     * Verifies a raw token value, the git-auth equivalent of
     * [UserService.verifyPassword]. A hash lookup rather than a bcrypt
     * comparison, so unlike password verification this is not a
     * meaningful CPU cost to rate-limit against.
     *
     * Deliberately does not record use here: a hash match only means the
     * credential is valid, not that the request it came with was actually
     * allowed to do anything, and a caller still has scope and permission
     * checks of its own to run after this returns. See [recordTokenUsed].
     *
     * @return The token's owning user, or null if the token is unknown,
     *   expired, or does not start with the recognized prefix at all
     */
    fun verifyToken(rawToken: String): VerifiedToken? {
        if (!rawToken.startsWith(TOKEN_PREFIX)) {
            return null
        }

        val hash = hashToken(rawToken)
        return transaction {
            val row = PersonalAccessTokensTable
                .join(UsersTable, JoinType.INNER, PersonalAccessTokensTable.userId, UsersTable.id)
                .select(
                    UsersTable.id,
                    UsersTable.username,
                    UsersTable.roles,
                    PersonalAccessTokensTable.id,
                    PersonalAccessTokensTable.expiresAt,
                    PersonalAccessTokensTable.scoped
                )
                .where { PersonalAccessTokensTable.tokenHash eq hash }
                .firstOrNull() ?: return@transaction null

            val expiresAt = row[PersonalAccessTokensTable.expiresAt]
            if (expiresAt != null && expiresAt.isBefore(Instant.now())) {
                return@transaction null
            }

            val tokenId = row[PersonalAccessTokensTable.id]
            val scope = PersonalAccessTokenProjectsTable
                .select(PersonalAccessTokenProjectsTable.projectId)
                .where { PersonalAccessTokenProjectsTable.tokenId eq tokenId }
                .map { it[PersonalAccessTokenProjectsTable.projectId].toJavaUuid() }
                .toSet()

            VerifiedToken(
                tokenId = tokenId.toJavaUuid(),
                user = User(
                    id = row[UsersTable.id].toJavaUuid().toString(),
                    username = row[UsersTable.username],
                    roles = parseRoles(row[UsersTable.roles]).toList()
                ),
                scoped = row[PersonalAccessTokensTable.scoped],
                scopedProjectIds = scope
            )
        }
    }

    /**
     * Records that a token was actually used to complete an authorized
     * request. Mirrors [SshKeyService.recordKeyUsed]: called only once every
     * check that could still reject the request - project scope, then
     * project permission - has passed, so the "last used" timestamp shown to
     * a token's owner (to help them spot a leaked one) reflects a request it
     * was actually allowed to make, not merely one whose hash matched.
     *
     * @param tokenId The token that authenticated and was then allowed through
     */
    fun recordTokenUsed(tokenId: UUID) {
        transaction {
            PersonalAccessTokensTable.update({ PersonalAccessTokensTable.id eq tokenId.toKotlinUuid() }) {
                it[lastUsedAt] = Instant.now()
            }
        }
    }

    private fun generateToken(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun hashToken(rawToken: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(rawToken.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun ResultRow.toInfo(projects: List<TokenProjectScope>): PersonalAccessTokenInfo {
        return PersonalAccessTokenInfo(
            id = this[PersonalAccessTokensTable.id].toJavaUuid().toString(),
            name = this[PersonalAccessTokensTable.name],
            tokenPrefix = this[PersonalAccessTokensTable.tokenPrefix],
            createdAt = this[PersonalAccessTokensTable.createdAt].toString(),
            lastUsedAt = this[PersonalAccessTokensTable.lastUsedAt]?.toString(),
            expiresAt = this[PersonalAccessTokensTable.expiresAt]?.toString(),
            scoped = this[PersonalAccessTokensTable.scoped],
            projects = projects
        )
    }

    private fun parseRoles(rawRoles: String): Set<String> {
        return rawRoles
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()
    }
}

/**
 * A personal access token that has been checked and found valid, together
 * with what it is allowed to reach.
 *
 * @property tokenId The token's own id, for [PersonalAccessTokenService.recordTokenUsed]
 *   once a caller has finished checking scope and permission
 * @property user The token's owner, who the caller is treated as
 * @property scoped Whether the token was created restricted to particular
 *   projects at all. False means it reaches every project the owner can,
 *   which is what a token deliberately created without a scope is.
 * @property scopedProjectIds The projects the token is restricted to, for a
 *   token that is [scoped]. This can be empty even so, once every project it
 *   named has been deleted, in which case the token reaches nothing.
 */
data class VerifiedToken(
    val tokenId: UUID,
    val user: User,
    val scoped: Boolean,
    val scopedProjectIds: Set<UUID>
) {
    /**
     * Whether this token may be used against [projectId]. Only ever a
     * restriction: the caller must still separately hold the project
     * permission the operation needs.
     *
     * Keyed on [scoped] rather than on whether [scopedProjectIds] is empty,
     * so deleting the last project a token was narrowed to leaves it
     * reaching nothing instead of quietly reaching everything.
     */
    fun allows(projectId: UUID): Boolean = !scoped || projectId in scopedProjectIds
}
