import type { IncomingMessage, Server } from "node:http";
import type { Duplex } from "node:stream";

/**
 * One WebSocket endpoint a service serves.
 */
export interface UpgradeRoute {
    /**
     * Decides whether this route serves the requested path.
     *
     * @param path The request path, without its query string
     * @returns True when this route should handle the upgrade
     */
    matches(path: string): boolean;

    /**
     * Completes the upgrade for a request this route matched.
     *
     * @param request The upgrade request
     * @param socket The connection being upgraded
     * @param head The first packet of the upgraded stream
     */
    handle(request: IncomingMessage, socket: Duplex, head: Buffer): void;
}

/**
 * Dispatches upgrade requests of one HTTP server to whichever endpoint serves their path.
 */
interface UpgradeRouter {
    routes: UpgradeRoute[];
    log: UpgradeLog;
}

/**
 * Where a router reports an upgrade it could not place.
 */
export interface UpgradeLog {
    /**
     * Reports an upgrade request that matched no route.
     *
     * @param message What went wrong
     */
    warn(message: string): void;
}

/**
 * Routers already installed, keyed by the server they listen on.
 *
 * Node stops rejecting unhandled upgrades itself as soon as anything listens for them, so a
 * request that matched nothing has to be answered by whoever took that responsibility. Keeping
 * exactly one listener per server is what makes that possible: each endpoint registers a route
 * rather than a listener of its own, and the single listener is the only place that decides an
 * upgrade belongs to nobody.
 */
const routers = new WeakMap<Server, UpgradeRouter>();

/**
 * Registers one WebSocket endpoint on a server.
 *
 * @param server The HTTP server the service listens on
 * @param route The endpoint to serve
 * @param log Where to report upgrades that match no endpoint
 */
export function registerUpgradeRoute(server: Server, route: UpgradeRoute, log: UpgradeLog): void {
    const existing = routers.get(server);
    if (existing) {
        existing.routes.push(route);
        return;
    }

    const router: UpgradeRouter = { routes: [route], log };
    routers.set(server, router);

    server.on("upgrade", (request, socket, head) => {
        const path = (request.url ?? "").split("?")[0];
        for (const candidate of router.routes) {
            if (candidate.matches(path)) {
                candidate.handle(request, socket, head);
                return;
            }
        }
        // Plugin services sit behind a reverse proxy under a per-plugin path prefix, which both
        // the nginx and Vite configurations strip before forwarding. A proxy that did not would
        // land here — and be rejected with the reason logged, which is the part that matters.
        router.log.warn(`Rejecting WebSocket upgrade for unknown path ${path}`);
        socket.destroy();
    });
}
