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
import type { ModelContributionPlugin, ModelImportContribution } from "./modelContributionPlugin.js";
import { BaseModelImport, Model } from "../grammar/modelTypes.js";
import { MetamodelFileImportRule } from "../grammar/modelRules.js";
import type { AstNode } from "langium";

/**
 * Naming and grammar information for a resolved import contribution.
 */
export interface ImportNamingInfo {
    /**
     * The keyword introducing this import (e.g. "CSV").
     */
    importName: string;

    /**
     * The plugin that contributes this import.
     */
    plugin: ModelContributionPlugin;

    /**
     * The resolved wrapper interface for this import, extending BaseModelImport.
     */
    interface: Interface<AstNode>;

    /**
     * The resolved wrapper parser rule for this import.
     */
    rule: ParserRule<AstNode>;
}

/**
 * Resolved plugins containing the contributions of all plugins.
 */
export interface ResolvedModelContributionPlugins {
    /**
     * All import naming info, indexed by the import keyword.
     */
    imports: Map<string, ImportNamingInfo>;

    /**
     * Keywords introduced by resolved plugins.
     */
    keywords: string[];

    /**
     * All wrapper parser rules from all plugins.
     */
    rules: ParserRule<AstNode>[];
}

/**
 * Sorts plugins by dependency order using topological sort.
 *
 * @param plugins The contribution plugins to sort
 * @returns The sorted plugins
 * @throws Error if circular dependencies are detected
 */
function sortPluginsByDependencies(plugins: ModelContributionPlugin[]): ModelContributionPlugin[] {
    const sorted: ModelContributionPlugin[] = [];
    const visited = new Set<string>();
    const visiting = new Set<string>();
    const pluginMap = new Map<string, ModelContributionPlugin>();

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
 * Class responsible for resolving model contribution plugins.
 * Encapsulates the state and logic for processing plugins and their imports.
 */
class ModelPluginResolver {
    private readonly imports: Map<string, ImportNamingInfo>;
    private readonly keywords: string[];
    private readonly rules: ParserRule<AstNode>[];
    private readonly exportedTypes: (Interface<AstNode> | Type<AstNode>)[];
    private readonly baseTypes: (Interface<AstNode> | Type<AstNode>)[];
    private readonly baseRules: ParserRule<AstNode>[];
    private readonly baseTerminals: TerminalRule<AstNode>[];

    constructor(deserializationContext: GrammarDeserializationContext) {
        this.imports = new Map();
        this.keywords = [];
        this.rules = [];
        this.exportedTypes = [];
        this.baseTypes = Array.from(deserializationContext.types?.values() ?? []);
        this.baseRules = Array.from(deserializationContext.parserRules?.values() ?? []);
        this.baseTerminals = Array.from(deserializationContext.terminalRules?.values() ?? []);
    }

    /**
     * Resolves all plugins and returns the combined result.
     *
     * @param sortedPlugins The plugins to process, sorted by dependency order
     * @returns The resolved plugins with naming information
     */
    resolve(sortedPlugins: ModelContributionPlugin[]): ResolvedModelContributionPlugins {
        for (const plugin of sortedPlugins) {
            this.processPlugin(plugin);
        }

        return {
            imports: this.imports,
            keywords: this.keywords,
            rules: this.rules
        };
    }

    /**
     * Processes a single plugin, deserializing its grammar and processing its import contribution.
     *
     * @param plugin The plugin to process
     */
    private processPlugin(plugin: ModelContributionPlugin): void {
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

        for (const imp of plugin.imports) {
            this.processImport(imp, plugin, ruleMap, interfaceMap);
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
     * Processes a single import contribution from a plugin.
     * Creates the wrapper interface and rule, and registers the import's keyword.
     *
     * @param imp The import contribution to process
     * @param plugin The plugin contributing the import
     * @param ruleMap Map of rule names to rules in the plugin's grammar
     * @param interfaceMap Map of interface names to interfaces in the plugin's grammar
     */
    private processImport(
        imp: ModelImportContribution,
        plugin: ModelContributionPlugin,
        ruleMap: Map<string, ParserRule<AstNode>>,
        interfaceMap: Map<string, Interface<AstNode>>
    ): void {
        if (this.imports.has(imp.name)) {
            const existing = this.imports.get(imp.name)!;
            throw new Error(
                `Import '${imp.name}' from plugin '${plugin.id}' conflicts with the same import already contributed by plugin '${existing.plugin.id}'.`
            );
        }

        const importRule = ruleMap.get(imp.ruleName);
        if (importRule == undefined) {
            throw new Error(
                `Import '${imp.name}' from plugin '${plugin.id}' references rule '${imp.ruleName}' which does not exist in the plugin's grammar.`
            );
        }

        const importInterface = interfaceMap.get(imp.interfaceName);
        if (importInterface == undefined) {
            throw new Error(
                `Import '${imp.name}' from plugin '${plugin.id}' references interface '${imp.interfaceName}' which does not exist in the plugin's grammar.`
            );
        }

        const { wrapperInterface, wrapperRule } = createImportWrapper(imp.name, importRule, importInterface);

        this.rules.push(wrapperRule);
        this.imports.set(imp.name, { importName: imp.name, plugin, interface: wrapperInterface, rule: wrapperRule });
        this.keywords.push(imp.name);
    }
}

/**
 * Creates the wrapper interface and rule for an import contribution.
 * The wrapper wraps the import's content interface in a BaseModelImport, gated by its keyword.
 *
 * @param importName The import keyword (e.g. "CSV")
 * @param contentRule The parser rule for the import's content
 * @param contentInterface The interface for the import's content
 * @returns The wrapper interface and rule
 */
function createImportWrapper(
    importName: string,
    contentRule: ParserRule<AstNode>,
    contentInterface: Interface<AstNode>
): { wrapperInterface: Interface<AstNode>; wrapperRule: ParserRule<AstNode> } {
    const wrapperInterfaceName = getWrapperInterfaceName(importName);
    const wrapperInterface = createInterface(wrapperInterfaceName).extends(BaseModelImport).attrs({
        content: contentInterface
    });

    const wrapperRuleName = getWrapperRuleName(importName);
    const wrapperRule = createRule(wrapperRuleName)
        .returns(wrapperInterface)
        .as(({ set }) => ["import", importName, set("content", contentRule)]);

    return { wrapperInterface, wrapperRule };
}

/**
 * Returns the wrapper interface name for a given import keyword.
 *
 * @param importName The import keyword (e.g. "CSV")
 * @returns The wrapper interface name (e.g. "ModelCsvImport")
 */
export function getWrapperInterfaceName(importName: string): string {
    return `Model${capitalize(importName)}Import`;
}

/**
 * Returns the wrapper parser rule name for a given import keyword.
 *
 * @param importName The import keyword (e.g. "CSV")
 * @returns The wrapper rule name (e.g. "ModelCsvImportWrapper")
 */
export function getWrapperRuleName(importName: string): string {
    return `Model${capitalize(importName)}ImportWrapper`;
}

function capitalize(value: string): string {
    return value.length === 0 ? value : value.charAt(0).toUpperCase() + value.slice(1).toLowerCase();
}

/**
 * Generates the grammar for the standalone (artificial) language of an import contribution plugin.
 * This is used by the plugin's own language service to parse partial model files that
 * contain only this plugin's import block, alongside the base "using" metamodel import.
 *
 * @param plugin The contribution plugin whose standalone grammar is to be generated
 * @param deserializationContext Context for resolving external type/rule references in the plugin's grammar
 * @returns The root parser rule for the standalone grammar
 */
export function generateModelContributionGrammar(
    plugin: ModelContributionPlugin,
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

    for (const imp of plugin.imports) {
        const contentRule = ruleMap.get(imp.ruleName);
        if (contentRule == undefined) {
            throw new Error(`Import '${imp.name}' in plugin '${plugin.id}' references rule '${imp.ruleName}' which does not exist.`);
        }

        const contentInterface = interfaceMap.get(imp.interfaceName);
        if (contentInterface == undefined) {
            throw new Error(
                `Import '${imp.name}' in plugin '${plugin.id}' references interface '${imp.interfaceName}' which does not exist.`
            );
        }

        const { wrapperRule } = createImportWrapper(imp.name, contentRule, contentInterface);
        wrapperRules.push(wrapperRule);
    }

    return createRule(`${capitalize(plugin.name)}PluginModelRule`)
        .returns(Model)
        .as(({ add, set }) => [
            many(NEWLINE),
            set("import", MetamodelFileImportRule),
            many(NEWLINE),
            many(or(...wrapperRules.map((r) => add("imports", r)), NEWLINE))
        ]);
}

/**
 * Resolves model contribution plugins in dependency order, deserializing their
 * grammars and wrapping each contributed import in a BaseModelImport-extending rule.
 *
 * @param plugins The contribution plugins
 * @param deserializationContext The deserialization context for resolving external references
 * @returns The resolved plugins with naming information
 */
export function resolveModelPlugins(
    plugins: ModelContributionPlugin[],
    deserializationContext: GrammarDeserializationContext
): ResolvedModelContributionPlugins {
    const sortedPlugins = sortPluginsByDependencies(plugins);
    const resolver = new ModelPluginResolver(deserializationContext);
    return resolver.resolve(sortedPlugins);
}
