import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { LangiumInstancePool, LangiumPoolExhaustedError } from "../dist/langium/langiumPool.js";

/**
 * A pool that can never create an instance, so every acquisition has to wait. What is tested is
 * how waiting ends, which does not need a language.
 */
function fullPool(acquireTimeoutMs: number, maxSessionInstances = 2): LangiumInstancePool<unknown> {
    return new LangiumInstancePool<unknown>({
        maxInstances: 0,
        maxSessionInstances,
        acquireTimeoutMs,
        languagePluginProvider: {
            create: () => {
                throw new Error("no instance may be created in this test");
            }
        }
    } as unknown as ConstructorParameters<typeof LangiumInstancePool<unknown>>[0]);
}

describe("Langium instance pool", () => {
    it("gives up waiting after the acquire timeout", async () => {
        const pool = fullPool(20);
        await assert.rejects(pool.acquire([], "jwt", "project"), LangiumPoolExhaustedError);
    });

    it("stops waiting as soon as the caller's signal aborts", async () => {
        const pool = fullPool(60_000);
        const controller = new AbortController();
        const started = Date.now();
        const waiting = pool.acquire([], "jwt", "project", undefined, controller.signal);
        setTimeout(() => controller.abort(new Error("caller left")), 10);
        await assert.rejects(waiting, /caller left/);
        assert.ok(Date.now() - started < 5_000);
    });

    it("does not wait at all for a signal that already aborted", async () => {
        const pool = fullPool(60_000);
        await assert.rejects(pool.acquire([], "jwt", "project", undefined, AbortSignal.abort()));
    });

    it("refuses a session once the session budget is used up", () => {
        const pool = fullPool(20, 0);
        assert.throws(() => pool.acquireForSession([], "jwt", "project"), LangiumPoolExhaustedError);
    });
});
