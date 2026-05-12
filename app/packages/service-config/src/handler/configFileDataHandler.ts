import { ConfigContributionPlugin, getWrapperInterfaceName } from "@mdeo/language-config";
import type { ConfigAdditionalServices, ConfigType } from "@mdeo/language-config";
import type {
    ConfigPluginDependencyData,
    ConfigPluginRequestBody,
    ConfigPluginRequestResponse
} from "@mdeo/service-config-common";
import { hasParserErrors, type FileDataHandler, type FileDependency, type DataDependency } from "@mdeo/service-common";
import type { AstNode } from "langium";

/**
 * The key for the configuration file-data handler.
 * Plugin request handlers in contribution plugins must use the same key.
 */
export const CONFIG_DATA_KEY = "config";

export type { ConfigPluginDependencyData, ConfigPluginRequestBody } from "@mdeo/service-config-common";

/**
 * The overall data returned by the config file-data handler.
 * Keys are plugin short names, values are the data returned by that plugin's request handler
 * (typically a mapping from section name to section-specific data).
 */
export type ConfigFileData = Record<string, Record<string, unknown>>;

/**
 * Information about a section type derived from a config contribution plugin.
 */
interface SectionTypeInfo {
    /**
     * The plugin that contributes this section.
     */
    plugin: ConfigContributionPlugin;
    /**
     * The qualified name of the section (sectionName.pluginName).
     */
    qualifiedName: string;
}

/**
 * Builds a map from section wrapper `$type` to its info for all active config plugins.
 *
 * @param plugins The active config contribution plugins
 * @returns A map from `$type` string to section type info
 */
function buildSectionTypeMap(plugins: ConfigContributionPlugin[]): Map<string, SectionTypeInfo> {
    const map = new Map<string, SectionTypeInfo>();
    for (const plugin of plugins) {
        for (const section of plugin.sections) {
            const type = getWrapperInterfaceName(section.name, plugin.name);
            map.set(type, {
                plugin,
                qualifiedName: `${section.name}.${plugin.name}`
            });
        }
    }
    return map;
}

/**
 * Checks whether the config document contains any duplicate sections.
 * A section is considered a duplicate if its `$type` appears more than once.
 * Uses the same logic as {@link ConfigValidator.validateSectionUniqueness}.
 *
 * @param config The parsed config root node
 * @returns True if any section type appears more than once
 */
function hasDuplicateSections(config: ConfigType): boolean {
    const typeCounts = new Map<string, number>();
    for (const section of config.sections ?? []) {
        typeCounts.set(section.$type, (typeCounts.get(section.$type) ?? 0) + 1);
    }
    for (const count of typeCounts.values()) {
        if (count > 1) {
            return true;
        }
    }
    return false;
}

/**
 * Checks whether all section dependencies declared by each present plugin section are satisfied.
 * Uses the same logic as {@link ConfigValidator.validateSectionDependencies}.
 *
 * @param config The parsed config root node
 * @param sectionTypeMap The map from `$type` to section type info
 * @returns True if any required dependency section is absent
 */
function hasMissingSectionDependencies(config: ConfigType, sectionTypeMap: Map<string, SectionTypeInfo>): boolean {
    const presentQualifiedNames = new Set<string>();
    for (const section of config.sections ?? []) {
        const info = sectionTypeMap.get(section.$type);
        if (info != undefined) {
            presentQualifiedNames.add(info.qualifiedName);
        }
    }

    for (const section of config.sections ?? []) {
        const info = sectionTypeMap.get(section.$type);
        if (info == undefined) {
            continue;
        }
        for (const dep of info.plugin.sectionDependencies) {
            const depQualifiedName = `${dep.sectionName}.${dep.pluginName}`;
            if (!presentQualifiedNames.has(depQualifiedName)) {
                return true;
            }
        }
    }
    return false;
}

/**
 * Sorts ConfigContributionPlugins in topological order based on their sectionDependencies.
 * Plugins whose sections are required by others are placed first.
 *
 * @param plugins All active ConfigContributionPlugins
 * @returns Plugins sorted so that dependencies are processed before dependents
 */
function sortBySectionDependencies(plugins: ConfigContributionPlugin[]): ConfigContributionPlugin[] {
    const pluginByName = new Map(plugins.map((plugin) => [plugin.name, plugin]));
    const sorted: ConfigContributionPlugin[] = [];
    const visited = new Set<string>();
    const visiting = new Set<string>();

    function visit(pluginName: string): void {
        if (visited.has(pluginName)) {
            return;
        }
        if (visiting.has(pluginName)) {
            throw new Error(`Circular sectionDependency detected involving plugin: ${pluginName}`);
        }

        visiting.add(pluginName);
        const plugin = pluginByName.get(pluginName);
        if (plugin != undefined) {
            const dependencyPluginNames = new Set(plugin.sectionDependencies.map((d) => d.pluginName));
            for (const depName of dependencyPluginNames) {
                if (pluginByName.has(depName)) {
                    visit(depName);
                }
            }
            visited.add(pluginName);
            visiting.delete(pluginName);
            sorted.push(plugin);
        }
    }

    for (const plugin of plugins) {
        visit(plugin.name);
    }

    return sorted;
}

/**
 * File-data handler that computes structured data for all contribution plugin sections
 * in a config file.
 *
 * Returns `null` when the config document cannot be processed correctly, specifically:
 * - When there are lexer or parser errors in the document.
 * - When any section appears more than once (duplicate sections).
 * - When a section's required dependency sections are absent.
 * This mirrors the null-return behaviour of the typed-AST handler in service-script.
 * Langium validation diagnostics (e.g. unresolved references) are deliberately excluded
 * from this check because the service does not have access to the other languages that
 * implement the referenced sections.
 *
 * Algorithm:
 * 1. Parses the config file and validates it structurally.
 * 2. Processes plugins in topological order based on sectionDependencies.
 * 3. For each plugin:
 *    a. Builds a partial config text containing only that plugin's sections,
 *       using simple (non-qualified) section keywords.
 *    b. Sends a plugin request to the plugin's language service via the backend proxy,
 *       including previously computed dependency data.
 *    c. Collects the plugin's result (a section-name → data map).
 * 4. Returns a map of plugin short name → plugin result, or `null` on any error.
 */
export const configDataHandler: FileDataHandler<ConfigFileData | null, ConfigAdditionalServices> = async (context) => {
    const { fileInfo, instance, contributionPlugins, serverApi } = context;

    if (fileInfo == undefined) {
        return { data: null, fileDependencies: [], dataDependencies: [] };
    }

    const document = await instance.buildDocument(fileInfo.uri);
    const config = document.parseResult.value as ConfigType | undefined;
    if (config == undefined || !Array.isArray(config.sections)) {
        return { data: null, fileDependencies: [], dataDependencies: [] };
    }

    if (hasParserErrors(document)) {
        return { data: null, fileDependencies: [], dataDependencies: [] };
    }

    const activeConfigPlugins = contributionPlugins.filter(ConfigContributionPlugin.is);
    const sectionTypeMap = buildSectionTypeMap(activeConfigPlugins);

    if (hasDuplicateSections(config)) {
        return { data: null, fileDependencies: [], dataDependencies: [] };
    }

    if (hasMissingSectionDependencies(config, sectionTypeMap)) {
        return { data: null, fileDependencies: [], dataDependencies: [] };
    }

    const sortedPlugins = sortBySectionDependencies(activeConfigPlugins);

    const sectionLookup = new Map(config.sections.map((section) => [section.$type, section]));

    const result: ConfigFileData = {};
    const accumulatedFileDeps: FileDependency[] = [];
    const accumulatedDataDeps: DataDependency[] = [];

    for (const plugin of sortedPlugins) {
        const partialLines: string[] = [];
        if (plugin.sections.length === 0) {
            continue;
        }
        for (const pluginSection of plugin.sections) {
            const section = sectionLookup.get(getWrapperInterfaceName(pluginSection.name, plugin.name));
            if (section == undefined) {
                continue;
            }
            const content = (section as AstNode & { content: AstNode }).content;
            const contentText: string = content?.$cstNode?.text ?? "";
            partialLines.push(`${pluginSection.name} ${contentText}`);
        }
        const partialText = partialLines.join("\n");

        const dependencyData: ConfigPluginDependencyData = {};
        for (const dep of plugin.sectionDependencies) {
            const depPluginData = result[dep.pluginName];
            if (depPluginData != undefined && dep.sectionName in depPluginData) {
                if (dependencyData[dep.pluginName] == undefined) {
                    dependencyData[dep.pluginName] = {};
                }
                dependencyData[dep.pluginName][dep.sectionName] = depPluginData[dep.sectionName];
            }
        }

        const requestBody: ConfigPluginRequestBody = {
            dependencyData,
            text: partialText,
            configFileUri: fileInfo.uri.toString()
        };

        const rawPluginResult = await serverApi.sendPluginRequest(plugin.languageKey, CONFIG_DATA_KEY, requestBody);
        const pluginResponse = rawPluginResult as ConfigPluginRequestResponse<Record<string, unknown>>;

        accumulatedFileDeps.push(...(pluginResponse?.fileDependencies ?? []));
        accumulatedDataDeps.push(...(pluginResponse?.dataDependencies ?? []));

        if (pluginResponse?.data == null) {
            const trackedRequests = serverApi.getTrackedRequests();
            return {
                data: null,
                fileDependencies: [...trackedRequests.fileDependencies, ...accumulatedFileDeps],
                dataDependencies: [...trackedRequests.dataDependencies, ...accumulatedDataDeps]
            };
        }

        result[plugin.name] = pluginResponse.data;
    }

    const trackedRequests = serverApi.getTrackedRequests();
    return {
        data: result,
        fileDependencies: [...trackedRequests.fileDependencies, ...accumulatedFileDeps],
        dataDependencies: [...trackedRequests.dataDependencies, ...accumulatedDataDeps]
    };
};
