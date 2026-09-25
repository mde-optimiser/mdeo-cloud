package com.mdeo.backend.git.ssh

import com.mdeo.backend.git.GitRepositoryService
import com.mdeo.backend.git.PostgresDfsRepository
import com.mdeo.backend.service.WebSocketNotificationService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.eclipse.jgit.errors.UnpackException
import org.eclipse.jgit.transport.ReceiveCommand
import org.eclipse.jgit.transport.ReceivePack
import org.eclipse.jgit.transport.UploadPack
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Drives JGit's [UploadPack]/[ReceivePack] against an SSH exec channel's
 * streams, configured identically to [com.mdeo.backend.routes.gitRoutes]'s
 * HTTP routes for the same two operations - the same size limits, object
 * checking, pre-receive hook, and post-push side effects, just wired to a
 * different transport's streams instead of Ktor's.
 *
 * @param operation Either "git-upload-pack" or "git-receive-pack"
 * @param projectId The project this command was authorized against
 * @param isProjectAdmin Whether the caller may change the project's plugins over a push
 * @param gitRepositoryService Opens and publishes project repositories
 * @param webSocketNotificationService Notifies open workbench tabs after a push changes files
 * @param maxPushPackSizeBytes Largest pack a push may send; see [com.mdeo.backend.config.GitConfig]
 */
class SshGitPackCommand(
    private val operation: String,
    private val projectId: UUID,
    private val isProjectAdmin: Boolean,
    private val gitRepositoryService: GitRepositoryService,
    private val webSocketNotificationService: WebSocketNotificationService,
    private val maxPushPackSizeBytes: Long
) : Command {
    private val logger = LoggerFactory.getLogger(SshGitPackCommand::class.java)

    private lateinit var input: InputStream
    private lateinit var output: OutputStream
    private lateinit var error: OutputStream
    private lateinit var exitCallback: ExitCallback

    override fun setInputStream(input: InputStream) {
        this.input = input
    }

    override fun setOutputStream(output: OutputStream) {
        this.output = output
    }

    override fun setErrorStream(error: OutputStream) {
        this.error = error
    }

    override fun setExitCallback(callback: ExitCallback) {
        this.exitCallback = callback
    }

    // MINA SSHD's own contract for Command.start(): it must return promptly
    // and do the real work on another thread, or it stalls this session's
    // packet processing (and potentially other sessions sharing the same
    // I/O thread). The spawned thread's runBlocking is the bridge into
    // GitRepositoryService's suspend API, the same pattern already used
    // for one-off suspend calls at startup in Application.kt.
    override fun start(channel: ChannelSession, env: Environment) {
        Thread {
            try {
                runBlocking(Dispatchers.IO) {
                    gitRepositoryService.withProjectLock(projectId) {
                        val repository = gitRepositoryService.openRepository(projectId)
                        repository.use {
                            if (operation == "git-upload-pack") {
                                runUploadPack(repository)
                            } else {
                                runReceivePack(repository)
                            }
                        }
                    }
                }
                exitCallback.onExit(0)
            } catch (e: Exception) {
                logger.warn("git-over-ssh {} failed for project {}", operation, projectId, e)
                runCatching {
                    error.write("could not complete the request: an unexpected error occurred\n".toByteArray())
                    error.flush()
                }
                exitCallback.onExit(1, e.message ?: "internal error")
            }
        }.apply {
            isDaemon = true
            name = "ssh-git-$operation-$projectId"
        }.start()
    }

    private fun runUploadPack(repository: PostgresDfsRepository) {
        // Deliberately NOT setBiDirectionalPipe(false), unlike the HTTP
        // route: HTTP splits advertisement (GET /info/refs) from the
        // exchange (POST git-upload-pack), so the exchange half must skip
        // re-advertising. SSH's exec channel is one continuous stream with
        // no separate advertisement step, so upload() needs to send the
        // advertisement itself first - which is exactly what leaving this
        // at its default (true) makes it do.
        val uploadPack = UploadPack(repository)
        uploadPack.upload(input, output, error)
    }

    private suspend fun runReceivePack(repository: PostgresDfsRepository) {
        // Same reasoning as runUploadPack: left at its default so receive()
        // sends the advertisement itself, since SSH has no separate
        // advertisement step the way HTTP's GET /info/refs does.
        val receivePack = ReceivePack(repository)
        receivePack.setMaxPackSizeLimit(maxPushPackSizeBytes)
        receivePack.setMaxObjectSizeLimit(maxPushPackSizeBytes)
        receivePack.setCheckReceivedObjects(true)
        receivePack.isAllowNonFastForwards = false

        var anyRejected = false
        var anyAccepted = false
        receivePack.setPreReceiveHook { _, commands ->
            for (command in commands) {
                if (command.refName != gitRepositoryService.branch) {
                    command.setResult(
                        ReceiveCommand.Result.REJECTED_OTHER_REASON,
                        "only ${gitRepositoryService.branch} can be pushed, " +
                            "a project has one set of files rather than one per branch"
                    )
                    anyRejected = true
                    continue
                }
                if (command.type == ReceiveCommand.Type.DELETE) {
                    command.setResult(
                        ReceiveCommand.Result.REJECTED_OTHER_REASON,
                        "the project branch cannot be deleted"
                    )
                    anyRejected = true
                    continue
                }
                val failure = gitRepositoryService.applyCommitToProject(
                    repository,
                    projectId,
                    command.newId,
                    isProjectAdmin
                )
                if (failure != null) {
                    command.setResult(ReceiveCommand.Result.REJECTED_OTHER_REASON, failure)
                    anyRejected = true
                } else {
                    anyAccepted = true
                }
            }
        }

        try {
            receivePack.receive(input, output, error)
        } catch (e: UnpackException) {
            logger.info("Rejected push for project {}: {}", projectId, e.message)
            anyRejected = true
        }

        if (anyRejected) {
            gitRepositoryService.reclaimRejectedPushGarbage(repository, projectId)
        }
        if (anyAccepted) {
            webSocketNotificationService.broadcastFilesChanged(projectId)
        }
    }

    override fun destroy(channel: ChannelSession) {
        // Nothing to release directly - the channel owns the streams and
        // closes them on teardown, which is what unblocks the spawned
        // thread's blocking read if a client disconnects mid-transfer.
    }
}
