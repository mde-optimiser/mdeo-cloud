import {
    CONFIG_EXECUTION_REQUEST_KEY,
    CONFIG_EXECUTION_GET_SUMMARY_REQUEST_KEY,
    CONFIG_EXECUTION_GET_FILE_TREE_REQUEST_KEY,
    CONFIG_EXECUTION_GET_FILE_REQUEST_KEY,
    CONFIG_EXECUTION_CANCEL_REQUEST_KEY,
    CONFIG_EXECUTION_DELETE_REQUEST_KEY
} from "@mdeo/service-config-common";
import type {
    ConfigExecutionPluginRequestBody,
    ConfigExecutionRoutingMetadata,
    ConfigExecutionFollowUpRequestBody,
    ConfigExecutionFileRequestBody
} from "@mdeo/service-config-common";
import { ConfigContributionPlugin, getWrapperInterfaceName } from "@mdeo/language-config";
import type { ConfigType } from "@mdeo/language-config";
import type {
    ExecutionHandler,
    ExecutionContext,
    ExecutionRequestContext,
    CanHandleResult,
    ExecuteResponse,
    FileEntry
} from "@mdeo/service-common";
import type { ServerContributionPlugin } from "@mdeo/plugin";
import { URI } from "vscode-uri";

interface ExecutableSectionInfo {
    plugin: ConfigContributionPlugin;
    sectionName: string;
}

interface ConfigExecutionMetadataEnvelope {
    configExecution?: ConfigExecutionRoutingMetadata;
}

/**
 * Execution handler for config files.
 *
 * It determines which executable config section is present and forwards execution
 * via plugin request to the corresponding contribution plugin language.
 */
export class ConfigExecutionHandler implements ExecutionHandler<ExecuteResponse> {
    async canHandle(context: ExecutionContext): Promise<CanHandleResult> {
        if (!context.filePath.endsWith(".config")) {
            return {
                canHandle: false,
                reason: "File must have .config extension"
            };
        }

        return { canHandle: true };
    }

    async execute(context: ExecutionContext): Promise<ExecuteResponse> {
        const plugins = (context.contributionPlugins as ServerContributionPlugin[]).filter(ConfigContributionPlugin.is);
        const executableSection = await this.resolveSingleExecutableSection(context, plugins);

        const requestBody: ConfigExecutionPluginRequestBody & { jwt?: string } = {
            executionId: context.executionId,
            project: context.project,
            filePath: context.filePath,
            fileContent: context.fileContent,
            fileVersion: context.fileVersion,
            data: context.data,
            jwt: context.jwt
        };

        const routingMetadata: ConfigExecutionRoutingMetadata = {
            languageId: executableSection.plugin.languageKey,
            sectionName: executableSection.sectionName,
            pluginName: executableSection.plugin.name
        };

        await context.serverApi.updateExecutionMetadata(context.executionId, {
            configExecution: routingMetadata
        });

        const result = (await context.serverApi.sendPluginRequest(
            executableSection.plugin.languageKey,
            CONFIG_EXECUTION_REQUEST_KEY,
            requestBody
        )) as ExecuteResponse | null;
        if (result == undefined || result == null) {
            throw new Error(
                `Execution forwarding for section '${executableSection.sectionName}.${executableSection.plugin.name}' returned no result`
            );
        }

        return result;
    }

    async getSummary(context: ExecutionRequestContext): Promise<string> {
        const routing = this.getRoutingMetadata(context);
        const requestBody: ConfigExecutionFollowUpRequestBody = {
            executionId: context.executionId
        };
        const result = await context.serverApi.sendPluginRequest(
            routing.languageId,
            CONFIG_EXECUTION_GET_SUMMARY_REQUEST_KEY,
            requestBody
        );
        return typeof result === "string" ? result : "";
    }

    async getFileTree(context: ExecutionRequestContext): Promise<FileEntry[]> {
        const routing = this.getRoutingMetadata(context);
        const requestBody: ConfigExecutionFollowUpRequestBody = {
            executionId: context.executionId
        };
        const result = await context.serverApi.sendPluginRequest(
            routing.languageId,
            CONFIG_EXECUTION_GET_FILE_TREE_REQUEST_KEY,
            requestBody
        );

        return Array.isArray(result) ? (result as FileEntry[]) : [];
    }

    async getFile(context: ExecutionRequestContext, path: string): Promise<Buffer> {
        const routing = this.getRoutingMetadata(context);
        const requestBody: ConfigExecutionFileRequestBody = {
            executionId: context.executionId,
            path
        };
        const result = await context.serverApi.sendPluginRequest(
            routing.languageId,
            CONFIG_EXECUTION_GET_FILE_REQUEST_KEY,
            requestBody
        );

        if (typeof result === "string") {
            return Buffer.from(result, "utf-8");
        }

        throw new Error("Invalid config execution file response");
    }

    async cancel(context: ExecutionRequestContext): Promise<void> {
        const routing = this.getRoutingMetadata(context);
        const requestBody: ConfigExecutionFollowUpRequestBody = {
            executionId: context.executionId
        };
        await context.serverApi.sendPluginRequest(routing.languageId, CONFIG_EXECUTION_CANCEL_REQUEST_KEY, requestBody);
    }

    async delete(context: ExecutionRequestContext): Promise<void> {
        const routing = this.getRoutingMetadata(context);
        const requestBody: ConfigExecutionFollowUpRequestBody = {
            executionId: context.executionId
        };
        await context.serverApi.sendPluginRequest(routing.languageId, CONFIG_EXECUTION_DELETE_REQUEST_KEY, requestBody);
    }

    private async resolveSingleExecutableSection(
        context: ExecutionContext,
        plugins: ConfigContributionPlugin[]
    ): Promise<ExecutableSectionInfo> {
        const sectionTypeMap = new Map<string, ExecutableSectionInfo>();
        for (const plugin of plugins) {
            for (const section of plugin.sections) {
                if (section.executable !== true) {
                    continue;
                }
                sectionTypeMap.set(getWrapperInterfaceName(section.name, plugin.name), {
                    plugin,
                    sectionName: section.name
                });
            }
        }

        const config = await this.loadConfig(context);
        const executableSections = (config.sections ?? [])
            .map((section) => (typeof section.$type === "string" ? sectionTypeMap.get(section.$type) : undefined))
            .filter((section): section is ExecutableSectionInfo => section != undefined);

        if (executableSections.length === 0) {
            throw new Error("No executable section found in config file");
        }

        if (executableSections.length > 1) {
            const names = executableSections
                .map((section) => `${section.sectionName}.${section.plugin.name}`)
                .join(", ");
            throw new Error(`Only one executable section can be executed, but found: ${names}`);
        }

        return executableSections[0];
    }

    private async loadConfig(context: ExecutionContext): Promise<ConfigType> {
        const uri = URI.parse(context.filePath);
        const document = await context.instance.buildDocument(uri);

        if (document.parseResult.lexerErrors.length > 0 || document.parseResult.parserErrors.length > 0) {
            throw new Error("Cannot execute config with parser or lexer errors");
        }

        const root = document.parseResult.value as ConfigType | undefined;
        if (root == undefined || !Array.isArray(root.sections)) {
            throw new Error("Failed to parse config document for execution forwarding");
        }

        return root;
    }

    private getRoutingMetadata(context: ExecutionRequestContext): ConfigExecutionRoutingMetadata {
        const envelope = context.metadata as ConfigExecutionMetadataEnvelope | undefined;
        const routing = envelope?.configExecution;
        if (routing == undefined || typeof routing.languageId !== "string" || routing.languageId.length === 0) {
            throw new Error("Missing execution routing metadata for config execution forwarding");
        }
        return routing;
    }
}
