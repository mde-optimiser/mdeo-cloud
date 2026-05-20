package com.mdeo.backend.plugins

import com.auth0.jwt.interfaces.Payload
import com.mdeo.backend.config.SessionConfig
import com.mdeo.backend.service.JwtService
import com.mdeo.backend.service.UserService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.*
import io.ktor.server.sessions.*
import java.time.Instant
import java.util.*

const val AUTH_SESSION = "auth-session"
const val AUTH_JWT = "auth-jwt"

/**
 * Principal for JWT authentication containing project access information.
 */
data class JwtPrincipal(
    val projectId: String,
    val scopes: List<String>,
    val payload: Payload
)

/**
 * Configure session-based authentication using Ktor's built-in session authentication.
 *
 * Implements a sliding-window session: on every authenticated request the response
 * carries a refreshed `Set-Cookie` so the idle timeout is extended from the current
 * time, while [SessionConfig.maxAbsoluteSeconds] caps the total session lifetime
 * measured from [UserSession.createdAt].
 *
 * Old cookies that pre-date the [UserSession.createdAt] field (`createdAt == 0`) are
 * treated as expired so that existing sessions are invalidated cleanly after a deploy.
 *
 * @param jwtService Service for JWT operations.
 * @param userService Service for user operations.
 * @param sessionConfig Session lifetime / cookie settings.
 */
fun Application.configureAuthentication(jwtService: JwtService, userService: UserService, sessionConfig: SessionConfig) {
    install(Authentication) {
        session<UserSession>(AUTH_SESSION) {
            validate { session ->
                val now = Instant.now().epochSecond
                val age = now - session.createdAt
                if (session.createdAt == 0L || age >= sessionConfig.maxAbsoluteSeconds) {
                    sessions.clear<UserSession>()
                    null
                } else {
                    sessions.set(session)
                    session
                }
            }
            challenge {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Not authenticated"))
            }
        }
        
        jwt(AUTH_JWT) {
            verifier(jwtService.getVerifier())
            validate { credential ->
                val projectId = credential.payload.getClaim(JwtService.CLAIM_PROJECT_ID)?.asString()
                
                if (projectId != null) {
                    val scopes = credential.payload.getClaim(JwtService.CLAIM_SCOPE)
                        ?.asList(String::class.java) ?: emptyList()
                    JwtPrincipal(projectId, scopes, credential.payload)
                } else {
                    null
                }
            }
            challenge { _, _ ->
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid or expired token"))
            }
        }
    }
    this.registerUserService(userService)
}

/**
 * Extension function to retrieve the current user session from the application call.
 *
 * @return The current UserSession if authenticated, null otherwise
 */
fun ApplicationCall.getUserSession(): UserSession? {
    return principal<UserSession>()
}

/**
 * Extension function to retrieve the JWT principal from the application call.
 *
 * @return The JwtPrincipal if authenticated via JWT, null otherwise
 */
fun ApplicationCall.getJwtPrincipal(): JwtPrincipal? {
    return principal<JwtPrincipal>()
}

/**
 * Extension function to check if the current user has administrator privileges.
 *
 * Permissions are loaded lazily from the database and cached for the duration of the request.
 * See [getUserPermissions] for the full resolution strategy.
 *
 * @return `true` if the authenticated session user holds the admin role, `false` otherwise.
 */
suspend fun ApplicationCall.isAdmin(): Boolean {
    return getUserPermissions().isAdmin
}

/**
 * Extension function to check if the current user can create projects globally.
 *
 * Permissions are loaded lazily from the database and cached for the duration of the request.
 * See [getUserPermissions] for the full resolution strategy.
 *
 * @return `true` if the user holds the admin or create-project role, `false` otherwise.
 */
suspend fun ApplicationCall.canCreateProject(): Boolean {
    return getUserPermissions().canCreateProject
}
