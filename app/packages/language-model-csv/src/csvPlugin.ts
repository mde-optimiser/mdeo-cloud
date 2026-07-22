import {
    type LangiumLanguagePlugin,
    type LangiumLanguagePluginProvider,
    type ExternalReferenceAdditionalServices,
    GrammarDeserializationContext,
    ID,
    NEWLINE,
    HIDDEN_NEWLINE,
    INT,
    FLOAT,
    STRING
} from "@mdeo/language-common";
import {
    DefaultAstSerializer,
    IdValueConverter,
    SerializerFormatter,
    registerDefaultTokenSerializers,
    NewlineAwareTokenBuilder,
    addExternalReferenceCollectionPhase,
    ActionHandlerRegistry,
    type ActionHandlerRegistryAdditionalServices,
    DefaultActionProvider
} from "@mdeo/language-shared";
import { generateModelContributionGrammar, ModelTerminals, ModelScopeProvider, ModelExternalReferenceCollector } from "@mdeo/language-model";
import { Class } from "@mdeo/language-metamodel";
import { createModelCsvContributionPlugin } from "./plugin/modelCsvContributionPlugin.js";

/**
 * Combined services type for the standalone model-csv language.
 * Reuses the base Model language's services, since the standalone grammar's
 * root is a real `Model` node (an `import` line plus the CSV import block).
 */
export type ModelCsvServices = ExternalReferenceAdditionalServices & ActionHandlerRegistryAdditionalServices;

/**
 * Deserialization context for the CSV grammar.
 * Provides the external interface types and common terminals so the CSV
 * grammar can be deserialized from its serialized form.
 */
const csvDeserializationContext = GrammarDeserializationContext.create([Class], [], [ID, NEWLINE, HIDDEN_NEWLINE, INT, FLOAT, STRING]);

/**
 * The root rule for the standalone model-csv language.
 * Generated from the CSV contribution plugin's grammar.
 */
const csvRootRule = generateModelContributionGrammar(createModelCsvContributionPlugin(), csvDeserializationContext);

/**
 * The plugin for the standalone model-csv generated language.
 * Its root type is a real `Model` node, so it reuses the base Model language's
 * own scope provider and external reference collector directly.
 */
const modelCsvPlugin: LangiumLanguagePlugin<ModelCsvServices> = {
    rootRule: csvRootRule,
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
            Formatter: (services) => new SerializerFormatter(services)
        },
        AstSerializer: (services) => new DefaultAstSerializer(services),
        action: {
            ActionHandlerRegistry: () => new ActionHandlerRegistry(),
            ActionProvider: () => new DefaultActionProvider()
        }
    },
    postCreate(services) {
        registerDefaultTokenSerializers(services);
        addExternalReferenceCollectionPhase(services);
    }
};

/**
 * Provider for the standalone model-csv language plugin.
 */
export const modelCsvPluginProvider: LangiumLanguagePluginProvider<ModelCsvServices> = {
    create(): LangiumLanguagePlugin<ModelCsvServices> {
        return modelCsvPlugin;
    }
};
