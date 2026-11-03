/**
 * The scopes a platform token can carry, mirrored by `Scopes` in the Kotlin `common` module.
 *
 * Each scope grants exactly one capability, and every service that serves that capability checks
 * the same scope — a token forwarded from one hop to the next is accepted for the same thing at
 * each. Scopes checked by the backend name the resource; scopes checked by plugin and execution
 * services start with `plugin:`.
 */
export const Scopes = {
    /** Read the project's files from the backend. */
    FilesRead: "files:read",
    /** Read computed file data from the backend. */
    FileDataRead: "file-data:read",
    /** Report an execution's state and metadata to the backend. */
    ExecutionWrite: "execution:write",
    /** Ask the backend for the token that opens a session. */
    SessionOpen: "session:open",
    /** Ask the backend which contribution plugins a `lang:` session loads. */
    SessionContributionsRead: "session:contributions:read",
    /** Compute file data on a plugin service. */
    PluginFileDataCompute: "plugin:file-data:compute",
    /** Send a request to a language plugin's request handler, directly or through the backend. */
    PluginRequestSend: "plugin:request:send",
    /** Start an execution on a plugin service and on the execution service it forwards to. */
    PluginExecutionStart: "plugin:execution:start",
    /** Read an execution's summary and result files. */
    PluginExecutionRead: "plugin:execution:read",
    /** Cancel a running execution. */
    PluginExecutionCancel: "plugin:execution:cancel",
    /** Delete an execution and its results. */
    PluginExecutionDelete: "plugin:execution:delete",
    /** Open a session on a plugin target. */
    PluginSessionConnect: "plugin:session:connect"
} as const;
