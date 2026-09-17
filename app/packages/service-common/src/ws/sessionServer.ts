import type { IncomingMessage, Server } from "node:http";
import { COMPRESSION_THRESHOLD_BYTES } from "../util/compression.js";
import { WebSocketServer, type WebSocket } from "ws";
import { parsePluginTarget, Scopes, type PluginTarget, type SessionType } from "@mdeo/plugin";
import type { JwtAuthMiddleware, JwtClaims } from "../auth/jwtAuth.js";
import type { LangiumInstance } from "../langium/langiumInstance.js";
import type { HttpServerApi } from "../service/serverApi.js";
import { registerUpgradeRoute, type UpgradeLog } from "./upgradeRouter.js";

/**
 * Path prefix every session endpoint lives under, followed by `<kind>/<targetId>/<name>`.
 */
export const SESSION_WS_PATH_PREFIX = "/ws/sessions/";

/**
 * Close codes the platform itself uses.
 *
 * Everything a plugin closes a session with is its own business; these are the refusals that
 * happen before any plugin code runs, and the one liveness failure the platform detects.
 */
export const SessionCloseCodes = {
    /**
     * The token was missing, malformed, expired, or not for this session.
     */
    Unauthorized: 4401,
    /**
     * No such target or session in this project.
     */
    NotFound: 4404,
    /**
     * No protocol version both sides can speak.
     */
    VersionMismatch: 4409,
    /**
     * The service has no capacity to hold another session right now.
     */
    Unavailable: 4503,
    /**
     * The peer stopped answering keepalives.
     */
    Unresponsive: 4408
} as const;

/**
 * How often a keepalive is sent on an idle session.
 *
 * Sessions are exempt from request timeouts — a call can legitimately take as long as the work
 * takes — so liveness is what tells the two sides the connection is still real. The reverse
 * proxy in front of a plugin drops connections idle for an hour, which makes this mandatory
 * rather than merely useful.
 */
const PING_INTERVAL_MS = 30_000;

/**
 * How long a peer may go without answering a keepalive before the session is closed.
 */
const PONG_TIMEOUT_MS = 90_000;

/**
 * Largest message accepted on a session.
 */
const MAX_PAYLOAD_BYTES = 512 * 1024 * 1024;

/**
 * What a handler is given when a session opens.
 *
 * The platform owns everything here: who is calling, what was negotiated, how to write to the
 * connection and how to end it. What is written is a protocol the plugin defines — the platform
 * imposes no envelope, no correlation scheme and no error shape on it.
 */
export interface SessionContext {
    /**
     * The project the session belongs to.
     */
    projectId: string;
    /**
     * The execution the session belongs to. A session outlives no execution.
     */
    executionId: string;
    /**
     * The addressed target.
     */
    target: PluginTarget;
    /**
     * The session name, the last segment of the address.
     */
    sessionName: string;
    /**
     * The protocol version both sides agreed on at the handshake.
     */
    version: number;
    /**
     * The token the session was opened with.
     */
    jwt: string;
    /**
     * Backend access for this session, already pointed at the caller's project.
     */
    serverApi: HttpServerApi;
    /**
     * The Langium instance this session owns, for a `lang:` target. Held for the lifetime of
     * the connection and reset back into the pool when it closes.
     */
    instance?: LangiumInstance<any>;
    /**
     * Sends one message to the peer.
     *
     * @param data The bytes to send, encoded by whatever the plugin's protocol uses
     */
    send(data: Uint8Array): void;
    /**
     * Ends the session.
     *
     * @param reason Why, reported to the peer
     */
    close(reason?: string): void;
}

/**
 * The plugin's side of one open session.
 */
export interface SessionPeer {
    /**
     * Handles one message from the caller.
     *
     * @param data The received bytes
     */
    onMessage(data: Uint8Array): void;
    /**
     * Handles the session ending, from either side.
     *
     * @param reason Why the session ended
     */
    onClose(reason: string): void;
}

/**
 * Serves one declared session type.
 */
export interface SessionHandler {
    /**
     * Starts one session.
     *
     * @param ctx Everything the platform knows about this connection
     * @returns The peer that handles its traffic
     */
    open(ctx: SessionContext): SessionPeer;
}

/**
 * The session handlers a service registers, keyed by target address and then by session name.
 *
 * The address is the same `<kind>:<id>` string the plugin manifest and the connect endpoint
 * use, so a registration is looked up with exactly what the caller asked for.
 */
export type SessionHandlers = Record<string, Record<string, SessionHandler>>;

/**
 * Everything the session endpoint needs from the service around it.
 */
export interface SessionServerDeps {
    /**
     * Verifies the token a connection is opened with.
     */
    jwtAuth: JwtAuthMiddleware;
    /**
     * The handlers this service registered.
     */
    handlers: SessionHandlers;
    /**
     * Resolves the session type a target declares, so the server can negotiate against what the
     * manifest advertises rather than against a second copy of it.
     *
     * @param address The target address, e.g. `lang:script`
     * @param sessionName The session name
     */
    resolveSessionType(address: string, sessionName: string): SessionType | undefined;
    /**
     * Takes a Langium instance out of the pool for the lifetime of a `lang:` session.
     *
     * The instance has the project's contribution plugins loaded, exactly like the instance a
     * request to the same language would get.
     *
     * @param address The `lang:` target and session being opened
     * @param jwt The token the session was opened with
     * @param project The owning project
     * @throws {LangiumPoolExhaustedError} When the service has no capacity for another session
     * @throws When the contribution plugins could not be fetched
     */
    acquireLanguageInstance(
        address: { languageId: string; sessionName: string },
        jwt: string,
        project: string
    ): Promise<LangiumInstance<any>>;
    /**
     * Returns the instance a closing session held.
     *
     * @param languageId The language the session addressed
     * @param instance The instance to return
     */
    releaseLanguageInstance(languageId: string, instance: LangiumInstance<any>): void;
    /**
     * Backend access for sessions that own no Langium instance.
     *
     * @param jwt The token the session was opened with
     * @param project The owning project
     */
    createServerApi(jwt: string, project: string): HttpServerApi;
    /**
     * Where to report what goes wrong.
     */
    log: { warn(message: string): void; error(message: string): void };
}

/**
 * Serves the session endpoint of a language service.
 *
 * A session is just a name for a connection. This endpoint owns the address it is reached at,
 * the token that authorizes it, the version both sides agreed to speak, and the keepalives that
 * decide it is still alive. It reads nothing that travels on it.
 *
 * The connection is authorized once, at the handshake, unlike the execution endpoint where each
 * request carries its own token: a session is opened for one execution against one target, and
 * it carries that one conversation for its whole life.
 *
 * @param server The HTTP server the language service listens on
 * @param deps Authentication, handler registry, instance management and logging
 * @returns The attached WebSocket server, for shutdown
 */
export function attachSessionServer(server: Server, deps: SessionServerDeps): WebSocketServer {
    const wss = new WebSocketServer({
        noServer: true,
        maxPayload: MAX_PAYLOAD_BYTES,
        perMessageDeflate: { threshold: COMPRESSION_THRESHOLD_BYTES }
    });

    const log: UpgradeLog = deps.log;

    registerUpgradeRoute(
        server,
        {
            matches: (path) => path.startsWith(SESSION_WS_PATH_PREFIX),
            handle: (request, socket, head) => {
                wss.handleUpgrade(request, socket, head, (ws) => {
                    openSession(ws, request, deps).catch((error: unknown) => {
                        deps.log.error(
                            `Session setup failed: ${error instanceof Error ? (error.stack ?? error.message) : String(error)}`
                        );
                        ws.terminate();
                    });
                });
            }
        },
        log
    );

    return wss;
}

/**
 * The parts of a session address, as they appear in the path.
 */
interface SessionAddress {
    target: PluginTarget;
    sessionName: string;
}

/**
 * Reads the target and session name out of a request path.
 *
 * @param path The request path, without its query string
 * @returns The parsed address, or undefined when the path is not a session address
 */
function parseSessionPath(path: string): SessionAddress | undefined {
    const rest = path.substring(SESSION_WS_PATH_PREFIX.length);
    const segments = rest.split("/").filter((segment) => segment.length > 0);
    if (segments.length !== 3) {
        return undefined;
    }
    const [kind, targetId, sessionName] = segments;
    try {
        return { target: parsePluginTarget(`${kind}:${targetId}`), sessionName };
    } catch {
        return undefined;
    }
}

/**
 * Authorizes one connection and hands it to its handler.
 *
 * Every refusal closes the socket with a code and a reason that names the thing that did not
 * resolve, because the author of a contribution reads these at execution start and has to be
 * able to tell a missing declaration from a version they cannot speak.
 */
async function openSession(socket: WebSocket, request: IncomingMessage, deps: SessionServerDeps): Promise<void> {
    const url = new URL(request.url ?? "", "http://localhost");
    const address = parseSessionPath(url.pathname);
    if (!address) {
        refuse(socket, SessionCloseCodes.NotFound, "Not a session address", deps);
        return;
    }

    const targetAddress = `${address.target.kind}:${address.target.id}`;
    const label = `${targetAddress}/${address.sessionName}`;

    const token = readToken(request);
    let claims: JwtClaims;
    try {
        claims = await deps.jwtAuth.verifyToken(token);
    } catch (error) {
        refuse(
            socket,
            SessionCloseCodes.Unauthorized,
            `Missing or invalid token: ${error instanceof Error ? error.message : String(error)}`,
            deps
        );
        return;
    }

    if (!claims.scope?.includes(Scopes.PluginSessionConnect)) {
        refuse(socket, SessionCloseCodes.Unauthorized, `Token missing ${Scopes.PluginSessionConnect} scope`, deps);
        return;
    }

    // The token names the one session it opens, so a token issued for one target cannot be
    // spent on another served by the same service.
    const claimedTarget = typeof claims.target === "string" ? claims.target : undefined;
    const claimedSession = typeof claims.session === "string" ? claims.session : undefined;
    if (claimedTarget !== targetAddress || claimedSession !== address.sessionName) {
        refuse(
            socket,
            SessionCloseCodes.Unauthorized,
            `Token is for ${claimedTarget ?? "no target"}/${claimedSession ?? "no session"}, not for ${label}`,
            deps
        );
        return;
    }

    const projectId = claims.projectId;
    const executionId = claims.executionId;
    if (!projectId || !executionId) {
        refuse(socket, SessionCloseCodes.Unauthorized, "Token names no project or no execution", deps);
        return;
    }

    const declared = deps.resolveSessionType(targetAddress, address.sessionName);
    const handler = deps.handlers[targetAddress]?.[address.sessionName];
    if (!declared || !handler) {
        refuse(socket, SessionCloseCodes.NotFound, `This service serves no session ${label}`, deps);
        return;
    }

    const version = negotiateVersion(url, declared);
    if (version == undefined) {
        refuse(
            socket,
            SessionCloseCodes.VersionMismatch,
            `Session ${label} speaks ${declared.protocol} ` +
                `version ${declared.versions.join(", ")}, and none of those was requested`,
            deps
        );
        return;
    }

    let instance: LangiumInstance<any> | undefined = undefined;
    if (address.target.kind === "lang") {
        try {
            instance = await deps.acquireLanguageInstance(
                { languageId: address.target.id, sessionName: address.sessionName },
                token!,
                projectId
            );
        } catch (error) {
            refuse(
                socket,
                SessionCloseCodes.Unavailable,
                error instanceof Error ? error.message : "No capacity for another session",
                deps
            );
            return;
        }
    }

    if (socket.readyState !== socket.OPEN) {
        // The caller gave up while its token was checked or the contribution plugins were fetched.
        if (instance) {
            deps.releaseLanguageInstance(address.target.id, instance);
        }
        return;
    }

    const serverApi = instance ? instance.services.shared.ServerApi : deps.createServerApi(token!, projectId);

    let closed = false;
    const release = (): void => {
        if (closed) {
            return;
        }
        closed = true;
        if (instance) {
            deps.releaseLanguageInstance(address.target.id, instance);
        }
    };

    const context: SessionContext = {
        projectId,
        executionId,
        target: address.target,
        sessionName: address.sessionName,
        version,
        jwt: token!,
        serverApi,
        instance,
        send: (data) => {
            if (socket.readyState === socket.OPEN) {
                socket.send(data);
            }
        },
        close: (reason) => {
            closeSocket(socket, 1000, reason ?? "Closed by handler");
        }
    };

    let peer: SessionPeer;
    try {
        peer = handler.open(context);
    } catch (error) {
        deps.log.error(`Session ${label} failed to open: ${error instanceof Error ? error.message : String(error)}`);
        release();
        refuse(socket, SessionCloseCodes.Unavailable, "Session could not be opened", deps);
        return;
    }

    let lastPong = Date.now();
    const keepalive = setInterval(() => {
        if (socket.readyState === socket.CLOSED) {
            clearInterval(keepalive);
            return;
        }
        if (Date.now() - lastPong > PONG_TIMEOUT_MS) {
            deps.log.warn(`Session ${label} stopped answering keepalives; closing`);
            closeSocket(socket, SessionCloseCodes.Unresponsive, "No keepalive response");
            socket.terminate();
            return;
        }
        if (socket.readyState === socket.OPEN) {
            socket.ping();
        }
    }, PING_INTERVAL_MS);
    keepalive.unref?.();

    socket.on("pong", () => {
        lastPong = Date.now();
    });

    socket.on("message", (raw, isBinary) => {
        // Anything the handler fetches while serving this message has to go out under the
        // session's own authorization, which `configure` pinned once at the handshake.
        instance?.refreshContext(token!, projectId);
        try {
            peer.onMessage(toBytes(raw, isBinary));
        } catch (error) {
            deps.log.error(
                `Session ${label} failed on a message: ${error instanceof Error ? (error.stack ?? error.message) : String(error)}`
            );
        }
    });

    socket.on("error", (error) => {
        deps.log.warn(`Session ${label} connection error: ${String(error)}`);
    });

    socket.on("close", (code, reason) => {
        clearInterval(keepalive);
        release();
        try {
            peer.onClose(reason.length > 0 ? reason.toString("utf-8") : `Closed with code ${code}`);
        } catch (error) {
            deps.log.error(
                `Session ${label} failed while closing: ${error instanceof Error ? error.message : String(error)}`
            );
        }
    });
}

/**
 * Picks the protocol version both sides can speak.
 *
 * The caller names the versions it can speak, most preferred first; the first of those the
 * plugin also declares wins. A caller that names none is taken to want the plugin's preferred
 * version, which keeps a hand-written client usable.
 *
 * @param url The connect URL, whose `v` parameter carries the caller's versions
 * @param declared The session type the plugin declares
 * @returns The agreed version, or undefined when there is no overlap
 */
function negotiateVersion(url: URL, declared: SessionType): number | undefined {
    const requested = url.searchParams
        .getAll("v")
        .flatMap((value) => value.split(","))
        .map((value) => Number.parseInt(value.trim(), 10))
        .filter((value) => Number.isInteger(value));

    if (requested.length === 0) {
        return declared.versions[0];
    }
    return requested.find((value) => declared.versions.includes(value));
}

/**
 * Reads the bearer token of a connection from its `Authorization` header.
 *
 * Only the header is read: sessions are dialed by services, which can set it, and a token in the
 * query string would end up in the access logs of every proxy on the way.
 *
 * @param request The upgrade request
 * @returns The raw token, or undefined when the request carries none
 */
function readToken(request: IncomingMessage): string | undefined {
    const header = request.headers.authorization;
    return header?.startsWith("Bearer ") ? header.substring(7) : undefined;
}

/**
 * Converts a received frame to bytes, whatever shape `ws` handed it over in.
 *
 * @param raw The received data
 * @param isBinary Whether the frame was a binary one
 * @returns The frame's bytes
 */
function toBytes(raw: unknown, isBinary: boolean): Uint8Array {
    if (Buffer.isBuffer(raw)) {
        return new Uint8Array(raw);
    }
    if (Array.isArray(raw)) {
        return new Uint8Array(Buffer.concat(raw as Buffer[]));
    }
    if (raw instanceof ArrayBuffer) {
        return new Uint8Array(raw);
    }
    return new Uint8Array(Buffer.from(String(raw), isBinary ? "binary" : "utf-8"));
}

/**
 * Closes a connection that never became a session, saying why.
 *
 * @param socket The connection to close
 * @param code One of {@link SessionCloseCodes}
 * @param reason What did not resolve, in terms the plugin author can act on
 * @param deps Where to log the refusal
 */
function refuse(socket: WebSocket, code: number, reason: string, deps: SessionServerDeps): void {
    deps.log.warn(`Refusing session: ${reason}`);
    closeSocket(socket, code, reason);
}

/**
 * The most bytes a close frame's reason may take; a longer one makes `ws` throw.
 */
const MAX_CLOSE_REASON_BYTES = 123;

/**
 * Closes a connection with a reason cut to what a close frame can carry.
 *
 * @param socket The connection to close
 * @param code The close code
 * @param reason Why; shortened on a character boundary when it does not fit
 */
function closeSocket(socket: WebSocket, code: number, reason: string): void {
    let fitted = reason;
    while (Buffer.byteLength(fitted, "utf-8") > MAX_CLOSE_REASON_BYTES) {
        fitted = fitted.slice(0, -1);
    }
    socket.close(code, fitted);
}
