/**
 * Where the authorization screen lives when the server has not been
 * configured to move it. Served by the SPA rather than the backend so the
 * screen is built from the same components as the rest of the product, and
 * so an unauthenticated visitor simply meets the ordinary login card in
 * place. The server is the authority on this path; this is only the value
 * to assume while its configuration is still being fetched, or if it cannot
 * be reached.
 */
export const DEFAULT_GIT_OAUTH_AUTHORIZE_PATH = "/oauth/authorize";

/**
 * The parameters a git credential helper puts on the authorization URL.
 */
export interface GitOAuthAuthorizationRequest {
    responseType: string;
    clientId?: string;
    redirectUri: string;
    state: string;
    codeChallenge: string;
    codeChallengeMethod: string;
    scope?: string;
}

/**
 * Whether the browser is on the git authorization screen.
 *
 * @param authorizePath The path the server serves it at
 */
export function isGitOAuthAuthorizePath(authorizePath: string): boolean {
    return window.location.pathname === authorizePath;
}

/**
 * Reads the authorization request out of the current URL.
 *
 * Only presence is checked here; whether the request is actually acceptable
 * is the server's decision, since it is the one that knows the client id and
 * has to enforce the loopback-only redirect rule.
 *
 * @return The request, or undefined when a required parameter is missing
 */
export function readGitOAuthRequest(): GitOAuthAuthorizationRequest | undefined {
    const params = new URLSearchParams(window.location.search);
    const responseType = params.get("response_type");
    const redirectUri = params.get("redirect_uri");
    const state = params.get("state");
    const codeChallenge = params.get("code_challenge");
    const codeChallengeMethod = params.get("code_challenge_method");

    if (
        responseType == undefined ||
        redirectUri == undefined ||
        state == undefined ||
        codeChallenge == undefined ||
        codeChallengeMethod == undefined
    ) {
        return undefined;
    }

    return {
        responseType,
        clientId: params.get("client_id") ?? undefined,
        redirectUri,
        state,
        codeChallenge,
        codeChallengeMethod,
        scope: params.get("scope") ?? undefined
    };
}
