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
    DefaultWorkspaceEditService
} from "@mdeo/language-shared";
import { GeneratedModelTransformationDiagramModule } from "./features/diagram-server/generated/generatedModelTransformationDiagramModule.js";
import { GeneratedModelTransformationActionProvider } from "./features/generatedModelTransformationActionProvider.js";
import { SaveGeneratedModelTransformationActionHandler } from "./action-handlers/saveGeneratedModelTransformationActionHandler.js";
import type { LangiumDocument, URI } from "langium";

export type GeneratedModelTransformationServices = ExternalReferenceAdditionalServices &
    ActionHandlerRegistryAdditionalServices;

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
            }
        },
        postCreate(services) {
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
