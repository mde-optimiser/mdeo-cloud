import { Table } from "lucide";
import {
    startLanguageService,
    parseServiceConfigFromEnv,
    initializePluginContext,
    AST_HANDLER_KEY,
    astHandler,
    type ServicePluginDefinition,
    type LanguageServiceConfig
} from "@mdeo/service-common";
import {
    convertIcon,
    defaultLanguageConfiguration,
    defaultMonarchTokenProvider,
    serializeMonarchTokensProvider,
    type ExternalReferenceAdditionalServices
} from "@mdeo/language-common";
import type { LanguagePlugin } from "@mdeo/plugin";

const csvLanguagePlugin: LanguagePlugin = {
    id: "csv",
    name: "CSV",
    extension: ".csv",
    newFileAction: true,
    icon: convertIcon(Table),
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
    isGenerated: false
};

const csvServicePlugin: ServicePluginDefinition = {
    id: "csv-service",
    name: "CSV",
    description: "Language support for CSV data files (.csv files)",
    icon: convertIcon(Table),
    languagePlugins: [csvLanguagePlugin],
    contributionPlugins: []
};

initializePluginContext();

const { csvPluginProvider } = await import("@mdeo/language-csv");

const envConfig = parseServiceConfigFromEnv();

const csvLanguageConfig: LanguageServiceConfig<ExternalReferenceAdditionalServices> = {
    languagePlugin: csvLanguagePlugin,
    languagePluginProvider: csvPluginProvider,
    fileDataHandlers: {
        [AST_HANDLER_KEY]: astHandler
    }
};

await startLanguageService({
    ...envConfig,
    plugin: csvServicePlugin,
    languages: [csvLanguageConfig]
});
