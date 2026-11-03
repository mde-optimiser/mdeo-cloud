package com.mdeo.common.model

import kotlinx.serialization.Serializable

/**
 * What a caller needs to open a session, handed back by the connect endpoint.
 *
 * Everything here is owned by the platform: where the session lives, what it is allowed to
 * speak, what authorizes it and how long that authorization lasts. What travels once the
 * connection is open is the plugin's own business.
 *
 * @property url WebSocket URL of the session endpoint
 * @property protocol Name of the protocol spoken on the session
 * @property versions Protocol versions the plugin side can speak, most preferred first
 * @property token Bearer token that authorizes exactly this session
 * @property expiresAt Epoch second at which [token] stops being accepted
 */
@Serializable
data class SessionConnectResponse(
    val url: String,
    val protocol: String,
    val versions: List<Int>,
    val token: String,
    val expiresAt: Long
)
