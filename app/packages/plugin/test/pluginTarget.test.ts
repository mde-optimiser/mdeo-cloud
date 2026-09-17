import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { formatPluginTarget, parsePluginTarget, PluginTargetKind } from "../dist/pluginTarget.js";

describe("plugin targets", () => {
    it("round trips both kinds", () => {
        for (const address of ["lang:script", "contrib:script-functions", "contrib:a.b_c-1"]) {
            assert.equal(formatPluginTarget(parsePluginTarget(address)), address);
        }
        assert.deepEqual(parsePluginTarget("lang:script"), { kind: PluginTargetKind.LANGUAGE, id: "script" });
    });

    it("refuses unknown kinds, missing separators and ids that need escaping", () => {
        for (const address of ["script", "plugin:script", "lang:", "lang:-x", "lang:a/b", "contrib:a b", "lang:a:b"]) {
            assert.throws(() => parsePluginTarget(address), /Invalid plugin target/, address);
        }
        assert.throws(() => formatPluginTarget({ kind: PluginTargetKind.LANGUAGE, id: "../x" }));
    });
});
