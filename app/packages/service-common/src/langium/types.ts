import type { LangiumLanguagePluginProvider, LanguageServices, PartialLanguageServices } from "@mdeo/language-common";
import type { HttpServerApi } from "../service/serverApi.js";
import type { JsonAstSerializer } from "./jsonAstSerializer.js";
import type { ExtendedIndexManager } from "./extendedIndexManager.js";
import type { DeepPartial, Module } from "langium";

/**
 * Additional shared services for the service context
 */
export interface ServiceAdditionalSharedServices {
    /**
     * Server API for backend communication
     */
    ServerApi: HttpServerApi;
    serializer: {
        /**
         * The JSON AST serializer for serializing and deserializing ASTs
         */
        JsonAstSerializer: JsonAstSerializer;
    };
    workspace: {
        /**
         * The extended index manager for managing the language index
         */
        IndexManager: ExtendedIndexManager;
    };
}

/**
 * Additional services for the service context
 */
export interface ServiceAdditionalServices {
    shared: ServiceAdditionalSharedServices;
}

/**
 * Configuration for the Langium instance pool
 */
export interface LangiumPoolConfig<T = object> {
    /**
     * Maximum number of instances to keep in the pool
     */
    maxInstances: number;

    /**
     * Maximum number of instances that may be held by open sessions at the same time.
     *
     * A session holds its instance for as long as the connection lives, which can be the whole
     * length of an execution. This budget is separate from [maxInstances] and is *refused* when
     * exhausted rather than queued: a caller that waits for a slot held by a session that is
     * not finished yet would wait for the rest of the run.
     */
    maxSessionInstances?: number;

    /**
     * How long an ordinary request may wait for a free instance before it fails, in milliseconds.
     *
     * Without a limit the wait queue is unbounded in time, so one instance that is never
     * released stalls every request behind it with nothing to show for it.
     */
    acquireTimeoutMs?: number;

    /**
     * The language plugin to use for creating instances
     */
    languagePluginProvider: LangiumLanguagePluginProvider<T>;

    /**
     * Optional service-specific module
     */
    serviceModule: Module<LanguageServices & T, PartialLanguageServices & DeepPartial<T>> | undefined;

    /**
     * The language ID
     */
    languageId: string;

    /**
     * The file extension for this language
     */
    extension: string;

    /**
     * The server API to use for file operations
     */
    backendUrl: string;
}
