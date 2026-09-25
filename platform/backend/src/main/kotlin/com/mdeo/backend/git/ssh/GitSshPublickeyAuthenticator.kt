package com.mdeo.backend.git.ssh

import com.mdeo.backend.service.SshKeyService
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator
import org.apache.sshd.server.session.ServerSession
import org.slf4j.LoggerFactory
import java.security.PublicKey

/**
 * Authenticates an SSH client by public key, looked up against
 * [SshKeyService]'s registered keys. The username the client offers is not
 * checked against anything - as with `git@github.com`, the key is what
 * identifies the caller, not the SSH username - so the same resolved user
 * is stored for [GitSshCommandFactory] regardless of what username was sent.
 *
 * @param sshKeyService Resolves an offered key to its owning user
 */
class GitSshPublickeyAuthenticator(private val sshKeyService: SshKeyService) : PublickeyAuthenticator {
    private val logger = LoggerFactory.getLogger(GitSshPublickeyAuthenticator::class.java)

    override fun authenticate(username: String, key: PublicKey, session: ServerSession): Boolean {
        val resolved = sshKeyService.findUserByPublicKey(key) ?: return false
        session.setAttribute(AUTHENTICATED_USER_KEY, resolved.user)
        session.setAttribute(AUTHENTICATED_KEY_ID, resolved.keyId)
        // Deliberately not logged as "succeeded", and deliberately not
        // recorded as use: MINA also calls this for the client's unsigned
        // probe, which proves nothing about who is on the other end. See
        // [com.mdeo.backend.service.SshKeyService.recordKeyUsed].
        logger.debug("SSH public key recognized for user {}, pending signature check", resolved.user.username)
        return true
    }
}
