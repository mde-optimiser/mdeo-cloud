package com.mdeo.backend.service

import com.mdeo.backend.database.SshPublicKeysTable
import com.mdeo.backend.database.UsersTable
import com.mdeo.common.model.SshPublicKeyInfo
import com.mdeo.common.model.User
import org.apache.sshd.common.config.keys.AuthorizedKeyEntry
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.config.keys.PublicKeyEntryResolver
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import java.security.PublicKey
import java.time.Instant
import java.util.UUID
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid

/**
 * The result of registering a new SSH public key.
 */
sealed class AddSshKeyResult {
    data class Success(val info: SshPublicKeyInfo) : AddSshKeyResult()
    data class Failure(val message: String) : AddSshKeyResult()
}

/**
 * Manages SSH public keys registered for git-over-SSH authentication.
 *
 * @param services The injected services providing access to configuration and other services
 */
class SshKeyService(services: InjectedServices) : BaseService(), InjectedServices by services {
    private val logger = LoggerFactory.getLogger(SshKeyService::class.java)

    /**
     * Parses and registers a new key for a user.
     *
     * @param userId The key's owner
     * @param name A label the user chooses to tell keys apart later
     * @param publicKeyLine The full authorized_keys-format line
     * @return The registered key's metadata, or a failure describing why
     *   the line could not be parsed or the key is already registered
     */
    fun addKey(userId: UUID, name: String, publicKeyLine: String): AddSshKeyResult {
        val key = try {
            val entry = AuthorizedKeyEntry.parseAuthorizedKeyEntry(publicKeyLine.trim())
            entry.resolvePublicKey(null, PublicKeyEntryResolver.FAILING)
        } catch (e: Exception) {
            return AddSshKeyResult.Failure("Not a valid SSH public key: ${e.message}")
        }

        val fingerprint = KeyUtils.getFingerPrint(key)
        val id = UUID.randomUUID()
        val now = Instant.now()

        val inserted = transaction {
            SshPublicKeysTable.insertIgnore {
                it[SshPublicKeysTable.id] = id.toKotlinUuid()
                it[SshPublicKeysTable.userId] = userId.toKotlinUuid()
                it[SshPublicKeysTable.name] = name
                it[SshPublicKeysTable.publicKey] = publicKeyLine.trim()
                it[SshPublicKeysTable.fingerprint] = fingerprint
                it[createdAt] = now
            }
        }

        if (inserted.insertedCount == 0) {
            return AddSshKeyResult.Failure("This key is already registered")
        }

        logger.info("Registered SSH key '{}' for user {}", name, userId)
        return AddSshKeyResult.Success(
            SshPublicKeyInfo(
                id = id.toString(),
                name = name,
                fingerprint = fingerprint,
                createdAt = now.toString()
            )
        )
    }

    /**
     * Lists a user's own registered keys.
     */
    fun listKeys(userId: UUID): List<SshPublicKeyInfo> {
        return transaction {
            SshPublicKeysTable.selectAll()
                .where { SshPublicKeysTable.userId eq userId.toKotlinUuid() }
                .map { it.toInfo() }
        }
    }

    /**
     * Removes one of a user's own keys, scoped so one user cannot remove
     * another's by guessing an id.
     *
     * @return true if a key was deleted, false if it did not exist or did not belong to [userId]
     */
    fun removeKey(userId: UUID, keyId: UUID): Boolean {
        return transaction {
            val deleted = SshPublicKeysTable.deleteWhere {
                (SshPublicKeysTable.id eq keyId.toKotlinUuid()) and (SshPublicKeysTable.userId eq userId.toKotlinUuid())
            }
            deleted > 0
        }
    }

    /**
     * Resolves the user a key belongs to, by fingerprint. Used by the SSH
     * server's [org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator]
     * to decide whether an offered key is recognized.
     */
    fun findUserByPublicKey(key: PublicKey): ResolvedSshKey? {
        val fingerprint = KeyUtils.getFingerPrint(key)
        return transaction {
            val row = SshPublicKeysTable
                .join(UsersTable, JoinType.INNER, SshPublicKeysTable.userId, UsersTable.id)
                .select(UsersTable.id, UsersTable.username, UsersTable.roles, SshPublicKeysTable.id)
                .where { SshPublicKeysTable.fingerprint eq fingerprint }
                .firstOrNull() ?: return@transaction null

            ResolvedSshKey(
                keyId = row[SshPublicKeysTable.id].toJavaUuid(),
                user = User(
                    id = row[UsersTable.id].toJavaUuid().toString(),
                    username = row[UsersTable.username],
                    roles = parseRoles(row[UsersTable.roles]).toList()
                )
            )
        }
    }

    /**
     * Records that a key was actually used, which is deliberately *not*
     * done when the key is merely resolved.
     *
     * MINA calls its [org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator]
     * for the client's unsigned "would you accept this key?" probe as well
     * as for the signed attempt that follows, and the probe carries no
     * proof the caller holds the private half. Since a public key is, by
     * definition, public, bumping the timestamp there would let anyone
     * holding someone else's public key forge their "last used" - and
     * would mark a key used on attempts that went on to fail. Recording it
     * once the session has reached the point of running a command means
     * the signature has necessarily been verified first.
     *
     * @param keyId The key that authenticated the session
     */
    fun recordKeyUsed(keyId: UUID) {
        transaction {
            SshPublicKeysTable.update({ SshPublicKeysTable.id eq keyId.toKotlinUuid() }) {
                it[lastUsedAt] = Instant.now()
            }
        }
    }

    private fun ResultRow.toInfo(): SshPublicKeyInfo {
        return SshPublicKeyInfo(
            id = this[SshPublicKeysTable.id].toJavaUuid().toString(),
            name = this[SshPublicKeysTable.name],
            fingerprint = this[SshPublicKeysTable.fingerprint],
            createdAt = this[SshPublicKeysTable.createdAt].toString(),
            lastUsedAt = this[SshPublicKeysTable.lastUsedAt]?.toString()
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
 * A registered SSH key matched to an offered public key, before any
 * signature over it has been verified.
 *
 * @property keyId The stored key's identifier, used to record genuine use
 *   once authentication has actually completed
 * @property user The key's owner
 */
data class ResolvedSshKey(
    val keyId: UUID,
    val user: User
)
