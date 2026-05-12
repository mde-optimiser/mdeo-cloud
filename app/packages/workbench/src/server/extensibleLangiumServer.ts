import {
    createConnection,
    BrowserMessageReader,
    BrowserMessageWriter,
    type RequestMessage
} from "vscode-languageserver/browser.js";
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
import type { ServerPlugin } from "@/data/plugin/serverPlugin";
import type { DefaultSharedModuleContext } from "langium/lsp";
import {
    configureGLSPServer,
    createGLSPModule,
    createModule,
    initializePluginContext,
    type ExternalReferenceSharedAdditionalServices,
    type LangiumLanguagePluginProvider
} from "@mdeo/language-common";
import { type ResolvedServerLanguagePlugin } from "./types";
import { GetPluginsRequest, ServerReadyNotification } from "./protocol";
import type {
    DeepPartial,
    LangiumCoreServices,
    LangiumGeneratedCoreServices,
    LangiumGeneratedSharedCoreServices,
    LangiumSharedCoreServices,
    LanguageMetaData,
    Module
} from "langium";
import { lspFileSystem } from "./lspFileSystem";
import { NoopExternalReferenceResolver } from "./noopExternalReferenceResolver";
import { addActionHandlers } from "./actionHandlers";
import { ConcurrentLangiumDocuments } from "./concurrentLangiumDocuments";
import { DependencyAwareDocumentBuilder } from "./dependencyAwareDocumentBuilder";

const messageReader = new BrowserMessageReader(self);
const messageWriter = new BrowserMessageWriter(self);

// @ts-expect-error define document because otherwise elkjs will not work in a webworker
globalThis.document = null;

const pluginContext = {
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
    elkjs
};

initializePluginContext(pluginContext);

const plugins = await requestPluginsFromClient();

/**
 * Requests the plugin configuration from the client using temporary message listening.
 * Sends a GetPluginsRequest and waits for the response.
 *
 * @returns Promise resolving to the array of server plugins
 */
async function requestPluginsFromClient(): Promise<ServerPlugin[]> {
    return new Promise((resolve) => {
        const requestId = "request/plugins";

        messageReader.listen((message: any) => {
            if (message.id === requestId && message.result !== undefined && message.result.plugins !== undefined) {
                resolve(message.result.plugins);
            }
        });

        const requestMessage: RequestMessage = {
            jsonrpc: "2.0",
            id: requestId,
            method: GetPluginsRequest.method,
            params: {}
        };
        messageWriter.write(requestMessage);
    });
}

const resolvedPlugins: ResolvedServerLanguagePlugin[] = await Promise.all(
    plugins.map(async (plugin) => {
        const module = (await import(/* @vite-ignore */ plugin.import)).default as LangiumLanguagePluginProvider<any>;
        return {
            ...plugin,
            languagePlugin: module.create(plugin.contributionPlugins, new URL(plugin.import, self.location.href).href)
        };
    })
);

const connection = createConnection(messageReader, messageWriter);

/**
 * Creates and configures Langium language services for all loaded plugins.
 * Initializes the shared services module with GLSP integration, registers all language
 * plugins, and invokes their postCreate hooks.
 *
 * @param context The default shared module context with connection and file system
 * @returns The configured Langium shared services
 */
function createLanguageServices(context: DefaultSharedModuleContext) {
    const languageModule = createModule(
        resolvedPlugins.map((plugin) => plugin.languagePlugin),
        pluginContext
    );
    const generatedSharedModule: Module<
        LangiumSharedCoreServices,
        LangiumGeneratedSharedCoreServices &
            ExternalReferenceSharedAdditionalServices &
            DeepPartial<LangiumSharedCoreServices>
    > = {
        AstReflection: () => languageModule.reflection,
        references: {
            ExternalReferenceResolver: () => new NoopExternalReferenceResolver()
        },
        workspace: {
            LangiumDocuments: (services) => new ConcurrentLangiumDocuments(services),
            DocumentBuilder: (services) => new DependencyAwareDocumentBuilder(services)
        }
    };
    const glspModule = createGLSPModule(pluginContext);

    const shared = langium.inject(langiumLsp.createDefaultSharedModule(context), generatedSharedModule, glspModule);

    for (const plugin of resolvedPlugins) {
        const grammar = languageModule.grammars.get(plugin.languagePlugin)!;
        const fileExtensions = plugin.extension ? [plugin.extension] : [];
        const languageMetaData: LanguageMetaData = {
            languageId: plugin.id,
            fileExtensions,
            caseInsensitive: false,
            mode: "development"
        };
        const generatedModule: Module<LangiumCoreServices, LangiumGeneratedCoreServices> = {
            Grammar: () => grammar,
            LanguageMetaData: () => languageMetaData,
            parser: {}
        };
        const services = langium.inject(
            langiumLsp.createDefaultModule({ shared }),
            generatedModule,
            plugin.languagePlugin.module
        );
        plugin.services = services;
    }
    for (const plugin of resolvedPlugins) {
        shared.ServiceRegistry.register(plugin.services!);
    }
    for (const plugin of resolvedPlugins) {
        if (plugin.languagePlugin.postCreate != undefined) {
            plugin.languagePlugin.postCreate(plugin.services!, context);
        }
    }

    return shared;
}

const shared = createLanguageServices({ connection, ...lspFileSystem(connection) });

const workspaceManager = shared.workspace.WorkspaceManager as { initialBuildOptions?: object };
workspaceManager.initialBuildOptions = {
    validation: {
        categories: ["built-in", "fast"]
    }
};

connection.sendNotification(ServerReadyNotification.method, {});

configureGLSPServer(shared);
addActionHandlers(connection, shared, pluginContext);
langiumLsp.startLanguageServer(shared);
