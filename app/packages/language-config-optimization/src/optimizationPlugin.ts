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
    NewlineAwareTokenBuilder,
    addExternalReferenceCollectionPhase
} from "@mdeo/language-shared";
import { generateContributionPluginGrammar, ConfigTerminals } from "@mdeo/language-config";
import { OptimizationScopeProvider } from "./features/optimizationScopeProvider.js";
import { OptimizationExternalReferenceCollector } from "./features/optimizationExternalReferenceCollector.js";
import { registerOptimizationSerializers } from "./features/optimizationSerializers.js";
import { createOptimizationContributionPlugin } from "./plugin/optimizationContributionPlugin.js";
import { AssociationEnd, Class, Property } from "@mdeo/language-metamodel";
import { Function } from "@mdeo/language-script";
import { registerOptimizationValidationChecks } from "./validation/optimizationValidator.js";
import { OptimizationCompletionProvider } from "./features/optimizationCompletionProvider.js";

/**
 * Deserialization context for the optimization grammar.
 * Provides the external interface types (from metamodel/script languages) and common terminals
 * so the optimization grammar can be deserialized from its serialized form.
 */
const optimizationDeserializationContext = GrammarDeserializationContext.create(
    [Class, Function, Property, AssociationEnd],
    [],
    [ID, NEWLINE, HIDDEN_NEWLINE, INT, FLOAT, STRING]
);

/**
 * The root rule for the standalone config-optimization language.
 * Generated from the optimization contribution plugin's grammar, it produces
 * a Config root with ConfigProblemSection_optimization / ConfigGoalSection_optimization
 * children — matching the structure of the full config language.
 */
const optimizationRootRule = generateContributionPluginGrammar(
    createOptimizationContributionPlugin(),
    optimizationDeserializationContext
);

/**
 * The plugin for the Config Optimization generated language.
 * Uses a proper grammar derived from the contribution plugin definition,
 * enabling parsing of partial config files containing only optimization sections.
 */
const configOptimizationPlugin: LangiumLanguagePlugin<ExternalReferenceAdditionalServices> = {
    rootRule: optimizationRootRule,
    additionalTerminals: ConfigTerminals,
    module: {
        parser: {
            TokenBuilder: () => new NewlineAwareTokenBuilder(new Set(["{"]), new Set(["("]), new Set(["}", ")"])),
            ValueConverter: () => new IdValueConverter(),
            ParserConfig: () => ({
                maxLookahead: 4
            })
        },
        references: {
            ScopeProvider: (services) => new OptimizationScopeProvider(services),
            ExternalReferenceCollector: () => new OptimizationExternalReferenceCollector()
        },
        lsp: {
            CompletionProvider: (services) => new OptimizationCompletionProvider(services as any),
            Formatter: (services) => new SerializerFormatter(services)
        },
        AstSerializer: (services) => new DefaultAstSerializer(services)
    },
    postCreate(services) {
        registerDefaultTokenSerializers(services);
        registerOptimizationSerializers(services);
        registerOptimizationValidationChecks(services);
        addExternalReferenceCollectionPhase(services);
    }
};

/**
 * Provider for the Config Optimization language plugin.
 */
export const configOptimizationPluginProvider: LangiumLanguagePluginProvider<ExternalReferenceAdditionalServices> = {
    create(): LangiumLanguagePlugin<ExternalReferenceAdditionalServices> {
        return configOptimizationPlugin;
    }
};
