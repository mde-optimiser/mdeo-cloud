import { ErrorCodes } from "@mdeo/plugin";
import type { Server } from "node:http";
import { COMPRESSION_THRESHOLD_BYTES } from "../util/compression.js";
import { WebSocketServer, type WebSocket } from "ws";
import type { JwtAuthMiddleware } from "../auth/jwtAuth.js";
import { registerUpgradeRoute } from "./upgradeRouter.js";
import type { ExecutionHandler, ExecutionRequestContext } from "../execution/types.js";
import type { LangiumInstance } from "../langium/langiumInstance.js";
import {
    EXECUTION_WS_PATH,
    ExecutionWsFileType,
    ExecutionWsRequestError,
    type ExecutionFilesWsRequest,
    type ExecutionWsContext,
    type ExecutionWsFileEntry,
    type ExecutionWsMessage,
    type ExecutionWsRequest
} from "./protocol.js";

/**
 * Largest message accepted on the execution endpoint. Result files are sent whole, and a
 * large model can be tens of megabytes.
 */
const MAX_PAYLOAD_BYTES = 512 * 1024 * 1024;

/**
 * Everything needed to serve execution requests for one language.
 */
export interface ExecutionWsLanguageBinding {
    /**
     * The execution handler registered for the language.
     */
    handler: ExecutionHandler<unknown>;
    /**
     * Acquires a Langium instance configured for this request.
     *
     * @param jwt The token the request was authorized with
     * @param project The owning project
     */
    acquire(jwt: string, project: string): Promise<LangiumInstance<any>>;
    /**
     * Returns an instance to the pool.
     *
     * @param instance The instance to release
     */
    release(instance: LangiumInstance<any>): void;
}

/**
 * Dependencies of the execution WebSocket endpoint.
 */
export interface ExecutionWsServerDeps {
    /**
     * Verifies the token supplied with each request.
     */
    jwtAuth: JwtAuthMiddleware;
    /**
     * Resolves a language id to its execution binding, or undefined if this service does not
     * serve that language.
     */
    resolveLanguage(languageId: string): ExecutionWsLanguageBinding | undefined;
    /**
     * Where to report what goes wrong.
     */
    log: { warn(message: string): void; error(message: string): void };
}

/**
 * Serves the execution result endpoints of the shared execution protocol alongside the
 * service's HTTP routes.
 *
 * Reading an execution result over HTTP costs one request per file on every hop between the
 * browser and the execution service. This endpoint answers the same questions over a
 * connection the backend keeps between requests, and adds `exec/files`, which streams a whole
 * result set back under one request id.
 *
 * The connection itself is anonymous. Authorization comes from the token on each request, so
 * an idle connection can be dropped and reopened without anything being re-established.
 *
 * @param server The HTTP server the language service listens on
 * @param deps Authentication, language resolution, and logging
 * @returns The attached WebSocket server, for shutdown
 */
export function attachExecutionWebSocketServer(server: Server, deps: ExecutionWsServerDeps): WebSocketServer {
    const wss = new WebSocketServer({
        noServer: true,
        maxPayload: MAX_PAYLOAD_BYTES,
        perMessageDeflate: { threshold: COMPRESSION_THRESHOLD_BYTES }
    });

    registerUpgradeRoute(
        server,
        {
            matches: (path) => path === EXECUTION_WS_PATH,
            handle: (request, socket, head) => {
                wss.handleUpgrade(request, socket, head, (ws) => {
                    wss.emit("connection", ws, request);
                });
            }
        },
        deps.log
    );

    wss.on("connection", (socket: WebSocket) => {
        socket.on("message", (raw) => {
            void handleFrame(socket, String(raw), deps);
        });
        socket.on("error", (error) => {
            deps.log.warn(`Execution WebSocket connection error: ${String(error)}`);
        });
    });

    return wss;
}

/**
 * Decodes one frame and dispatches it.
 *
 * Requests on one connection are handled concurrently and answered out of order — each is
 * correlated by its own request id — so a bulk load streaming hundreds of files does not
 * hold up a small request that arrives behind it. Failures are turned into `exec/error`
 * responses rather than closing the connection, so one bad request does not cost every
 * other caller sharing it their work.
 */
async function handleFrame(socket: WebSocket, raw: string, deps: ExecutionWsServerDeps): Promise<void> {
    let message: ExecutionWsMessage;
    try {
        message = JSON.parse(raw) as ExecutionWsMessage;
    } catch (error) {
        deps.log.warn(`Discarding undecodable execution WebSocket frame: ${String(error)}`);
        return;
    }

    if (
        message.messageType === "exec/response" ||
        message.messageType === "exec/error" ||
        message.messageType === "exec/fileData"
    ) {
        deps.log.warn(`Ignoring response-shaped message ${message.requestId} received by a server`);
        return;
    }

    const request = message;
    const label = `${request.messageType}[${request.requestId}]`;
    try {
        await handleRequest(socket, request, deps);
    } catch (error) {
        if (error instanceof ExecutionWsRequestError) {
            deps.log.warn(`Execution WS request ${label} rejected with ${error.code}: ${error.message}`);
            send(socket, {
                messageType: "exec/error",
                requestId: request.requestId,
                error: { code: error.code, message: error.message }
            });
            return;
        }
        deps.log.error(
            `Execution WS request ${label} failed: ` +
                (error instanceof Error ? (error.stack ?? error.message) : String(error))
        );
        send(socket, {
            messageType: "exec/error",
            requestId: request.requestId,
            error: {
                code: ErrorCodes.Internal,
                message: error instanceof Error ? error.message : "Internal error"
            }
        });
    }
}

async function handleRequest(
    socket: WebSocket,
    request: ExecutionWsRequest,
    deps: ExecutionWsServerDeps
): Promise<void> {
    const context = request.context;
    const jwt = await authorize(request, deps);

    const languageId = context.languageId;
    if (!languageId) {
        throw new ExecutionWsRequestError(ErrorCodes.BadRequest, "Request is missing a language id");
    }

    const binding = deps.resolveLanguage(languageId);
    if (!binding) {
        throw new ExecutionWsRequestError(ErrorCodes.NotFound, `Unknown language: ${languageId}`);
    }

    const project = context.projectId ?? undefined;
    if (!project) {
        throw new ExecutionWsRequestError(ErrorCodes.BadRequest, "Request is missing a project id");
    }

    const instance = await binding.acquire(jwt, project);
    const requestContext: ExecutionRequestContext = {
        executionId: context.executionId,
        project,
        jwt,
        metadata: context.metadata ?? undefined,
        instance,
        serverApi: instance.services.shared.ServerApi
    };

    try {
        switch (request.messageType) {
            case "exec/summary": {
                const summary = await binding.handler.getSummary(requestContext);
                respond(socket, request.requestId, { summary });
                return;
            }
            case "exec/fileTree": {
                const files = (await binding.handler.getFileTree(requestContext)) as unknown as ExecutionWsFileEntry[];
                respond(socket, request.requestId, { files });
                return;
            }
            case "exec/file": {
                const content = await binding.handler.getFile(requestContext, request.path);
                respond(socket, request.requestId, { content: content.toString("utf-8") });
                return;
            }
            case "exec/files": {
                const files = await streamFiles(socket, request, requestContext, binding);
                respond(socket, request.requestId, { files });
                return;
            }
            case "exec/cancel": {
                await binding.handler.cancel(requestContext);
                respond(socket, request.requestId, null);
                return;
            }
            case "exec/delete": {
                await binding.handler.delete(requestContext);
                respond(socket, request.requestId, null);
                return;
            }
        }
    } finally {
        binding.release(instance);
    }
}

/**
 * Streams the requested files of an execution back under the request's id.
 *
 * A handler that implements `getFiles` forwards the bulk request to whatever it proxies, so
 * the hop below this one is also a single request. Handlers that do not fall back to reading
 * the tree and each file individually, which still collapses the hop *above* this one to a
 * single request.
 *
 * @returns The entries that were actually sent
 */
async function streamFiles(
    socket: WebSocket,
    request: ExecutionFilesWsRequest,
    context: ExecutionRequestContext,
    binding: ExecutionWsLanguageBinding
): Promise<ExecutionWsFileEntry[]> {
    const requested = request.paths ?? null;
    const sendFile = (path: string, content: string): void => {
        send(socket, { messageType: "exec/fileData", requestId: request.requestId, path, content });
    };

    if (binding.handler.getFiles) {
        return binding.handler.getFiles(context, requested, sendFile);
    }

    const tree = (await binding.handler.getFileTree(context)) as unknown as ExecutionWsFileEntry[];
    const wanted = requested === null ? null : new Set(requested);
    const sent: ExecutionWsFileEntry[] = [];

    for (const entry of tree) {
        if (entry.type !== ExecutionWsFileType.FILE) {
            sent.push(entry);
            continue;
        }
        if (wanted !== null && !wanted.has(entry.name)) {
            continue;
        }
        // A file that cannot be read is skipped rather than failing the whole load: a
        // partially readable result set is still worth showing, and the tree the caller
        // receives back tells it exactly which files it did not get.
        try {
            const content = await binding.handler.getFile(context, entry.name);
            sendFile(entry.name, content.toString("utf-8"));
            sent.push(entry);
        } catch {
            continue;
        }
    }

    return sent;
}

/**
 * Verifies the token a request carries and checks it grants the scope that request needs.
 *
 * @returns The raw token, to be forwarded to the hop below
 */
async function authorize(request: ExecutionWsRequest, deps: ExecutionWsServerDeps): Promise<string> {
    const context: ExecutionWsContext = request.context;
    const token = context.auth ?? undefined;

    let claims;
    try {
        claims = await deps.jwtAuth.verifyToken(token);
    } catch (error) {
        throw new ExecutionWsRequestError(
            ErrorCodes.Forbidden,
            `Missing or invalid token: ${error instanceof Error ? error.message : String(error)}`
        );
    }

    const requiredScope =
        request.messageType === "exec/cancel"
            ? "plugin:execution:cancel"
            : request.messageType === "exec/delete"
              ? "plugin:execution:delete"
              : "plugin:execution:read";

    if (!claims.scope?.includes(requiredScope)) {
        throw new ExecutionWsRequestError(ErrorCodes.Forbidden, `Token missing ${requiredScope} scope`);
    }

    // The token is issued for one execution; a connection shared by several of them must not
    // let a token for one address the results of another.
    if (claims.executionId && claims.executionId !== context.executionId) {
        throw new ExecutionWsRequestError(ErrorCodes.Forbidden, "Token is not valid for this execution");
    }
    if (claims.projectId && context.projectId && claims.projectId !== context.projectId) {
        throw new ExecutionWsRequestError(ErrorCodes.Forbidden, "Token is not valid for this project");
    }

    return token!;
}

function respond(socket: WebSocket, requestId: string, data: unknown): void {
    send(socket, { messageType: "exec/response", requestId, data });
}

function send(socket: WebSocket, message: ExecutionWsMessage): void {
    if (socket.readyState === socket.OPEN) {
        socket.send(JSON.stringify(message));
    }
}
