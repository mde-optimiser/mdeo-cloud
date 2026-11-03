import type { Connection } from "vscode-languageserver/browser";
import type { LangiumSharedCoreServices, LangiumCoreServices } from "langium";
import { createActionProtocol, getServicesByLanguageId } from "@mdeo/language-common";
import type { ActionHandlerRegistryAdditionalServices } from "@mdeo/language-shared";
import type { PluginContext } from "@mdeo/language-common";

/**
 * Language services extended with action handler registry support.
 */
type ActionExtendedServices = LangiumCoreServices & Partial<ActionHandlerRegistryAdditionalServices>;

/**
 * Registers action dialog request handlers on the LSP connection.
 * Handles action/start, action/submit, and action/getFileActions requests.
 *
 * @param connection The LSP connection to register handlers on
 * @param services The shared Langium services for accessing the service registry
 * @param pluginContext The plugin context providing protocol dependencies
 */
export function addActionHandlers(
    connection: Connection,
    services: LangiumSharedCoreServices,
    pluginContext: PluginContext
): void {
    const ActionProtocol = createActionProtocol(pluginContext["vscode-languageserver-protocol"]);

    connection.onRequest(ActionProtocol.ActionStartRequest, async (params) => {
        const languageServices = getServicesByLanguageId(services.ServiceRegistry, params.languageId) as
            ActionExtendedServices | undefined;
        if (languageServices == undefined) {
            throw new Error(`Language services not found for language: ${params.languageId}`);
        }

        const registry = languageServices.action?.ActionHandlerRegistry;
        if (registry == undefined) {
            throw new Error(`Action handler registry not available for language: ${params.languageId}`);
        }

        const handler = registry.getHandler(params.type);
        if (!handler) {
            throw new Error(
                `No handler registered for action type: ${params.type}. ` +
                    `Available types: ${registry.getRegisteredTypes().join(", ")}`
            );
        }

        return await handler.startAction(params);
    });

    connection.onRequest(ActionProtocol.ActionSubmitRequest, async (params) => {
        const languageId = params.config.languageId;
        const languageServices = getServicesByLanguageId(services.ServiceRegistry, languageId) as
            ActionExtendedServices | undefined;
        if (languageServices == undefined) {
            throw new Error(`Language services not found for language: ${languageId}`);
        }

        const registry = languageServices.action?.ActionHandlerRegistry;
        if (registry == undefined) {
            throw new Error(`Action handler registry not available for language: ${languageId}`);
        }

        const handler = registry.getHandler(params.config.type);
        if (!handler) {
            throw new Error(
                `No handler registered for action type: ${params.config.type}. ` +
                    `Available types: ${registry.getRegisteredTypes().join(", ")}`
            );
        }

        return await handler.submitAction(params);
    });

    connection.onRequest(ActionProtocol.GetFileActionsRequest, async (params) => {
        const languageServices = getServicesByLanguageId(services.ServiceRegistry, params.languageId) as
            ActionExtendedServices | undefined;
        if (languageServices == undefined) {
            return { actions: [] };
        }

        const actionProvider = languageServices.action?.ActionProvider;
        if (actionProvider == undefined) {
            return { actions: [] };
        }

        return await actionProvider.getFileActions(params);
    });
}
