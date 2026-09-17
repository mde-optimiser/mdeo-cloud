package com.mdeo.backend.service

import com.mdeo.common.auth.Scopes
import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.DecodedJWT
import com.mdeo.backend.config.JwtConfig
import com.mdeo.common.model.PluginTarget
import com.mdeo.common.model.PluginTargetKind
import org.slf4j.LoggerFactory
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.*

/**
 * Service for JWT token generation and verification using RSA key pairs.
 *
 * @param services The injected services providing access to configuration and other services
 */
class JwtService(services: InjectedServices) : BaseService(), InjectedServices by services {
    private val logger = LoggerFactory.getLogger(JwtService::class.java)
    
    /**
     * JWT configuration settings 
     */
    private val jwtConfig: JwtConfig get() = config.jwt
    
    private lateinit var privateKey: RSAPrivateKey
    private lateinit var publicKey: RSAPublicKey
    private lateinit var algorithm: Algorithm
    
    companion object {
        const val CLAIM_PROJECT_ID = "projectId"
        const val CLAIM_EXECUTION_ID = "executionId"
        const val CLAIM_COMPUTATION_ID = "computationId"
        const val CLAIM_SCOPE = "scope"

        /**
         * Claim naming the plugin target a session token was issued for, written as the same
         * `<kind>:<id>` address the caller used — `lang:script`, `contrib:script-functions`.
         */
        const val CLAIM_TARGET = "target"

        /**
         * Claim naming the session, the last segment of the address.
         */
        const val CLAIM_SESSION = "session"

        /**
         * Claim naming the piece of work a token is bound to. A bound token is only accepted while
         * that work is still in progress, which keeps a long-lived token from outliving its purpose.
         * Tokens without this claim are only bounded by their expiry.
         */
        const val CLAIM_BINDING = "binding"

        /**
         * Binding value for tokens that are accepted only while the execution named by
         * [CLAIM_EXECUTION_ID] exists and has not reached a terminal state.
         */
        const val BINDING_ACTIVE_EXECUTION = "active-execution"

        /**
         * Binding value for tokens that are accepted only while the file data computation named by
         * [CLAIM_COMPUTATION_ID] is still running.
         */
        const val BINDING_FILE_DATA_COMPUTATION = "file-data-computation"

        /**
         * Longest a session token is accepted. It is only needed to open a connection, and a plugin
         * service cannot tell that the execution it names has ended, so a short lifetime is what
         * keeps a leaked token from opening sessions long after the run. A reconnect fetches a new one.
         */
        const val MAX_SESSION_TOKEN_TTL_SECONDS = 300L
    }
    
    /**
     * Initialize the JWT service by loading keys from config or generating new ones.
     * Must be called before using other methods.
     */
    fun init() {
        logger.info("Initializing JWT service...")
        
        val configPrivateKey = jwtConfig.privateKey
        val configPublicKey = jwtConfig.publicKey
        if (configPrivateKey != null && configPublicKey != null) {
            logger.info("Loading RSA key pair from configuration")
            val privateKeyBytes = decodePemOrBase64(configPrivateKey)
            val publicKeyBytes = decodePemOrBase64(configPublicKey)
            
            val keyFactory = KeyFactory.getInstance("RSA")
            privateKey = keyFactory.generatePrivate(PKCS8EncodedKeySpec(privateKeyBytes)) as RSAPrivateKey
            publicKey = keyFactory.generatePublic(X509EncodedKeySpec(publicKeyBytes)) as RSAPublicKey
        } else {
            logger.warn("No RSA keys provided in configuration, generating new key pair")
            logger.warn("For production use, set JWT_PRIVATE_KEY and JWT_PUBLIC_KEY environment variables")
            val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
            keyPairGenerator.initialize(2048)
            val keyPair = keyPairGenerator.generateKeyPair()
            
            privateKey = keyPair.private as RSAPrivateKey
            publicKey = keyPair.public as RSAPublicKey
        }
        
        algorithm = Algorithm.RSA256(publicKey, privateKey)
        logger.info("JWT service initialized successfully")
    }
    
    /**
     * Generates the token a language plugin request is sent with, unless its caller delegates.
     *
     * The handler may read the project's files and file data, and may pass the request on to
     * another plugin through the backend.
     *
     * A request made while computing file data keeps naming that computation, and is bound to it
     * like the computation's own token. The name is what lets a nested request for data that is
     * already being computed further up compute it instead of waiting for itself.
     *
     * @param projectId The UUID of the project to grant access to
     * @param computationId The file data computation the request is made for, if any
     * @return The generated JWT token string
     */
    fun generatePluginRequestToken(projectId: UUID, computationId: UUID? = null): String {
        val now = Instant.now()
        val expiration = now.plusSeconds(jwtConfig.expirationSeconds)

        return JWT.create()
            .withIssuer(jwtConfig.issuer)
            .withIssuedAt(Date.from(now))
            .withExpiresAt(Date.from(expiration))
            .withClaim(CLAIM_PROJECT_ID, projectId.toString())
            .apply {
                if (computationId != null) {
                    withClaim(CLAIM_COMPUTATION_ID, computationId.toString())
                    withClaim(CLAIM_BINDING, BINDING_FILE_DATA_COMPUTATION)
                }
            }
            .withArrayClaim(CLAIM_SCOPE, arrayOf(Scopes.FILES_READ, Scopes.FILE_DATA_READ, Scopes.PLUGIN_REQUEST_SEND))
            .sign(algorithm)
    }
    
    /**
     * Generates the token an execution is started with and its execution node keeps for the run.
     *
     * The plugin service and the execution service it forwards to accept it for starting the
     * execution. The node then reads the project data it needs, opens sessions, and reports progress
     * and the final state with it. It is the only token that may report execution state, and it is
     * bound to the execution so that a lifetime long enough to cover the run does not keep granting
     * access once the run is over.
     *
     * @param projectId The UUID of the project
     * @param executionId The UUID of the execution
     * @param ttlSeconds Token lifetime in seconds. Must outlive the longest expected run, otherwise
     *   the node cannot report its terminal state.
     * @return The generated JWT token string
     */
    fun generateExecutionRunToken(projectId: UUID, executionId: UUID, ttlSeconds: Long): String {
        val now = Instant.now()
        val expiration = now.plusSeconds(ttlSeconds)

        return JWT.create()
            .withIssuer(jwtConfig.issuer)
            .withIssuedAt(Date.from(now))
            .withExpiresAt(Date.from(expiration))
            .withClaim(CLAIM_PROJECT_ID, projectId.toString())
            .withClaim(CLAIM_EXECUTION_ID, executionId.toString())
            .withClaim(CLAIM_BINDING, BINDING_ACTIVE_EXECUTION)
            .withArrayClaim(CLAIM_SCOPE, arrayOf(
                Scopes.PLUGIN_EXECUTION_START,
                Scopes.FILES_READ,
                Scopes.FILE_DATA_READ,
                Scopes.PLUGIN_REQUEST_SEND,
                Scopes.SESSION_OPEN,
                Scopes.EXECUTION_WRITE
            ))
            .sign(algorithm)
    }

    /**
     * Generates a token authorising a single call this backend makes out to a plugin, such as
     * fetching an execution's files, its summary, or cancelling and deleting it.
     *
     * The plugin serves these calls by reading back what belongs to the execution, and a plugin that
     * only routes them onwards - the config plugin forwards to whichever contribution plugin owns
     * the executable section - has to reach this backend to do so. The token therefore carries the
     * same scopes as a plugin request token, plus the single `plugin:execution:*` scope the call
     * itself requires.
     *
     * These calls legitimately target executions that have already finished, so the token cannot be
     * bound to an active execution. It is instead kept to the general (request-scoped) lifetime and
     * to read access, unlike the long-lived token an execution node holds; see
     * [generateExecutionRunToken].
     *
     * @param projectId The UUID of the project
     * @param executionId The UUID of the execution the plugin call targets
     * @param pluginScope The single `plugin:execution:*` scope the call requires
     * @return The generated JWT token string
     */
    fun generatePluginExecutionToken(projectId: UUID, executionId: UUID, pluginScope: String): String {
        val now = Instant.now()
        val expiration = now.plusSeconds(jwtConfig.expirationSeconds)

        return JWT.create()
            .withIssuer(jwtConfig.issuer)
            .withIssuedAt(Date.from(now))
            .withExpiresAt(Date.from(expiration))
            .withClaim(CLAIM_PROJECT_ID, projectId.toString())
            .withClaim(CLAIM_EXECUTION_ID, executionId.toString())
            .withArrayClaim(CLAIM_SCOPE, arrayOf(
                pluginScope,
                Scopes.FILES_READ,
                Scopes.FILE_DATA_READ,
                Scopes.PLUGIN_REQUEST_SEND
            ))
            .sign(algorithm)
    }

    /**
     * Generates a token for a plugin computing file data, bound to that computation.
     *
     * Besides computing, the token lets the plugin fetch the files and file data it depends on and ask
     * other plugins, but it is only accepted while [computationId] is still running.
     *
     * @param projectId The UUID of the project
     * @param computationId The UUID of the in-flight computation, as recorded in the database
     * @return The generated JWT token string
     */
    fun generateFileDataComputationToken(projectId: UUID, computationId: UUID): String {
        val now = Instant.now()
        val expiration = now.plusSeconds(jwtConfig.expirationSeconds)

        return JWT.create()
            .withIssuer(jwtConfig.issuer)
            .withIssuedAt(Date.from(now))
            .withExpiresAt(Date.from(expiration))
            .withClaim(CLAIM_PROJECT_ID, projectId.toString())
            .withClaim(CLAIM_COMPUTATION_ID, computationId.toString())
            .withClaim(CLAIM_BINDING, BINDING_FILE_DATA_COMPUTATION)
            .withArrayClaim(CLAIM_SCOPE, arrayOf(
                Scopes.PLUGIN_FILE_DATA_COMPUTE,
                Scopes.FILES_READ,
                Scopes.FILE_DATA_READ,
                Scopes.PLUGIN_REQUEST_SEND
            ))
            .sign(algorithm)
    }

    /**
     * Generates the token that opens one session on one plugin target.
     *
     * The token names the target and the session it may be used on, so a node holding a token
     * for one session cannot open another, and it is bound to the execution so that a lifetime
     * long enough to cover a run stops granting anything once the run ends. A session carries a
     * plugin-defined protocol, so the token grants connecting and nothing more, except for a `lang:`
     * target: the language service may ask this backend which contribution plugins to load.
     *
     * @param projectId The UUID of the project
     * @param executionId The UUID of the execution the session belongs to
     * @param target The addressed target, written into the token as `<kind>:<id>`
     * @param sessionName The session name
     * @param ttlSeconds Token lifetime in seconds. A reconnect needs a fresh token, so this need
     *   only outlive the connect attempt rather than the whole run.
     * @return The generated JWT token string
     */
    fun generateSessionConnectToken(
        projectId: UUID,
        executionId: UUID,
        target: PluginTarget,
        sessionName: String,
        ttlSeconds: Long = jwtConfig.expirationSeconds
    ): String {
        val now = Instant.now()
        val expiration = now.plusSeconds(ttlSeconds)

        return JWT.create()
            .withIssuer(jwtConfig.issuer)
            .withIssuedAt(Date.from(now))
            .withExpiresAt(Date.from(expiration))
            .withClaim(CLAIM_PROJECT_ID, projectId.toString())
            .withClaim(CLAIM_EXECUTION_ID, executionId.toString())
            .withClaim(CLAIM_TARGET, target.toString())
            .withClaim(CLAIM_SESSION, sessionName)
            .withClaim(CLAIM_BINDING, BINDING_ACTIVE_EXECUTION)
            .withArrayClaim(
                CLAIM_SCOPE,
                if (target.kind == PluginTargetKind.LANGUAGE) {
                    arrayOf(Scopes.PLUGIN_SESSION_CONNECT, Scopes.SESSION_CONTRIBUTIONS_READ)
                } else {
                    arrayOf(Scopes.PLUGIN_SESSION_CONNECT)
                }
            )
            .sign(algorithm)
    }

    /**
     * Lifetime of a session connect token, in seconds.
     *
     * @return The general token lifetime, at most [MAX_SESSION_TOKEN_TTL_SECONDS]
     */
    fun sessionConnectTokenTtlSeconds(): Long = minOf(jwtConfig.expirationSeconds, MAX_SESSION_TOKEN_TTL_SECONDS)

    /**
     * Gets a JWT verifier for validating tokens.
     *
     * @return A configured JWT verifier
     */
    fun getVerifier(): JWTVerifier {
        return JWT.require(algorithm)
            .withIssuer(jwtConfig.issuer)
            .build()
    }
    
    /**
     * Verifies a JWT token and returns the decoded token.
     *
     * @param token The JWT token to verify
     * @return The decoded JWT if valid
     * @throws com.auth0.jwt.exceptions.JWTVerificationException if the token is invalid
     */
    fun verifyToken(token: String): DecodedJWT {
        return getVerifier().verify(token)
    }
    
    /**
     * Extracts the project ID from a verified JWT token.
     *
     * @param token The decoded JWT token
     * @return The project UUID, or null if not present
     */
    fun getProjectId(token: DecodedJWT): UUID? {
        return token.getClaim(CLAIM_PROJECT_ID).asString()?.let { 
            try { UUID.fromString(it) } catch (e: Exception) { null }
        }
    }
    
    /**
     * Checks if a token has a specific scope.
     *
     * @param token The decoded JWT token
     * @param scope The scope to check for
     * @return true if the token has the scope, false otherwise
     */
    fun hasScope(token: DecodedJWT, scope: String): Boolean {
        val scopes = token.getClaim(CLAIM_SCOPE).asList(String::class.java) ?: return false
        return scope in scopes
    }
    
    /**
     * Gets the public key in PEM format for external verification.
     *
     * @return The public key as a PEM-encoded string
     */
    fun getPublicKeyPem(): String {
        val base64 = Base64.getMimeEncoder(64, "\n".toByteArray())
            .encodeToString(publicKey.encoded)
        return "-----BEGIN PUBLIC KEY-----\n$base64\n-----END PUBLIC KEY-----"
    }
    
    /**
     * Gets the public key in JWKS (JSON Web Key Set) format for external verification.
     * Returns a JWKS containing the RSA public key with standard parameters.
     *
     * @return Map representing the JWKS structure
     */
    fun getJwks(): Map<String, Any> {
        val modulusBytes = publicKey.modulus.toByteArray()
        val modulusStripped = if (modulusBytes[0] == 0.toByte() && modulusBytes.size > 1) {
            modulusBytes.copyOfRange(1, modulusBytes.size)
        } else {
            modulusBytes
        }
        
        val exponentBytes = publicKey.publicExponent.toByteArray()
        val exponentStripped = if (exponentBytes[0] == 0.toByte() && exponentBytes.size > 1) {
            exponentBytes.copyOfRange(1, exponentBytes.size)
        } else {
            exponentBytes
        }
        
        val modulusBase64 = Base64.getUrlEncoder().withoutPadding().encodeToString(modulusStripped)
        val exponentBase64 = Base64.getUrlEncoder().withoutPadding().encodeToString(exponentStripped)
        
        val keyId = "mdeo-key-1"
        
        val key = mapOf(
            "kty" to "RSA",
            "use" to "sig",
            "alg" to "RS256",
            "kid" to keyId,
            "n" to modulusBase64,
            "e" to exponentBase64
        )
        
        return mapOf("keys" to listOf(key))
    }
    
    /**
     * Gets the RSA algorithm for use in authentication configuration.
     *
     * @return The RSA algorithm instance
     */
    fun getAlgorithm(): Algorithm = algorithm
    
    /**
     * Gets the issuer string for JWT verification.
     *
     * @return The issuer string
     */
    fun getIssuer(): String = jwtConfig.issuer

    /**
     * Decodes a cryptographic key that is provided either as a PEM string (with
     * `-----BEGIN ...-----` / `-----END ...-----` headers) or as raw Base64-encoded
     * DER bytes.  Both formats are accepted so that:
     * - Automated provisioning tools (e.g. Terraform `tls_private_key`) can pass PEM
     *   directly without extra encoding steps.
     * - Existing deployments that already store raw Base64 DER continue to work unchanged.
     */
    private fun decodePemOrBase64(key: String): ByteArray {
        val trimmed = key.trim()
        return if (trimmed.startsWith("-----")) {
            // PEM: strip header/footer lines and decode the inner Base64 block.
            val b64 = trimmed.lines()
                .filter { !it.startsWith("-----") }
                .joinToString("")
            Base64.getDecoder().decode(b64)
        } else {
            Base64.getDecoder().decode(trimmed)
        }
    }
}
