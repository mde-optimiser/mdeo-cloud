package com.mdeo.execution.common.auth

import com.mdeo.common.transport.respondError
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*

/**
 * Result of an authorization check.
 * Sealed class allowing for type-safe handling of authorization outcomes.
 */
sealed class AuthorizationResult {
    /**
     * Authorization succeeded with the given principal data.
     */
    data class Authorized(val principal: JwtPrincipalData) : AuthorizationResult()

    /**
     * Authorization failed with the given error details.
     */
    data class Denied(val status: HttpStatusCode, val message: String) : AuthorizationResult()
}

/**
 * Validates that the request has a valid JWT principal.
 *
 * @return AuthorizationResult indicating success or failure
 */
fun ApplicationCall.requireAuthentication(): AuthorizationResult {
    val principal = getJwtPrincipalData()
    return if (principal == null) {
        AuthorizationResult.Denied(HttpStatusCode.Unauthorized, "Authentication required")
    } else {
        AuthorizationResult.Authorized(principal)
    }
}

/**
 * Validates that the request has a valid JWT principal with the specified scope.
 *
 * @param scope The required scope
 * @return AuthorizationResult indicating success or failure
 */
fun ApplicationCall.requireScope(scope: String): AuthorizationResult {
    return when (val authResult = requireAuthentication()) {
        is AuthorizationResult.Denied -> authResult
        is AuthorizationResult.Authorized -> {
            if (authResult.principal.hasScope(scope)) {
                authResult
            } else {
                AuthorizationResult.Denied(HttpStatusCode.Forbidden, "Insufficient permissions")
            }
        }
    }
}

/**
 * Validates that the execution ID in the path matches the one in the token.
 *
 * @param pathExecutionId The execution ID from the request path
 * @param principal The JWT principal data
 * @return AuthorizationResult indicating success or failure
 */
fun validateExecutionIdMatch(
    pathExecutionId: String,
    principal: JwtPrincipalData
): AuthorizationResult {
    if (principal.executionId != null && principal.executionId != pathExecutionId) {
        return AuthorizationResult.Denied(HttpStatusCode.Forbidden, "Token execution mismatch")
    }
    return AuthorizationResult.Authorized(principal)
}

/**
 * Validates that the project ID matches the one in the token.
 *
 * @param projectId The project ID from the request
 * @param principal The JWT principal data
 * @return AuthorizationResult indicating success or failure
 */
fun validateProjectIdMatch(
    projectId: String,
    principal: JwtPrincipalData
): AuthorizationResult {
    if (principal.projectId != projectId) {
        return AuthorizationResult.Denied(HttpStatusCode.Forbidden, "Token project mismatch")
    }
    return AuthorizationResult.Authorized(principal)
}

/**
 * Responds with an authorization error.
 *
 * @param result The denied authorization result
 */
suspend fun ApplicationCall.respondAuthError(result: AuthorizationResult.Denied) {
    respondError(result.status, result.message)
}
