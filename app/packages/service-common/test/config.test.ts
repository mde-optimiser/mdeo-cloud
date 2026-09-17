import { afterEach, describe, it } from "node:test";
import assert from "node:assert/strict";
import { parseServiceConfigFromEnv } from "../dist/util/config.js";

const names = ["MAX_LANGIUM_INSTANCES", "MAX_SESSION_INSTANCES", "MAX_SESSIONS", "LANGIUM_ACQUIRE_TIMEOUT_MS"];

describe("service configuration", () => {
    afterEach(() => {
        for (const name of names) {
            delete process.env[name];
        }
    });

    it("uses the defaults when nothing is set", () => {
        const config = parseServiceConfigFromEnv();
        assert.equal(config.maxLangiumInstances, 5);
        assert.equal(config.maxSessionInstances, 2);
        assert.equal(config.maxSessions, 64);
        assert.equal(config.langiumAcquireTimeoutMs, 30_000);
    });

    it("reads whole numbers", () => {
        process.env.MAX_SESSIONS = "8";
        assert.equal(parseServiceConfigFromEnv().maxSessions, 8);
    });

    it("refuses a value that is not a whole number at or above the minimum", () => {
        for (const [name, value] of [
            ["MAX_SESSION_INSTANCES", "abc"],
            ["LANGIUM_ACQUIRE_TIMEOUT_MS", "0"],
            ["MAX_LANGIUM_INSTANCES", "2.5"]
        ]) {
            process.env[name] = value;
            assert.throws(() => parseServiceConfigFromEnv(), new RegExp(name));
            delete process.env[name];
        }
    });
});
