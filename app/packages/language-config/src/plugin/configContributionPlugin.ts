import type { ServerContributionPlugin } from "@mdeo/plugin";
import type { SerializedGrammar } from "@mdeo/language-common";

/**
 * Represents a section definition that can be contributed by a plugin.
 * Each section defines a named block in the config file (e.g., "problem", "goal").
 */
export interface ConfigSection {
    /**
     * The name of this section (e.g., "problem", "goal").
     * Used as the keyword in the config file syntax.
     */
    name: string;

    /**
     * The name of the parser rule in the plugin's grammar that defines this section.
     * This rule name must exist in the plugin's serialized grammar.
     */
    ruleName: string;

    /**
     * The name of the interface in the plugin's grammar that defines this section's AST type.
     * This interface name must exist in the plugin's serialized grammar.
     */
    interfaceName: string;

    /**
     * Marks this section as executable.
     * If true, the config language can expose the regular "run" file action
     * when this section is present in a config file.
     */
    executable: boolean;
}

/**
 * Plugin for contributing sections to the config language.
 * Each plugin can contribute multiple sections with their associated grammars.
 */
export interface ConfigContributionPlugin extends ServerContributionPlugin {
    /**
     * Identifies the plugin as a Config language contribution.
     */
    type: typeof ConfigContributionPlugin.TYPE;

    /**
     * The short name of the plugin, used for qualified section names (e.g., "optimization").
     * Qualified names allow disambiguation when multiple plugins contribute sections with the same name.
     */
    shortName: string;

    /**
     * The language key used to get language services for this plugin.
     * This is the language ID registered with Langium's ServiceRegistry.
     */
    languageKey: string;

    /**
     * The serialized grammar containing all rules for all sections in this plugin.
     */
    grammar: SerializedGrammar;

    /**
     * Array of sections contributed by this plugin.
     */
    sections: ConfigSection[];

    /**
     * Plugin IDs that this plugin depends on.
     * These plugins must be loaded before this one, and their exported types
     * will be available in this plugin's deserialization context.
     */
    dependencies: string[];

    /**
     * Names of types/interfaces exported by this plugin for use by other plugins.
     * These are string keys that reference types in this plugin's serialized grammar.
     * The referenced types will be added to the deserialization context for plugins
     * that depend on this one.
     */
    exportedTypes: string[];

    /**
     * Section-level dependencies required for file-data computation.
     * Before this plugin's request handler is invoked, the specified sections from
     * the listed plugins must have already been computed and their results are
     * forwarded as dependency data in the request.
     * These are distinct from `dependencies` (which are grammar-level dependencies).
     */
    sectionDependencies: SectionDependency[];
}

/**
 * A dependency on a specific section from another contribution plugin.
 * Used during config file-data computation to determine plugin execution order.
 */
export interface SectionDependency {
    /**
     * The short name of the plugin that contributes the required section.
     */
    pluginName: string;

    /**
     * The name of the required section within that plugin.
     */
    sectionName: string;
}

export namespace ConfigContributionPlugin {
    /**
     * The type identifier for ConfigContributionPlugin
     */
    export const TYPE = "config-language-contribution";

    /**
     * Type guard for ConfigContributionPlugin
     *
     * @param value The value to check
     * @returns True if the value is a ConfigContributionPlugin, false otherwise
     */
    export function is(value: ServerContributionPlugin): value is ConfigContributionPlugin {
        return "type" in value && value.type === TYPE;
    }
}
