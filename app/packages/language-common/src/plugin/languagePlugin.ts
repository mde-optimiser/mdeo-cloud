import type { DeepPartial, Module } from "langium";
import type { TerminalRule } from "../grammar/rule/terminal/types.js";
import type { ParserRule } from "../grammar/rule/types.js";
import type { PluginContext } from "./pluginContext.js";
import type { DefaultSharedModuleContext } from "langium/lsp";
import type { ExtendedLangiumServices } from "../grammar/module/extendedServices.js";
import type { GLSPSharedAdditionalServices } from "../glsp/glspModule.js";
import type { MetadataFileSystemProviderAdditionalServices } from "../protocol/metadataFileSystemProvider.js";
import type { AstSerializerAdditionalServices } from "../protocol/astSerializer.js";
import type { ServerContributionPlugin } from "@mdeo/plugin";
import type { ExternalReferenceSharedAdditionalServices } from "../protocol/externalReference.js";
import type { WorkspaceEditAdditionalServices } from "../protocol/workspaceEditAdditionalServices.js";

/**
 * Combined language services including GLSP shared additional services
 */
export type LanguageServices = ExtendedLangiumServices &
    MetadataFileSystemProviderAdditionalServices & {
        shared: GLSPSharedAdditionalServices & ExternalReferenceSharedAdditionalServices;
    } & AstSerializerAdditionalServices &
    WorkspaceEditAdditionalServices;

/**
 * Partial type for language services including GLSP shared additional services
 */
export type PartialLanguageServices = DeepPartial<LanguageServices>;

/**
 * Language plugin, which provides support for one language for a langium-based language server.
 *
 * @template T The type of the language's additional services
 */
export interface LangiumLanguagePlugin<T> {
    /**
     * The root parser rule that serves as the entry point for parsing
     */
    rootRule: ParserRule<any>;
    /**
     * Array of terminal rules that should be included in the grammar
     */
    additionalTerminals: TerminalRule<any>[];
    /**
     * The module for the language
     */
    module: Module<LanguageServices & T, PartialLanguageServices & T>;
    /**
     * Optional callback that is invoked after the language services have been created.
     *
     * @param services the created language services
     * @param context module context with the LSP connection
     */
    postCreate?: (services: LanguageServices & T, context: DefaultSharedModuleContext) => void;
}

/**
 * Provider for a Langium language plugin.
 *
 * @template T The type of the language's additional services
 */
export interface LangiumLanguagePluginProvider<T> {
    /**
     * @param contributionPlugins The server contribution plugins to include
     * @param languageJsUrl The HTTP URL of the plugin's served `language.js` file.
     *                      When provided it is forwarded to the diagram module so
     *                      the metadata manager can derive the correct GED worker
     *                      URL at runtime, even when static files are versioned.
     */
    create(contributionPlugins: ServerContributionPlugin[], languageJsUrl?: string): LangiumLanguagePlugin<T>;
}

/**
 * Type for a function which when provided a PluginContext returns a service provider function which can be used for module definitions.
 *
 * @template T The type of the language's additional services
 * @template V The type of the provided service
 */
export type ServiceProvider<T, V> = (context: PluginContext) => (services: LanguageServices & T) => V;
