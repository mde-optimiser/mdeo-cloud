import {
    type LangiumLanguagePlugin,
    type LangiumLanguagePluginProvider,
    type ExternalReferenceAdditionalServices,
    ID,
    NEWLINE,
    FLOAT,
    INT,
    HIDDEN_NEWLINE,
    GrammarDeserializationContext,
    STRING
} from "@mdeo/language-common";
import { createConfigRule, ConfigTerminals } from "./grammar/configRules.js";
import { resolveConfigPlugins } from "./plugin/resolvePlugins.js";
import { ConfigContributionPlugin } from "./plugin/configContributionPlugin.js";
import { ConfigDelegatingScopeProvider } from "./features/configDelegatingScopeProvider.js";
import { ConfigAstSerializer } from "./features/configAstSerializer.js";
import {
    IdValueConverter,
    SerializerFormatter,
    registerDefaultTokenSerializers,
    ActionHandlerRegistry,
    type ActionHandlerRegistryAdditionalServices
} from "@mdeo/language-shared";
import { registerConfigSerializers } from "./features/configSerializers.js";
import { ConfigDelegatingExternalReferenceCollector } from "./features/configDelegatingExternalReferenceCollector.js";
import { registerConfigValidationChecks } from "./validation/configValidator.js";
import { ConfigActionProvider } from "./features/configActionProvider.js";
import { RunConfigActionHandler } from "./action-handlers/runConfigActionHandler.js";
import { ConfigDelegatingCompletionProvider } from "./features/configDelegatingCompletionProvider.js";
import { ConfigTokenBuilder } from "./features/configTokenBuilder.js";
import { ConfigDelegatingHoverProvider } from "./features/configDelegatingHoverProvider.js";

/**
 * Additional services for the Config language.
 */
export type ConfigAdditionalServices = ExternalReferenceAdditionalServices & ActionHandlerRegistryAdditionalServices;

/**
 * Provider for the Config language plugin.
 */
export const configPluginProvider: LangiumLanguagePluginProvider<ConfigAdditionalServices> = {
    create(contributionPlugins: ConfigContributionPlugin[]): LangiumLanguagePlugin<ConfigAdditionalServices> {
        const configPlugins = contributionPlugins.filter(ConfigContributionPlugin.is);

        const deserializationContext = GrammarDeserializationContext.create(
            [],
            [],
            [ID, NEWLINE, HIDDEN_NEWLINE, INT, FLOAT, STRING]
        );

        const resolvedPlugins = resolveConfigPlugins(configPlugins, deserializationContext);

        const configRule = createConfigRule(resolvedPlugins);

        return {
            rootRule: configRule,
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
                    ScopeProvider: (services) => new ConfigDelegatingScopeProvider(services, resolvedPlugins),
                    ExternalReferenceCollector: (services) =>
                        new ConfigDelegatingExternalReferenceCollector(services, resolvedPlugins)
                },
                lsp: {
                    CompletionProvider: (services) => new ConfigDelegatingCompletionProvider(services, resolvedPlugins),
                    HoverProvider: (services) => new ConfigDelegatingHoverProvider(services, resolvedPlugins),
                    Formatter: (services) => new SerializerFormatter(services)
                },
                AstSerializer: (services) =>
                    new ConfigAstSerializer(services, services.shared.ServiceRegistry, configPlugins),
                action: {
                    ActionHandlerRegistry: (services) => {
                        const registry = new ActionHandlerRegistry();
                        registry.register(
                            "run",
                            new RunConfigActionHandler(
                                services.shared,
                                services.shared.ServiceRegistry,
                                resolvedPlugins,
                                configPlugins
                            )
                        );
                        return registry;
                    },
                    ActionProvider: (services) =>
                        new ConfigActionProvider(
                            services.shared,
                            services.shared.ServiceRegistry,
                            resolvedPlugins,
                            configPlugins
                        )
                }
            },
            postCreate(services) {
                registerDefaultTokenSerializers(services);
                registerConfigSerializers(services, resolvedPlugins);
                registerConfigValidationChecks(
                    services,
                    services.shared.ServiceRegistry,
                    configPlugins,
                    resolvedPlugins
                );
            }
        };
    }
};
