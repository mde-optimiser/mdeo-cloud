import {
    type ActionMessage,
    type JsonRpcServerInstance,
    type MaybePromise,
    type ServerModule,
    LogLevel
} from "@eclipse-glsp/server";
import type { PluginContext } from "../plugin/pluginContext.js";
import type { Container, ContainerModule } from "inversify";
import type { CustomGLSPServerLauncher, CustomGLSPServerLauncherOptions } from "./customLauncher.js";
import type { LangiumSharedServices } from "langium/lsp";
import type { Module } from "langium";
import { createJsonrpcGLSPClient } from "../protocol/glsp.js";
import type { MessageConnection } from "vscode-jsonrpc";

/**
 * The message connection type of the GLSP server's own vscode-jsonrpc copy.
 *
 * GLSP pins vscode-jsonrpc 8.2.0 while Langium's language server runs on 9.x, so the two
 * MessageConnection types differ only in their private declarations. At runtime GLSP is always
 * handed the Langium connection, and every message type sent over it is created from the plugin
 * context's vscode-jsonrpc (see createJsonrpcGLSPClient), so both sides of a call use the same copy.
 */
type GLSPMessageConnection = JsonRpcServerInstance["clientConnection"];

/**
 * Additional services provided by GLSP integration in Langium shared services.
 */
export interface GLSPSharedAdditionalServices {
    glsp: {
        /**
         * The inversify container for dependency injection.
         */
        container: Container;
        /**
         * The GLSP server module configuration.
         */
        serverModule: ServerModule;
        /**
         * The custom GLSP server launcher.
         */
        launcher: CustomGLSPServerLauncher;
    };
}

/**
 * Combined type of Langium shared services with GLSP additions.
 */
export type LangiumSharedGLSPServices = GLSPSharedAdditionalServices & LangiumSharedServices;

/**
 * Creates a Langium module that provides GLSP integration services.
 *
 * @param context The plugin context providing necessary dependencies
 * @returns A Langium module with GLSP service providers
 */
export function createGLSPModule(
    context: PluginContext
): Module<LangiumSharedGLSPServices, GLSPSharedAdditionalServices> {
    return {
        glsp: {
            container: (services) => createContainer(services, context),
            serverModule: (services) => createServerModule(services, context),
            launcher: (services) => createLauncher(services, context)
        }
    };
}

/**
 * Creates the inversify container for GLSP dependency injection.
 * Initializes the container with the GLSP application module.
 *
 * @param _services The Langium shared GLSP services (unused)
 * @param context The plugin context providing inversify and GLSP server dependencies
 * @returns The configured inversify container
 */
function createContainer(
    _services: LangiumSharedGLSPServices,
    { inversify, "@eclipse-glsp/server/browser.js": glspServerBrowser }: PluginContext
): Container {
    const { Container } = inversify;
    const { createAppModule } = glspServerBrowser;

    const container = new Container();
    const appModule = createAppModule({ logLevel: LogLevel.warn });
    container.load(appModule);
    return container;
}

/**
 * Creates the GLSP server module for diagram configuration.
 * This module is used to register diagram-specific services and handlers.
 *
 * @param _services The Langium shared GLSP services (unused)
 * @param context The plugin context providing GLSP server dependencies
 * @returns A new server module instance
 */
function createServerModule(
    _services: LangiumSharedGLSPServices,
    { "@eclipse-glsp/server": glspServer }: PluginContext
): ServerModule {
    const { ServerModule } = glspServer;

    const serverModule = new ServerModule();
    return serverModule;
}

/**
 * Creates and configures the custom GLSP server launcher.
 * The launcher handles starting the GLSP server and configuring client-server communication.
 *
 * @param services The Langium shared GLSP services providing container and server module
 * @param context The plugin context providing GLSP server and inversify dependencies
 * @returns A configured custom GLSP server launcher
 */
function createLauncher(services: LangiumSharedGLSPServices, context: PluginContext): CustomGLSPServerLauncher {
    const { "@eclipse-glsp/server": glspServer, inversify } = context;
    const { JsonRpcGLSPServerLauncher, GLSPClientProxy, JsonrpcClientProxy } = glspServer;
    const { injectable, ContainerModule } = inversify;
    const { container, serverModule } = services.glsp;

    const JsonrpcGLSPClient = createJsonrpcGLSPClient(context["vscode-jsonrpc"]);

    /**
     * Custom JSON-RPC client proxy to handle action messages.
     */
    class CustomJsonRPCClientProxy extends JsonrpcClientProxy {
        override process(message: ActionMessage): void {
            const clientConnection = this.clientConnection as MessageConnection | undefined;
            clientConnection?.sendNotification(JsonrpcGLSPClient.ActionMessageNotification, message);
        }
    }

    /**
     * Launcher implementation for GLSP server.
     * Extends the base JSON-RPC launcher with custom connection configuration.
     */
    @injectable()
    class CustomLauncher
        extends JsonRpcGLSPServerLauncher<CustomGLSPServerLauncherOptions>
        implements CustomGLSPServerLauncher
    {
        /**
         * Runs the GLSP server with the provided connection options.
         *
         * @param startParams The connection and configuration parameters
         */
        protected override run(startParams: CustomGLSPServerLauncherOptions): MaybePromise<void> {
            const connection = startParams.connection;
            this.createServerInstance(connection as unknown as GLSPMessageConnection);
        }

        /**
         * Configures the client connection by registering request and notification handlers.
         * Sets up the JSON-RPC protocol handlers for GLSP communication.
         *
         * @param serverInstance The server instance containing the connection and server
         */
        protected override configureClientConnection(serverInstance: JsonRpcServerInstance): void {
            const clientConnection = serverInstance.clientConnection as unknown as MessageConnection;
            const server = serverInstance.server;

            clientConnection.onRequest(JsonrpcGLSPClient.InitializeRequest.method, (params) =>
                server.initialize(params)
            );
            clientConnection.onRequest(JsonrpcGLSPClient.InitializeClientSessionRequest, (params) =>
                server.initializeClientSession(params)
            );
            clientConnection.onRequest(JsonrpcGLSPClient.DisposeClientSessionRequest, (params) =>
                server.disposeClientSession(params)
            );
            clientConnection.onNotification(JsonrpcGLSPClient.ActionMessageNotification, (message) => {
                server.process(message);
            });
        }

        protected override createJsonRpcModule(clientConnection: GLSPMessageConnection): ContainerModule {
            return new ContainerModule((bind) => {
                bind(GLSPClientProxy).toDynamicValue((ctx) => {
                    const proxy = ctx.container.resolve(CustomJsonRPCClientProxy);
                    proxy.initialize(clientConnection);
                    return proxy;
                });
            });
        }
    }

    const launcher = container.resolve(CustomLauncher);
    launcher.configure(serverModule);
    return launcher;
}
