import type { FileDependency, DataDependency, DocumentDiagnostic, ExecutionResultEntry } from "@mdeo/service-common";

/**
 * Dependency data passed to each plugin request handler.
 * Keys are plugin short names; values are section-name → data maps from
 * plugins that this plugin declared as sectionDependencies.
 */
export type ConfigPluginDependencyData = Record<string, Record<string, unknown>>;

/**
 * Request body sent to each contribution plugin's request handler.
 */
export interface ConfigPluginRequestBody {
    /**
     * Computed data from dependency plugins, keyed by plugin short name then section name.
     */
    dependencyData: ConfigPluginDependencyData;
    /**
     * Partial config text containing only sections from this plugin, using simple keywords.
     */
    text: string;
    /**
     * The URI string of the originating config file. Used to construct a stable synthetic document URI.
     */
    configFileUri: string;
}

/**
 * Response returned by a config contribution plugin's request handler.
 * Wraps the actual section-data result together with the file and data
 * dependencies accumulated during processing. Dependencies MUST be
 * included even when {@link data} is `null` so that the config file-data
 * handler can propagate them to the cache and avoid incorrect cache
 * invalidation.
 *
 * @template T The type of the section-data payload.
 */
export interface ConfigPluginRequestResponse<T = Record<string, unknown>> {
    /**
     * The computed section data, or `null` when the plugin encountered an
     * error (e.g. parse or validation error) that prevents a valid result.
     */
    data: T | null;
    /**
     * File paths (and their versions) that were read during processing.
     */
    fileDependencies: FileDependency[];
    /**
     * Other file-data computations that were consulted during processing.
     */
    dataDependencies: DataDependency[];
}

/**
 * Response to a {@link CONFIG_DIAGNOSTICS_REQUEST_KEY} request: the diagnostics of the partial
 * config text, with ranges in that text.
 */
export interface ConfigPluginDiagnosticsResponse {
    /**
     * The diagnostics of the partial config text, of every severity.
     */
    diagnostics: DocumentDiagnostic[];
    /**
     * The paths of the files the sections depend on, directly or transitively, that have errors
     * themselves.
     */
    dependenciesWithErrors: string[];
    /**
     * File paths (and their versions) that were read during processing.
     */
    fileDependencies: FileDependency[];
    /**
     * Other file-data computations that were consulted during processing.
     */
    dataDependencies: DataDependency[];
}

/**
 * Request body sent by service-config when forwarding an execution to
 * the contribution plugin that owns the executable config section.
 */
export interface ConfigExecutionPluginRequestBody {
    /**
     * Execution identifier created by the backend.
     */
    executionId: string;
    /**
     * Project identifier of the running execution.
     */
    project: string;
    /**
     * Path to the originating .config file.
     */
    filePath: string;
    /**
     * Raw content of the originating .config file.
     */
    fileContent: string;
    /**
     * File version of the originating .config file.
     */
    fileVersion: number;
    /**
     * Action/execution payload created by the plugin action handler.
     */
    data: object;
}

/**
 * Metadata persisted on an execution to identify where config execution requests were forwarded.
 */
export interface ConfigExecutionRoutingMetadata {
    /**
     * Language ID of the contribution plugin that handled execution.
     */
    languageId: string;
    /**
     * Executable section name in the config that initiated execution.
     */
    sectionName: string;
    /**
     * Contribution plugin short name.
     */
    pluginName: string;
}

/**
 * Base request body for config execution follow-up operations.
 */
export interface ConfigExecutionFollowUpRequestBody {
    executionId: string;
}

/**
 * Request body for config execution file reads.
 */
export interface ConfigExecutionFileRequestBody extends ConfigExecutionFollowUpRequestBody {
    path: string;
}

/**
 * Request body for reading many config execution result files at once.
 *
 * A config execution's results are forwarded twice — service-config asks the contribution
 * plugin, which asks the execution service — so reading them one file at a time multiplies
 * the round trips. This asks for the whole set in one request.
 */
export interface ConfigExecutionFilesRequestBody extends ConfigExecutionFollowUpRequestBody {
    /**
     * Files to read, or null for every file in the result tree.
     */
    paths: string[] | null;
}

/**
 * Response to a {@link ConfigExecutionFilesRequestBody}.
 *
 * The plugin-request channel is a plain request/response call through the backend and cannot
 * stream, so the contents come back together rather than file by file.
 */
export interface ConfigExecutionFilesResponse {
    /**
     * The entries that were read, in result-tree order.
     */
    files: ExecutionResultEntry[];
    /**
     * File contents, keyed by path.
     */
    contents: Record<string, string>;
}

/**
 * Request-handler key used by config contribution plugin services
 * to register and handle partial-config data requests from service-config.
 */
export const CONFIG_PLUGIN_REQUEST_KEY = "config";

/**
 * Request-handler key used by config contribution plugin services to report the diagnostics of
 * a partial config, with the same {@link ConfigPluginRequestBody} as {@link CONFIG_PLUGIN_REQUEST_KEY}.
 *
 * Only the plugin can resolve the references in its sections, so service-config cannot tell
 * whether they resolve on its own.
 */
export const CONFIG_DIAGNOSTICS_REQUEST_KEY = "config-diagnostics";

/**
 * Request-handler key for forwarding config execution to a contribution plugin language.
 */
export const CONFIG_EXECUTION_REQUEST_KEY = "config-execution";

/**
 * Request-handler key for config execution summary forwarding.
 */
export const CONFIG_EXECUTION_GET_SUMMARY_REQUEST_KEY = "config-execution-get-summary";

/**
 * Request-handler key for config execution file tree forwarding.
 */
export const CONFIG_EXECUTION_GET_FILE_TREE_REQUEST_KEY = "config-execution-get-file-tree";

/**
 * Request-handler key for config execution file forwarding.
 */
export const CONFIG_EXECUTION_GET_FILE_REQUEST_KEY = "config-execution-get-file";

/**
 * Request-handler key for bulk config execution file forwarding.
 */
export const CONFIG_EXECUTION_GET_FILES_REQUEST_KEY = "config-execution-get-files";

/**
 * Request-handler key for config execution cancel forwarding.
 */
export const CONFIG_EXECUTION_CANCEL_REQUEST_KEY = "config-execution-cancel";

/**
 * Request-handler key for config execution delete forwarding.
 */
export const CONFIG_EXECUTION_DELETE_REQUEST_KEY = "config-execution-delete";
