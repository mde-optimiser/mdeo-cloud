import type { ServerContributionPlugin } from "@mdeo/plugin";
import type { SerializedGrammar } from "@mdeo/language-common";

/**
 * A data import format that can be contributed by a plugin (e.g. CSV).
 * Each plugin contributes exactly one import keyword and the rule/interface
 * in its own serialized grammar that defines the content following that keyword.
 */
export interface ModelImportContribution {
    /**
     * The keyword introducing this import in the model file syntax (e.g. "CSV"),
     * used as `import <name> { ... }`.
     */
    name: string;

    /**
     * The name of the parser rule in the plugin's serialized grammar that defines
     * the content of this import block (everything after the `{`).
     */
    ruleName: string;

    /**
     * The name of the interface in the plugin's serialized grammar that defines
     * this import's AST type.
     */
    interfaceName: string;
}

/**
 * Plugin for contributing a data import format to the Model language.
 */
export interface ModelContributionPlugin extends ServerContributionPlugin {
    /**
     * Identifies the plugin as a Model language contribution.
     */
    type: typeof ModelContributionPlugin.TYPE;

    /**
     * The short name of the plugin (e.g. "csv").
     */
    name: string;

    /**
     * The language key used to get language services for this plugin.
     * This is the language ID registered with Langium's ServiceRegistry.
     */
    languageKey: string;

    /**
     * The serialized grammar containing all rules for this plugin's import.
     */
    grammar: SerializedGrammar;

    /**
     * The import format contributed by this plugin.
     */
    imports: ModelImportContribution[];

    /**
     * Plugin IDs that this plugin depends on.
     * These plugins must be loaded before this one, and their exported types
     * will be available in this plugin's deserialization context.
     */
    dependencies: string[];

    /**
     * Names of types/interfaces exported by this plugin for use by other plugins.
     */
    exportedTypes: string[];
}

export namespace ModelContributionPlugin {
    /**
     * The type identifier for ModelContributionPlugin.
     */
    export const TYPE = "model-language-contribution";

    /**
     * Type guard for ModelContributionPlugin.
     *
     * @param value The value to check
     * @returns True if the value is a ModelContributionPlugin, false otherwise
     */
    export function is(value: ServerContributionPlugin): value is ModelContributionPlugin {
        return "type" in value && value.type === TYPE;
    }
}
