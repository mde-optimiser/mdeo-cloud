package com.mdeo.backend.routes

import com.mdeo.backend.plugins.*
import com.mdeo.backend.service.AddSshKeyResult
import com.mdeo.backend.service.SshKeyService
import com.mdeo.common.model.AddSshPublicKeyRequest
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.util.UUID

/**
 * Self-service management of a user's own registered SSH public keys,
 * used to authenticate git-over-SSH. Mounted behind session authentication,
 * like [patRoutes].
 *
 * @param sshKeyService Registers, lists, and removes keys
 */
fun Route.sshKeyRoutes(sshKeyService: SshKeyService) {
    route("/api/ssh-keys") {
        /**
         * Registers a new SSH public key for the current user.
         */
        post {
            val session = call.getUserSession()
            if (session == null) {
                call.respond(HttpStatusCode.Unauthorized)
                return@post
            }

            val userId = try {
                UUID.fromString(session.userId)
            } catch (_: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid user ID"))
                return@post
            }

            val request = call.receive<AddSshPublicKeyRequest>()
            if (request.name.isBlank() || request.publicKey.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Name and public key are required"))
                return@post
            }

            when (val result = sshKeyService.addKey(userId, request.name, request.publicKey)) {
                is AddSshKeyResult.Success -> call.respond(result.info)
                is AddSshKeyResult.Failure -> call.respond(HttpStatusCode.BadRequest, mapOf("error" to result.message))
            }
        }

        /**
         * Lists the current user's own registered keys.
         */
        get {
            val session = call.getUserSession()
            if (session == null) {
                call.respond(HttpStatusCode.Unauthorized)
                return@get
            }

            val userId = try {
                UUID.fromString(session.userId)
            } catch (_: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid user ID"))
                return@get
            }

            call.respond(sshKeyService.listKeys(userId))
        }

        /**
         * Removes one of the current user's own keys.
         */
        delete("/{keyId}") {
            val session = call.getUserSession()
            if (session == null) {
                call.respond(HttpStatusCode.Unauthorized)
                return@delete
            }

            val userId = try {
                UUID.fromString(session.userId)
            } catch (_: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid user ID"))
                return@delete
            }

            val keyId = call.parameters["keyId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            if (keyId == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid key ID"))
                return@delete
            }

            val removed = sshKeyService.removeKey(userId, keyId)
            if (!removed) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Key not found"))
                return@delete
            }

            call.respond(HttpStatusCode.OK, mapOf("message" to "Key removed"))
        }
    }
}
