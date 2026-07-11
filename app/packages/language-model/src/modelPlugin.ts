import {
    type ExternalReferenceAdditionalServices,
    type LangiumLanguagePlugin,
    type LangiumLanguagePluginProvider
} from "@mdeo/language-common";
import { createModelRule, ModelTerminals } from "./grammar/modelRules.js";
import {
    addExternalReferenceCollectionPhase,
    type ActionHandlerRegistryAdditionalServices,
    DefaultAstSerializer,
    IdValueConverter,
    NewlineAwareTokenBuilder,
    SerializerFormatter,
    DefaultActionProvider,
    ActionHandlerRegistry,
    registerDefaultTokenSerializers,
    DefaultWorkspaceEditService
} from "@mdeo/language-shared";
import { ModelScopeProvider } from "./features/modelScopeProvider.js";
import { ModelExternalReferenceCollector } from "./features/modelExternalReferenceCollector.js";
import { NewFileActionHandler } from "./action-handlers/newFileActionHandler.js";
import { registerModelSerializers } from "./features/modelSerializers.js";
import { ModelDiagramModule } from "./features/diagram-server/modelDiagramModule.js";
import { registerModelValidationChecks } from "./validation/modelValidator.js";
import { ModelCompletionProvider } from "./features/modelCompletionProvider.js";
import type { ModelContributionPlugin } from "./modelContributionPlugin.js";
import type { ServerContributionPlugin } from "@mdeo/plugin";
import { CsvClassImportRule, CsvImportBlockRule } from "./csv/csvImportRules.js";

export type ModelServices = ExternalReferenceAdditionalServices & ActionHandlerRegistryAdditionalServices;

const CSV_CONTRIBUTION: ModelContributionPlugin = {
    type: "model-contribution-plugin",
    id: "model-csv",
    name: "CSV Import",
    additionalTerminals: [],
    additionalRules: [CsvClassImportRule, CsvImportBlockRule],
    topLevelRuleNames: ["CsvImportBlockRule"],
    keywords: ["import", "CSV", "from"]
};

function isModelContributionPlugin(plugin: ServerContributionPlugin): plugin is ModelContributionPlugin {
    return (plugin as ModelContributionPlugin).topLevelRuleNames !== undefined;
}

function createModelPlugin(contributionPlugins: ServerContributionPlugin[], languageJsUrl?: string): LangiumLanguagePlugin<ModelServices> {
    const allContributions = [
        CSV_CONTRIBUTION,
        ...contributionPlugins.filter(isModelContributionPlugin)
    ];

    const contributedTopLevelRules = allContributions.flatMap(p =>
        p.additionalRules.filter(r => p.topLevelRuleNames.includes(r.name))
    );
    const contributedTerminals = allContributions.flatMap(p => p.additionalTerminals);

    const ModelRule = createModelRule(contributedTopLevelRules);

    return {
        rootRule: ModelRule,
        additionalTerminals: [...ModelTerminals, ...contributedTerminals],
        module: {
            parser: {
                TokenBuilder: () => new NewlineAwareTokenBuilder(
                    new Set(["{"]),
                    new Set(["("]),
                    new Set(["}", ")"])
                ),
                ValueConverter: () => new IdValueConverter(),
                ParserConfig: () => ({
                    maxLookahead: 4
                })
            },
            references: {
                ScopeProvider: (services) => new ModelScopeProvider(services),
                ExternalReferenceCollector: () => new ModelExternalReferenceCollector()
            },
            lsp: {
                CompletionProvider: (services) => new ModelCompletionProvider(services as any),
                Formatter: (services) => new SerializerFormatter(services)
            },
            AstSerializer: (services) => new DefaultAstSerializer(services),
            action: {
                ActionHandlerRegistry: (services) => {
                    const registry = new ActionHandlerRegistry();
                    registry.register("new-file", new NewFileActionHandler(services.shared));
                    return registry;
                },
                ActionProvider: () => new DefaultActionProvider()
            },
            workspace: {
                WorkspaceEdit: (services) => new DefaultWorkspaceEditService(services)
            }
        },
        postCreate(services) {
            registerDefaultTokenSerializers(services);
            registerModelSerializers(services);
            registerModelValidationChecks(services);
            services.shared.glsp.serverModule.configureDiagramModule(new ModelDiagramModule(services, languageJsUrl));
            addExternalReferenceCollectionPhase(services);
        }
    };
}

export const modelPluginProvider: LangiumLanguagePluginProvider<ModelServices> = {
    create(contributionPlugins, languageJsUrl): LangiumLanguagePlugin<ModelServices> {
        return createModelPlugin(contributionPlugins, languageJsUrl);
    }
};

export function registerModelContributionPlugin(_plugin: ModelContributionPlugin): void {}
