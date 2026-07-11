import { BookOpen } from "lucide";
import {
    defaultLanguageConfiguration,
    defaultMonarchTokenProvider,
    serializeMonarchTokensProvider,
    convertIcon
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
import type { ModelServices } from "@mdeo/language-model";
import type { LanguagePlugin } from "@mdeo/plugin";

const modelLanguagePlugin: LanguagePlugin = {
    id: "model",
    name: "Model",
    extension: ".m",
    icon: convertIcon(BookOpen),
    serverPlugin: {
        import: "language.js"
    },
    graphicalEditorPlugin: {
        import: "editor.js",
        stylesUrl: "styles.css",
        stylesCls: "editor-model"
    },
    textualEditorPlugin: {
        languageConfiguration: defaultLanguageConfiguration,
        monarchTokensProvider: serializeMonarchTokensProvider({
            ...defaultMonarchTokenProvider,
            keywords: ["using", "import", "CSV", "from"]
        })
    },
    isGenerated: false
};

initializePluginContext();

const { modelPluginProvider, registerModelContributionPlugin } = await import("@mdeo/language-model");
const { createModelCsvContributionPlugin } = await import("@mdeo/language-model-csv");

registerModelContributionPlugin(createModelCsvContributionPlugin());

const envConfig = parseServiceConfigFromEnv();

const modelServicePlugin: ServicePluginDefinition = {
    id: "model-service",
    name: "Model",
    description: "Language support for model definitions (.m files)",
    icon: convertIcon(BookOpen),
    languagePlugins: [modelLanguagePlugin],
    contributionPlugins: []
};

const modelLanguageConfig: LanguageServiceConfig<ModelServices> = {
    languagePlugin: modelLanguagePlugin,
    languagePluginProvider: modelPluginProvider,
    fileDataHandlers: {
        [AST_HANDLER_KEY]: astHandler
    }
};

const config: ServiceConfig<ModelServices> = {
    ...envConfig,
    plugin: modelServicePlugin,
    languages: [modelLanguageConfig]
};

await startLanguageService(config);
