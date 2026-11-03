import type * as langium from "langium";
import type * as langiumLsp from "langium/lsp";
import type * as langiumGrammar from "langium/grammar";
import type * as typirLangium from "typir-langium";
import type * as typir from "typir";
import type * as prettier from "prettier";
import type * as glspServer from "@eclipse-glsp/server";
import type * as glspServerBrowser from "@eclipse-glsp/server/browser.js";
import type * as glspProtocol from "@eclipse-glsp/protocol";
import type * as glspGraph from "@eclipse-glsp/graph";
import type * as inversify from "inversify";
import type * as vscodeJsonrpc from "vscode-jsonrpc";
import type * as vscodeLanguageserverTypes from "vscode-languageserver-types";
import type * as vscodeLanguageserverProtocol from "vscode-languageserver-protocol";
import type * as elkjs from "elkjs/lib/elk.bundled.js";

/**
 * The part of the orthoflow API (TALA layout, WebAssembly) provided to plugins. The module is initialized by the host.
 */
export interface OrthoflowPluginApi {
    /**
     * Lays out a TALA graphjson graph and returns the report as JSON.
     */
    layoutJson(graph: string, options?: { seeds?: number[] }): string;
}

/**
 * Context provided to plugins when they are initialized
 */
export interface PluginContext {
    langium: typeof langium;
    "langium/lsp": typeof langiumLsp;
    "langium/grammar": typeof langiumGrammar;
    "typir-langium": typeof typirLangium;
    typir: typeof typir;
    prettier: typeof prettier;
    "@eclipse-glsp/server": typeof glspServer;
    "@eclipse-glsp/server/browser.js": typeof glspServerBrowser;
    "@eclipse-glsp/graph": typeof glspGraph;
    "@eclipse-glsp/protocol": typeof glspProtocol;
    inversify: typeof inversify;
    "vscode-jsonrpc": typeof vscodeJsonrpc;
    "vscode-languageserver-types": typeof vscodeLanguageserverTypes;
    "vscode-languageserver-protocol": typeof vscodeLanguageserverProtocol;
    elkjs: typeof elkjs;
    /**
     * The initialized orthoflow module, if the host provides it (used by the TALA layout).
     */
    orthoflow?: OrthoflowPluginApi;
}
