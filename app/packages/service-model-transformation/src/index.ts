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
    astHandler,
    AST_HANDLER_KEY,
    startLanguageService
} from "@mdeo/service-common";
import type { ModelTransformationServices } from "@mdeo/language-model-transformation";
import type { GeneratedModelTransformationServices } from "@mdeo/language-model-transformation";
import type { LanguagePlugin } from "@mdeo/plugin";

const icon: ActionIconNode = [
    [
        "path",
        {
            d: "M 19 15 L 22 18"
        }
    ],
    [
        "path",
        {
            d: "M 22 18 L 19 21"
        }
    ],
    [
        "path",
        {
            d: "M2 18h20"
        }
    ],
    [
        "path",
        {
            d: "M4.8 10.4V8.6a.6.6 0 0 1 .6-.6h7.2a.6.6 0 0 1 .6.6v1.8"
        }
    ],
    [
        "path",
        {
            d: "M9 8V5.6"
        }
    ],
    [
        "rect",
        {
            x: "11.4",
            y: "10.4",
            width: "3.6",
            height: "3.6",
            rx: ".6",
            fill: "currentColor"
        }
    ],
    [
        "rect",
        {
            x: "3",
            y: "10.4",
            width: "3.6",
            height: "3.6",
            rx: ".6",
            fill: "currentColor"
        }
    ],
    [
        "rect",
        {
            x: "7.2",
            y: "2",
            width: "3.6",
            height: "3.6",
            rx: ".6",
            fill: "currentColor"
        }
    ]
];

/**
 * Language plugin definition for the model transformation language.
 */
const modelTransformationLanguagePlugin: LanguagePlugin = {
    id: "model-transformation",
    name: "Model Transformation",
    extension: ".mt",
    newFileAction: true,
    icon,
    serverPlugin: {
        import: "language.js"
    },
    textualEditorPlugin: {
        languageConfiguration: defaultLanguageConfiguration,
        monarchTokensProvider: serializeMonarchTokensProvider({
            ...defaultMonarchTokenProvider,
            keywords: [
                "using",
                "match",
                "if",
                "then",
                "else",
                "while",
                "until",
                "for",
                "do",
                "var",
                "create",
                "delete",
                "forbid",
                "require",
                "where",
                "kill",
                "stop",
                "true",
                "false",
                "null"
            ]
        })
    },
    graphicalEditorPlugin: {
        import: "editor.js",
        stylesUrl: "styles.css",
        stylesCls: "editor-model-transformation"
    },
    isGenerated: false
};

/**
 * Language plugin definition for generated model transformation files.
 */
const generatedModelTransformationLanguagePlugin: LanguagePlugin = {
    id: "model-transformation_gen",
    name: "Generated Model Transformation",
    extension: ".mt_gen",
    newFileAction: false,
    icon,
    serverPlugin: {
        import: "generatedLanguage.js"
    },
    graphicalEditorPlugin: {
        import: "editor.js",
        stylesUrl: "styles.css",
        stylesCls: "editor-model-transformation"
    },
    textualEditorPlugin: undefined,
    isGenerated: true
};

/**
 * Plugin definition for the model transformation service.
 */
const modelTransformationServicePlugin: ServicePluginDefinition = {
    id: "model-transformation-service",
    name: "Model Transformation",
    description: "Language support for model transformation definitions (.mt files)",
    icon,
    languagePlugins: [modelTransformationLanguagePlugin, generatedModelTransformationLanguagePlugin],
    contributionPlugins: []
};

initializePluginContext();

const { modelTransformationPluginProvider } = await import("@mdeo/language-model-transformation");
const { generatedModelTransformationPluginProvider } = await import("@mdeo/language-model-transformation");
const { typedAstHandler, TYPED_AST_HANDLER_KEY } = await import("./handler/typedAstHandler.js");
const { generatedModelTransformationTextHandler, GENERATED_MODEL_TRANSFORMATION_TEXT_HANDLER_KEY } =
    await import("./handler/generatedModelTransformationTextHandler.js");
const { ModelTransformationExecutionHandler } = await import("./handler/modelTransformationExecutionHandler.js");

const envConfig = parseServiceConfigFromEnv();

/**
 * URL of the model-transformation-execution backend service.
 * Defaults to http://localhost:8082 if not specified.
 */
const modelTransformationExecutionServiceUrl =
    process.env.MODEL_TRANSFORMATION_EXECUTION_SERVICE_URL ?? "http://localhost:8082";

/**
 * Execution handler for model transformation executions.
 * Forwards execution requests to the model-transformation-execution backend service.
 */
const modelTransformationExecutionHandler = new ModelTransformationExecutionHandler(
    modelTransformationExecutionServiceUrl
);

/**
 * Language configuration for the model transformation language.
 */
const modelTransformationLanguageConfig: LanguageServiceConfig<ModelTransformationServices> = {
    languagePlugin: modelTransformationLanguagePlugin,
    languagePluginProvider: modelTransformationPluginProvider,
    fileDataHandlers: {
        [AST_HANDLER_KEY]: astHandler,
        [TYPED_AST_HANDLER_KEY]: typedAstHandler
    },
    executionHandlers: [modelTransformationExecutionHandler]
};

/**
 * Language configuration for generated model transformation files.
 */
const generatedModelTransformationLanguageConfig: LanguageServiceConfig<GeneratedModelTransformationServices> = {
    languagePlugin: generatedModelTransformationLanguagePlugin,
    languagePluginProvider: generatedModelTransformationPluginProvider,
    fileDataHandlers: {
        [AST_HANDLER_KEY]: astHandler,
        [GENERATED_MODEL_TRANSFORMATION_TEXT_HANDLER_KEY]: generatedModelTransformationTextHandler
    },
    executionHandlers: []
};

const config: ServiceConfig<any> = {
    ...envConfig,
    plugin: modelTransformationServicePlugin,
    languages: [modelTransformationLanguageConfig, generatedModelTransformationLanguageConfig]
};

await startLanguageService(config);
