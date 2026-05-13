import {
    type AstReflection,
    type ExternalReferenceAdditionalServices,
    type LangiumLanguagePlugin,
    type LangiumLanguagePluginProvider
} from "@mdeo/language-common";
import {
    IdValueConverter,
    DefaultAstSerializer,
    SerializerFormatter,
    registerDefaultTokenSerializers,
    sharedImport,
    ActionHandlerRegistry,
    generateExtendedParser,
    type ActionHandlerRegistryAdditionalServices,
    DefaultWorkspaceEditService
} from "@mdeo/language-shared";
import {
    defaultExtendedTypirServices,
    type AdditionalTypirServices,
    type ExpressionTypirServices,
    registerExpressionSerializers,
    registerTypeSerializers,
    generateExpressionRuleOverride,
    type DocumentPackageCacheService,
    ExpressionHoverProvider
} from "@mdeo/language-expression";
import type { TypirLangiumSpecifics } from "typir-langium";
import type { AbstractAstReflection } from "langium";
import { generateModelTransformationRules, ModelTransformationTerminals } from "./grammar/modelTransformationRules.js";
import { ModelTransformationCompletionProvider } from "./features/modelTransformationCompletionProvider.js";
import { ModelTransformationTokenBuilder } from "./features/modelTransformationTokenBuilder.js";
import { expressionConfig, expressionTypes, typeTypes } from "./grammar/modelTransformationTypes.js";
import { ModelTransformationDocumentPackageCacheService } from "./features/modelTransformationDocumentPackageCacheService.js";
import { ModelTransformationLangiumScopeProvider } from "./features/modelTransformationScopeProvider.js";
import { ModelTransformationScopeComputation } from "./features/modelTransformationScopeComputation.js";
import { ModelTransformationTypeSystem } from "./features/type-system/modelTransformationTypeSystem.js";
import { ModelTransformationTypirScopeProvider } from "./features/type-system/modelTransformationTypirScopeProvider.js";
import { registerModelTransformationSerializers } from "./features/modelTransformationSerializers.js";
import { ModelTransformationExternalReferenceCollector } from "./features/modelTransformationExternalReferenceCollector.js";
import { NewFileActionHandler } from "./action-handlers/newFileActionHandler.js";
import { RunModelTransformationActionHandler } from "./action-handlers/runModelTransformationActionHandler.js";
import { ModelTransformationActionProvider } from "./features/modelTransformationActionProvider.js";
import { ModelTransformationCodeActionProvider } from "./features/modelTransformationCodeActionProvider.js";
import { addExternalReferenceCollectionPhase } from "@mdeo/language-shared";
import { registerModelTransformationValidationChecks } from "./validation/modelTransformationValidator.js";
import { ModelTransformationDiagramModule } from "./features/diagram-server/modelTransformationDiagramModule.js";

const { createTypirLangiumServicesWithAdditionalServices, initializeLangiumTypirServices } =
    sharedImport("typir-langium");

/**
 * Typir specifics for the Model Transformation language.
 */
export type ModelTransformationTypirSpecifics = TypirLangiumSpecifics;

/**
 * Additional Typir services for Model Transformation.
 */
type AdditionalModelTransformationTypirServices = AdditionalTypirServices<ModelTransformationTypirSpecifics>;

/**
 * Typir services for Model Transformation.
 */
export type ModelTransformationTypirServices = ExpressionTypirServices<ModelTransformationTypirSpecifics> &
    AdditionalModelTransformationTypirServices;

/**
 * Additional services for the Model Transformation language.
 */
export type ModelTransformationServices = {
    typir: ModelTransformationTypirServices;
} & ExternalReferenceAdditionalServices &
    ActionHandlerRegistryAdditionalServices;

/**
 * Creates the Model Transformation language plugin.
 *
 * @param importMetaUrl The `import.meta.url` of the plugin entry module, forwarded
 *                      to the diagram module for GED worker URL resolution.
 * @returns The Model Transformation language plugin.
 */
function createModelTransformationPlugin(languageJsUrl?: string): LangiumLanguagePlugin<ModelTransformationServices> {
    const { rule } = generateModelTransformationRules();

    return {
        rootRule: rule,
        additionalTerminals: ModelTransformationTerminals,
        module: {
            parser: {
                TokenBuilder: () =>
                    new ModelTransformationTokenBuilder(new Set(["{"]), new Set(["("]), new Set(["}", ")"])),
                ValueConverter: () => new IdValueConverter(),
                ...generateExtendedParser(generateExpressionRuleOverride(expressionConfig)),
                ParserConfig: () => ({
                    maxLookahead: 4
                })
            },
            references: {
                ScopeProvider: (services) => new ModelTransformationLangiumScopeProvider(services),
                ScopeComputation: (services) => new ModelTransformationScopeComputation(services),
                ExternalReferenceCollector: () => new ModelTransformationExternalReferenceCollector()
            },
            typir: (outerServices) => {
                const langiumSharedServices = outerServices.shared;

                return createTypirLangiumServicesWithAdditionalServices<
                    ModelTransformationTypirSpecifics,
                    AdditionalModelTransformationTypirServices
                >(
                    langiumSharedServices,
                    langiumSharedServices.AstReflection as AbstractAstReflection & AstReflection,
                    new ModelTransformationTypeSystem(),
                    {
                        ...defaultExtendedTypirServices<ModelTransformationTypirSpecifics>(),
                        ScopeProvider: (typirServices) =>
                            new ModelTransformationTypirScopeProvider(
                                typirServices as ExpressionTypirServices<ModelTransformationTypirSpecifics>
                            ),
                        PackageMapCache: (): DocumentPackageCacheService =>
                            new ModelTransformationDocumentPackageCacheService(langiumSharedServices)
                    }
                ) as ModelTransformationTypirServices;
            },
            lsp: {
                CompletionProvider: (services) => new ModelTransformationCompletionProvider(services, expressionTypes),
                HoverProvider: (services) => new ExpressionHoverProvider(services, expressionTypes, typeTypes),
                Formatter: (services) => new SerializerFormatter(services),
                CodeActionProvider: (services) => new ModelTransformationCodeActionProvider(services)
            },
            AstSerializer: (services) => new DefaultAstSerializer(services),
            action: {
                ActionHandlerRegistry: (services) => {
                    const registry = new ActionHandlerRegistry();
                    registry.register("new-file", new NewFileActionHandler(services.shared));
                    registry.register("run", new RunModelTransformationActionHandler(services.shared));
                    return registry;
                },
                ActionProvider: () => new ModelTransformationActionProvider()
            },
            workspace: {
                WorkspaceEdit: (services) => new DefaultWorkspaceEditService(services)
            }
        },
        postCreate(services) {
            initializeLangiumTypirServices(services, services.typir);
            registerDefaultTokenSerializers(services);
            registerTypeSerializers(services, typeTypes);
            registerExpressionSerializers(services, expressionTypes);
            registerModelTransformationSerializers(services);
            registerModelTransformationValidationChecks(services);
            services.shared.glsp.serverModule.configureDiagramModule(
                new ModelTransformationDiagramModule(services, languageJsUrl)
            );
            addExternalReferenceCollectionPhase(services);
        }
    };
}

/**
 * Provider for the Model Transformation language plugin.
 */
export const modelTransformationPluginProvider: LangiumLanguagePluginProvider<ModelTransformationServices> = {
    create(_contributionPlugins, languageJsUrl): LangiumLanguagePlugin<ModelTransformationServices> {
        return createModelTransformationPlugin(languageJsUrl);
    }
};
