package com.mdeo.backend.routes

import com.mdeo.backend.plugins.*
import com.mdeo.backend.service.AuthRateLimiter
import com.mdeo.backend.service.UserService
import com.mdeo.backend.service.JwtService
import com.mdeo.common.model.*
import com.mdeo.common.model.UserRoles
import io.ktor.http.*
import io.ktor.server.plugins.origin
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import java.time.Instant

/**
 * Configures authentication routes.
 *
 * @param userService Service for user authentication and management
 * @param jwtService Service for JWT operations
 * @param authRateLimiter Throttles login attempts, shared with git's HTTP basic authentication
 */
fun Route.authRoutes(userService: UserService, jwtService: JwtService, authRateLimiter: AuthRateLimiter) {
    route("/api/auth") {
        /**
         * Authenticates a user and creates a session.
         *
         * @param body LoginRequest containing username and password
         * @return LoginResponse with user information on success, 401 Unauthorized on failure,
         *   429 Too Many Requests when rate limited
         */
        post("/login") {
            val request = call.receive<LoginRequest>()

            if (!authRateLimiter.tryAcquire(request.username, call.request.origin.remoteHost)) {
                call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "Too many attempts, try again later"))
                return@post
            }

            val user = userService.verifyPassword(request.username, request.password)
            if (user == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid credentials"))
                return@post
            }
            
            call.sessions.set(UserSession(
                userId = user.id,
                username = user.username,
                createdAt = Instant.now().epochSecond
            ))

            val isAdmin = user.roles.contains(UserRoles.ADMIN)
            call.respond(LoginResponse(UserInfo(
                id = user.id,
                username = user.username,
                isAdmin = isAdmin,
                canCreateProject = isAdmin || user.roles.contains(UserRoles.CREATE_PROJECT)
            )))
        }

        /**
         * Registers a new user account and creates a session.
         */
        post("/register") {
            val request = call.receive<RegisterRequest>()

            if (request.username.isBlank() || request.password.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Username and password are required"))
                return@post
            }

            val user = userService.createUser(request.username, request.password)
            if (user == null) {
                call.respond(HttpStatusCode.Conflict, mapOf("error" to "Username already exists"))
                return@post
            }

            call.sessions.set(UserSession(
                userId = user.id,
                username = user.username,
                createdAt = Instant.now().epochSecond
            ))

            val isAdmin = user.roles.contains(UserRoles.ADMIN)
            call.respond(LoginResponse(UserInfo(
                id = user.id,
                username = user.username,
                isAdmin = isAdmin,
                canCreateProject = isAdmin || user.roles.contains(UserRoles.CREATE_PROJECT)
            )))
        }
        
        /**
         * Logs out the current user by clearing their session.
         *
         * @return 200 OK with success message
         */
        post("/logout") {
            call.sessions.clear<UserSession>()
            call.respond(HttpStatusCode.OK, mapOf("message" to "Logged out"))
        }
        
        /**
         * Gets the current authenticated user's information.
         *
         * @return ApiResult.Success with UserInfo if authenticated, 401 Unauthorized otherwise
         */
        get("/me") {
            val session = call.sessions.get<UserSession>()
            if (session == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Not authenticated"))
                return@get
            }

            val userId = try {
                java.util.UUID.fromString(session.userId)
            } catch (_: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid user ID"))
                return@get
            }

            val user = userService.findById(userId)
            if (user == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "User not found"))
                return@get
            }

            val isAdmin = user.roles.contains(UserRoles.ADMIN)
            call.respond(UserInfo(
                id = session.userId,
                username = session.username,
                isAdmin = isAdmin,
                canCreateProject = isAdmin || user.roles.contains(UserRoles.CREATE_PROJECT)
            ))
        }

    }
    
    /**
     * Standard JWKS (JSON Web Key Set) endpoint for JWT verification.
     * Located at /api/.well-known/jwks.json as per RFC 8414 (OAuth 2.0 Authorization Server Metadata)
     * and OpenID Connect Discovery specifications.
     * Follows the JWKS format specification (RFC 7517).
     *
     * @return JWKS containing the RSA public key
     */
    get("/api/.well-known/jwks.json") {
        val jwks = jwtService.getJwks()
        call.respond(jwks)
    }
}