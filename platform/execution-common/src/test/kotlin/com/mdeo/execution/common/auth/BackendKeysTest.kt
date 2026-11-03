package com.mdeo.execution.common.auth

import com.auth0.jwk.Jwk
import com.auth0.jwk.JwkProvider
import com.auth0.jwk.SigningKeyNotFoundException
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.SignatureVerificationException
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * A backend that restarts with a new signing key under the same key id, as the dev backend does
 * when it is not configured with a key.
 */
class BackendKeysTest {

    private val issuer = "mdeo-platform"

    /** The backend's JWKS: one key, under the id "mdeo-key-1". */
    private inner class Backend : JwkProvider {
        var key: KeyPair = newKey()
        var fetches = 0

        override fun get(keyId: String?): Jwk {
            fetches++
            val public = key.public as RSAPublicKey
            val encode = { bytes: ByteArray -> Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.dropWhile { it == 0.toByte() }.toByteArray()) }
            return Jwk.fromValues(
                mapOf(
                    "kty" to "RSA", "kid" to "mdeo-key-1", "alg" to "RS256", "use" to "sig",
                    "n" to encode(public.modulus.toByteArray()), "e" to encode(public.publicExponent.toByteArray())
                )
            )
        }
    }

    private fun newKey(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    private fun sign(key: KeyPair, keyId: String? = "mdeo-key-1"): String =
        JWT.create().withKeyId(keyId).withIssuer(issuer).withClaim("projectId", "p")
            .sign(Algorithm.RSA256(null, key.private as RSAPrivateKey))

    private var now = 0L

    private fun keys(backend: Backend) =
        BackendKeys({ backend.get(it).publicKey as RSAPublicKey }, issuer, minFetchIntervalMillis = 10_000, clock = { now })

    @Test
    fun `a token signed with the key the backend published after a restart is accepted`() {
        val backend = Backend()
        val keys = keys(backend)
        assertEquals("p", keys.verifierFor(sign(backend.key)).verify(sign(backend.key)).getClaim("projectId").asString())

        backend.key = newKey()
        now += 60_000
        val token = sign(backend.key)
        assertEquals("p", keys.verifierFor(token).verify(token).getClaim("projectId").asString())
        assertEquals(2, backend.fetches)
    }

    @Test
    fun `the keys are fetched at most once per interval`() {
        val backend = Backend()
        val keys = keys(backend)
        keys.verifierFor(sign(backend.key))
        now += 60_000

        val forged = sign(newKey())
        assertFailsWith<SignatureVerificationException> { keys.verifierFor(forged).verify(forged) }
        assertFailsWith<SignatureVerificationException> { keys.verifierFor(forged).verify(forged) }
        assertEquals(2, backend.fetches)

        now += 10_000
        assertFailsWith<SignatureVerificationException> { keys.verifierFor(forged).verify(forged) }
        assertEquals(3, backend.fetches)
    }

    @Test
    fun `the backend's JWKS is fetched afresh although the key id stays the same`() {
        val backend = Backend()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/.well-known/jwks.json") { exchange ->
            val jwk = backend.get(null)
            val public = jwk.publicKey as RSAPublicKey
            val encode = { bytes: ByteArray -> Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.dropWhile { it == 0.toByte() }.toByteArray()) }
            val body = """{"keys":[{"kty":"RSA","kid":"mdeo-key-1","alg":"RS256","use":"sig","n":"${encode(public.modulus.toByteArray())}","e":"${encode(public.publicExponent.toByteArray())}"}]}"""
                .toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val url = URI("http://127.0.0.1:${server.address.port}/.well-known/jwks.json").toURL()
            val keys = BackendKeys(BackendKeys.jwksFetcher(url), issuer, minFetchIntervalMillis = 0, clock = { now })
            keys.verifierFor(sign(backend.key)).verify(sign(backend.key))

            backend.key = newKey()
            val token = sign(backend.key)
            assertEquals("p", keys.verifierFor(token).verify(token).getClaim("projectId").asString())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `a key id fetched moments ago and still unknown is refused without fetching`() {
        val backend = Backend()
        val keys = keys(backend)
        keys.verifierFor(sign(backend.key))
        assertFailsWith<SigningKeyNotFoundException> { keys.verifierFor(sign(backend.key, keyId = "other")) }
        assertEquals(1, backend.fetches)
    }

    @Test
    fun `a token without a key id is verified against the backend's key`() {
        val backend = Backend()
        val token = sign(backend.key, keyId = null)
        assertEquals("p", keys(backend).verifierFor(token).verify(token).getClaim("projectId").asString())
    }
}
