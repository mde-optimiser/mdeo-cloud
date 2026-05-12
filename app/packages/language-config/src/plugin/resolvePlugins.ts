import {
    GrammarDeserializer,
    isTerminalRule,
    GrammarDeserializationContext,
    type Interface,
    type ParserRule,
    type Type,
    type TerminalRule,
    createInterface,
    createRule,
    or,
    many,
    NEWLINE
} from "@mdeo/language-common";
import type { ConfigContributionPlugin } from "./configContributionPlugin.js";
import { BaseConfigSection, Config } from "../grammar/configTypes.js";
import type { AstNode } from "langium";

/**
 * Information about a section's naming requirements
 */
export interface SectionNamingInfo {
    /**
     * The base section name
     */
    sectionName: string;

    /**
     * The plugin that contributes this section
     */
    plugin: ConfigContributionPlugin;

    /**
     * Whether the qualified name (section.plugin) is required
     * (true if multiple plugins contribute a section with the same name)
     */
    requiresQualifiedName: boolean;

    /**
     * The qualified name (section.plugin) that can always be used
     */
    qualifiedName: string;

    /**
     * The resolved grammar interface for this section
     */
    interface: Interface<AstNode>;

    /**
     * The resolved parser rule for this section
     */
    rule: ParserRule<AstNode>;
}

/**
 * Resolved plugins containing contributions of all plugins
 */
export interface ResolvedConfigContributionPlugins {
    /**
     * All section naming info, indexed by the qualified name
     */
    sections: Map<string, SectionNamingInfo>;

    /**
     * Map from simple section name to qualified names
     * If a section name maps to multiple qualified names, it's ambiguous
     */
    sectionNameToQualified: Map<string, string[]>;

    /**
     * Keywords to use in the grammar (both simple and qualified names where appropriate)
     */
    keywords: string[];

    /**
     * All parser rules from all plugins
     */
    rules: ParserRule<AstNode>[];
}

/**
 * Sorts plugins by dependency order using topological sort.
 * Plugins with no dependencies come first, followed by plugins that depend on them.
 *
 * @param plugins The contribution plugins to sort
 * @returns The sorted plugins
 * @throws Error if circular dependencies are detected
 */
function sortPluginsByDependencies(plugins: ConfigContributionPlugin[]): ConfigContributionPlugin[] {
    const sorted: ConfigContributionPlugin[] = [];
    const visited = new Set<string>();
    const visiting = new Set<string>();
    const pluginMap = new Map<string, ConfigContributionPlugin>();

    for (const plugin of plugins) {
        pluginMap.set(plugin.id, plugin);
    }

    function visit(pluginId: string): void {
        if (visited.has(pluginId)) {
            return;
        }
        if (visiting.has(pluginId)) {
            throw new Error(`Circular dependency detected involving plugin: ${pluginId}`);
        }

        visiting.add(pluginId);

        const plugin = pluginMap.get(pluginId);
        if (plugin) {
            for (const depId of plugin.dependencies) {
                const depPlugin = pluginMap.get(depId);
                if (!depPlugin) {
                    throw new Error(`Plugin '${pluginId}' depends on '${depId}' which is not available`);
                }
                visit(depId);
            }
            visited.add(pluginId);
            visiting.delete(pluginId);
            sorted.push(plugin);
        }
    }

    for (const plugin of plugins) {
        visit(plugin.id);
    }

    return sorted;
}

/**
 * Counts how many times each section name appears across all plugins.
 * This is used to determine whether qualified names are required.
 *
 * @param plugins The contribution plugins to analyze
 * @returns A map from section name to occurrence count
 */
function countSectionNameOccurrences(plugins: ConfigContributionPlugin[]): Map<string, number> {
    const sectionNameCounts = new Map<string, number>();
    for (const plugin of plugins) {
        for (const section of plugin.sections) {
            const count = sectionNameCounts.get(section.name) ?? 0;
            sectionNameCounts.set(section.name, count + 1);
        }
    }
    return sectionNameCounts;
}

/**
 * Class responsible for resolving config contribution plugins.
 * Encapsulates the state and logic for processing plugins and their sections.
 */
class ConfigPluginResolver {
    private readonly sections: Map<string, SectionNamingInfo>;
    private readonly sectionNameToQualified: Map<string, string[]>;
    private readonly keywords: string[];
    private readonly rules: ParserRule<AstNode>[];
    private readonly exportedTypes: (Interface<AstNode> | Type<AstNode>)[];
    private readonly baseTypes: (Interface<AstNode> | Type<AstNode>)[];
    private readonly baseRules: ParserRule<AstNode>[];
    private readonly baseTerminals: TerminalRule<AstNode>[];
    private readonly sectionNameCounts: Map<string, number>;

    constructor(sortedPlugins: ConfigContributionPlugin[], deserializationContext: GrammarDeserializationContext) {
        this.sections = new Map();
        this.sectionNameToQualified = new Map();
        this.keywords = [];
        this.rules = [];
        this.exportedTypes = [];
        this.baseTypes = Array.from(deserializationContext.types?.values() ?? []);
        this.baseRules = Array.from(deserializationContext.parserRules?.values() ?? []);
        this.baseTerminals = Array.from(deserializationContext.terminalRules?.values() ?? []);
        this.sectionNameCounts = countSectionNameOccurrences(sortedPlugins);
    }

    /**
     * Resolves all plugins and returns the combined result.
     *
     * @param sortedPlugins The plugins to process, sorted by dependency order
     * @returns The resolved plugins with naming information
     */
    resolve(sortedPlugins: ConfigContributionPlugin[]): ResolvedConfigContributionPlugins {
        for (const plugin of sortedPlugins) {
            this.processPlugin(plugin);
        }

        return {
            sections: this.sections,
            sectionNameToQualified: this.sectionNameToQualified,
            keywords: this.keywords,
            rules: this.rules
        };
    }

    /**
     * Processes a single plugin, deserializing its grammar and processing all its contributions.
     * Handles section contributions and exported type resolution.
     *
     * @param plugin The plugin to process
     */
    private processPlugin(plugin: ConfigContributionPlugin): void {
        const pluginDeserializationContext = GrammarDeserializationContext.create(
            [...this.baseTypes, ...this.exportedTypes],
            this.baseRules,
            this.baseTerminals
        );

        const deserializer = new GrammarDeserializer(plugin.grammar, pluginDeserializationContext);
        const grammar = deserializer.deserializeGrammar();

        const ruleMap = new Map<string, ParserRule<AstNode>>();
        for (const rule of grammar.rules) {
            if (!isTerminalRule(rule)) {
                ruleMap.set(rule.name, rule);
            }
        }

        const interfaceMap = new Map<string, Interface<AstNode>>();
        for (const iface of grammar.interfaces) {
            interfaceMap.set(iface.name, iface);
        }

        const typeMap = new Map<string, Type<AstNode>>();
        for (const type of grammar.types) {
            typeMap.set(type.name, type);
        }

        for (const section of plugin.sections) {
            this.processPluginSection(section, plugin, ruleMap, interfaceMap);
        }

        for (const exportedTypeName of plugin.exportedTypes) {
            const exportedType = interfaceMap.get(exportedTypeName) ?? typeMap.get(exportedTypeName);
            if (!exportedType) {
                throw new Error(
                    `Plugin '${plugin.id}' exports type '${exportedTypeName}' which does not exist in its grammar.`
                );
            }
            this.exportedTypes.push(exportedType);
        }
    }

    /**
     * Processes a single section contribution from a plugin.
     * Creates wrapper interface and rule, updates naming maps, and adds keywords.
     *
     * @param section The section to process
     * @param plugin The plugin contributing the section
     * @param ruleMap Map of rule names to rules in the plugin's grammar
     * @param interfaceMap Map of interface names to interfaces in the plugin's grammar
     */
    private processPluginSection(
        section: ConfigContributionPlugin["sections"][number],
        plugin: ConfigContributionPlugin,
        ruleMap: Map<string, ParserRule<AstNode>>,
        interfaceMap: Map<string, Interface<AstNode>>
    ): void {
        const pluginName = plugin.name;
        const qualifiedName = `${section.name}.${pluginName}`;
        const requiresQualifiedName = (this.sectionNameCounts.get(section.name) ?? 0) > 1;

        const sectionRule = ruleMap.get(section.ruleName);
        if (sectionRule == undefined) {
            throw new Error(
                `Section '${section.name}' from plugin '${plugin.id}' references rule '${section.ruleName}' which does not exist in the plugin's grammar.`
            );
        }

        const sectionInterface = interfaceMap.get(section.interfaceName);
        if (sectionInterface == undefined) {
            throw new Error(
                `Section '${section.name}' from plugin '${plugin.id}' references interface '${section.interfaceName}' which does not exist in the plugin's grammar.`
            );
        }

        const { wrapperInterface, wrapperRule } = this.createSectionWrapper(
            section,
            plugin,
            requiresQualifiedName,
            sectionRule,
            sectionInterface
        );

        this.rules.push(wrapperRule);

        const namingInfo: SectionNamingInfo = {
            sectionName: section.name,
            plugin,
            requiresQualifiedName,
            qualifiedName,
            interface: wrapperInterface,
            rule: wrapperRule
        };

        this.sections.set(qualifiedName, namingInfo);

        const qualifiedNames = this.sectionNameToQualified.get(section.name) ?? [];
        qualifiedNames.push(qualifiedName);
        this.sectionNameToQualified.set(section.name, qualifiedNames);

        this.keywords.push(qualifiedName);

        if (!requiresQualifiedName && !this.keywords.includes(section.name)) {
            this.keywords.push(section.name);
        }
    }

    /**
     * Creates wrapper interface and rule for a section contribution.
     * The wrapper wraps the section's interface in a BaseConfigSection with the appropriate keyword.
     *
     * @param section The section contribution to wrap
     * @param plugin The plugin contributing the section
     * @param requiresQualifiedName Whether the qualified name syntax is required
     * @param sectionRule The parser rule for the section content
     * @param sectionInterface The interface for the section content
     * @returns The wrapper interface and rule
     */
    private createSectionWrapper(
        section: ConfigContributionPlugin["sections"][number],
        plugin: ConfigContributionPlugin,
        requiresQualifiedName: boolean,
        sectionRule: ParserRule<AstNode>,
        sectionInterface: Interface<AstNode>
    ): { wrapperInterface: Interface<AstNode>; wrapperRule: ParserRule<AstNode> } {
        const pluginName = plugin.name;
        const wrapperInterfaceName = getWrapperInterfaceName(section.name, pluginName);
        const wrapperInterface = createInterface(wrapperInterfaceName).extends(BaseConfigSection).attrs({
            content: sectionInterface
        });

        const wrapperRuleName = getWrapperRuleName(section.name, pluginName);

        let wrapperRule: ParserRule<AstNode>;
        if (requiresQualifiedName) {
            wrapperRule = createRule(wrapperRuleName)
                .returns(wrapperInterface)
                .as(({ set }) => [`${section.name}.${pluginName}`, set("content", sectionRule)]);
        } else {
            wrapperRule = createRule(wrapperRuleName)
                .returns(wrapperInterface)
                .as(({ set }) => [or(section.name, `${section.name}.${pluginName}`), set("content", sectionRule)]);
        }

        return { wrapperInterface, wrapperRule };
    }
}

/**
 * Returns the wrapper interface name for a given section and plugin.
 * The wrapper interface extends BaseConfigSection and holds the section content.
 *
 * @param sectionName The section name (e.g., "problem")
 * @param pluginName The short name of the plugin (e.g., "optimization")
 * @returns The wrapper interface name (e.g., "ConfigProblemSection_optimization")
 */
export function getWrapperInterfaceName(sectionName: string, pluginName: string): string {
    return `Config${sectionName.charAt(0).toUpperCase()}${sectionName.slice(1)}Section_${pluginName}`;
}

/**
 * Returns the wrapper parser rule name for a given section and plugin.
 *
 * @param sectionName The section name (e.g., "problem")
 * @param pluginName The short name of the plugin (e.g., "optimization")
 * @returns The wrapper rule name (e.g., "ConfigProblemSectionWrapper_optimization")
 */
export function getWrapperRuleName(sectionName: string, pluginName: string): string {
    return `Config${sectionName.charAt(0).toUpperCase()}${sectionName.slice(1)}SectionWrapper_${pluginName}`;
}

/**
 * Generates the grammar for the standalone (artificial) language of a contribution plugin.
 * This is used by the plugin's own language service to parse partial config files that
 * contain only sections from this plugin (without disambiguation/qualified keywords).
 *
 * The generated grammar:
 * - Deserializes the plugin's contribution grammar
 * - Creates wrapper rules/interfaces for each section using simple (non-qualified) keywords
 * - Creates a root rule returning Config that alternates between all section wrappers
 *
 * @param plugin The contribution plugin whose standalone grammar is to be generated
 * @param deserializationContext Context for resolving external type/rule references in the plugin's grammar
 * @returns The root parser rule for the standalone grammar
 */
export function generateContributionPluginGrammar(
    plugin: ConfigContributionPlugin,
    deserializationContext: GrammarDeserializationContext
): ParserRule<AstNode> {
    const deserializer = new GrammarDeserializer(plugin.grammar, deserializationContext);
    const grammar = deserializer.deserializeGrammar();

    const ruleMap = new Map<string, ParserRule<AstNode>>();
    for (const rule of grammar.rules) {
        if (!isTerminalRule(rule)) {
            ruleMap.set(rule.name, rule);
        }
    }

    const interfaceMap = new Map<string, Interface<AstNode>>();
    for (const iface of grammar.interfaces) {
        interfaceMap.set(iface.name, iface);
    }

    const wrapperRules: ParserRule<AstNode>[] = [];

    for (const section of plugin.sections) {
        const sectionRule = ruleMap.get(section.ruleName);
        if (sectionRule == undefined) {
            throw new Error(
                `Section '${section.name}' in plugin '${plugin.id}' references rule '${section.ruleName}' which does not exist.`
            );
        }

        const sectionInterface = interfaceMap.get(section.interfaceName);
        if (sectionInterface == undefined) {
            throw new Error(
                `Section '${section.name}' in plugin '${plugin.id}' references interface '${section.interfaceName}' which does not exist.`
            );
        }

        const wrapperInterfaceName = getWrapperInterfaceName(section.name, plugin.name);
        const wrapperRuleName = getWrapperRuleName(section.name, plugin.name);

        const wrapperInterface = createInterface(wrapperInterfaceName).extends(BaseConfigSection).attrs({
            content: sectionInterface
        });

        const wrapperRule = createRule(wrapperRuleName)
            .returns(wrapperInterface)
            .as(({ set }) => [section.name, set("content", sectionRule)]);

        wrapperRules.push(wrapperRule);
    }

    return createRule(`${plugin.name.charAt(0).toUpperCase()}${plugin.name.slice(1)}PluginConfigRule`)
        .returns(Config)
        .as(({ add }) => [many(or(...wrapperRules.map((r) => add("sections", r)), NEWLINE))]);
}

/**
 * Analyzes section contributions to determine naming requirements.
 * If a section name appears in multiple plugins, the qualified syntax (section.plugin) is required.
 * If it appears in only one plugin, both simple and qualified syntax are allowed.
 *
 * @param plugins The contribution plugins
 * @param deserializationContext The deserialization context for resolving external references
 * @returns The resolved plugins with naming information
 */
export function resolveConfigPlugins(
    plugins: ConfigContributionPlugin[],
    deserializationContext: GrammarDeserializationContext
): ResolvedConfigContributionPlugins {
    const sortedPlugins = sortPluginsByDependencies(plugins);
    const resolver = new ConfigPluginResolver(sortedPlugins, deserializationContext);
    return resolver.resolve(sortedPlugins);
}
