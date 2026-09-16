import type { LangiumLanguagePluginProvider, LanguageServices, PartialLanguageServices } from "@mdeo/language-common";
import type { Plugin, LanguagePlugin } from "@mdeo/plugin";
import type { FastifyInstance } from "fastify";
import type { FileDataHandler, FileDataResult, RequestHandler } from "../handler/types.js";
import type { ExecutionHandler } from "../execution/types.js";
import type { SessionHandlers } from "../ws/sessionServer.js";
import type { DeepPartial, Module } from "langium";

/**
 * Plugin definition for the service.
 * This is the Plugin interface from @mdeo/plugin with the 'url' field omitted
 * since the URL is derived from the service's deployment.
 */
export type ServicePlugin = Omit<Plugin, "url" | "default">;

/**
 * Definition for constructing a service plugin.
 */
export interface ServicePluginDefinition extends Omit<ServicePlugin, "languagePlugins"> {
    /**
     * Language plugins provided by the service plugin
     */
    languagePlugins: LanguagePlugin[];
}

/**
 * Configuration for a single language within a service.
 *
 * @template T Type of additional services for this language
 */
export interface LanguageServiceConfig<T = object> {
    /**
     * The language plugin definition for this language
     */
    languagePlugin: LanguagePlugin;

    /**
     * Langium language plugin provider for this language
     */
    languagePluginProvider: LangiumLanguagePluginProvider<T>;

    /**
     * Optional service-specific module
     */
    serviceModule?: Module<LanguageServices & T, PartialLanguageServices & DeepPartial<T>>;

    /**
     * File data handlers keyed by data key (e.g., "ast", "diagram")
     */
    fileDataHandlers: Record<string, FileDataHandler<unknown, T>>;

    /**
     * Request handlers for general language plugin requests keyed by request key
     */
    requestHandlers?: Record<string, RequestHandler<unknown, T>>;

    /**
     * Execution handlers for processing execution requests (optional)
     */
    executionHandlers?: ExecutionHandler<unknown>[];
}

/**
 * Configuration for the language service supporting multiple languages.
 *
 * @template T Type of additional services
 */
export interface ServiceConfig<T = object> {
    /**
     * Port to run the service on
     */
    port: number;

    /**
     * Host to bind to (default: '0.0.0.0')
     */
    host?: string;

    /**
     * Maximum number of Langium instances to keep in the pool per language
     */
    maxLangiumInstances?: number;

    /**
     * Maximum size in bytes of an accepted request body. Must be large enough to hold the biggest
     * source file the service is asked to process, since file content is sent in the request body.
     */
    maxRequestBodyBytes?: number;

    /**
     * Base URL of the backend API for ServerApi
     */
    backendApiUrl: string;

    /**
     * Expected JWT issuer
     */
    jwtIssuer: string;

    /**
     * Plugin definition for this service
     */
    plugin: ServicePluginDefinition;

    /**
     * Language configurations for all supported languages.
     * Each configuration defines handlers and providers for a specific language.
     */
    languages: LanguageServiceConfig<T>[];

    /**
     * Session handlers this service serves, keyed by target address and then by session name.
     *
     * A handler must have a matching declaration in {@link plugin} — on the language plugin for
     * a `lang:` address, on the contribution payload for a `contrib:` one. The declaration is
     * what a caller negotiates against; this is only what answers once it has.
     */
    sessions?: SessionHandlers;

    /**
     * Maximum number of Langium instances open sessions may hold at once, per language.
     *
     * Separate from {@link maxLangiumInstances}, because a session keeps its instance for the
     * length of a connection rather than the length of a request.
     */
    maxSessionInstances?: number;

    /**
     * How long a request may wait for a free Langium instance before failing, in milliseconds.
     */
    langiumAcquireTimeoutMs?: number;

    /**
     * Whether to serve static files (default: true)
     */
    serveStatic?: boolean;

    /**
     * Path to static files to serve (default: dist directory)
     */
    staticPath?: string;

    /**
     * Optional static asset version used for cache busting.
     * When set, static assets are served under /static/{version}/.
     */
    version?: string;
}

/**
 * Directory entry returned by listDirectory
 */
export interface DirectoryEntry {
    /**
     * Name of the entry
     */
    name: string;
    /**
     * Whether the entry is a file or directory
     */
    isFile: boolean;
    /**
     * Whether the entry is a directory
     */
    isDirectory: boolean;
}

/**
 * Source data for file or directory.
 * Contains version, content, and path for files.
 * Null/absent for directories.
 */
export interface FileSource {
    /**
     * Version of the file (only for files, not directories)
     */
    version: number;
    /**
     * Content of the file (only for files, not directories)
     */
    content: string;
    /**
     * Path to the file
     */
    path: string;
}

/**
 * Request body for file data computation.
 * Can be used for both files and directories.
 * For files, source contains version, content, and path.
 * For directories, source is absent/null.
 */
export interface FileDataComputeRequest {
    /**
     * Project identifier for the project the file/directory belongs to
     */
    project: string;
    /**
     * Source data (version, content, and path), only present for files, absent for directories
     */
    source?: FileSource;
    /**
     * Contribution plugins to apply for this request
     */
    contributionPlugins?: object[];
    /**
     * Identifies the contribution set. Sent alone once the backend knows this service keeps sets by
     * hash; see `ContributionCache`.
     */
    contributionHash?: string;
}

/**
 * Response body for file data computation
 */
export type FileDataComputeResponse<T = unknown> = Required<FileDataResult<T>>;

/**
 * Factory function type for creating a language service
 */
export type ServiceFactory = (fastify: FastifyInstance) => Promise<void>;

/**
 * Contribution plugin configuration passed in requests
 */
export interface ContributionPluginConfig {
    [key: string]: unknown;
}

/**
 * Key for identifying a Langium instance based on contribution plugins
 */
export type ContributionPluginKey = string;
