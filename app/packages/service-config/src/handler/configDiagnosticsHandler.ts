import { ConfigContributionPlugin } from "@mdeo/language-config";
import type { ConfigAdditionalServices, ConfigType } from "@mdeo/language-config";
import {
    CONFIG_DIAGNOSTICS_REQUEST_KEY,
    type ConfigPluginDiagnosticsResponse,
    type ConfigPluginRequestBody,
    type ConfigPluginRequestResponse
} from "@mdeo/service-config-common";
import {
    collectDiagnostics,
    hasParserErrors,
    type DataDependency,
    type DiagnosticsData,
    type DocumentDiagnostic,
    type FileDataHandler,
    type FileDependency
} from "@mdeo/service-common";
import type { LangiumDocument } from "langium";
import {
    buildPartialConfig,
    buildSectionTypeMap,
    collectDependencyData,
    CONFIG_DATA_KEY,
    hasDuplicateSections,
    hasMissingSectionDependencies,
    sortBySectionDependencies,
    type ConfigFileData,
    type PartialConfig
} from "./configFileDataHandler.js";

/**
 * A range in a document.
 */
type Range = DocumentDiagnostic["range"];

/**
 * File-data handler for the diagnostics of a config file.
 *
 * This service parses the whole config, but only the plugins can resolve the references in
 * their sections and run their validations, so it reports the diagnostics of the config as a
 * whole and asks each plugin for those of its sections:
 * 1. Builds the config and keeps its diagnostics except unresolved references, which it reports
 *    for every reference in a plugin section.
 * 2. Unless the config has parser errors, duplicate sections or missing section dependencies,
 *    which make partial configs meaningless, sends each plugin its partial config, as the config
 *    file-data handler does, and moves the diagnostics the plugin reports into the config.
 *
 * A plugin whose section dependencies have no data, as those sections have errors, is not asked:
 * its sections cannot be checked without that data, and the errors are reported where they are.
 */
export const configDiagnosticsHandler: FileDataHandler<DiagnosticsData, ConfigAdditionalServices> = async (context) => {
    const { fileInfo, instance, contributionPlugins, serverApi } = context;

    if (fileInfo == undefined) {
        throw new Error("Diagnostics handler does not support directory requests");
    }

    const document = await instance.buildDocument(fileInfo.uri);
    const config = document.parseResult.value as ConfigType | undefined;
    const diagnostics = collectDiagnostics(document).filter((diagnostic) => diagnostic.source !== "linking");
    const dependenciesWithErrors = new Set<string>();
    const fileDependencies: FileDependency[] = [];
    const dataDependencies: DataDependency[] = [];

    const activeConfigPlugins = contributionPlugins.filter(ConfigContributionPlugin.is);
    const sectionTypeMap = buildSectionTypeMap(activeConfigPlugins);
    const canAskPlugins =
        config != undefined &&
        Array.isArray(config.sections) &&
        !hasParserErrors(document) &&
        !hasDuplicateSections(config) &&
        !hasMissingSectionDependencies(config, sectionTypeMap);

    if (canAskPlugins) {
        const sortedPlugins = sortBySectionDependencies(activeConfigPlugins);
        const dependedOn = new Map<string, Set<string>>();
        for (const dep of sortedPlugins.flatMap((plugin) => plugin.sectionDependencies)) {
            dependedOn.set(dep.pluginName, (dependedOn.get(dep.pluginName) ?? new Set()).add(dep.sectionName));
        }
        const sectionLookup = new Map(config.sections.map((section) => [section.$type, section]));
        const computed: ConfigFileData = {};
        const withoutData = new Set<string>();

        for (const plugin of sortedPlugins) {
            const partial = buildPartialConfig(plugin, sectionLookup);
            if (partial.segments.length === 0) {
                continue;
            }
            if (plugin.sectionDependencies.some((dep) => withoutData.has(dep.pluginName))) {
                withoutData.add(plugin.name);
                continue;
            }

            const requestBody: ConfigPluginRequestBody = {
                dependencyData: collectDependencyData(plugin, computed),
                text: partial.text,
                configFileUri: fileInfo.uri.toString()
            };

            const sectionsDependedOn = dependedOn.get(plugin.name);
            if (sectionsDependedOn != undefined) {
                // Only the sections others depend on, so errors in the rest do not keep them unchecked.
                const dataResponse = (await serverApi.sendPluginRequest(plugin.languageKey, CONFIG_DATA_KEY, {
                    ...requestBody,
                    text: buildPartialConfig(plugin, sectionLookup, sectionsDependedOn).text
                })) as ConfigPluginRequestResponse | null;
                fileDependencies.push(...(dataResponse?.fileDependencies ?? []));
                dataDependencies.push(...(dataResponse?.dataDependencies ?? []));
                if (dataResponse?.data == null) {
                    withoutData.add(plugin.name);
                } else {
                    computed[plugin.name] = dataResponse.data;
                }
            }

            const response = (await serverApi.sendPluginRequest(
                plugin.languageKey,
                CONFIG_DIAGNOSTICS_REQUEST_KEY,
                requestBody
            )) as ConfigPluginDiagnosticsResponse;
            fileDependencies.push(...response.fileDependencies);
            dataDependencies.push(...response.dataDependencies);
            for (const diagnostic of response.diagnostics) {
                diagnostics.push({ ...diagnostic, range: toConfigRange(diagnostic.range, partial, document) });
            }
            for (const path of response.dependenciesWithErrors) {
                dependenciesWithErrors.add(path);
            }
        }
    }

    const trackedRequests = serverApi.getTrackedRequests();
    return {
        data: {
            path: document.uri.path,
            diagnostics: diagnostics.sort(compareStarts),
            dependenciesWithErrors: [...dependenciesWithErrors].sort()
        },
        fileDependencies: [...trackedRequests.fileDependencies, ...fileDependencies],
        dataDependencies: [...trackedRequests.dataDependencies, ...dataDependencies]
    };
};

/**
 * Moves a range of a partial config to where its text is in the config. The config may name a
 * section differently, so a position on a section keyword moves to the start of the keyword in
 * the config if it is at the start, else to the end.
 *
 * @param range The range in the partial config
 * @param partial The partial config
 * @param document The config document
 * @returns The range in the config
 */
function toConfigRange(range: Range, partial: PartialConfig, document: LangiumDocument): Range {
    const lineOffsets = [0];
    for (let offset = partial.text.indexOf("\n"); offset !== -1; offset = partial.text.indexOf("\n", offset + 1)) {
        lineOffsets.push(offset + 1);
    }
    const toConfigPosition = (position: Range["start"]) => {
        const offset = (lineOffsets[position.line] ?? partial.text.length) + position.character;
        const segment =
            partial.segments.findLast((candidate) => candidate.partialSectionOffset <= offset) ?? partial.segments[0];
        let configOffset: number;
        if (offset <= segment.partialSectionOffset) {
            configOffset = segment.sectionOffset;
        } else if (offset < segment.partialContentOffset) {
            const keyword = document.textDocument.getText().slice(segment.sectionOffset, segment.contentOffset);
            configOffset = segment.sectionOffset + keyword.trimEnd().length;
        } else {
            configOffset = segment.contentOffset + Math.min(offset - segment.partialContentOffset, segment.length);
        }
        return document.textDocument.positionAt(configOffset);
    };
    return { start: toConfigPosition(range.start), end: toConfigPosition(range.end) };
}

/**
 * Orders diagnostics by where they start.
 */
function compareStarts(a: DocumentDiagnostic, b: DocumentDiagnostic): number {
    return a.range.start.line - b.range.start.line || a.range.start.character - b.range.start.character;
}
