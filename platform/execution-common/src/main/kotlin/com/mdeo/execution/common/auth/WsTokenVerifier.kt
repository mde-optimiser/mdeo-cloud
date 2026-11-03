package com.mdeo.execution.common.auth

import com.auth0.jwt.interfaces.DecodedJWT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Verifies bearer tokens outside of Ktor's request authentication pipeline.
 *
 * The HTTP routes get their verification from `configureJwtAuth`, which only applies to a
 * call that carries an `Authorization` header. A WebSocket connection is authorized once at
 * the handshake and then carries many requests for potentially different work, so the
 * connection's own credentials say nothing about any individual request on it. This verifies
 * the token each request supplies for itself, against the same JWKS and issuer.
 *
 * @param backendUrl Base URL of the backend serving `/.well-known/jwks.json`
 * @param issuer Expected token issuer
 */
class WsTokenVerifier(
    backendUrl: String,
    issuer: String
) {
    private val keys = BackendKeys(backendUrl, issuer)

    /**
     * Verifies a token and extracts the claims execution services authorize against.
     *
     * @param token The raw bearer token
     * @return The token's principal data, or null if the token is missing or invalid
     */
    suspend fun verify(token: String?): JwtPrincipalData? {
        if (token.isNullOrBlank()) {
            return null
        }
        val decoded = try {
            // Fetching the backend's keys is a blocking network call.
            withContext(Dispatchers.IO) { verifyToken(token) }
        } catch (e: Exception) {
            return null
        }
        return JwtPrincipalData(
            projectId = decoded.getClaim("projectId")?.asString(),
            executionId = decoded.getClaim("executionId")?.asString(),
            scopes = decoded.getClaim("scope")?.asList(String::class.java) ?: emptyList()
        )
    }

    private fun verifyToken(token: String): DecodedJWT = keys.verifierFor(token).verify(token)
}
