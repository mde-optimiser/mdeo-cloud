package com.mdeo.common.model

import kotlinx.serialization.Serializable

/**
 * Request payload to register a new SSH public key for git-over-SSH access.
 *
 * @property name A label the user chooses to tell keys apart later
 * @property publicKey The full authorized_keys-format line, e.g. "ssh-ed25519 AAAA... comment"
 */
@Serializable
data class AddSshPublicKeyRequest(
    val name: String,
    val publicKey: String
)

/**
 * A registered SSH public key's metadata. The key material itself is not a
 * secret (only its matching private key, which never leaves the client,
 * is), but is not returned here either - the fingerprint is enough to tell
 * keys apart, matching how GitHub's own key list displays them.
 *
 * @property id Unique identifier for the key
 * @property name The label the user gave it
 * @property fingerprint SHA256 fingerprint, in the same form `ssh-keygen -lf` prints
 * @property createdAt When the key was registered (ISO 8601 timestamp)
 * @property lastUsedAt When the key was last used to authenticate, or null if never (ISO 8601 timestamp)
 */
@Serializable
data class SshPublicKeyInfo(
    val id: String,
    val name: String,
    val fingerprint: String,
    val createdAt: String,
    val lastUsedAt: String? = null
)
