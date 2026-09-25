package com.mdeo.backend.git.ssh

import com.mdeo.backend.git.GitRepositoryService
import com.mdeo.backend.git.parseProjectIdFromGitPath
import com.mdeo.backend.service.ProjectPermission
import com.mdeo.backend.service.ProjectService
import com.mdeo.backend.service.SshKeyService
import com.mdeo.backend.service.WebSocketNotificationService
import com.mdeo.common.model.UserRoles
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.command.CommandFactory
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Resolves an SSH `exec` request into a git pack command, mirroring what
 * [com.mdeo.backend.routes.gitRoutes]'s HTTP routes do for the same two
 * operations: parse the requested repository and operation, check the
 * caller has the matching permission, then hand off to JGit.
 *
 * @param gitRepositoryService Opens and publishes project repositories
 * @param projectService Used to check the caller may access the project
 * @param sshKeyService Used to record that the authenticating key was genuinely used
 * @param webSocketNotificationService Notifies open workbench tabs after a push changes files
 * @param maxPushPackSizeBytes Largest pack a push may send; see [com.mdeo.backend.config.GitConfig]
 */
class GitSshCommandFactory(
    private val gitRepositoryService: GitRepositoryService,
    private val projectService: ProjectService,
    private val sshKeyService: SshKeyService,
    private val webSocketNotificationService: WebSocketNotificationService,
    private val maxPushPackSizeBytes: Long
) : CommandFactory {
    private val logger = LoggerFactory.getLogger(GitSshCommandFactory::class.java)

    override fun createCommand(channel: ChannelSession, command: String): Command {
        val args = CommandFactory.split(command)
        val operation = args.getOrNull(0)
        val path = args.getOrNull(1)

        if (args.size != 2 || (operation != "git-upload-pack" && operation != "git-receive-pack")) {
            logger.info("Rejecting unsupported SSH command: {}", command)
            return RejectingCommand("unsupported command")
        }

        val projectId = parseProjectIdFromGitPath(path!!)
        val user = channel.getSession().getAttribute(AUTHENTICATED_USER_KEY)
        val isGlobalAdmin = user?.roles?.contains(UserRoles.ADMIN) == true
        val userId = user?.let { runCatching { UUID.fromString(it.id) }.getOrNull() }
        val permission = if (operation == "git-receive-pack") ProjectPermission.WRITE else ProjectPermission.READ

        // Deliberately the same rejection for a nonexistent project and one
        // the caller may not access, so this cannot be used to discover
        // which project ids exist - see GitRoutes.authorizeGit for the
        // same reasoning on the HTTP side.
        if (projectId == null || userId == null ||
            !projectService.hasProjectPermission(projectId, userId, isGlobalAdmin, permission)
        ) {
            return RejectingCommand("unknown repository")
        }

        // Reaching here means MINA completed public key authentication, so
        // the client has proven it holds the private half - the first point
        // at which recording the key as used is actually truthful.
        channel.getSession().getAttribute(AUTHENTICATED_KEY_ID)?.let { sshKeyService.recordKeyUsed(it) }

        val isProjectAdmin = projectService.hasProjectPermission(projectId, userId, isGlobalAdmin, ProjectPermission.ADMIN)
        return SshGitPackCommand(
            operation = operation,
            projectId = projectId,
            isProjectAdmin = isProjectAdmin,
            gitRepositoryService = gitRepositoryService,
            webSocketNotificationService = webSocketNotificationService,
            maxPushPackSizeBytes = maxPushPackSizeBytes
        )
    }
}

/**
 * A command that does nothing but report a failure, for a request
 * [GitSshCommandFactory] could not authorize or did not recognize.
 */
private class RejectingCommand(private val message: String) : Command {
    private lateinit var error: OutputStream
    private lateinit var exitCallback: ExitCallback

    override fun setInputStream(input: InputStream) {}
    override fun setOutputStream(output: OutputStream) {}
    override fun setErrorStream(error: OutputStream) {
        this.error = error
    }

    override fun setExitCallback(callback: ExitCallback) {
        this.exitCallback = callback
    }

    override fun start(channel: ChannelSession, env: Environment) {
        error.write("$message\n".toByteArray())
        error.flush()
        exitCallback.onExit(1, message)
    }

    override fun destroy(channel: ChannelSession) {}
}
