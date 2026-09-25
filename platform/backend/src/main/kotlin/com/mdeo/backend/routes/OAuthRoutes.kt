package com.mdeo.backend.routes

import com.mdeo.backend.plugins.UserSession
import com.mdeo.backend.service.CodeRedemption
import com.mdeo.backend.service.OAuthCodeService
import com.mdeo.backend.service.PersonalAccessTokenService
import com.mdeo.common.model.GitOAuthAuthorizationRequest
import com.mdeo.common.model.GitOAuthDecision
import com.mdeo.common.model.GitOAuthRequestInfo
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import org.slf4j.LoggerFactory
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * A validated authorization request.
 *
 * @property clientId The requesting client, resolved to the configured one when omitted
 * @property redirectUri Where to send the browser back to with the code
 * @property state Opaque value the client uses to tie the response to its request
 * @property codeChallenge The PKCE S256 challenge
 */
private data class ValidatedRequest(
    val clientId: String,
    val redirectUri: String,
    val state: String,
    val codeChallenge: String
)

/**
 * OAuth 2.0 authorization endpoints, so a git credential helper - Git
 * Credential Manager in particular - can trade a browser sign-in for a
 * personal access token, and never see the account password at all.
 *
 * This is the authorization code flow with PKCE, and deliberately nothing
 * more: no client secret, because a helper installed on a developer's
 * machine is a public client that cannot keep one, and no refresh tokens,
 * because the access token it hands back is an ordinary personal access
 * token revoked from the Account dialog like any other. GCM stores it in
 * the operating system's credential store, so the sign-in happens once and
 * every later clone or push is unattended.
 *
 * These routes serve JSON only. The screen the user actually sees is a
 * workbench view at `/oauth/authorize`, built from the same components as
 * the rest of the product - so there is one login card, one card style, and
 * one set of buttons rather than a lookalike rendered by the backend.
 *
 * @param personalAccessTokenService Mints the token the flow hands back
 * @param oauthCodeService Issues and redeems the authorization codes
 * @param clientId The single public client id this server recognizes
 * @param codeTtl How long an issued authorization code stays redeemable
 * @param sessionMaxAbsoluteSeconds Session lifetime, applied here for the same reason the
 *   session authentication provider applies it
 * @param tokenPath Where to mount the token endpoint. The same value the workbench advertises in
 *   its setup commands, so the two cannot disagree.
 * @param authorizePath Where the authorization screen lives, for forwarding a browser that
 *   arrived at this JSON API instead.
 */
fun Route.oauthRoutes(
    personalAccessTokenService: PersonalAccessTokenService,
    oauthCodeService: OAuthCodeService,
    clientId: String,
    codeTtl: Duration,
    sessionMaxAbsoluteSeconds: Long,
    tokenPath: String,
    authorizePath: String
) {
    val logger = LoggerFactory.getLogger("com.mdeo.backend.routes.OAuthRoutes")

    route("/api/oauth") {
        /**
         * Validates an authorization request before the screen offers to
         * approve it, so a malformed one is reported as such instead of
         * being presented as a legitimate thing to say yes to.
         */
        post("/request-info") {
            val body = runCatching { call.receive<GitOAuthAuthorizationRequest>() }.getOrNull()
                ?: return@post call.respondOAuthError(
                    HttpStatusCode.BadRequest, "invalid_request", "Malformed authorization request"
                )
            validate(body, clientId) ?: return@post call.respondOAuthError(
                HttpStatusCode.BadRequest, "invalid_request", INVALID_REQUEST_MESSAGE
            )
            call.respond(GitOAuthRequestInfo(scope = body.scope))
        }

        /**
         * Sends a browser that arrived here to the authorization screen.
         *
         * This path is the JSON API the screen talks to, not the screen
         * itself - but it is also where a credential helper configured
         * before the screen moved will open a window, and answering a
         * browser's GET with "405 Method Not Allowed" tells nobody
         * anything. Forwarding the query string means such a helper keeps
         * working rather than failing in a way that looks like a server
         * fault.
         */
        get("/authorize") {
            val query = call.request.queryString()
            call.respondRedirect(if (query.isEmpty()) authorizePath else "$authorizePath?$query")
        }

        /**
         * Approves or declines an authorization request on behalf of the
         * signed-in user, and reports where to send the browser next.
         */
        post("/authorize") {
            val body = runCatching { call.receive<GitOAuthAuthorizationRequest>() }.getOrNull()
                ?: return@post call.respondOAuthError(
                    HttpStatusCode.BadRequest, "invalid_request", "Malformed authorization request"
                )
            val request = validate(body, clientId) ?: return@post call.respondOAuthError(
                HttpStatusCode.BadRequest, "invalid_request", INVALID_REQUEST_MESSAGE
            )

            // Approval is the user's to give, so it needs a session. The
            // screen only renders for a signed-in user, but a session can
            // expire while it sits open.
            val session = call.currentBrowserSession(sessionMaxAbsoluteSeconds)
                ?: return@post call.respondOAuthError(
                    HttpStatusCode.Unauthorized, "login_required", "Sign in to authorize git access"
                )

            // Declining is a legitimate answer and RFC 6749 has a way to say
            // it, so the client fails cleanly instead of waiting on a
            // redirect that never comes.
            if (call.request.queryParameters["approve"] != "true") {
                return@post call.respond(
                    GitOAuthDecision(
                        appendQuery(
                            request.redirectUri,
                            mapOf("error" to "access_denied", "state" to request.state)
                        )
                    )
                )
            }

            val userId = runCatching { UUID.fromString(session.userId) }.getOrNull()
                ?: return@post call.respondOAuthError(
                    HttpStatusCode.Unauthorized, "login_required", "Sign in to authorize git access"
                )

            val code = oauthCodeService.issue(
                userId = userId,
                clientId = request.clientId,
                redirectUri = request.redirectUri,
                codeChallenge = request.codeChallenge,
                ttl = codeTtl
            )

            logger.info("Issued git OAuth authorization code for user {}", session.username)
            call.respond(
                GitOAuthDecision(
                    appendQuery(request.redirectUri, mapOf("code" to code, "state" to request.state))
                )
            )
        }

    }

    /**
     * Exchanges an authorization code for a personal access token.
     *
     * Mounted at the configured path rather than a literal one, so the
     * commands the workbench tells people to run always name the endpoint
     * this server actually serves.
     *
     * Errors follow RFC 6749's shape - a JSON body with an `error` field -
     * because that is what GCM parses a failure out of.
     */
    post(tokenPath) {
        // A caller that sends no body, or one that is not form encoded, is
        // making a malformed request - not causing a server error. Ktor's
        // receiveParameters throws on both, so this is caught rather than
        // left to the generic 500 handler.
        val parameters = runCatching { call.receiveParameters() }.getOrNull()
            ?: return@post call.respondOAuthError(
                HttpStatusCode.BadRequest,
                "invalid_request",
                "Expected an application/x-www-form-urlencoded body"
            )

        if (parameters["grant_type"] != "authorization_code") {
            return@post call.respondOAuthError(
                HttpStatusCode.BadRequest,
                "unsupported_grant_type",
                "Only the authorization_code grant is supported"
            )
        }

        val code = parameters["code"]
        if (code.isNullOrEmpty()) {
            return@post call.respondOAuthError(HttpStatusCode.BadRequest, "invalid_request", "Missing code")
        }

        // GCM sends client_id in the form body, and by default also repeats
        // it in an HTTP basic Authorization header (oauthUseClientAuthHeader).
        // Either is accepted, as is neither: this is a public client, so the
        // id names the client but does not authenticate it - PKCE does that.
        val presentedClientId = parameters["client_id"]?.takeIf { it.isNotEmpty() }
            ?: call.basicAuthClientId()
            ?: clientId
        if (presentedClientId != clientId) {
            return@post call.respondOAuthError(HttpStatusCode.Unauthorized, "invalid_client", "Unknown client")
        }

        val redemption = oauthCodeService.redeem(
            code = code,
            clientId = presentedClientId,
            redirectUri = parameters["redirect_uri"],
            codeVerifier = parameters["code_verifier"]
        )
        if (redemption !is CodeRedemption.Success) {
            return@post call.respondOAuthError(
                HttpStatusCode.BadRequest,
                "invalid_grant",
                "The authorization code is invalid, expired, or already used"
            )
        }

        // The access token is an ordinary personal access token, so it shows
        // up in the Account dialog alongside hand-made ones and is revoked
        // the same way. Named for where it came from, since nobody typed a
        // name for it.
        val issuedOn = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC).format(Instant.now())
        val token = personalAccessTokenService.createToken(
            userId = redemption.userId,
            name = "git sign-in ($issuedOn)",
            expiresAt = null
        ) ?: return@post call.respondOAuthError(
            HttpStatusCode.InternalServerError,
            "server_error",
            "Could not issue a token"
        )

        logger.info("Issued git OAuth access token '{}' for user {}", token.name, redemption.userId)
        // RFC 6749 §5.1 requires both headers on a token response: the body carries a live
        // credential, and without them an intermediate cache (or the browser's own) is otherwise
        // free to store and replay it for a later request that never asked for one.
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.response.header(HttpHeaders.Pragma, "no-cache")
        call.respondText(
            """{"access_token":"${token.token}","token_type":"Bearer","scope":"git"}""",
            ContentType.Application.Json
        )
    }
}

/**
 * Deliberately one message for every way a request can be malformed. Which
 * part a caller got wrong is not something worth telling them precisely,
 * and the screen has nothing useful to do with the distinction.
 */
private const val INVALID_REQUEST_MESSAGE =
    "This git sign-in link is not valid for this server. Check your git credential helper configuration."

/**
 * Validates an authorization request's parameters.
 *
 * @param body The request as the screen received it
 * @param expectedClientId The only client id this server recognizes
 * @return The validated request, or null when it does not hold up
 */
private fun validate(body: GitOAuthAuthorizationRequest, expectedClientId: String): ValidatedRequest? {
    // The client id is optional. This server has exactly one client and it is
    // public, so the id names the caller but proves nothing about it - PKCE
    // and the loopback-only redirect are what actually bind a code to the
    // helper that asked for it. Letting it be omitted is what keeps the
    // client-side setup down to the two endpoint settings GCM requires.
    val clientId = body.clientId?.takeIf { it.isNotEmpty() } ?: expectedClientId
    if (clientId != expectedClientId) {
        return null
    }
    if (body.responseType != "code") {
        return null
    }
    // A native client may only be sent back to a loopback address (RFC 8252),
    // and anything else here would make this an open redirect.
    if (!isLoopbackRedirect(body.redirectUri)) {
        return null
    }
    // PKCE is required, not merely accepted: this is a public client with no
    // secret, so the verifier is the only thing binding the code to the
    // helper that asked for it.
    if (body.codeChallenge.isEmpty() || body.codeChallengeMethod != "S256") {
        return null
    }
    if (body.state.isEmpty()) {
        return null
    }
    return ValidatedRequest(clientId, body.redirectUri, body.state, body.codeChallenge)
}

/**
 * Whether a redirect URI is a loopback address, the only kind a native
 * client may use (RFC 8252). The port is deliberately not constrained: GCM
 * listens on an ephemeral one it picks per attempt.
 *
 * @param uri The redirect URI as sent
 * @return true when it is an http loopback URI
 */
private fun isLoopbackRedirect(uri: String): Boolean {
    val parsed = runCatching { URI(uri) }.getOrNull() ?: return false
    if (!parsed.scheme.equals("http", ignoreCase = true)) {
        return false
    }
    if (parsed.fragment != null) {
        // appendQuery below appends "?code=...&state=..." by string concatenation, which lands
        // after a fragment rather than before it if the redirect URI already has one - so the
        // credential helper's own HTTP server, which only ever sees the path and query of the
        // request the browser makes, would never receive the code or state at all. Not something
        // a native client legitimately needs, since RFC 8252 does not call for one, so refusing it
        // outright is simpler than trying to append correctly around it.
        return false
    }
    return parsed.host in setOf("127.0.0.1", "::1", "[::1]", "localhost")
}

/**
 * Reads the workbench's session cookie directly.
 *
 * These routes are mounted outside the `authenticate` block, because the
 * token endpoint is called by a credential helper that has no session at
 * all. That also means `principal<UserSession>()` is never populated here,
 * so the cookie has to be read straight from the session store, and the
 * absolute-expiry rule the session authentication provider applies has to
 * be repeated rather than inherited.
 *
 * @param maxAbsoluteSeconds How long a session stays valid from its creation
 * @return The signed-in user's session, or null when there is none or it has expired
 */
private fun ApplicationCall.currentBrowserSession(maxAbsoluteSeconds: Long): UserSession? {
    val session = sessions.get<UserSession>() ?: return null
    val age = Instant.now().epochSecond - session.createdAt
    if (session.createdAt == 0L || age >= maxAbsoluteSeconds) {
        return null
    }
    return session
}

/**
 * @param base A URI that may already carry a query string
 * @param params Parameters to add to it
 * @return The URI with [params] appended, each value percent encoded
 */
private fun appendQuery(base: String, params: Map<String, String>): String {
    val encoded = params.entries.joinToString("&") { "${it.key}=${it.value.encodeURLParameter()}" }
    return if (base.contains('?')) "$base&$encoded" else "$base?$encoded"
}

/**
 * @return The client id from an HTTP basic Authorization header, if one is present
 */
private fun ApplicationCall.basicAuthClientId(): String? {
    val header = request.headers[HttpHeaders.Authorization]?.takeIf { it.startsWith("Basic ", ignoreCase = true) }
        ?: return null
    val decoded = runCatching {
        String(java.util.Base64.getDecoder().decode(header.substringAfter(' ')), Charsets.UTF_8)
    }.getOrNull() ?: return null
    return decoded.substringBefore(':').decodeURLPart()
}

private suspend fun ApplicationCall.respondOAuthError(status: HttpStatusCode, error: String, description: String) {
    respondText("""{"error":"$error","error_description":"$description"}""", ContentType.Application.Json, status)
}
