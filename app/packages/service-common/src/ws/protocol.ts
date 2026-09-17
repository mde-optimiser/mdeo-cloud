import type { ApiError } from "@mdeo/plugin";

/**
 * Message protocol for execution result access over WebSocket.
 *
 * This is the TypeScript side of the protocol defined in the platform's Kotlin `common`
 * module (`com.mdeo.common.transport.ExecutionWsProtocol`). The same messages are spoken on
 * all three hops of an execution result read — workbench to backend, backend to language
 * plugin service, language plugin service to execution service — so a language service is
 * both a server for the hop above it and a client for the hop below.
 *
 * The two service-to-service hops hold no session state: every request repeats the bearer
 * token that authorizes it in {@link ExecutionWsContext.auth}. That is what makes the
 * connections underneath poolable, shareable between unrelated requests, and safe to drop
 * once idle.
 */

/**
 * Path the execution WebSocket endpoint is served under on plugin and execution services.
 */
export const EXECUTION_WS_PATH = "/ws/executions";

/**
 * Everything a peer needs to authorize and route a single execution request.
 */
export interface ExecutionWsContext {
    /**
     * The execution being addressed.
     */
    executionId: string;
    /**
     * Bearer token authorizing this single request, or null on session-authenticated hops.
     */
    auth?: string | null;
    /**
     * The owning project, used by the backend to check the caller's permissions.
     */
    projectId?: string | null;
    /**
     * Language plugin that owns the execution, used to route within a plugin service.
     */
    languageId?: string | null;
    /**
     * Execution metadata the backend forwards so downstream services can locate results.
     */
    metadata?: Record<string, unknown> | null;
}

/**
 * Base shape of every execution WebSocket message.
 */
export interface ExecutionWsMessageBase {
    /**
     * Discriminator identifying the message type.
     */
    messageType: string;
    /**
     * Correlates a request with its responses; unique per connection.
     */
    requestId: string;
}

/**
 * Requests the markdown summary of an execution.
 */
export interface ExecutionSummaryWsRequest extends ExecutionWsMessageBase {
    messageType: "exec/summary";
    context: ExecutionWsContext;
}

/**
 * Requests the result file tree of an execution.
 */
export interface ExecutionFileTreeWsRequest extends ExecutionWsMessageBase {
    messageType: "exec/fileTree";
    context: ExecutionWsContext;
}

/**
 * Requests a single result file of an execution.
 */
export interface ExecutionFileWsRequest extends ExecutionWsMessageBase {
    messageType: "exec/file";
    context: ExecutionWsContext;
    /**
     * Path of the file within the execution results.
     */
    path: string;
}

/**
 * Requests the file tree and the contents of result files in one round trip.
 *
 * Each file arrives as its own {@link ExecutionFileDataMessage}, so the receiver can
 * populate its cache progressively, and the terminating {@link ExecutionWsResponse} carries
 * an {@link ExecutionFileTreePayload} describing everything that was sent.
 */
export interface ExecutionFilesWsRequest extends ExecutionWsMessageBase {
    messageType: "exec/files";
    context: ExecutionWsContext;
    /**
     * Files to include, or null/absent to include every file in the result tree.
     */
    paths?: string[] | null;
}

/**
 * Requests cancellation of a running execution.
 */
export interface ExecutionCancelWsRequest extends ExecutionWsMessageBase {
    messageType: "exec/cancel";
    context: ExecutionWsContext;
}

/**
 * Requests deletion of an execution and its results.
 */
export interface ExecutionDeleteWsRequest extends ExecutionWsMessageBase {
    messageType: "exec/delete";
    context: ExecutionWsContext;
}

/**
 * Terminating success response for a request.
 */
export interface ExecutionWsResponse extends ExecutionWsMessageBase {
    messageType: "exec/response";
    /**
     * The payload, or null for requests that return nothing.
     */
    data?: unknown;
}

/**
 * Terminating failure response for a request.
 */
export interface ExecutionWsError extends ExecutionWsMessageBase {
    messageType: "exec/error";
    /**
     * What went wrong, in the platform's error shape.
     */
    error: ApiError;
}

/**
 * One file of a streaming {@link ExecutionFilesWsRequest} response.
 */
export interface ExecutionFileDataMessage extends ExecutionWsMessageBase {
    messageType: "exec/fileData";
    /**
     * Path of the file within the execution results.
     */
    path: string;
    /**
     * The file's text content.
     */
    content: string;
}

/**
 * Any request a peer may send.
 */
export type ExecutionWsRequest =
    | ExecutionSummaryWsRequest
    | ExecutionFileTreeWsRequest
    | ExecutionFileWsRequest
    | ExecutionFilesWsRequest
    | ExecutionCancelWsRequest
    | ExecutionDeleteWsRequest;

/**
 * Any message that may travel over an execution WebSocket connection.
 */
export type ExecutionWsMessage = ExecutionWsRequest | ExecutionWsResponse | ExecutionWsError | ExecutionFileDataMessage;

/**
 * Entry in an execution result file tree.
 */
export interface ExecutionWsFileEntry {
    /**
     * Path of the file or directory within the execution results.
     */
    name: string;
    /**
     * 1 for a file, 2 for a directory.
     */
    type: number;
}

/**
 * File entry type constants, matching the platform's `FileType`.
 */
export const ExecutionWsFileType = {
    FILE: 1,
    DIRECTORY: 2
} as const;

/**
 * Payload of a successful summary request.
 */
export interface ExecutionSummaryPayload {
    /**
     * Markdown-formatted summary of the execution.
     */
    summary: string;
}

/**
 * Payload of a successful file tree request, and the terminator of a streamed bulk load.
 */
export interface ExecutionFileTreePayload {
    /**
     * Flat list of entries in the execution result tree.
     */
    files: ExecutionWsFileEntry[];
}

/**
 * Payload of a successful single-file request.
 */
export interface ExecutionFilePayload {
    /**
     * The file's text content.
     */
    content: string;
}

/**
 * Failure of an execution WebSocket request, carrying the protocol error code so that the
 * originating failure keeps its meaning as it is relayed back up the chain of hops.
 */
export class ExecutionWsRequestError extends Error {
    /**
     * Creates a new error.
     *
     * @param code One of `ErrorCodes` from `@mdeo/plugin`
     * @param message Human-readable description
     */
    constructor(
        public readonly code: string,
        message: string
    ) {
        super(message);
        this.name = "ExecutionWsRequestError";
    }
}

/**
 * Converts an `http(s)` service base URL into the `ws(s)` URL of its execution endpoint.
 *
 * @param baseUrl Base URL of the target service
 * @returns The WebSocket URL of that service's execution endpoint
 */
export function toExecutionWsUrl(baseUrl: string): string {
    const trimmed = baseUrl.replace(/\/+$/, "");
    let withScheme = trimmed;
    if (trimmed.startsWith("https://")) {
        withScheme = `wss://${trimmed.slice("https://".length)}`;
    } else if (trimmed.startsWith("http://")) {
        withScheme = `ws://${trimmed.slice("http://".length)}`;
    }
    return withScheme + EXECUTION_WS_PATH;
}
