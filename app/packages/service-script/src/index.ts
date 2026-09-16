import { FileCode } from "lucide";
import { TYPED_AST_CLOSURE_HANDLER_KEY, typedAstClosureHandler } from "./handler/typedAstClosureHandler.js";
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
    AST_HANDLER_KEY,
    astHandler
} from "@mdeo/service-common";
import { convertIcon } from "@mdeo/language-common";
import type { ScriptServices } from "@mdeo/language-script";
import { DOCUMENTATION_BASE_URL, type LanguagePlugin } from "@mdeo/plugin";

/**
 * Language plugin definition for the script language.
 */
const scriptLanguagePlugin: LanguagePlugin = {
    id: "script",
    name: "Script",
    extension: ".fn",
    newFileAction: true,
    icon: convertIcon(FileCode),
    serverPlugin: {
        import: "language.js"
    },
    graphicalEditorPlugin: undefined,
    textualEditorPlugin: {
        languageConfiguration: defaultLanguageConfiguration,
        monarchTokensProvider: serializeMonarchTokensProvider({
            ...defaultMonarchTokenProvider,
            keywords: [
                "import",
                "using",
                "from",
                "as",
                "fun",
                "return",
                "if",
                "else",
                "while",
                "for",
                "break",
                "continue",
                "var",
                "true",
                "false",
                "null",
                "in",
                "is"
            ]
        })
    },
    isGenerated: false,
    documentationUrl: `${DOCUMENTATION_BASE_URL}/plugins/script`
};

initializePluginContext();

const { scriptPluginProvider } = await import("@mdeo/language-script");
const { typedAstHandler, TYPED_AST_HANDLER_KEY } = await import("./handler/typedAstHandler.js");
const { ScriptExecutionHandler } = await import("./handler/scriptExecutionHandler.js");
const { createScriptConfigContributionPlugin } = await import("./scriptConfigContributionPlugin.js");

const envConfig = parseServiceConfigFromEnv();

/**
 * Plugin definition for the script service.
 */
const scriptServicePlugin: ServicePluginDefinition = {
    id: "script-service",
    name: "Script",
    description: "Language support for script definitions (.s files)",
    icon: convertIcon(FileCode),
    languagePlugins: [scriptLanguagePlugin],
    contributionPlugins: [
        {
            languageId: "config",
            description: "Provides script function type exports for config language",
            additionalKeywords: [],
            serverContributionPlugins: [createScriptConfigContributionPlugin()]
        }
    ]
};

/**
 * URL of the script-execution backend service.
 * Defaults to http://localhost:8081 if not specified.
 */
const scriptExecutionServiceUrl = process.env.SCRIPT_EXECUTION_SERVICE_URL ?? "http://localhost:8081";

/**
 * Execution handler for script executions.
 * Forwards execution requests to the script-execution backend service.
 */
const scriptExecutionHandler = new ScriptExecutionHandler(scriptExecutionServiceUrl);

/**
 * Language configuration for the script language.
 */
const scriptLanguageConfig: LanguageServiceConfig<ScriptServices> = {
    languagePlugin: scriptLanguagePlugin,
    languagePluginProvider: scriptPluginProvider,
    fileDataHandlers: {
        [AST_HANDLER_KEY]: astHandler,
        [TYPED_AST_HANDLER_KEY]: typedAstHandler,
        [TYPED_AST_CLOSURE_HANDLER_KEY]: typedAstClosureHandler
    },
    executionHandlers: [scriptExecutionHandler]
};

const config: ServiceConfig<ScriptServices> = {
    ...envConfig,
    plugin: scriptServicePlugin,
    languages: [scriptLanguageConfig]
};

await startLanguageService(config);
