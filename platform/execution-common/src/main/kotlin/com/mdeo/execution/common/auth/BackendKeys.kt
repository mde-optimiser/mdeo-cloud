package com.mdeo.execution.common.auth

import com.auth0.jwk.SigningKeyNotFoundException
import com.auth0.jwk.UrlJwkProvider
import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.SignatureVerificationException
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.URL
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.ConcurrentHashMap

/**
 * The keys the backend signs tokens with, fetched from its JWKS and kept.
 *
 * A backend that is not configured with a key signs with a new one after every restart, under
 * the same key id, so a kept key can go stale without the tokens saying so. A token whose
 * signature fails with the kept key has the keys fetched again and is checked with the fetched
 * key. The keys are fetched at most once per [minFetchIntervalMillis].
 *
 * @param fetchKey Fetches the backend's key of an id, without caching it
 * @param issuer Expected token issuer
 * @param minFetchIntervalMillis Least time between two fetches of the keys
 * @param clock The current time in milliseconds
 */
class BackendKeys internal constructor(
    private val fetchKey: (keyId: String?) -> RSAPublicKey,
    private val issuer: String,
    private val minFetchIntervalMillis: Long,
    private val clock: () -> Long
) {
    /**
     * @param backendUrl Base URL of the backend serving `/.well-known/jwks.json`
     * @param issuer Expected token issuer
     */
    constructor(backendUrl: String, issuer: String) : this(
        jwksFetcher(URI("${backendUrl.trimEnd('/')}/.well-known/jwks.json").toURL()),
        issuer,
        10_000,
        System::currentTimeMillis
    )

    private val logger = LoggerFactory.getLogger(BackendKeys::class.java)
    private val keys = ConcurrentHashMap<String, RSAPublicKey>()
    private var lastFetch: Long? = null

    /**
     * Returns a verifier for [token] with the key it claims to be signed with. Fetches that key
     * when none is kept, or when the kept one does not verify the token's signature.
     *
     * Fetching blocks on the network.
     *
     * @param token The raw bearer token
     * @throws Exception If the token cannot be decoded or its key cannot be fetched
     */
    fun verifierFor(token: String): JWTVerifier {
        val keyId = JWT.decode(token).keyId
        val kept = keys[keyId.orEmpty()]
            ?: fetch(keyId, stale = null)
            ?: throw SigningKeyNotFoundException("The backend's key '$keyId' is not known yet", null)
        val verifier = verifier(kept)
        try {
            verifier.verify(token)
        } catch (e: SignatureVerificationException) {
            val fetched = fetch(keyId, stale = kept)
            if (fetched != null && fetched != kept) {
                logger.info("The backend signs with a new key; using it from now on")
                return verifier(fetched)
            }
        } catch (e: Exception) {
            // Expired, another issuer and the like: fetching again changes nothing.
        }
        return verifier
    }

    private fun verifier(key: RSAPublicKey): JWTVerifier =
        JWT.require(Algorithm.RSA256(key, null))
            .withIssuer(issuer)
            .acceptLeeway(3)
            .build()

    /**
     * Fetches the key [keyId] unless another call replaced [stale] meanwhile, or the keys were
     * fetched less than [minFetchIntervalMillis] ago.
     *
     * @return The kept key after the call, or null when there is none
     */
    @Synchronized
    private fun fetch(keyId: String?, stale: RSAPublicKey?): RSAPublicKey? {
        val current = keys[keyId.orEmpty()]
        if (current != null && current != stale) return current
        val now = clock()
        val last = lastFetch
        if (last != null && now - last < minFetchIntervalMillis) return current
        lastFetch = now
        return fetchKey(keyId).also { keys[keyId.orEmpty()] = it }
    }

    internal companion object {
        /**
         * Fetches a key from the JWKS at [url] afresh on every call. A [UrlJwkProvider] keeps the
         * key set it fetched and only fetches again for an id it does not know, so every call gets
         * a new one.
         */
        fun jwksFetcher(url: URL): (String?) -> RSAPublicKey = { keyId ->
            UrlJwkProvider(url, 5_000, 5_000).get(keyId).publicKey as RSAPublicKey
        }
    }
}
