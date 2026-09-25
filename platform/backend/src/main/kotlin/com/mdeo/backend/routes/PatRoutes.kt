package com.mdeo.backend.routes

import com.mdeo.backend.plugins.*
import com.mdeo.backend.service.PersonalAccessTokenService
import com.mdeo.common.model.CreatePersonalAccessTokenRequest
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * Self-service management of a user's own personal access tokens - an
 * alternative to the account password for git's HTTP basic authentication.
 * Mounted behind session authentication, like [userRoutes].
 *
 * @param personalAccessTokenService Creates, lists, and revokes tokens
 */
fun Route.patRoutes(personalAccessTokenService: PersonalAccessTokenService) {
    route("/api/tokens") {
        /**
         * Creates a new token for the current user. The raw token value is
         * only ever present in this one response.
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

            val request = call.receive<CreatePersonalAccessTokenRequest>()
            if (request.name.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Name is required"))
                return@post
            }

            val expiresAt = try {
                request.expiresAt?.let { Instant.parse(it) }
            } catch (_: DateTimeParseException) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid expiresAt"))
                return@post
            }

            val projectIds = try {
                request.projectIds.map { UUID.fromString(it) }
            } catch (_: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid project ID"))
                return@post
            }

            val created = personalAccessTokenService.createToken(userId, request.name, expiresAt, projectIds)
            if (created == null) {
                // The scope named a project the caller cannot read. Refused
                // rather than narrowed, so nobody ends up holding a token
                // that silently covers less than they asked for.
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Cannot scope a token to a project you cannot access"))
                return@post
            }
            call.respond(created)
        }

        /**
         * Lists the current user's own tokens. Never includes a raw value.
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

            call.respond(personalAccessTokenService.listTokens(userId))
        }

        /**
         * Revokes one of the current user's own tokens.
         */
        delete("/{tokenId}") {
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

            val tokenId = call.parameters["tokenId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            if (tokenId == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid token ID"))
                return@delete
            }

            val revoked = personalAccessTokenService.revokeToken(userId, tokenId)
            if (!revoked) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Token not found"))
                return@delete
            }

            call.respond(HttpStatusCode.OK, mapOf("message" to "Token revoked"))
        }
    }
}
