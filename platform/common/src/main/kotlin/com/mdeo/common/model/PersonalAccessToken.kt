package com.mdeo.common.model

import kotlinx.serialization.Serializable

/**
 * Request payload to create a new personal access token.
 *
 * @property name A label the user chooses to tell tokens apart later
 * @property expiresAt When the token stops working, or null for no expiry (ISO 8601 timestamp)
 * @property projectIds The projects the token may reach. An empty list - or
 *   the property being absent, which is what a client written before
 *   scoping existed sends - means the token is unscoped and can reach
 *   every project its owner can.
 */
@Serializable
data class CreatePersonalAccessTokenRequest(
    val name: String,
    val expiresAt: String? = null,
    val projectIds: List<String> = emptyList()
)

/**
 * A project a token is scoped to, named so a token list can show what the
 * token reaches without the client having to resolve ids itself.
 *
 * @property id The project's unique identifier
 * @property name The project's name at the time the token was listed
 */
@Serializable
data class TokenProjectScope(
    val id: String,
    val name: String
)

/**
 * Response to creating a personal access token. [token] is the raw secret
 * value and is only ever present in this one response - it cannot be
 * recovered afterward, only revoked and replaced with a new one.
 *
 * @property id Unique identifier for the token
 * @property name The label the user gave it
 * @property token The raw token value, shown once
 * @property createdAt When the token was created (ISO 8601 timestamp)
 * @property expiresAt When the token stops working, or null for no expiry (ISO 8601 timestamp)
 * @property projects The projects the token is restricted to, empty when unscoped
 */
@Serializable
data class PersonalAccessTokenCreated(
    val id: String,
    val name: String,
    val token: String,
    val createdAt: String,
    val expiresAt: String? = null,
    val projects: List<TokenProjectScope> = emptyList()
)

/**
 * A personal access token's metadata, without the raw value.
 *
 * @property id Unique identifier for the token
 * @property name The label the user gave it
 * @property tokenPrefix First few characters of the raw token, enough to tell tokens apart in a list
 * @property createdAt When the token was created (ISO 8601 timestamp)
 * @property lastUsedAt When the token was last used to authenticate, or null if never (ISO 8601 timestamp)
 * @property expiresAt When the token stops working, or null for no expiry (ISO 8601 timestamp)
 * @property scoped Whether the token was created restricted to particular projects at all. A
 *   scoped token can still list no projects, once every project it named has been deleted, and
 *   then reaches nothing - which is the opposite of what an unscoped token does.
 * @property projects The projects the token is restricted to; only meaningful when [scoped]
 */
@Serializable
data class PersonalAccessTokenInfo(
    val id: String,
    val name: String,
    val tokenPrefix: String,
    val createdAt: String,
    val lastUsedAt: String? = null,
    val expiresAt: String? = null,
    val scoped: Boolean = false,
    val projects: List<TokenProjectScope> = emptyList()
)
