import { Settings2 } from "lucide";
import { convertIcon } from "@mdeo/language-common";
import type { MdeoServices } from "@mdeo/language-config-mdeo";
import {
    CONFIG_PLUGIN_REQUEST_KEY,
    CONFIG_DIAGNOSTICS_REQUEST_KEY,
    CONFIG_EXECUTION_REQUEST_KEY,
    CONFIG_EXECUTION_GET_SUMMARY_REQUEST_KEY,
    CONFIG_EXECUTION_GET_FILE_TREE_REQUEST_KEY,
    CONFIG_EXECUTION_GET_FILE_REQUEST_KEY,
    CONFIG_EXECUTION_GET_FILES_REQUEST_KEY,
    CONFIG_EXECUTION_CANCEL_REQUEST_KEY,
    CONFIG_EXECUTION_DELETE_REQUEST_KEY
} from "@mdeo/service-config-common";
import {
    parseServiceConfigFromEnv,
    type ServiceConfig,
    type ServicePluginDefinition,
    type LanguageServiceConfig,
    initializePluginContext,
    astHandler,
    AST_HANDLER_KEY,
    startLanguageService
} from "@mdeo/service-common";
import { DOCUMENTATION_BASE_URL, type LanguagePlugin } from "@mdeo/plugin";
import { ServiceMdeoMetamodelResolver } from "./serviceMdeoMetamodelResolver.js";

const icon = convertIcon(Settings2);

/**
 * Language plugin definition for the config-mdeo language.
 * This is a generated language that provides services for MDEO sections.
 */
const configMdeoLanguagePlugin: LanguagePlugin = {
    id: "config-mdeo",
    name: "Config MDEO",
    extension: undefined,
    newFileAction: false,
    icon,
    serverPlugin: {
        import: "language.js"
    },
    graphicalEditorPlugin: undefined,
    textualEditorPlugin: undefined,
    isGenerated: true,
    documentationUrl: `${DOCUMENTATION_BASE_URL}/plugins/config-mdeo`
};

initializePluginContext();

const { configMdeoPluginProvider, createMdeoContributionPlugin } = await import("@mdeo/language-config-mdeo");
const { mdeoRequestHandler, mdeoDiagnosticsRequestHandler } = await import("./handler/mdeoRequestHandler.js");
const {
    mdeoExecutionRequestHandler,
    mdeoExecutionGetSummaryRequestHandler,
    mdeoExecutionGetFileTreeRequestHandler,
    mdeoExecutionGetFileRequestHandler,
    mdeoExecutionGetFilesRequestHandler,
    mdeoExecutionCancelRequestHandler,
    mdeoExecutionDeleteRequestHandler
} = await import("./handler/mdeoExecutionRequestHandler.js");

/**
 * Plugin definition for the config-mdeo service.
 */
const configMdeoServicePlugin: ServicePluginDefinition = {
    id: "config-mdeo-service",
    name: "Config MDEO",
    description: "Language support for config MDEO sections (search and solver)",
    icon,
    languagePlugins: [configMdeoLanguagePlugin],
    contributionPlugins: [
        {
            languageId: "config",
            description: "Provides search and solver section support for config language",
            additionalKeywords: [
                "search",
                "solver",
                "mutations",
                "crossovers",
                "using",
                "with",
                "create",
                "delete",
                "mutate",
                "add",
                "remove",
                "provider",
                "algorithm",
                "parameters",
                "crossover",
                "rate",
                "parents",
                "termination",
                "batches",
                "runtime",
                "timeout",
                "script",
                "transformation",
                "backend",
                "resources",
                "threads",
                "nodes",
                "threadsPerNode"
            ],
            serverContributionPlugins: [createMdeoContributionPlugin()]
        }
    ]
};

const envConfig = parseServiceConfigFromEnv();

/**
 * Language configuration for the config-mdeo language.
 */
const configMdeoLanguageConfig: LanguageServiceConfig<MdeoServices> = {
    languagePlugin: configMdeoLanguagePlugin,
    languagePluginProvider: configMdeoPluginProvider,
    serviceModule: {
        MdeoMetamodelResolver: () => new ServiceMdeoMetamodelResolver()
    },
    fileDataHandlers: {
        [AST_HANDLER_KEY]: astHandler
    },
    requestHandlers: {
        [CONFIG_PLUGIN_REQUEST_KEY]: mdeoRequestHandler,
        [CONFIG_DIAGNOSTICS_REQUEST_KEY]: mdeoDiagnosticsRequestHandler,
        [CONFIG_EXECUTION_REQUEST_KEY]: mdeoExecutionRequestHandler,
        [CONFIG_EXECUTION_GET_SUMMARY_REQUEST_KEY]: mdeoExecutionGetSummaryRequestHandler,
        [CONFIG_EXECUTION_GET_FILE_TREE_REQUEST_KEY]: mdeoExecutionGetFileTreeRequestHandler,
        [CONFIG_EXECUTION_GET_FILE_REQUEST_KEY]: mdeoExecutionGetFileRequestHandler,
        [CONFIG_EXECUTION_GET_FILES_REQUEST_KEY]: mdeoExecutionGetFilesRequestHandler,
        [CONFIG_EXECUTION_CANCEL_REQUEST_KEY]: mdeoExecutionCancelRequestHandler,
        [CONFIG_EXECUTION_DELETE_REQUEST_KEY]: mdeoExecutionDeleteRequestHandler
    }
};

const config: ServiceConfig<any> = {
    ...envConfig,
    plugin: configMdeoServicePlugin,
    languages: [configMdeoLanguageConfig]
};

await startLanguageService(config);
