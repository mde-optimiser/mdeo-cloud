package com.mdeo.pluginservice.session

import com.auth0.jwk.JwkProvider
import com.auth0.jwk.JwkProviderBuilder
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.mdeo.common.model.PluginTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.TimeUnit

/**
 * The claims of a verified session token that the session endpoint authorizes against.
 *
 * @property projectId The project the token was issued for
 * @property executionId The execution the token is bound to
 * @property scopes The granted scopes; a session needs `plugin:session:connect`
 * @property target The one target the token opens; null when the claim is missing or no address
 * @property session The one session name the token opens
 */
data class SessionTokenClaims(
    val projectId: String?,
    val executionId: String?,
    val scopes: List<String>,
    val target: PluginTarget?,
    val session: String?
)

/**
 * Verifies the token a session is opened with.
 */
fun interface SessionTokenVerifier {
    /**
     * Verifies a raw bearer token.
     *
     * @param token The token
     * @return Its claims, or null when the token is malformed, expired or not issued by the backend
     */
    suspend fun verify(token: String): SessionTokenClaims?
}

/**
 * Verifies session tokens against the keys the backend publishes.
 *
 * Tokens are RS256-signed by the backend, which serves its public keys at
 * `<backendApiUrl>/.well-known/jwks.json`. Keys are cached, so a verification only reaches the
 * backend when it sees a key id for the first time.
 *
 * @param backendApiUrl Base URL of the backend API
 * @param issuer The issuer every accepted token must name
 */
class JwksSessionTokenVerifier(
    backendApiUrl: String,
    private val issuer: String
) : SessionTokenVerifier {

    private val jwkProvider: JwkProvider = JwkProviderBuilder(backendApiUrl.trimEnd('/'))
        .cached(10, 24, TimeUnit.HOURS)
        .rateLimited(10, 1, TimeUnit.MINUTES)
        .build()

    override suspend fun verify(token: String): SessionTokenClaims? {
        val decoded = try {
            // A key the cache does not hold yet is fetched with a blocking call.
            withContext(Dispatchers.IO) {
                val publicKey = jwkProvider.get(JWT.decode(token).keyId).publicKey as RSAPublicKey
                JWT.require(Algorithm.RSA256(publicKey, null))
                    .withIssuer(issuer)
                    .acceptLeeway(3)
                    .build()
                    .verify(token)
            }
        } catch (e: Exception) {
            return null
        }
        return SessionTokenClaims(
            projectId = decoded.getClaim("projectId")?.asString(),
            executionId = decoded.getClaim("executionId")?.asString(),
            scopes = decoded.getClaim("scope")?.asList(String::class.java) ?: emptyList(),
            target = decoded.getClaim("target")?.asString()?.let { PluginTarget.parseOrNull(it) },
            session = decoded.getClaim("session")?.asString()
        )
    }
}
