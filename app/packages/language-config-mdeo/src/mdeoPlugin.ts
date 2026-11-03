import {
    type LangiumLanguagePlugin,
    type LangiumLanguagePluginProvider,
    type ExternalReferenceAdditionalServices,
    GrammarDeserializationContext,
    ID,
    NEWLINE,
    INT,
    STRING,
    FLOAT,
    HIDDEN_NEWLINE
} from "@mdeo/language-common";
import {
    DefaultAstSerializer,
    IdValueConverter,
    SerializerFormatter,
    registerDefaultTokenSerializers,
    addExternalReferenceCollectionPhase,
    ActionHandlerRegistry,
    type ActionHandlerRegistryAdditionalServices,
    DefaultActionProvider
} from "@mdeo/language-shared";
import { generateContributionPluginGrammar, ConfigTerminals, ConfigTokenBuilder } from "@mdeo/language-config";
import { Class, Enum, EnumEntry, Property } from "@mdeo/language-metamodel";
import { ModelTransformation } from "@mdeo/language-model-transformation";
import { MdeoScopeProvider } from "./features/mdeoScopeProvider.js";
import { MdeoExternalReferenceCollector } from "./features/mdeoExternalReferenceCollector.js";
import { registerMdeoSerializers } from "./features/mdeoSerializers.js";
import { createMdeoContributionPlugin } from "./plugin/mdeoContributionPlugin.js";
import { registerMdeoValidationChecks } from "./validation/mdeoValidator.js";
import { DefaultMdeoMetamodelResolver } from "./features/defaultMdeoMetamodelResolver.js";
import type { MdeoMetamodelResolver } from "./features/mdeoMetamodelResolver.js";
import { RunMdeoConfigActionHandler } from "./action-handlers/runMdeoConfigActionHandler.js";
import { MdeoCompletionProvider } from "./features/mdeoCompletionProvider.js";
import { MdeoHoverProvider } from "./features/mdeoHoverProvider.js";

/**
 * Additional services required by the MDEO language plugin.
 */
export interface MdeoAdditionalServices {
    /**
     * The metamodel resolver service for MDEO scope resolution.
     */
    MdeoMetamodelResolver: MdeoMetamodelResolver;
}

/**
 * Combined services type for MDEO language.
 */
export type MdeoServices = ExternalReferenceAdditionalServices &
    MdeoAdditionalServices &
    ActionHandlerRegistryAdditionalServices;

/**
 * Deserialization context for the MDEO grammar.
 * Provides the external interface types and common terminals
 * so the MDEO grammar can be deserialized from its serialized form.
 */
const mdeoDeserializationContext = GrammarDeserializationContext.create(
    [Class, Property, Enum, EnumEntry, ModelTransformation],
    [],
    [ID, NEWLINE, HIDDEN_NEWLINE, INT, FLOAT, STRING]
);

/**
 * The root rule for the standalone config-mdeo language.
 * Generated from the MDEO contribution plugin's grammar.
 */
const mdeoRootRule = generateContributionPluginGrammar(createMdeoContributionPlugin(), mdeoDeserializationContext);

/**
 * The plugin for the Config MDEO generated language.
 * Uses a proper grammar derived from the contribution plugin definition.
 */
const configMdeoPlugin: LangiumLanguagePlugin<MdeoServices> = {
    rootRule: mdeoRootRule,
    additionalTerminals: ConfigTerminals,
    module: {
        parser: {
            TokenBuilder: () => new ConfigTokenBuilder(new Set(["{"]), new Set(["("]), new Set(["}", ")"])),
            ValueConverter: () => new IdValueConverter(),
            ParserConfig: () => ({
                maxLookahead: 4
            })
        },
        references: {
            ScopeProvider: (services) => new MdeoScopeProvider(services),
            ExternalReferenceCollector: (services) => new MdeoExternalReferenceCollector(services.MdeoMetamodelResolver)
        },
        lsp: {
            CompletionProvider: (services) => new MdeoCompletionProvider(services as any),
            HoverProvider: (services) => new MdeoHoverProvider(services),
            Formatter: (services) => new SerializerFormatter(services)
        },
        AstSerializer: (services) => new DefaultAstSerializer(services),
        MdeoMetamodelResolver: () => new DefaultMdeoMetamodelResolver(),
        action: {
            ActionHandlerRegistry: (services) => {
                const registry = new ActionHandlerRegistry();
                registry.register("run", new RunMdeoConfigActionHandler(services.shared));
                return registry;
            },
            ActionProvider: () => new DefaultActionProvider()
        }
    },
    postCreate(services) {
        registerDefaultTokenSerializers(services);
        registerMdeoSerializers(services);
        registerMdeoValidationChecks(services);
        addExternalReferenceCollectionPhase(services);
    }
};

/**
 * Provider for the Config MDEO language plugin.
 */
export const configMdeoPluginProvider: LangiumLanguagePluginProvider<MdeoServices> = {
    create(): LangiumLanguagePlugin<MdeoServices> {
        return configMdeoPlugin;
    }
};
