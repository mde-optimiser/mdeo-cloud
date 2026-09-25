package com.mdeo.common.model

import kotlinx.serialization.Serializable

/**
 * How git can be reached for this deployment, for building clone URLs the
 * workbench shows.
 *
 * @property sshPort The port the SSH server listens on
 * @property sshHost The host to use in an SSH clone URL, or null when it is the same host the
 *   workbench is served from
 * @property sshEnabled Whether SSH is reachable by clients at all; false in a deployment that
 *   keeps the port internal, where showing a URL nobody can use would only mislead
 * @property oauthAuthorizePath Where the git authorization screen lives
 * @property oauthTokenPath Where credential helpers exchange a code for a token
 */
@Serializable
data class GitAccessConfig(
    val sshPort: Int,
    val sshHost: String? = null,
    val sshEnabled: Boolean = true,
    val oauthAuthorizePath: String,
    val oauthTokenPath: String
)
