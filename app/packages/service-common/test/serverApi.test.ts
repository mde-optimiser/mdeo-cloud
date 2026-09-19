import { afterEach, describe, it } from "node:test";
import assert from "node:assert/strict";
import { HttpServerApi } from "../dist/service/serverApi.js";

const realFetch = globalThis.fetch;

/**
 * Stands in for the backend's batch endpoint, answering at most `limit` entries per request.
 */
function batchBackend(limit: number): { requests: number[] } {
    const seen = { requests: [] as number[] };
    globalThis.fetch = (async (_url: string, init: RequestInit) => {
        const { requests } = JSON.parse(init.body as string) as { requests: { path: string; key: string }[] };
        seen.requests.push(requests.length);
        const results = requests.slice(0, limit).map((entry) => ({ data: entry.path, version: 1 }));
        return new Response(JSON.stringify({ results }), { status: 200 });
    }) as typeof fetch;
    return seen;
}

describe("HttpServerApi file data batches", () => {
    afterEach(() => {
        globalThis.fetch = realFetch;
    });

    it("asks again for the entries the backend did not answer", async () => {
        const seen = batchBackend(2);
        const api = new HttpServerApi("http://backend/api");
        api.setContext("token", "project");

        const paths = ["/a", "/b", "/c", "/d", "/e"];
        const answers = await Promise.all(paths.map((path) => api.getFileData(path, "ast")));

        assert.deepEqual(
            answers.map((answer) => answer.data),
            paths
        );
        assert.deepEqual(seen.requests, [5, 3, 1]);
    });

    it("fails the entries when the backend answers none of them", async () => {
        batchBackend(0);
        const api = new HttpServerApi("http://backend/api");
        api.setContext("token", "project");

        const results = await Promise.allSettled(["/a", "/b"].map((path) => api.getFileData(path, "ast")));

        assert.ok(results.every((result) => result.status === "rejected"));
    });
});
