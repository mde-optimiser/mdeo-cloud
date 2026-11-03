import {
    type ExternalReferenceAdditionalServices,
    type LangiumLanguagePlugin,
    type LangiumLanguagePluginProvider,
    type ExternalReferenceCollector,
    type ExternalReferences
} from "@mdeo/language-common";
import { GeneratedModelTransformationRule } from "./grammar/generatedModelTransformationRules.js";
import {
    type ActionHandlerRegistryAdditionalServices,
    ActionHandlerRegistry,
    DefaultAstSerializer,
    DefaultWorkspaceEditService,
    registerDefaultTokenSerializers
} from "@mdeo/language-shared";
import { registerExpressionSerializers, registerTypeSerializers } from "@mdeo/language-expression";
import type { AstSerializerAdditionalServices } from "@mdeo/language-common";
import { expressionTypes, typeTypes } from "./grammar/modelTransformationTypes.js";
import { RenderedExpression } from "./grammar/generatedModelTransformationTypes.js";
import { registerModelTransformationSerializers } from "./features/modelTransformationSerializers.js";
import { GeneratedModelTransformationDiagramModule } from "./features/diagram-server/generated/generatedModelTransformationDiagramModule.js";
import { GeneratedModelTransformationActionProvider } from "./features/generatedModelTransformationActionProvider.js";
import { SaveGeneratedModelTransformationActionHandler } from "./action-handlers/saveGeneratedModelTransformationActionHandler.js";
import type { LangiumDocument, URI } from "langium";

export type GeneratedModelTransformationServices = ExternalReferenceAdditionalServices &
    ActionHandlerRegistryAdditionalServices &
    AstSerializerAdditionalServices;

/**
 * Empty external reference collector for generated model transformations.
 */
class EmptyExternalReferenceCollector implements ExternalReferenceCollector {
    findExternalReferences(_docs: LangiumDocument[]): ExternalReferences {
        return { local: [] as URI[], external: [] as URI[] };
    }
}

/**
 * Plugin for generated model transformations (.mt_gen).
 *
 * @param languageJsUrl The HTTP URL of this plugin's `language.js` entry file, used
 * to bind the GED worker so the metadata manager can attempt a smart old-to-new ID
 * remapping after a textual edit, instead of always falling back to naive ID matching.
 */
function createGeneratedModelTransformationPlugin(
    languageJsUrl?: string
): LangiumLanguagePlugin<GeneratedModelTransformationServices> {
    return {
        rootRule: GeneratedModelTransformationRule,
        additionalTerminals: [],
        module: {
            references: {
                ExternalReferenceCollector: () => new EmptyExternalReferenceCollector()
            },
            action: {
                ActionHandlerRegistry: (services) => {
                    const registry = new ActionHandlerRegistry();
                    registry.register(
                        "save-as-model-transformation",
                        new SaveGeneratedModelTransformationActionHandler(services.shared)
                    );
                    return registry;
                },
                ActionProvider: () => new GeneratedModelTransformationActionProvider()
            },
            workspace: {
                WorkspaceEdit: (services) => new DefaultWorkspaceEditService(services)
            },
            // Prints the regular model transformation a generated one stands for.
            AstSerializer: (services) => new DefaultAstSerializer(services)
        },
        postCreate(services) {
            registerDefaultTokenSerializers(services);
            registerTypeSerializers(services, typeTypes);
            registerExpressionSerializers(services, expressionTypes);
            registerModelTransformationSerializers(services);
            services.AstSerializer.registerNodeSerializer(
                RenderedExpression,
                (context) => context.ctx.$cstNode?.text ?? "?"
            );
            services.shared.glsp.serverModule.configureDiagramModule(
                new GeneratedModelTransformationDiagramModule(services, languageJsUrl)
            );
        }
    };
}

/**
 * Provider for the generated model transformation language plugin.
 */
export const generatedModelTransformationPluginProvider: LangiumLanguagePluginProvider<GeneratedModelTransformationServices> =
    {
        create(_contributionPlugins, languageJsUrl): LangiumLanguagePlugin<GeneratedModelTransformationServices> {
            return createGeneratedModelTransformationPlugin(languageJsUrl);
        }
    };
