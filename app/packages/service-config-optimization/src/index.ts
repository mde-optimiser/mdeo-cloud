import { Settings2 } from "lucide";
import { convertIcon } from "@mdeo/language-common";
import type { ExternalReferenceAdditionalServices } from "@mdeo/language-common";
import { CONFIG_DIAGNOSTICS_REQUEST_KEY, CONFIG_PLUGIN_REQUEST_KEY } from "@mdeo/service-config-common";
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

const icon = convertIcon(Settings2);

/**
 * Language plugin definition for the config-optimization language.
 * This is a generated language that provides services for optimization sections.
 */
const configOptimizationLanguagePlugin: LanguagePlugin = {
    id: "config-optimization",
    name: "Config Optimization",
    extension: undefined,
    newFileAction: false,
    icon,
    serverPlugin: {
        import: "language.js"
    },
    graphicalEditorPlugin: undefined,
    textualEditorPlugin: undefined,
    isGenerated: true,
    documentationUrl: `${DOCUMENTATION_BASE_URL}/plugins/config-optimization`
};

initializePluginContext();

const { configOptimizationPluginProvider, createOptimizationContributionPlugin } =
    await import("@mdeo/language-config-optimization");
const { optimizationRequestHandler, optimizationDiagnosticsRequestHandler } =
    await import("./handler/optimizationRequestHandler.js");

/**
 * Plugin definition for the config-optimization service.
 */
const configOptimizationServicePlugin: ServicePluginDefinition = {
    id: "config-optimization-service",
    name: "Config Optimization",
    description: "Language support for config optimization sections",
    icon,
    languagePlugins: [configOptimizationLanguagePlugin],
    contributionPlugins: [
        {
            languageId: "config",
            description: "Provides optimization section support for config language",
            additionalKeywords: [
                "problem",
                "goal",
                "metamodel",
                "model",
                "constraint",
                "maximize",
                "minimize",
                "refine",
                "import",
                "as",
                "from"
            ],
            serverContributionPlugins: [createOptimizationContributionPlugin()]
        }
    ]
};

const envConfig = parseServiceConfigFromEnv();

/**
 * Language configuration for the config-optimization language.
 */
const configOptimizationLanguageConfig: LanguageServiceConfig<ExternalReferenceAdditionalServices> = {
    languagePlugin: configOptimizationLanguagePlugin,
    languagePluginProvider: configOptimizationPluginProvider,
    fileDataHandlers: {
        [AST_HANDLER_KEY]: astHandler
    },
    requestHandlers: {
        [CONFIG_PLUGIN_REQUEST_KEY]: optimizationRequestHandler,
        [CONFIG_DIAGNOSTICS_REQUEST_KEY]: optimizationDiagnosticsRequestHandler
    }
};

const config: ServiceConfig<any> = {
    ...envConfig,
    plugin: configOptimizationServicePlugin,
    languages: [configOptimizationLanguageConfig]
};

await startLanguageService(config);
