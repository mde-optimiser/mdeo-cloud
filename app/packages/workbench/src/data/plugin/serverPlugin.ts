import type { LanguagePlugin, LanguageServerPlugin, ServerContributionPlugin } from "@mdeo/plugin";

/**
 * Server plugin configuration for language-specific functionality.
 * Extends the base plugin with language identification and file extension mapping.
 */
export interface ServerPlugin extends LanguageServerPlugin, Pick<LanguagePlugin, "id" | "extension"> {
    /**
     * Registered contribution plugins for this language
     */
    contributionPlugins: ServerContributionPlugin[];
}
