package com.mdeo.common.model

import kotlinx.serialization.Serializable

/**
 * The parameters a git credential helper puts on the authorization URL,
 * passed back to the server so it can validate them and, on approval, mint
 * a code for them.
 *
 * @property responseType Must be `code`; only the authorization code flow is supported
 * @property clientId The requesting client, or null to mean the server's one configured client
 * @property redirectUri Where the helper is listening for the code
 * @property state Opaque value the helper uses to tie the response to its request
 * @property codeChallenge The PKCE challenge
 * @property codeChallengeMethod Must be `S256`
 * @property scope What the helper asked for, carried only for display
 */
@Serializable
data class GitOAuthAuthorizationRequest(
    val responseType: String,
    val clientId: String? = null,
    val redirectUri: String,
    val state: String,
    val codeChallenge: String,
    val codeChallengeMethod: String,
    val scope: String? = null
)

/**
 * What the authorization screen needs to know to describe the request it is
 * asking the user to approve.
 *
 * @property scope What the client asked for, or null when it asked for nothing in particular
 */
@Serializable
data class GitOAuthRequestInfo(
    val scope: String? = null
)

/**
 * The result of approving or declining an authorization request.
 *
 * @property redirectTo Where to send the browser next. Carries the code on
 *   approval and `error=access_denied` on refusal, since a decline is an
 *   answer the client is entitled to hear rather than a dead end.
 */
@Serializable
data class GitOAuthDecision(
    val redirectTo: String
)
