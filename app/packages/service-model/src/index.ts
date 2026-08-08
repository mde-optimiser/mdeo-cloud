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
import type { ModelServices, GeneratedModelServices } from "@mdeo/language-model";
import { DOCUMENTATION_BASE_URL, type LanguagePlugin } from "@mdeo/plugin";

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
    isGenerated: false,
    documentationUrl: `${DOCUMENTATION_BASE_URL}/plugins/model`
};

/**
 * Language plugin definition for the generated model language (.m_gen), e.g. optimizer solutions.
 */
const generatedModelLanguagePlugin: LanguagePlugin = {
    id: "model_gen",
    name: "Generated Model",
    extension: ".m_gen",
    newFileAction: false,
    icon: convertIcon(BookOpen),
    serverPlugin: {
        import: "generatedLanguage.js"
    },
    graphicalEditorPlugin: {
        import: "editor.js",
        stylesUrl: "styles.css",
        stylesCls: "editor-model"
    },
    textualEditorPlugin: undefined,
    isGenerated: true,
    documentationUrl: `${DOCUMENTATION_BASE_URL}/plugins/model`
};

initializePluginContext();

const { modelPluginProvider } = await import("@mdeo/language-model");
const { generatedModelPluginProvider } = await import("@mdeo/language-model");
const { modelDataHandler, MODEL_DATA_HANDLER_KEY } = await import("./handler/modelDataHandler.js");

const envConfig = parseServiceConfigFromEnv();

const modelServicePlugin: ServicePluginDefinition = {
    id: "model-service",
    name: "Model",
    description: "Language support for model definitions (.m and .m_gen files)",
    icon: convertIcon(BookOpen),
    languagePlugins: [modelLanguagePlugin, generatedModelLanguagePlugin],
    contributionPlugins: []
};

const modelLanguageConfig: LanguageServiceConfig<ModelServices> = {
    languagePlugin: modelLanguagePlugin,
    languagePluginProvider: modelPluginProvider,
    fileDataHandlers: {
        [AST_HANDLER_KEY]: astHandler,
        [MODEL_DATA_HANDLER_KEY]: modelDataHandler
    }
};

const generatedModelLanguageConfig: LanguageServiceConfig<GeneratedModelServices> = {
    languagePlugin: generatedModelLanguagePlugin,
    languagePluginProvider: generatedModelPluginProvider,
    fileDataHandlers: {
        [AST_HANDLER_KEY]: astHandler
    }
};

const config: ServiceConfig<any> = {
    ...envConfig,
    plugin: modelServicePlugin,
    languages: [modelLanguageConfig, generatedModelLanguageConfig]
};

await startLanguageService(config);
