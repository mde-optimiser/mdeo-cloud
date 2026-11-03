package com.mdeo.execution.common.auth

import io.ktor.http.auth.HttpAuthHeader
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import org.slf4j.LoggerFactory

/**
 * Default authentication name for JWT-based authentication.
 */
const val AUTH_JWT = "auth-jwt"

private val logger = LoggerFactory.getLogger("JwtAuth")

/**
 * Configures JWT authentication for an execution service.
 *
 * Tokens are RS256, verified against the backend's keys from /.well-known/jwks.json as
 * [BackendKeys] keeps them: fetched once, and again when a token's signature fails with the
 * kept key, since the backend may have started signing with a new one.
 *
 * @param backendUrl The base URL of the backend service (e.g., "http://localhost:8080")
 * @param issuer The expected JWT issuer
 *
 * @see <a href="https://ktor.io/docs/server-jwt.html">Ktor JWT Authentication Guide</a>
 */
fun Application.configureJwtAuth(
    backendUrl: String,
    issuer: String
) {
    logger.info("Configuring JWT authentication with backend URL: $backendUrl")
    logger.info("JWKS endpoint: $backendUrl/.well-known/jwks.json")

    val keys = BackendKeys(backendUrl, issuer)

    install(Authentication) {
        jwt(AUTH_JWT) {
            verifier { header ->
                val token = (header as? HttpAuthHeader.Single)?.blob ?: return@verifier null
                try {
                    keys.verifierFor(token)
                } catch (e: Exception) {
                    logger.warn("Cannot verify token: ${e.message}")
                    null
                }
            }

            validate { credential ->
                validateJwtCredential(credential)
            }
        }
    }

    logger.info("JWT authentication configured successfully")
}

/**
 * Validates JWT credentials and creates a principal if valid.
 *
 * @param credential The JWT credential to validate
 * @return JWTPrincipal if valid, null otherwise
 */
private fun validateJwtCredential(credential: JWTCredential): JWTPrincipal? {
    return try {
        val projectId = credential.payload.getClaim("projectId")?.asString()
        val executionId = credential.payload.getClaim("executionId")?.asString()
        
        logger.info("JWT validated for projectId: $projectId, executionId: $executionId")
        JWTPrincipal(credential.payload)
    } catch (e: Exception) {
        logger.error("Token validation failed", e)
        null
    }
}
