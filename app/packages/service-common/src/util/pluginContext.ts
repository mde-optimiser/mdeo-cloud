import { initializePluginContext as initializePluginContextInternal } from "@mdeo/language-common";
import * as langium from "langium";
import * as langiumLsp from "langium/lsp";
import * as langiumGrammar from "langium/grammar";
import * as typirLangium from "typir-langium";
import * as typir from "typir";
import * as prettier from "prettier";
import * as glspServer from "@eclipse-glsp/server";
import * as glspServerBrowser from "@eclipse-glsp/server/browser.js";
import * as glspProtocol from "@eclipse-glsp/protocol";
import * as glspGraph from "@eclipse-glsp/graph";
import * as inversify from "inversify";
import * as vscodeJsonrpc from "vscode-jsonrpc";
import * as vscodeLanguageserverTypes from "vscode-languageserver-types";
import * as vscodeLanguageserverProtocol from "vscode-languageserver-protocol";
import * as elkjs from "elkjs/lib/elk.bundled.js";
import * as orthoflow from "orthoflow";
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";

/**
 * Initializes orthoflow (TALA layout) from the file system, once.
 *
 * @returns the module, or undefined if it could not be loaded
 */
function loadOrthoflow(): typeof orthoflow | undefined {
    try {
        if (!orthoflow.isInitialized()) {
            const require = createRequire(import.meta.url);
            orthoflow.initSync(readFileSync(require.resolve("orthoflow/orthoflow.wasm")));
        }
        return orthoflow;
    } catch (error) {
        // eslint-disable-next-line no-console
        console.error("Failed to load orthoflow, the TALA layout is not available", error);
        return undefined;
    }
}

/**
 * Initializes the plugin context with required dependencies.
 */
export function initializePluginContext(): void {
    initializePluginContextInternal({
        langium,
        "langium/lsp": langiumLsp,
        "langium/grammar": langiumGrammar,
        "typir-langium": typirLangium,
        typir,
        prettier,
        "@eclipse-glsp/server": glspServer,
        "@eclipse-glsp/server/browser.js": glspServerBrowser,
        "@eclipse-glsp/protocol": glspProtocol,
        "@eclipse-glsp/graph": glspGraph,
        inversify,
        "vscode-jsonrpc": vscodeJsonrpc,
        "vscode-languageserver-types": vscodeLanguageserverTypes,
        "vscode-languageserver-protocol": vscodeLanguageserverProtocol,
        elkjs,
        orthoflow: loadOrthoflow()
    });
}
