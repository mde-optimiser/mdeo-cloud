package com.mdeo.common.transport

/**
 * How often a keepalive is sent on a plugin session, by both ends. Sessions have no request timeout,
 * so this is what notices a peer that vanished without closing, and what keeps a reverse proxy from
 * dropping a connection that is busy computing. The TypeScript session server uses the same period.
 */
const val SESSION_PING_PERIOD_SECONDS = 30L

/**
 * How long a peer may go without answering a keepalive before its session is closed.
 */
const val SESSION_PONG_TIMEOUT_SECONDS = 90L
