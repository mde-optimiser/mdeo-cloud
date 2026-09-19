package com.mdeo.common.model

import kotlinx.serialization.Serializable

/**
 * The kinds of thing a plugin capability can be addressed on.
 *
 * Language plugins and contribution plugins both carry ids, and those id spaces overlap —
 * `config-mdeo` names both a language and a contribution — so every address spells out which
 * of the two it means.
 *
 * @property wire The string written into addresses, claims, configuration keys and log lines.
 */
enum class PluginTargetKind(val wire: String) {
    /**
     * A language plugin, addressed by its language id.
     */
    LANGUAGE("lang"),

    /**
     * A server contribution plugin, addressed by its contribution id.
     */
    CONTRIBUTION("contrib");

    companion object {
        /**
         * Resolves a wire string to its kind.
         *
         * @param wire The string to resolve, e.g. `lang`
         * @return The matching kind, or null when the string names none
         */
        fun fromWire(wire: String): PluginTargetKind? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * A plugin capability target: a kind and the id of a thing of that kind.
 *
 * This is the Kotlin twin of the `PluginTarget` helpers in `@mdeo/plugin`; the two must keep
 * producing and accepting exactly the same strings.
 *
 * @property kind Whether the id names a language or a contribution.
 * @property id The language id or contribution id.
 */
@Serializable
data class PluginTarget(
    val kind: PluginTargetKind,
    val id: String
) {
    /**
     * Renders the target as the single string used everywhere one is written down — URL
     * segments, the `target` claim of a session token, configuration keys, logs and errors.
     */
    override fun toString(): String = "${kind.wire}:$id"

    companion object {
        /**
         * Characters an id may consist of. Ids travel through URL segments, JWT claims and log
         * lines, so they are kept to a set that needs no escaping in any of them.
         */
        private val ID_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9_.-]*$")

        /**
         * Builds a target from its two parts, rejecting an id an address could not carry.
         *
         * @param kind The kind of target
         * @param id The language or contribution id
         * @return The target
         * @throws IllegalArgumentException if the id is empty or carries unusable characters
         */
        fun of(kind: PluginTargetKind, id: String): PluginTarget =
            ofOrNull(kind, id) ?: throw IllegalArgumentException("Invalid plugin target id '$id'")

        /**
         * Builds a target from its two parts.
         *
         * @param kind The kind of target
         * @param id The language or contribution id
         * @return The target, or null when the id is empty or carries unusable characters
         */
        fun ofOrNull(kind: PluginTargetKind, id: String): PluginTarget? =
            if (ID_PATTERN.matches(id)) PluginTarget(kind, id) else null

        /**
         * Builds a target from a kind given as its wire string and an id, as they arrive in
         * separate URL segments.
         *
         * @param kind The wire string of the kind, e.g. `lang`
         * @param id The language or contribution id
         * @return The target, or null when the kind is unknown or the id unusable
         */
        fun ofOrNull(kind: String, id: String): PluginTarget? =
            PluginTargetKind.fromWire(kind)?.let { ofOrNull(it, id) }

        /**
         * Parses an address of the form `<kind>:<id>`.
         *
         * @param address The address to parse
         * @return The parsed target, or null when the address names no known kind or carries
         *         an id an address may not hold
         */
        fun parseOrNull(address: String): PluginTarget? {
            val separator = address.indexOf(':')
            if (separator < 0) return null
            return ofOrNull(address.substring(0, separator), address.substring(separator + 1))
        }

        /**
         * Parses an address, failing loudly.
         *
         * @param address The address to parse
         * @return The parsed target
         * @throws IllegalArgumentException if the address is not a valid target address
         */
        fun parse(address: String): PluginTarget =
            parseOrNull(address) ?: throw IllegalArgumentException(
                "Invalid plugin target '$address': expected '${PluginTargetKind.LANGUAGE.wire}:<id>' " +
                        "or '${PluginTargetKind.CONTRIBUTION.wire}:<id>'"
            )
    }
}

/**
 * A long-lived binary connection a plugin target accepts.
 *
 * The platform owns the address, the token, the lifetime, the liveness checks and the encoding
 * helpers. What travels inside is a protocol the plugin defines: the platform reads none of it
 * and imposes no envelope, correlation scheme or error shape on it.
 *
 * @property protocol Name of the protocol spoken on this session, owned by whoever defines the
 *           contract — `script-functions` belongs to the script language, not to the platform.
 * @property versions Protocol versions this side can speak, most preferred first.
 */
@Serializable
data class SessionType(
    val protocol: String,
    val versions: List<Int> = emptyList()
)
