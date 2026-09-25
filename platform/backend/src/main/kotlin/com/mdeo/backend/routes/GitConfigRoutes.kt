package com.mdeo.backend.routes

import com.mdeo.common.model.GitAccessConfig
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * Tells the workbench how git can actually be reached, so the project panel
 * can show a working SSH clone URL instead of guessing at a port the
 * deployment may have moved or never exposed.
 *
 * @param sshPort The port the SSH server listens on
 * @param sshPublicHost The host to use in clone URLs, or null for the workbench's own
 * @param sshPubliclyReachable Whether to offer an SSH URL at all
 * @param oauthAuthorizePath The browser-facing authorization screen's path
 * @param oauthTokenPath The token endpoint credential helpers are pointed at
 */
fun Route.gitConfigRoutes(
    sshPort: Int,
    sshPublicHost: String?,
    sshPubliclyReachable: Boolean,
    oauthAuthorizePath: String,
    oauthTokenPath: String
) {
    get("/api/git/config") {
        call.respond(
            GitAccessConfig(
                sshPort = sshPort,
                sshHost = sshPublicHost,
                sshEnabled = sshPubliclyReachable,
                oauthAuthorizePath = oauthAuthorizePath,
                oauthTokenPath = oauthTokenPath
            )
        )
    }
}
