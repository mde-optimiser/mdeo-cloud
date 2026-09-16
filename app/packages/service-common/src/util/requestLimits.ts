import type { FastifyReply, FastifyRequest } from "fastify";

/**
 * The header a caller sends the milliseconds it is still willing to wait in, and that a service
 * forwards its own remaining time in when it calls the backend on the caller's behalf.
 */
export const TIMEOUT_HEADER = "x-mdeo-timeout-ms";

/**
 * How long a request may still run, and the signal that tells its handler to stop.
 */
export interface RequestLimits {
    /**
     * Aborts when the caller's deadline passes or the caller goes away. Pass it to anything
     * long-running; every `ServerApi` call already uses it.
     */
    signal: AbortSignal;
    /**
     * When the caller stops waiting, in epoch milliseconds, if it said.
     */
    deadline: number | undefined;
    /**
     * Whether the signal aborted because the deadline passed, rather than because the caller left.
     */
    readonly timedOut: boolean;
    /**
     * Stops watching the request. Call once the handler has finished.
     */
    dispose(): void;
}

/**
 * Watches one request for its deadline and for the caller going away.
 *
 * @param request The request, whose {@link TIMEOUT_HEADER} sets the deadline
 * @param reply The reply, whose connection closing before it is sent means the caller left
 * @returns The request's limits
 */
export function createRequestLimits(request: FastifyRequest, reply: FastifyReply): RequestLimits {
    const controller = new AbortController();
    const header = request.headers[TIMEOUT_HEADER];
    const timeoutMs = Number.parseInt(Array.isArray(header) ? (header[0] ?? "") : (header ?? ""), 10);
    const hasDeadline = Number.isFinite(timeoutMs) && timeoutMs > 0;
    let timedOut = false;

    const timer = hasDeadline
        ? setTimeout(() => {
              timedOut = true;
              controller.abort(new Error(`The caller's deadline of ${timeoutMs} ms passed`));
          }, timeoutMs)
        : undefined;
    timer?.unref?.();

    const onClose = (): void => {
        if (!reply.raw.writableFinished) {
            controller.abort(new Error("The caller stopped waiting"));
        }
    };
    reply.raw.on("close", onClose);

    return {
        signal: controller.signal,
        deadline: hasDeadline ? Date.now() + timeoutMs : undefined,
        get timedOut() {
            return timedOut;
        },
        dispose() {
            if (timer != undefined) {
                clearTimeout(timer);
            }
            reply.raw.off("close", onClose);
        }
    };
}
