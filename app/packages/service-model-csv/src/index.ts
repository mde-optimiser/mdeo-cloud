import { Table } from "lucide";
import { convertIcon } from "@mdeo/language-common";
import type { ModelCsvServices } from "@mdeo/language-model-csv";
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
import { MODEL_PLUGIN_REQUEST_KEY } from "@mdeo/service-model-common";
import { DOCUMENTATION_BASE_URL, type LanguagePlugin } from "@mdeo/plugin";

const icon = convertIcon(Table);

/**
 * Language plugin definition for the model-csv language.
 * This is a generated language that provides services for the CSV import contribution.
 */
const modelCsvLanguagePlugin: LanguagePlugin = {
    id: "model-csv",
    name: "Model CSV",
    extension: undefined,
    newFileAction: false,
    icon,
    serverPlugin: {
        import: "language.js"
    },
    graphicalEditorPlugin: undefined,
    textualEditorPlugin: undefined,
    isGenerated: true,
    documentationUrl: `${DOCUMENTATION_BASE_URL}/plugins/model-csv`
};

initializePluginContext();

const { modelCsvPluginProvider, createModelCsvContributionPlugin } = await import("@mdeo/language-model-csv");
const { csvImportRequestHandler } = await import("./handler/csvImportRequestHandler.js");

/**
 * Plugin definition for the model-csv service.
 */
const modelCsvServicePlugin: ServicePluginDefinition = {
    id: "model-csv-service",
    name: "Model CSV",
    description: "Language support for the CSV import contribution to the model language",
    icon,
    languagePlugins: [modelCsvLanguagePlugin],
    contributionPlugins: [
        {
            languageId: "model",
            description: "Provides import CSV { ClassName from \"file.csv\" { \"column\" = property } } syntax for the model language",
            additionalKeywords: ["import", "CSV", "from"],
            serverContributionPlugins: [createModelCsvContributionPlugin()]
        }
    ]
};

const envConfig = parseServiceConfigFromEnv();

/**
 * Language configuration for the model-csv language.
 */
const modelCsvLanguageConfig: LanguageServiceConfig<ModelCsvServices> = {
    languagePlugin: modelCsvLanguagePlugin,
    languagePluginProvider: modelCsvPluginProvider,
    fileDataHandlers: {
        [AST_HANDLER_KEY]: astHandler
    },
    requestHandlers: {
        [MODEL_PLUGIN_REQUEST_KEY]: csvImportRequestHandler
    }
};

const config: ServiceConfig<any> = {
    ...envConfig,
    plugin: modelCsvServicePlugin,
    languages: [modelCsvLanguageConfig]
};

await startLanguageService(config);
