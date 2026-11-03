package com.mdeo.common.auth

/**
 * The scopes a platform token can carry, mirrored by `Scopes` in `@mdeo/plugin`.
 *
 * Each scope grants exactly one capability, and every service that serves that capability checks
 * the same scope — a token forwarded from one hop to the next is accepted for the same thing at
 * each. Scopes checked by the backend name the resource; scopes checked by plugin and execution
 * services start with `plugin:`.
 */
object Scopes {
    /** Read the project's files from the backend. */
    const val FILES_READ = "files:read"

    /** Read computed file data from the backend. */
    const val FILE_DATA_READ = "file-data:read"

    /** Report an execution's state and metadata to the backend. */
    const val EXECUTION_WRITE = "execution:write"

    /** Ask the backend for the token that opens a session. */
    const val SESSION_OPEN = "session:open"

    /** Ask the backend which contribution plugins a `lang:` session loads. */
    const val SESSION_CONTRIBUTIONS_READ = "session:contributions:read"

    /** Compute file data on a plugin service. */
    const val PLUGIN_FILE_DATA_COMPUTE = "plugin:file-data:compute"

    /** Send a request to a language plugin's request handler, directly or through the backend. */
    const val PLUGIN_REQUEST_SEND = "plugin:request:send"

    /** Start an execution on a plugin service and on the execution service it forwards to. */
    const val PLUGIN_EXECUTION_START = "plugin:execution:start"

    /** Read an execution's summary and result files. */
    const val PLUGIN_EXECUTION_READ = "plugin:execution:read"

    /** Cancel a running execution. */
    const val PLUGIN_EXECUTION_CANCEL = "plugin:execution:cancel"

    /** Delete an execution and its results. */
    const val PLUGIN_EXECUTION_DELETE = "plugin:execution:delete"

    /** Open a session on a plugin target. */
    const val PLUGIN_SESSION_CONNECT = "plugin:session:connect"
}
