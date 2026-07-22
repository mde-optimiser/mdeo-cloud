import {
    type ExternalReferenceAdditionalServices,
    type LangiumLanguagePlugin,
    type LangiumLanguagePluginProvider,
    type ExternalReferenceCollector,
    type ExternalReferences
} from "@mdeo/language-common";
import { GeneratedModelRule } from "./grammar/generatedModelRules.js";
import {
    type ActionHandlerRegistryAdditionalServices,
    ActionHandlerRegistry,
    DefaultWorkspaceEditService
} from "@mdeo/language-shared";
import { GeneratedModelDiagramModule } from "./features/diagram-server/generated/generatedModelDiagramModule.js";
import { GeneratedModelActionProvider } from "./features/generatedModelActionProvider.js";
import { SaveGeneratedModelActionHandler } from "./action-handlers/saveGeneratedModelActionHandler.js";
import type { LangiumDocument, URI } from "langium";

export type GeneratedModelServices = ExternalReferenceAdditionalServices & ActionHandlerRegistryAdditionalServices;

/**
 * Empty external reference collector for the generated model language.
 * Generated models do not reference external files.
 */
class EmptyExternalReferenceCollector implements ExternalReferenceCollector {
    findExternalReferences(_docs: LangiumDocument[]): ExternalReferences {
        return { local: [] as URI[], external: [] as URI[] };
    }
}

/**
 * The plugin for the Generated Model language.
 * Provides minimal language server functionality for .m_gen files.
 * The language treats the entire file content as JSON, captured in a single string field.
 *
 * @param languageJsUrl The HTTP URL of this plugin's `language.js` entry file, used
 * to bind the GED worker so the metadata manager can attempt a smart old-to-new ID
 * remapping after a textual edit, instead of always falling back to naive ID matching.
 */
function createGeneratedModelPlugin(languageJsUrl?: string): LangiumLanguagePlugin<GeneratedModelServices> {
    return {
        rootRule: GeneratedModelRule,
        additionalTerminals: [],
        module: {
            references: {
                ExternalReferenceCollector: () => new EmptyExternalReferenceCollector()
            },
            action: {
                ActionHandlerRegistry: (services) => {
                    const registry = new ActionHandlerRegistry();
                    registry.register("save-as-model", new SaveGeneratedModelActionHandler(services.shared));
                    return registry;
                },
                ActionProvider: () => new GeneratedModelActionProvider()
            },
            workspace: {
                WorkspaceEdit: (services) => new DefaultWorkspaceEditService(services)
            }
        },
        postCreate(services) {
            services.shared.glsp.serverModule.configureDiagramModule(
                new GeneratedModelDiagramModule(services, languageJsUrl)
            );
        }
    };
}

/**
 * Provider for the Generated Model language plugin.
 */
export const generatedModelPluginProvider: LangiumLanguagePluginProvider<GeneratedModelServices> = {
    create(_contributionPlugins, languageJsUrl): LangiumLanguagePlugin<GeneratedModelServices> {
        return createGeneratedModelPlugin(languageJsUrl);
    }
};
