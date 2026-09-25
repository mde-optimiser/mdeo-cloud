package com.mdeo.backend.plugins

import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin

/**
 * The address a request should be attributed to, resolved through however
 * many reverse proxies the deployment actually puts in front of the backend.
 *
 * `request.origin.remoteHost` is the address of whoever opened the TCP
 * connection, which behind a proxy is the proxy. Anything keyed on it would
 * then treat every user of a proxied deployment as one caller - for the
 * authentication rate limiter that means a single shared bucket, so one
 * user's failed logins throttle everybody.
 *
 * `X-Forwarded-For` carries the answer, but it is client-supplied: a caller
 * can put anything in it, and a proxy only ever *appends* what it saw. That
 * is what makes the count of trusted hops the whole security of this. With
 * [trustedProxyHops] proxies actually in front, the nth entry from the right
 * is the one the innermost trusted proxy observed itself, and everything to
 * its left is unverifiable and ignored. A caller reaching the backend
 * directly cannot forge a position it does not have.
 *
 * Zero hops means the header is not trusted at all and the peer address is
 * used, which is the right answer whenever the backend is reachable without
 * going through a proxy - so it is the default, and deployments that do put
 * a proxy in front say so explicitly.
 *
 * @param trustedProxyHops How many reverse proxies sit between clients and
 *   this backend; zero to ignore forwarded headers entirely
 * @return The client address, or the direct peer when it cannot be resolved
 */
fun ApplicationCall.clientAddress(trustedProxyHops: Int): String {
    val peer = request.origin.remoteHost
    if (trustedProxyHops <= 0) {
        return peer
    }

    // Repeated headers and comma-separated lists are equivalent, and both
    // occur in practice depending on how each hop was configured.
    val forwarded = request.headers.getAll("X-Forwarded-For")
        ?.flatMap { it.split(',') }
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?: return peer

    return forwarded.getOrNull(forwarded.size - trustedProxyHops) ?: peer
}
