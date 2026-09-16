import type { LanguageServices } from "@mdeo/language-common";
import type { URI } from "vscode-uri";
import type { LangiumInstance } from "../langium/langiumInstance.js";
import type { ServerApi } from "../service/serverApi.js";
import type { ServiceAdditionalServices } from "../langium/types.js";
import type { ServerContributionPlugin } from "@mdeo/plugin";

/**
 * Handler for file data computation requests
 *
 * @template T The return type of the handler
 * @template S The type of LanguageServices used
 */
export type FileDataHandler<T = unknown, S = object> = (context: FileDataContext<S>) => Promise<FileDataResult<T>>;

/**
 * Handler for general language plugin requests
 *
 * @template T The return type of the handler
 * @template S The type of LanguageServices used
 */
export type RequestHandler<T = unknown, S = object> = (context: RequestContext<S>) => Promise<T | null>;

/**
 * File information for file data handlers.
 * Contains path and version for file-based requests.
 * Undefined for directory-based requests.
 */
export interface FileInfo {
    /**
     * The URI of the file
     */
    uri: URI;
    /**
     * Version of the file
     */
    version: number;
}

/**
 * Context provided to file data handlers
 */
export interface FileDataContext<T = object> {
    /**
     * File information (path and version) for file-based requests.
     * Undefined for directory-based requests.
     */
    fileInfo?: FileInfo;

    /**
     * The Langium instance to use for processing
     */
    instance: LangiumInstance<T>;

    /**
     * The Langium services for this language
     */
    services: LanguageServices & ServiceAdditionalServices & T;

    /**
     * Server API for accessing backend endpoints
     */
    serverApi: ServerApi;

    /**
     * The contribution plugins active for this request.
     * Useful for handlers that need to inspect or forward plugin configurations.
     */
    contributionPlugins: ServerContributionPlugin[];

    /**
     * Aborts when the caller's deadline passes or the caller goes away. Nobody waits for the result
     * after that, so pass it to anything long-running. Calls through {@link serverApi} use it already.
     */
    signal: AbortSignal;
}

/**
 * Context provided to request handlers
 */
export interface RequestContext<T = object> {
    /**
     * The request body data
     */
    body: unknown;

    /**
     * The JWT token extracted from the Authorization header.
     * Use this to forward authorization when calling downstream services.
     */
    jwt: string;

    /**
     * The Langium instance to use for processing
     */
    instance: LangiumInstance<T>;

    /**
     * The Langium services for this language
     */
    services: LanguageServices & ServiceAdditionalServices & T;

    /**
     * Server API for accessing backend endpoints
     */
    serverApi: ServerApi;

    /**
     * The contribution plugins active for this request.
     */
    contributionPlugins: ServerContributionPlugin[];

    /**
     * Aborts when the caller's deadline passes or the caller goes away. Nobody waits for the result
     * after that, so pass it to anything long-running. Calls through {@link serverApi} use it already.
     */
    signal: AbortSignal;
}

/**
 * Result of a file data computation
 * @template T The type of the computed data
 */
export interface FileDataResult<T = unknown> {
    /**
     * The computed data (JSON value)
     */
    data: T;

    /**
     * File dependencies (paths and versions)
     */
    fileDependencies: FileDependency[];

    /**
     * Data dependencies (other file data computations)
     */
    dataDependencies: DataDependency[];

    /**
     * Additional file data computed as part of this request
     */
    additionalFileData?: AdditionalFileData<T>[];
}

/**
 * File dependency information
 */
export interface FileDependency {
    /**
     * Path to the file
     */
    path: string;
    /**
     * Version of the file (only for files, not directories)
     */
    version?: number;
}
/**
 * Data dependency information
 */

export interface DataDependency {
    /**
     * Path to the file or directory
     */
    path: string;
    /**
     * Key for the file-data request
     */
    key: string;
    /**
     * Version of the file the data was computed for (only for files, not directories)
     */
    version?: number;
}

/**
 * Additional file data computed as part of another computation
 * Eagerly cached in the backend for efficiency.
 */
export interface AdditionalFileData<T = unknown> extends Omit<FileDataResult<T>, "additionalFileData"> {
    /**
     * Path to the file or directory
     */
    path: string;
    /**
     * Key for the file-data request
     */
    key: string;
    /**
     * Version of the file the data was computed for (only for files, not directories)
     */
    sourceVersion?: number;
}
