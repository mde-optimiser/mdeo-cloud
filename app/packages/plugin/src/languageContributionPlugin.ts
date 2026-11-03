import type { SessionTypes } from "./sessionType.js";

/**
 * Language plugin configuration for language-specific contributions.
 */
export interface LanguageContributionPlugin {
    /**
     * The language ID that this contribution plugin is associated with.
     */
    languageId: string;
    /**
     * Description of what this contribution plugin provides.
     */
    description: string;
    /**
     * Optional array of additional keywords that this contribution plugin introduces.
     */
    additionalKeywords: string[];
    /**
     * Server contribution plugins provided by the plugin
     */
    serverContributionPlugins: ServerContributionPlugin[];
}

/**
 * Server plugin configuration for general contributions.
 * These plugins extend the server with additional functionality that is not language-specific.
 */
export interface ServerContributionPlugin {
    id: string;
    /**
     * Sessions this contribution accepts, keyed by session name.
     * Callers reach them at the `contrib:<id>` address.
     */
    sessions?: SessionTypes;
}
