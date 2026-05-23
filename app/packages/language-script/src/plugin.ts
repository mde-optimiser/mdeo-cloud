import {
    HIDDEN_NEWLINE,
    ML_COMMENT,
    SL_COMMENT,
    WS,
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
    addExternalReferenceCollectionPhase,
    ActionHandlerRegistry,
    type ActionHandlerRegistryAdditionalServices,
    generateExtendedParser
} from "@mdeo/language-shared";
import {
    defaultExtendedTypirServices,
    type AdditionalTypirServices,
    type ExpressionTypirServices,
    registerExpressionSerializers,
    registerStatementSerializers,
    registerTypeSerializers,
    generateExpressionRuleOverride
} from "@mdeo/language-expression";
import type { TypirLangiumSpecifics } from "typir-langium";
import { ScriptTypeSystem } from "./features/type-system/scriptTypeSystem.js";
import { ScriptScopeProvider } from "./features/type-system/scriptScopeProvider.js";
import { registerScriptSerializers } from "./features/scriptSerializers.js";
import { expressionConfig, expressionTypes, statementTypes, typeTypes } from "./grammar/scriptTypes.js";
import { ScriptTokenBuilder } from "./features/scriptTokenBuilder.js";
import { ScriptLangiumScopeProvider } from "./features/scriptScopeProvider.js";
import { ScriptExternalReferenceCollector } from "./features/scriptExternalReferenceCollector.js";
import { registerScriptValidationChecks } from "./features/scriptValidator.js";
import type { ResolvedScriptContributionPlugins, ScriptContributionPlugin } from "./plugin/scriptContributionPlugin.js";
import { generateScriptRule } from "./grammar/scriptRules.js";
import type { AbstractAstReflection } from "langium";
import { ScriptActionProvider } from "./features/scriptActionProvider.js";
import { RunScriptActionHandler } from "./action-handlers/runScriptActionHandler.js";
import { NewFileActionHandler } from "./action-handlers/newFileActionHandler.js";
import { ScriptCompletionProvider } from "./features/scriptCompletionProvider.js";
import { ExpressionHoverProvider, ExpressionReferenceDescriptionProvider } from "@mdeo/language-expression";
import type { DocumentPackageCacheService } from "@mdeo/language-expression";
import { ScriptDocumentPackageCacheService } from "./features/scriptDocumentPackageCacheService.js";

const { createTypirLangiumServicesWithAdditionalServices, initializeLangiumTypirServices } =
    sharedImport("typir-langium");

/**
 * The Typir specifics for the Script language.
 */
export type ScriptTypirSpecifics = TypirLangiumSpecifics;

/**
 * The resolved contribution plugins for the Script language.
 */
type AdditionalScriptTypirServices = AdditionalTypirServices<ScriptTypirSpecifics> & {
    ResolvedContributionPlugins: ResolvedScriptContributionPlugins;
};

/**
 * Typir services with additional services for the Script language.
 */
export type ScriptTypirServices = ExpressionTypirServices<ScriptTypirSpecifics> & AdditionalScriptTypirServices;

/**
 * The additional services for the Script language.
 */
export type ScriptServices = {
    typir: ScriptTypirServices;
} & ExternalReferenceAdditionalServices &
    ActionHandlerRegistryAdditionalServices;

/**
 * Provider for the Script language plugin.
 */
export const scriptPluginProvider: LangiumLanguagePluginProvider<ScriptServices> = {
    create(contributionPlugins: ScriptContributionPlugin[]): LangiumLanguagePlugin<ScriptServices> {
        const { rule, resolvedPlugins } = generateScriptRule(contributionPlugins);
        return {
            rootRule: rule,
            additionalTerminals: [WS, HIDDEN_NEWLINE, ML_COMMENT, SL_COMMENT],
            module: {
                parser: {
                    TokenBuilder: () => new ScriptTokenBuilder(new Set(["{"]), new Set(["("]), new Set(["}", ")"])),
                    ValueConverter: () => new IdValueConverter(),
                    ...generateExtendedParser(generateExpressionRuleOverride(expressionConfig)),
                    ParserConfig: () => ({
                        maxLookahead: 4
                    })
                },
                references: {
                    ScopeProvider: (services) => new ScriptLangiumScopeProvider(services),
                    ExternalReferenceCollector: () => new ScriptExternalReferenceCollector()
                },
                typir: (outerServices) => {
                    const langiumSharedServices = outerServices.shared;

                    return createTypirLangiumServicesWithAdditionalServices<
                        ScriptTypirSpecifics,
                        AdditionalScriptTypirServices
                    >(
                        langiumSharedServices,
                        langiumSharedServices.AstReflection as AbstractAstReflection & AstReflection,
                        new ScriptTypeSystem(resolvedPlugins),
                        {
                            ...defaultExtendedTypirServices<ScriptTypirSpecifics>(),
                            ScopeProvider: (services) => new ScriptScopeProvider(services as ScriptTypirServices),
                            ResolvedContributionPlugins: () => resolvedPlugins,
                            PackageMapCache: (): DocumentPackageCacheService =>
                                new ScriptDocumentPackageCacheService(langiumSharedServices)
                        }
                    ) as ScriptTypirServices;
                },
                lsp: {
                    CompletionProvider: (services) =>
                        new ScriptCompletionProvider(services, expressionTypes, typeTypes),
                    HoverProvider: (services) => new ExpressionHoverProvider(services, expressionTypes, typeTypes),
                    Formatter: (services) => new SerializerFormatter(services)
                },
                workspace: {
                    ReferenceDescriptionProvider: (services) =>
                        new ExpressionReferenceDescriptionProvider(services, expressionTypes, typeTypes, statementTypes)
                },
                AstSerializer: (services) => new DefaultAstSerializer(services),
                action: {
                    ActionHandlerRegistry: (services) => {
                        const registry = new ActionHandlerRegistry();
                        registry.register("new-file", new NewFileActionHandler(services.shared));
                        registry.register("run", new RunScriptActionHandler(services.shared));
                        return registry;
                    },
                    ActionProvider: (services) => new ScriptActionProvider(services.shared)
                }
            },
            postCreate(services) {
                initializeLangiumTypirServices(services, services.typir);
                registerDefaultTokenSerializers(services);
                registerTypeSerializers(services, typeTypes);
                registerExpressionSerializers(services, expressionTypes);
                registerStatementSerializers(services, statementTypes);
                registerScriptSerializers(services);
                registerScriptValidationChecks(services);
                addExternalReferenceCollectionPhase(services);
            }
        };
    }
};
