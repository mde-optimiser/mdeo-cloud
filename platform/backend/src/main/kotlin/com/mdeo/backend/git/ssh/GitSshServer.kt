package com.mdeo.backend.git.ssh

import org.apache.sshd.common.NamedResource
import org.apache.sshd.common.config.keys.FilePasswordProvider
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.common.util.security.SecurityUtils
import org.apache.sshd.server.SshServer
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.slf4j.LoggerFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Security

/**
 * Owns the git-over-SSH listener's lifecycle: a TCP port serving the same
 * two git operations [com.mdeo.backend.routes.gitRoutes] serves over HTTP,
 * authenticated by public key instead of HTTP basic credentials.
 *
 * The per-project lock [com.mdeo.backend.git.GitRepositoryService] uses is
 * in-process only, so this server has to run in the same JVM as the Ktor
 * one (never a separate deployment) to keep serializing SSH pushes/fetches
 * against concurrent HTTP ones on the same project - see that lock's own
 * doc comment for the full reasoning.
 *
 * @param port The port to listen on
 * @param hostKeyPem An OpenSSH-format private key to use as the server's
 *   host key (the literal contents of an `ssh-keygen` output file), or
 *   null to generate an ephemeral one
 * @param publickeyAuthenticator Resolves an offered key to its owning user
 * @param commandFactory Resolves an exec request into a git pack command
 */
class GitSshServer(
    port: Int,
    hostKeyPem: String?,
    publickeyAuthenticator: GitSshPublickeyAuthenticator,
    commandFactory: GitSshCommandFactory
) {
    private val logger = LoggerFactory.getLogger(GitSshServer::class.java)
    private val port = port

    private val server: SshServer = SshServer.setUpDefaultServer().apply {
        setPort(port)
        setKeyPairProvider(KeyPairProvider.wrap(loadOrGenerateHostKey(hostKeyPem)))
        setPublickeyAuthenticator(publickeyAuthenticator)
        setCommandFactory(commandFactory)
    }

    /**
     * Binds the listening socket. Non-blocking: MINA SSHD accepts
     * connections on its own I/O thread, so this returns immediately and
     * does not need to run off the caller's own thread.
     */
    fun start() {
        server.start()
        logger.info("git-over-SSH server listening on port {}", port)
    }

    fun stop() {
        server.stop()
    }

    private fun loadOrGenerateHostKey(hostKeyPem: String?): KeyPair {
        if (hostKeyPem != null) {
            val parser = SecurityUtils.getKeyPairResourceParser()
            val keyPairs = parser.loadKeyPairs(null, NamedResource.ofName("ssh-host-key"), FilePasswordProvider.EMPTY, hostKeyPem)
            return keyPairs.first()
        }

        logger.warn("No SSH host key provided in configuration, generating an ephemeral one")
        logger.warn("Clients will see a new host key on every restart; set GIT_SSH_HOST_KEY for a stable one")
        // Explicitly the BC provider, not just "Ed25519" (which would
        // resolve to whichever provider the JVM happens to check first,
        // often the JDK's own native one): MINA SSHD's EdDSA support only
        // recognizes key objects a registered EdDSA-capable provider
        // produced, and silently fails signature operations on a
        // native-JDK-generated key that otherwise looks identical.
        Security.addProvider(BouncyCastleProvider())
        return KeyPairGenerator.getInstance("Ed25519", "BC").generateKeyPair()
    }
}
