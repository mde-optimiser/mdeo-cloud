import { ConfigContributionPlugin } from "@mdeo/language-config";
import { GrammarSerializer } from "@mdeo/language-common";
import { Function } from "@mdeo/language-script";

/**
 * The unique plugin ID for the script config contribution plugin.
 */
export const SCRIPT_CONFIG_PLUGIN_ID = "config-script";

/**
 * The language key for the script language.
 */
export const SCRIPT_LANGUAGE_KEY = "script";

/**
 * Creates the script config contribution plugin.
 * This plugin exports script function type for use by other config plugins.
 *
 * @returns The ConfigContributionPlugin for script
 */
export function createScriptConfigContributionPlugin(): ConfigContributionPlugin {
    const serializer = new GrammarSerializer({
        rules: [],
        additionalTerminals: [],
        interfaces: [Function]
    });

    return {
        id: SCRIPT_CONFIG_PLUGIN_ID,
        type: ConfigContributionPlugin.TYPE,
        name: "script",
        languageKey: SCRIPT_LANGUAGE_KEY,
        grammar: serializer.grammar,
        sections: [],
        dependencies: [],
        exportedTypes: [Function.name],
        sectionDependencies: []
    };
}
