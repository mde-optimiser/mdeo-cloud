import { Network } from "lucide";
import {
    defaultLanguageConfiguration,
    defaultMonarchTokenProvider,
    serializeMonarchTokensProvider
} from "@mdeo/language-common";
import {
    startLanguageService,
    parseServiceConfigFromEnv,
    type ServiceConfig,
    type ServicePluginDefinition,
    type LanguageServiceConfig,
    initializePluginContext,
    astHandler,
    AST_HANDLER_KEY
} from "@mdeo/service-common";
import { convertIcon } from "@mdeo/language-common";
import type { MetamodelServices } from "@mdeo/language-metamodel";
import { DOCUMENTATION_BASE_URL, type LanguagePlugin } from "@mdeo/plugin";

/**
 * Language plugin definition for the metamodel language.
 */
const metamodelLanguagePlugin: LanguagePlugin = {
    id: "metamodel",
    name: "Metamodel",
    extension: ".mm",
    icon: convertIcon(Network),
    serverPlugin: {
        import: "language.js"
    },
    graphicalEditorPlugin: {
        import: "editor.js",
        stylesUrl: "styles.css",
        stylesCls: "editor-metamodel"
    },
    textualEditorPlugin: {
        languageConfiguration: defaultLanguageConfiguration,
        monarchTokensProvider: serializeMonarchTokensProvider({
            ...defaultMonarchTokenProvider,
            keywords: ["class", "extends", "abstract", "import", "from", "as", "enum", "view"]
        })
    },
    isGenerated: false,
    documentationUrl: `${DOCUMENTATION_BASE_URL}/plugins/metamodel`
};

initializePluginContext();

const { metamodelPluginProvider } = await import("@mdeo/language-metamodel");
const { createMetamodelConfigContributionPlugin } = await import("./metamodelConfigContributionPlugin.js");
const { metamodelAstDataHandler, METAMODEL_AST_DATA_HANDLER_KEY } =
    await import("./handler/metamodelAstDataHandler.js");

const envConfig = parseServiceConfigFromEnv();

/**
 * Plugin definition for the metamodel service.
 */
const metamodelServicePlugin: ServicePluginDefinition = {
    id: "metamodel-service",
    name: "Metamodel",
    description: "Language support for metamodel definitions (.mm files)",
    icon: convertIcon(Network),
    languagePlugins: [metamodelLanguagePlugin],
    contributionPlugins: [
        {
            languageId: "config",
            description: "Provides metamodel type exports for config language",
            additionalKeywords: [],
            serverContributionPlugins: [createMetamodelConfigContributionPlugin()]
        }
    ]
};

/**
 * Language configuration for the metamodel language.
 */
const metamodelLanguageConfig: LanguageServiceConfig<MetamodelServices> = {
    languagePlugin: metamodelLanguagePlugin,
    languagePluginProvider: metamodelPluginProvider,
    fileDataHandlers: {
        [AST_HANDLER_KEY]: astHandler,
        [METAMODEL_AST_DATA_HANDLER_KEY]: metamodelAstDataHandler
    }
};

const config: ServiceConfig<MetamodelServices> = {
    ...envConfig,
    plugin: metamodelServicePlugin,
    languages: [metamodelLanguageConfig]
};

await startLanguageService(config);
