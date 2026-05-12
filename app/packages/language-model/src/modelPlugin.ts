import {
    type ExternalReferenceAdditionalServices,
    type LangiumLanguagePlugin,
    type LangiumLanguagePluginProvider
} from "@mdeo/language-common";
import { ModelRule, ModelTerminals } from "./grammar/modelRules.js";
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

export type ModelServices = ExternalReferenceAdditionalServices & ActionHandlerRegistryAdditionalServices;

/**
 * The plugin for the Model language.
 * Provides minimal language server functionality for .m files.
 */
function createModelPlugin(languageJsUrl?: string): LangiumLanguagePlugin<ModelServices> {
    return {
        rootRule: ModelRule,
        additionalTerminals: ModelTerminals,
        module: {
        parser: {
            TokenBuilder: () => new NewlineAwareTokenBuilder(new Set(["{"]), new Set(["("]), new Set(["}", ")"])),
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

/**
 * Provider for the Model language plugin.
 */
export const modelPluginProvider: LangiumLanguagePluginProvider<ModelServices> = {
    create(_contributionPlugins, languageJsUrl): LangiumLanguagePlugin<ModelServices> {
        return createModelPlugin(languageJsUrl);
    }
};
