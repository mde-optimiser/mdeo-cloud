import { randomUUID } from "node:crypto";
import { COMPRESSION_THRESHOLD_BYTES } from "../util/compression.js";
import { WebSocket } from "ws";
import {
    ExecutionWsErrorCodes,
    ExecutionWsRequestError,
    toExecutionWsUrl,
    type ExecutionWsMessage,
    type ExecutionWsRequest,
    type ExecutionWsResponse
} from "./protocol.js";

/**
 * Default idle lifetime of a pooled connection. Long enough that the bursts of requests one
 * user interaction produces all share a connection, short enough that an idle service is not
 * holding sockets open for sessions nobody is using.
 */
const DEFAULT_IDLE_TIMEOUT_MS = 180_000;

/**
 * Default per-request timeout. Bulk loads of a large result set are the slow case this has
 * to accommodate.
 */
const DEFAULT_REQUEST_TIMEOUT_MS = 300_000;

/**
 * How often the reaper looks for connections that have gone idle.
 */
const REAPER_INTERVAL_MS = 30_000;

/**
 * How long to wait for a connection to be established.
 */
const CONNECT_TIMEOUT_MS = 10_000;

/**
 * Largest message accepted on the execution endpoint. Result files are sent whole, and a
 * large model can be tens of megabytes.
 */
const MAX_PAYLOAD_BYTES = 512 * 1024 * 1024;

/**
 * Reports connection failures.
 *
 * These connections are opened from module scope by the handlers that proxy to an execution
 * service, long before any Fastify request context exists, so this writes to the console
 * rather than the service logger.
 */
const log = {
    warn: (message: string): void => {
        // eslint-disable-next-line no-console
        console.warn(`[execution-ws] ${message}`);
    }
};

/**
 * Configuration for an {@link ExecutionWsClient}.
 */
export interface ExecutionWsClientOptions {
    /**
     * How long a connection may sit without traffic before it is closed.
     */
    idleTimeoutMs?: number;
    /**
     * How long a single request may wait for its terminating response.
     */
    requestTimeoutMs?: number;
}

/**
 * A request awaiting its terminating response.
 */
interface PendingRequest {
    resolve: (response: ExecutionWsResponse) => void;
    reject: (error: Error) => void;
    onStream?: (message: ExecutionWsMessage) => void;
    timer: NodeJS.Timeout;
}

/**
 * A connection held open for reuse between requests to one target service.
 */
class PooledConnection {
    readonly pending = new Map<string, PendingRequest>();
    lastUsedAt = Date.now();
    private socket: WebSocket | undefined;
    private opening: Promise<WebSocket> | undefined;

    constructor(private readonly url: string) {}

    /**
     * Returns the open socket, dialling if this connection has none yet.
     *
     * @returns The open socket
     */
    async open(): Promise<WebSocket> {
        if (this.socket && this.socket.readyState === WebSocket.OPEN) {
            this.lastUsedAt = Date.now();
            return this.socket;
        }
        if (this.opening) {
            return this.opening;
        }

        this.opening = new Promise<WebSocket>((resolve, reject) => {
            const socket = new WebSocket(this.url, {
                maxPayload: MAX_PAYLOAD_BYTES,
                perMessageDeflate: { threshold: COMPRESSION_THRESHOLD_BYTES }
            });
            const timer = setTimeout(() => {
                log.warn(`timed out connecting to ${this.url} after ${CONNECT_TIMEOUT_MS}ms`);
                socket.terminate();
                reject(new Error(`Timed out connecting to ${this.url}`));
            }, CONNECT_TIMEOUT_MS);

            socket.on("open", () => {
                clearTimeout(timer);
                this.socket = socket;
                this.lastUsedAt = Date.now();
                resolve(socket);
            });
            socket.on("message", (raw) => this.handleMessage(String(raw)));
            socket.on("error", (error) => {
                clearTimeout(timer);
                log.warn(`connection to ${this.url} errored: ${String(error)}`);
                reject(error);
            });
            socket.on("close", () => {
                clearTimeout(timer);
                this.socket = undefined;
                this.opening = undefined;
                if (this.pending.size > 0) {
                    log.warn(`connection to ${this.url} closed with ${this.pending.size} request(s) in flight`);
                }
                this.drain(
                    new ExecutionWsRequestError(ExecutionWsErrorCodes.Unavailable, `Connection to ${this.url} closed`)
                );
            });
        }).finally(() => {
            this.opening = undefined;
        });

        return this.opening;
    }

    /**
     * Reports whether this connection is usable without redialling.
     *
     * @returns true if the socket is open
     */
    get isOpen(): boolean {
        return this.socket?.readyState === WebSocket.OPEN;
    }

    /**
     * Sends a message on this connection.
     *
     * @param message The message to send
     */
    async send(message: ExecutionWsRequest): Promise<void> {
        const socket = await this.open();
        this.lastUsedAt = Date.now();
        socket.send(JSON.stringify(message));
    }

    /**
     * Closes the socket and fails everything still waiting on it.
     */
    close(): void {
        this.drain(new ExecutionWsRequestError(ExecutionWsErrorCodes.Unavailable, `Connection to ${this.url} closed`));
        this.socket?.close();
        this.socket = undefined;
    }

    private handleMessage(raw: string): void {
        this.lastUsedAt = Date.now();

        let message: ExecutionWsMessage;
        try {
            message = JSON.parse(raw) as ExecutionWsMessage;
        } catch {
            return;
        }

        const pending = this.pending.get(message.requestId);
        if (!pending) {
            return;
        }

        if (message.messageType === "exec/response") {
            this.settle(message.requestId, () => pending.resolve(message));
        } else if (message.messageType === "exec/error") {
            this.settle(message.requestId, () =>
                pending.reject(new ExecutionWsRequestError(message.code, message.message))
            );
        } else {
            pending.onStream?.(message);
        }
    }

    private settle(requestId: string, action: () => void): void {
        const pending = this.pending.get(requestId);
        if (!pending) {
            return;
        }
        clearTimeout(pending.timer);
        this.pending.delete(requestId);
        action();
    }

    private drain(error: Error): void {
        for (const requestId of [...this.pending.keys()]) {
            const pending = this.pending.get(requestId);
            if (pending) {
                clearTimeout(pending.timer);
                this.pending.delete(requestId);
                pending.reject(error);
            }
        }
    }
}

/**
 * Request/response client for the execution protocol, over WebSocket connections that are
 * cached per target service and closed again once they go unused.
 *
 * A connection is opened on the first request to a target, shared by every request to that
 * target afterwards, and closed after the configured idle timeout. Nothing about a request
 * depends on connection state — each one carries its own authorization — so a connection
 * that was dropped in between is simply reopened, and two unrelated callers can share one
 * without either being able to observe the other.
 */
export class ExecutionWsClient {
    private readonly connections = new Map<string, PooledConnection>();
    private readonly idleTimeoutMs: number;
    private readonly requestTimeoutMs: number;
    private readonly reaper: NodeJS.Timeout;

    /**
     * Creates a new client.
     *
     * @param options Idle and request timeouts; sensible defaults are used when omitted
     */
    constructor(options: ExecutionWsClientOptions = {}) {
        this.idleTimeoutMs = options.idleTimeoutMs ?? DEFAULT_IDLE_TIMEOUT_MS;
        this.requestTimeoutMs = options.requestTimeoutMs ?? DEFAULT_REQUEST_TIMEOUT_MS;
        this.reaper = setInterval(() => this.reapIdleConnections(), REAPER_INTERVAL_MS);
        // The reaper must not be what keeps the process alive.
        this.reaper.unref?.();
    }

    /**
     * Sends a request to a target service and awaits its terminating response.
     *
     * @param baseUrl Base URL of the target service
     * @param build Builds the request message from the generated request id
     * @param onStream Invoked for each intermediate message, such as the per-file messages
     *        of a bulk load, before the terminating response arrives
     * @returns The successful response
     * @throws ExecutionWsRequestError if the peer answered with an error or could not be reached
     */
    async request(
        baseUrl: string,
        build: (requestId: string) => ExecutionWsRequest,
        onStream?: (message: ExecutionWsMessage) => void
    ): Promise<ExecutionWsResponse> {
        const url = toExecutionWsUrl(baseUrl);
        // A cached connection can be closed by the peer between the moment it is handed out
        // and the moment it is written to, which is indistinguishable from it never having
        // worked. Retrying once on a fresh connection separates the two.
        try {
            return await this.sendOnce(url, build, onStream);
        } catch (error) {
            if (error instanceof ExecutionWsRequestError && error.code !== ExecutionWsErrorCodes.Unavailable) {
                throw error;
            }
            this.connections.get(url)?.close();
            this.connections.delete(url);
            try {
                return await this.sendOnce(url, build, onStream);
            } catch (retryError) {
                if (retryError instanceof ExecutionWsRequestError) {
                    throw retryError;
                }
                throw new ExecutionWsRequestError(
                    ExecutionWsErrorCodes.Unavailable,
                    `Execution WebSocket request to ${url} failed: ${String(retryError)}`
                );
            }
        }
    }

    /**
     * Closes every pooled connection and stops the idle reaper.
     */
    close(): void {
        clearInterval(this.reaper);
        for (const connection of this.connections.values()) {
            connection.close();
        }
        this.connections.clear();
    }

    private async sendOnce(
        url: string,
        build: (requestId: string) => ExecutionWsRequest,
        onStream?: (message: ExecutionWsMessage) => void
    ): Promise<ExecutionWsResponse> {
        let connection = this.connections.get(url);
        if (!connection) {
            connection = new PooledConnection(url);
            this.connections.set(url, connection);
        }

        const requestId = randomUUID();
        return new Promise<ExecutionWsResponse>((resolve, reject) => {
            const timer = setTimeout(() => {
                connection!.pending.delete(requestId);
                log.warn(`request ${requestId} to ${url} timed out after ${this.requestTimeoutMs}ms`);
                reject(
                    new ExecutionWsRequestError(
                        ExecutionWsErrorCodes.Unavailable,
                        `Execution WebSocket request ${requestId} to ${url} timed out`
                    )
                );
            }, this.requestTimeoutMs);

            connection!.pending.set(requestId, { resolve, reject, onStream, timer });

            connection!.send(build(requestId)).catch((error: unknown) => {
                clearTimeout(timer);
                connection!.pending.delete(requestId);
                log.warn(`failed to send request ${requestId} to ${url}: ${String(error)}`);
                reject(
                    new ExecutionWsRequestError(
                        ExecutionWsErrorCodes.Unavailable,
                        `Failed to send execution WebSocket request to ${url}: ${String(error)}`
                    )
                );
            });
        });
    }

    private reapIdleConnections(): void {
        const now = Date.now();
        for (const [url, connection] of [...this.connections.entries()]) {
            const idleFor = now - connection.lastUsedAt;
            if (connection.pending.size > 0) {
                continue;
            }
            if (!connection.isOpen || idleFor >= this.idleTimeoutMs) {
                connection.close();
                this.connections.delete(url);
            }
        }
    }
}
