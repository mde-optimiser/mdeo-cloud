package com.mdeo.backend.service

import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * An authorization code that has been issued but not yet redeemed.
 *
 * @property userId The user who signed in and approved the request
 * @property clientId The client the code was issued to
 * @property redirectUri The exact redirect URI the request carried, which
 *   the token request must repeat byte for byte
 * @property codeChallenge The PKCE challenge the request carried
 * @property expiresAt When the code stops being redeemable
 */
private data class PendingCode(
    val userId: UUID,
    val clientId: String,
    val redirectUri: String,
    val codeChallenge: String,
    val expiresAt: Instant
)

/**
 * The outcome of redeeming an authorization code.
 */
sealed interface CodeRedemption {
    /**
     * The code was valid and has now been consumed.
     *
     * @property userId The user who approved the original request
     */
    data class Success(val userId: UUID) : CodeRedemption

    /**
     * The code was unknown, already used, expired, or did not match the
     * client, redirect URI, or PKCE verifier presented with it. Deliberately
     * one outcome rather than several: telling a caller *which* part of
     * their redemption was wrong tells an attacker the same thing.
     */
    data object Failure : CodeRedemption
}

/**
 * Issues and redeems the short-lived authorization codes of the OAuth
 * authorization code flow, so a git credential helper can trade a browser
 * sign-in for a personal access token without ever handling the account
 * password.
 *
 * Codes live in memory rather than in the database. They are single-use and
 * expire within minutes, so there is nothing worth persisting across a
 * restart - a code in flight when the backend restarts simply fails, and the
 * helper starts the flow again. This does assume a single backend instance,
 * which is what the deployment runs (`replicas = 1`) and what
 * [com.mdeo.backend.git.GitRepositoryService]'s own in-memory project locks
 * already assume; a second replica would need this moved into Postgres.
 */
class OAuthCodeService {
    private val secureRandom = SecureRandom()
    private val codes = ConcurrentHashMap<String, PendingCode>()

    /**
     * Issues a code for a request the user has just approved.
     *
     * @param userId The approving user
     * @param clientId The client the code is for
     * @param redirectUri The request's redirect URI
     * @param codeChallenge The request's PKCE challenge
     * @param ttl How long the code stays redeemable
     * @return The raw authorization code to hand back through the redirect
     */
    fun issue(
        userId: UUID,
        clientId: String,
        redirectUri: String,
        codeChallenge: String,
        ttl: Duration
    ): String {
        purgeExpired()
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        val code = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        codes[code] = PendingCode(
            userId = userId,
            clientId = clientId,
            redirectUri = redirectUri,
            codeChallenge = codeChallenge,
            expiresAt = Instant.now().plus(ttl)
        )
        return code
    }

    /**
     * Redeems a code, consuming it whether or not the rest of the request
     * checks out: a code that has been presented once must never be
     * redeemable again, including after a failed attempt to guess the
     * verifier that goes with it.
     *
     * @param code The raw authorization code
     * @param clientId The client id the token request carried
     * @param redirectUri The redirect URI the token request carried
     * @param codeVerifier The PKCE verifier the token request carried
     * @return The approving user on success, or [CodeRedemption.Failure]
     */
    fun redeem(code: String, clientId: String, redirectUri: String?, codeVerifier: String?): CodeRedemption {
        purgeExpired()
        val pending = codes.remove(code) ?: return CodeRedemption.Failure

        if (pending.expiresAt.isBefore(Instant.now())) {
            return CodeRedemption.Failure
        }
        if (pending.clientId != clientId) {
            return CodeRedemption.Failure
        }
        // RFC 6749 requires the redirect URI to be repeated and to match
        // exactly when it was present in the authorization request.
        if (pending.redirectUri != redirectUri) {
            return CodeRedemption.Failure
        }
        if (codeVerifier == null || challengeFor(codeVerifier) != pending.codeChallenge) {
            return CodeRedemption.Failure
        }
        return CodeRedemption.Success(pending.userId)
    }

    /**
     * @param verifier A PKCE code verifier
     * @return Its S256 challenge, base64url encoded without padding
     */
    private fun challengeFor(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    private fun purgeExpired() {
        val now = Instant.now()
        codes.entries.removeIf { it.value.expiresAt.isBefore(now) }
    }
}
