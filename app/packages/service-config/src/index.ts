import { Settings } from "lucide";
import {
    defaultLanguageConfiguration,
    defaultMonarchTokenProvider,
    serializeMonarchTokensProvider,
    type ActionIconNode
} from "@mdeo/language-common";
import {
    parseServiceConfigFromEnv,
    type ServiceConfig,
    type ServicePluginDefinition,
    type LanguageServiceConfig,
    initializePluginContext,
    startLanguageService,
    DIAGNOSTICS_HANDLER_KEY
} from "@mdeo/service-common";
import { convertIcon } from "@mdeo/language-common";
import type { ConfigAdditionalServices } from "@mdeo/language-config";
import { DOCUMENTATION_BASE_URL, type LanguagePlugin } from "@mdeo/plugin";
import { DefaultScopeProvider } from "langium";

const icon: ActionIconNode = convertIcon(Settings);

/**
 * Language plugin definition for the config language.
 */
const configLanguagePlugin: LanguagePlugin = {
    id: "config",
    name: "Config",
    extension: ".config",
    icon,
    serverPlugin: {
        import: "language.js"
    },
    graphicalEditorPlugin: undefined,
    textualEditorPlugin: {
        languageConfiguration: defaultLanguageConfiguration,
        monarchTokensProvider: serializeMonarchTokensProvider({
            ...defaultMonarchTokenProvider,
            keywords: []
        })
    },
    isGenerated: false,
    documentationUrl: `${DOCUMENTATION_BASE_URL}/plugins/config`
};

/**
 * Plugin definition for the config service.
 */
const configServicePlugin: ServicePluginDefinition = {
    id: "config-service",
    name: "Config",
    description: "Language support for configuration files (.config)",
    icon,
    languagePlugins: [configLanguagePlugin],
    contributionPlugins: []
};

initializePluginContext();

const { configPluginProvider } = await import("@mdeo/language-config");
const { configDataHandler, CONFIG_DATA_KEY } = await import("./handler/configFileDataHandler.js");
const { ConfigExecutionHandler } = await import("./handler/configExecutionHandler.js");
const { configDiagnosticsHandler } = await import("./handler/configDiagnosticsHandler.js");
const { DefaultExternalReferenceCollector } = await import("@mdeo/language-shared");

const envConfig = parseServiceConfigFromEnv();
const configExecutionHandler = new ConfigExecutionHandler();

/**
 * Language configuration for the config language.
 */
const configLanguageConfig: LanguageServiceConfig<ConfigAdditionalServices> = {
    languagePlugin: configLanguagePlugin,
    languagePluginProvider: configPluginProvider,
    serviceModule: {
        references: {
            ScopeProvider: (services) => new DefaultScopeProvider(services),
            ExternalReferenceCollector: () => new DefaultExternalReferenceCollector()
        }
    },
    fileDataHandlers: {
        [CONFIG_DATA_KEY]: configDataHandler,
        [DIAGNOSTICS_HANDLER_KEY]: configDiagnosticsHandler
    },
    executionHandlers: [configExecutionHandler]
};

const config: ServiceConfig<any> = {
    ...envConfig,
    plugin: configServicePlugin,
    languages: [configLanguageConfig]
};

await startLanguageService(config);
