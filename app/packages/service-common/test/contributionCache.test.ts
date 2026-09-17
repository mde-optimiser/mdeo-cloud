import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { ContributionCache } from "../dist/util/contributionCache.js";

describe("ContributionCache", () => {
    it("remembers a set sent with its hash, and resolves the hash alone later", () => {
        const cache = new ContributionCache();
        const plugins = [{ id: "a" }];
        assert.equal(cache.resolve(plugins, "h1"), plugins);
        assert.deepEqual(cache.resolve(undefined, "h1"), plugins);
    });

    it("answers undefined for a hash it does not hold, and an empty set for no hash", () => {
        const cache = new ContributionCache();
        assert.equal(cache.resolve(undefined, "unknown"), undefined);
        assert.deepEqual(cache.resolve(undefined, undefined), []);
    });

    it("forgets the least recently used set first", () => {
        const cache = new ContributionCache(2);
        cache.resolve([{ id: "a" }], "a");
        cache.resolve([{ id: "b" }], "b");
        // Using "a" makes "b" the least recently used.
        cache.resolve(undefined, "a");
        cache.resolve([{ id: "c" }], "c");
        assert.notEqual(cache.resolve(undefined, "a"), undefined);
        assert.equal(cache.resolve(undefined, "b"), undefined);
        assert.notEqual(cache.resolve(undefined, "c"), undefined);
    });
});
