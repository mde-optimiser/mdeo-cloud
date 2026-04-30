import { MetaModelRule } from "./grammar/metamodelRules.js";
import {
    type LangiumLanguagePlugin,
    WS,
    ML_COMMENT,
    SL_COMMENT,
    HIDDEN_NEWLINE,
    type LangiumLanguagePluginProvider,
    type ExternalReferenceAdditionalServices
} from "@mdeo/language-common";
import {
    IdValueConverter,
    NewlineAwareTokenBuilder,
    DefaultAstSerializer,
    SerializerFormatter,
    registerDefaultTokenSerializers,
    addExternalReferenceCollectionPhase,
    type ActionHandlerRegistryAdditionalServices,
    DefaultActionProvider,
    DefaultWorkspaceEditService,
    ActionHandlerRegistry
} from "@mdeo/language-shared";
import { MetamodelScopeProvider } from "./features/metamodelScopeProvider.js";
import { registerMetamodelSerializers } from "./features/metamodelSerializers.js";
import { MetamodelDiagramModule } from "./features/diagram-server/metamodelDiagramModule.js";
import { MetamodelNameProvider } from "./features/metamodelNameProvider.js";
import { MetamodelScopeComputation } from "./features/metamodelScopeComputation.js";
import { MetamodelExternalReferenceCollector } from "./features/metamodelExternalReferenceCollector.js";
import { ImportFileActionHandler } from "./action-handlers/importFileActionHandler.js";
import { registerMetamodelValidationChecks } from "./validation/metamodelValidator.js";
import { MetamodelCompletionProvider } from "./features/metamodelCompletionProvider.js";
import { MetamodelCodeActionProvider } from "./features/metamodelCodeActionProvider.js";

/**
 * The additional services for the Metamodel language.
 */
export type MetamodelServices = ExternalReferenceAdditionalServices & ActionHandlerRegistryAdditionalServices;

/**
 * The plugin for the Metamodel language.
 * Configures the language with newline-aware lexing and custom parsing behavior.
 */
const metamodelPlugin: LangiumLanguagePlugin<MetamodelServices> = {
    rootRule: MetaModelRule,
    additionalTerminals: [WS, HIDDEN_NEWLINE, ML_COMMENT, SL_COMMENT],
    module: {
        parser: {
            TokenBuilder: () => new NewlineAwareTokenBuilder(new Set(["{"]), new Set(["("]), new Set(["}", ")"])),
            ValueConverter: () => new IdValueConverter(),
            ParserConfig: () => ({
                maxLookahead: 4
            })
        },
        references: {
            ScopeProvider: (services) => new MetamodelScopeProvider(services),
            NameProvider: (services) => new MetamodelNameProvider(services),
            ScopeComputation: (services) => new MetamodelScopeComputation(services),
            ExternalReferenceCollector: () => new MetamodelExternalReferenceCollector()
        },
        lsp: {
            CompletionProvider: (services) => new MetamodelCompletionProvider(services as any),
            Formatter: (services) => new SerializerFormatter(services),
            CodeActionProvider: (services) => new MetamodelCodeActionProvider(services)
        },
        AstSerializer: (services) => new DefaultAstSerializer(services),
        action: {
            ActionHandlerRegistry: (services) => {
                const registry = new ActionHandlerRegistry();
                registry.register("import-file", new ImportFileActionHandler(services));
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
        registerMetamodelSerializers(services);
        registerMetamodelValidationChecks(services);
        services.shared.glsp.serverModule.configureDiagramModule(new MetamodelDiagramModule(services));
        addExternalReferenceCollectionPhase(services);
    }
};

/**
 * Provider for the Metamodel language plugin.
 */
export const metamodelPluginProvider: LangiumLanguagePluginProvider<MetamodelServices> = {
    create(): LangiumLanguagePlugin<MetamodelServices> {
        return metamodelPlugin;
    }
};
