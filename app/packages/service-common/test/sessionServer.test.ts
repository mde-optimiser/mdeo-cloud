import { after, before, describe, it } from "node:test";
import assert from "node:assert/strict";
import { createServer, type Server } from "node:http";
import type { AddressInfo } from "node:net";
import { WebSocket } from "ws";
import { attachSessionServer, SessionCloseCodes, type SessionServerDeps } from "../dist/ws/sessionServer.js";

/**
 * Claims a real token would carry, by token value; anything else fails verification.
 */
const tokens: Record<string, Record<string, unknown>> = {
    good: {
        scope: ["plugin:session:connect"],
        target: "contrib:echoes",
        session: "echo",
        projectId: "p",
        executionId: "e"
    },
    misdirected: {
        scope: ["plugin:session:connect"],
        target: "contrib:other",
        session: "echo",
        projectId: "p",
        executionId: "e"
    },
    unscoped: { scope: ["session:open"], target: "contrib:echoes", session: "echo", projectId: "p", executionId: "e" }
};

const logged: string[] = [];

function deps(maxSessions: number): SessionServerDeps {
    return {
        jwtAuth: {
            verifyToken: async (token: string | undefined) => {
                const claims = token == undefined ? undefined : tokens[token];
                if (claims == undefined) {
                    throw new Error("invalid token");
                }
                return claims;
            }
        } as unknown as SessionServerDeps["jwtAuth"],
        handlers: {
            "contrib:echoes": {
                echo: {
                    open: (ctx) => ({
                        onMessage: (data) => ctx.send(data),
                        onClose: () => {}
                    })
                }
            }
        },
        resolveSessionType: (address, name) =>
            address === "contrib:echoes" && name === "echo" ? { protocol: "echo", versions: [2, 1] } : undefined,
        acquireLanguageInstance: async () => {
            throw new Error("no languages here");
        },
        releaseLanguageInstance: () => {},
        createServerApi: () => ({}) as ReturnType<SessionServerDeps["createServerApi"]>,
        maxSessions,
        log: { warn: (message) => logged.push(message), error: (message) => logged.push(message) }
    };
}

interface Outcome {
    code: number;
    reason: string;
    echoed?: Buffer;
}

/**
 * Connects, sends one message when the session opens, and reports how the connection ended.
 */
function connect(port: number, path: string, token?: string, keepOpen?: (socket: WebSocket) => void): Promise<Outcome> {
    return new Promise((resolve, reject) => {
        const socket = new WebSocket(`ws://127.0.0.1:${port}${path}`, {
            headers: token == undefined ? {} : { Authorization: `Bearer ${token}` }
        });
        let echoed: Buffer | undefined;
        socket.on("open", () => socket.send(Buffer.from([7])));
        socket.on("message", (data: Buffer) => {
            echoed = data;
            if (keepOpen) {
                keepOpen(socket);
            } else {
                socket.close(1000, "done");
            }
        });
        socket.on("close", (code, reason) => resolve({ code, reason: reason.toString(), echoed }));
        socket.on("error", reject);
    });
}

describe("session server", () => {
    let server: Server;
    let port: number;

    before(async () => {
        server = createServer();
        attachSessionServer(server, deps(1));
        await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
        port = (server.address() as AddressInfo).port;
    });

    after(() => {
        server.closeAllConnections();
        server.close();
    });

    it("refuses an address with an overlong path instead of crashing", async () => {
        const outcome = await connect(port, `/ws/sessions/a/b/c/d/${"x".repeat(300)}`, "good");
        assert.equal(outcome.code, SessionCloseCodes.NotFound);
        assert.ok(Buffer.byteLength(outcome.reason) <= 123);
        // The service is still there.
        assert.equal((await connect(port, "/ws/sessions/contrib/echoes/echo", "good")).code, 1000);
    });

    it("keeps an overlong reason within what a close frame carries", async () => {
        tokens[`long-${"y".repeat(10)}`] = {
            ...tokens.misdirected,
            target: `contrib:${"z".repeat(200)}`
        };
        const outcome = await connect(port, "/ws/sessions/contrib/echoes/echo", `long-${"y".repeat(10)}`);
        assert.equal(outcome.code, SessionCloseCodes.Unauthorized);
        assert.ok(Buffer.byteLength(outcome.reason) <= 123);
    });

    it("refuses missing, invalid, unscoped and misdirected tokens", async () => {
        for (const token of [undefined, "forged", "unscoped", "misdirected"]) {
            const outcome = await connect(port, "/ws/sessions/contrib/echoes/echo", token);
            assert.equal(outcome.code, SessionCloseCodes.Unauthorized, `token ${token}`);
        }
    });

    it("does not read a token from the query string", async () => {
        const outcome = await connect(port, "/ws/sessions/contrib/echoes/echo?token=good");
        assert.equal(outcome.code, SessionCloseCodes.Unauthorized);
    });

    it("refuses a version neither side speaks, and serves one both do", async () => {
        assert.equal(
            (await connect(port, "/ws/sessions/contrib/echoes/echo?v=3", "good")).code,
            SessionCloseCodes.VersionMismatch
        );
        const served = await connect(port, "/ws/sessions/contrib/echoes/echo?v=3,1", "good");
        assert.equal(served.code, 1000);
        assert.deepEqual([...(served.echoed ?? [])], [7]);
    });

    it("refuses a session beyond the limit until one closes", async () => {
        let release: (() => void) | undefined;
        const first = connect(port, "/ws/sessions/contrib/echoes/echo", "good", (socket) => {
            release = () => socket.close(1000, "done");
        });
        while (release == undefined) {
            await new Promise((resolve) => setTimeout(resolve, 5));
        }

        const refused = await connect(port, "/ws/sessions/contrib/echoes/echo", "good");
        assert.equal(refused.code, SessionCloseCodes.Unavailable);

        release();
        await first;
        // The server frees the slot when it sees the connection close, which may be a moment later.
        let code = 0;
        for (let attempt = 0; attempt < 50 && code !== 1000; attempt++) {
            code = (await connect(port, "/ws/sessions/contrib/echoes/echo", "good")).code;
            if (code !== 1000) {
                await new Promise((resolve) => setTimeout(resolve, 10));
            }
        }
        assert.equal(code, 1000);
    });
});
