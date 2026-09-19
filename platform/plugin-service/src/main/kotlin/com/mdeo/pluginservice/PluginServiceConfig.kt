package com.mdeo.pluginservice

/**
 * Where a plugin service listens and how it reaches the backend.
 *
 * The environment variables are the same ones the TypeScript services read, so a Kotlin plugin
 * service is deployed exactly like any other plugin service.
 *
 * @property port Port to listen on
 * @property host Host to bind to
 * @property backendApiUrl Base URL of the backend API, e.g. `http://backend:8080/api`. Session
 *           tokens are verified against the keys it publishes at `/.well-known/jwks.json`.
 * @property jwtIssuer Issuer every accepted token must name
 * @property maxSessions How many sessions may be open at once; a session beyond that is refused
 */
data class PluginServiceConfig(
    val port: Int = DEFAULT_PORT,
    val host: String = DEFAULT_HOST,
    val backendApiUrl: String = DEFAULT_BACKEND_API_URL,
    val jwtIssuer: String = DEFAULT_JWT_ISSUER,
    val maxSessions: Int = DEFAULT_MAX_SESSIONS
) {
    companion object {
        const val DEFAULT_PORT = 3000
        const val DEFAULT_HOST = "0.0.0.0"
        const val DEFAULT_BACKEND_API_URL = "http://localhost:8080/api"
        const val DEFAULT_JWT_ISSUER = "mdeo-platform"
        const val DEFAULT_MAX_SESSIONS = 64

        /**
         * Reads the configuration from `PORT`, `HOST`, `BACKEND_API_URL`, `JWT_ISSUER` and
         * `MAX_SESSIONS`, falling back to the defaults for anything unset.
         *
         * @param environment Where to read from; the process environment unless a test says otherwise
         * @return The configuration
         * @throws IllegalArgumentException when a numeric variable is set to something else than a
         *         whole number in its range
         */
        fun fromEnvironment(environment: Map<String, String> = System.getenv()): PluginServiceConfig {
            return PluginServiceConfig(
                port = readInteger(environment, "PORT", DEFAULT_PORT, minimum = 0),
                host = environment["HOST"] ?: DEFAULT_HOST,
                backendApiUrl = environment["BACKEND_API_URL"] ?: DEFAULT_BACKEND_API_URL,
                jwtIssuer = environment["JWT_ISSUER"] ?: DEFAULT_JWT_ISSUER,
                maxSessions = readInteger(environment, "MAX_SESSIONS", DEFAULT_MAX_SESSIONS, minimum = 1)
            )
        }

        /**
         * Reads a whole number from the environment.
         *
         * A value that is not one, or is below the minimum, is a configuration mistake. It is
         * refused at startup rather than replaced by the default, which would hide the mistake.
         *
         * @param environment Where to read from
         * @param name The variable
         * @param fallback The value when the variable is not set
         * @param minimum The smallest accepted value
         * @return The value
         * @throws IllegalArgumentException when the variable is set to something else
         */
        private fun readInteger(environment: Map<String, String>, name: String, fallback: Int, minimum: Int): Int {
            val raw = environment[name]?.trim()
            if (raw.isNullOrEmpty()) return fallback
            val value = raw.toIntOrNull()
            require(value != null && value >= minimum) {
                "$name must be a whole number of at least $minimum, but is '$raw'"
            }
            return value
        }
    }
}
