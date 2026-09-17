import {
    EmptyFileSystemProvider,
    inject,
    type LangiumCoreServices,
    type LangiumGeneratedCoreServices,
    type LangiumGeneratedSharedCoreServices,
    type LangiumSharedCoreServices,
    type LanguageMetaData,
    type Module
} from "langium";
import type { LanguageServices, ExternalReferenceSharedAdditionalServices } from "@mdeo/language-common";
import type { ServerContributionPlugin } from "@mdeo/plugin";
import { createGLSPModule, createModule } from "@mdeo/language-common";
import { HttpServerApi } from "../service/serverApi.js";
import type { ContributionPluginKey } from "../service/types.js";
import { createDefaultModule, createDefaultSharedModule } from "langium/lsp";
import * as langium from "langium";
import * as langiumGrammar from "langium/grammar";
import type { ServiceAdditionalSharedServices, LangiumPoolConfig } from "./types.js";
import { BackendExternalReferencesResolver } from "./backendExternalReferencesResolver.js";
import { LangiumInstance } from "./langiumInstance.js";
import { JsonAstSerializer } from "./jsonAstSerializer.js";
import { ExtendedIndexManager } from "./extendedIndexManager.js";

/**
 * How long a request waits for a free instance before giving up, when the pool is not
 * configured otherwise. Long enough to ride out a slow request ahead of it, short enough that
 * a caller learns the pool is stuck instead of hanging on it.
 */
const DEFAULT_ACQUIRE_TIMEOUT_MS = 30_000;

/**
 * How many instances open sessions may hold at once, when the pool is not configured otherwise.
 */
const DEFAULT_MAX_SESSION_INSTANCES = 2;

/**
 * Raised when the pool cannot serve an acquisition.
 *
 * Both causes are worth telling apart from an ordinary failure: the request path waited out
 * its timeout because every instance is held, or a session was refused because the session
 * budget is full. Neither is the caller's fault, and both say something specific about the
 * service's state.
 */
export class LangiumPoolExhaustedError extends Error {
    /**
     * Creates the error.
     *
     * @param message What could not be served, and why
     */
    constructor(message: string) {
        super(message);
        this.name = "LangiumPoolExhaustedError";
    }
}

/**
 * One request waiting for an instance to come free.
 */
interface InstanceWaiter<T> {
    /**
     * Hands the waiter an instance that just became available.
     */
    resolve(instance: LangiumInstance<T>): void;
    /**
     * Whether this waiter has already been served or has given up.
     */
    settled: boolean;
}

/**
 * Manages a pool of Langium instances for handling file data requests.
 *
 * Each instance is tied to a specific contribution plugin configuration.
 * Instances are reused when the same configuration is requested, and
 * evicted based on LRU when the pool is full.
 *
 * Sessions draw on a separate budget. An instance handed to a session leaves the pool for the
 * lifetime of the connection, which can be a whole execution, so it must not be counted among
 * the instances the request path expects to get back.
 */
export class LangiumInstancePool<T> {
    /**
     * Map of instance IDs to Langium instances
     */
    private readonly instances: Map<string, LangiumInstance<T>> = new Map();
    /**
     * Instances currently held by open sessions, keyed by instance id.
     */
    private readonly sessionInstances: Map<string, LangiumInstance<T>> = new Map();
    /**
     * Counter for generating unique instance IDs
     */
    private instanceCounter = 0;

    /**
     * Queue of waiters for instances when all are busy
     */
    private readonly instanceWaitQueue: InstanceWaiter<T>[] = [];

    constructor(private readonly config: LangiumPoolConfig<T>) {
        this.config = config;
    }

    /**
     * Generates a unique key for a contribution plugin configuration.
     * Uses a stable JSON representation of the sorted plugins.
     *
     * @param contributionPlugins Array of contribution plugin configurations
     * @returns A unique string key identifying this configuration
     */
    private generateContributionKey(contributionPlugins: ServerContributionPlugin[]): ContributionPluginKey {
        if (contributionPlugins.length === 0) {
            return "[]";
        }
        const sorted = contributionPlugins.map((plugin) => JSON.stringify(plugin)).sort();
        return JSON.stringify(sorted);
    }

    /**
     * Acquires a Langium instance for the given contribution plugins.
     * This method handles instance creation, reuse, and eviction.
     *
     * @param contributionPlugins The contribution plugins configuration
     * @param jwt The JWT token for this request
     * @param project The project context for this request
     * @param contributionHash The hash the backend identifies the contribution set by, which keys the
     *        instance when given instead of the payloads themselves
     * @param signal Stops waiting for an instance when it aborts
     * @returns Promise resolving to the acquired Langium instance
     */
    async acquire(
        contributionPlugins: ServerContributionPlugin[],
        jwt: string,
        project: string,
        contributionHash?: string,
        signal?: AbortSignal
    ): Promise<LangiumInstance<T>> {
        signal?.throwIfAborted();
        const key =
            contributionHash != undefined
                ? `hash:${contributionHash}`
                : this.generateContributionKey(contributionPlugins);

        let instance: LangiumInstance<T> | undefined = undefined;

        for (const candidate of this.instances.values()) {
            if (candidate.contributionPluginKey === key) {
                if (!candidate.busy) {
                    instance = candidate;
                    break;
                }
            }
        }

        if (instance == undefined && this.instances.size < this.config.maxInstances) {
            instance = this.createInstance(contributionPlugins, key);
        }

        const usedInstance = instance ?? (await this.evictOrWait(key, contributionPlugins, signal));

        usedInstance.configure(jwt, project);
        return usedInstance;
    }

    /**
     * Evicts the least recently used available instance or waits for one to become available.
     *
     * @param key The contribution plugin key
     * @param contributionPlugins The contribution plugins configuration
     * @param signal Stops the wait when it aborts
     * @returns Promise resolving to the acquired Langium instance
     */
    private async evictOrWait(
        key: ContributionPluginKey,
        contributionPlugins: ServerContributionPlugin[],
        signal?: AbortSignal
    ): Promise<LangiumInstance<T>> {
        let oldestAvailable: LangiumInstance<T> | undefined = undefined;

        for (const instance of this.instances.values()) {
            if (instance.contributionPluginKey !== key && !instance.busy) {
                if (oldestAvailable == undefined || instance.lastUsed < oldestAvailable.lastUsed) {
                    oldestAvailable = instance;
                }
            }
        }

        if (oldestAvailable != undefined) {
            this.instances.delete(oldestAvailable.id);
            return this.createInstance(contributionPlugins, key);
        }

        const timeoutMs = this.config.acquireTimeoutMs ?? DEFAULT_ACQUIRE_TIMEOUT_MS;

        return new Promise<LangiumInstance<T>>((resolve, reject) => {
            const giveUp = (): void => {
                waiter.settled = true;
                clearTimeout(timer);
                signal?.removeEventListener("abort", onAbort);
                const queued = this.instanceWaitQueue.indexOf(waiter);
                if (queued >= 0) {
                    this.instanceWaitQueue.splice(queued, 1);
                }
            };
            const onAbort = (): void => {
                giveUp();
                reject(signal?.reason ?? new Error("Stopped waiting for a Langium instance"));
            };

            const waiter: InstanceWaiter<T> = {
                settled: false,
                resolve: (availableInstance) => {
                    clearTimeout(timer);
                    signal?.removeEventListener("abort", onAbort);
                    if (availableInstance.contributionPluginKey === key) {
                        resolve(availableInstance);
                    } else {
                        this.instances.delete(availableInstance.id);
                        resolve(this.createInstance(contributionPlugins, key));
                    }
                }
            };

            const timer = setTimeout(() => {
                giveUp();
                reject(
                    new LangiumPoolExhaustedError(
                        `No Langium instance became available within ${timeoutMs}ms ` +
                            `(${this.instances.size} pooled, ${this.sessionInstances.size} held by sessions)`
                    )
                );
            }, timeoutMs);
            // A pool that is merely busy must not keep the process alive on its own account.
            timer.unref?.();

            signal?.addEventListener("abort", onAbort, { once: true });
            this.instanceWaitQueue.push(waiter);
        });
    }

    /**
     * Takes an instance out of the pool for the lifetime of a session.
     *
     * The instance is removed from the pool rather than marked busy, so the request path never
     * waits on it. Sessions have their own budget, and it is refused when full instead of
     * queued: the slot only frees when a connection closes, which may be the end of a run.
     *
     * @param contributionPlugins The contribution plugins configuration
     * @param jwt The JWT token of the caller that opened the session
     * @param project The project context of the session
     * @returns The instance the session owns until it closes
     * @throws LangiumPoolExhaustedError when the session budget is exhausted
     */
    acquireForSession(
        contributionPlugins: ServerContributionPlugin[],
        jwt: string,
        project: string
    ): LangiumInstance<T> {
        const maxSessionInstances = this.config.maxSessionInstances ?? DEFAULT_MAX_SESSION_INSTANCES;
        if (this.sessionInstances.size >= maxSessionInstances) {
            throw new LangiumPoolExhaustedError(
                `All ${maxSessionInstances} session instances are in use; try again once a session closes`
            );
        }

        const key = this.generateContributionKey(contributionPlugins);

        let instance: LangiumInstance<T> | undefined = undefined;
        for (const candidate of this.instances.values()) {
            if (candidate.contributionPluginKey === key && !candidate.busy) {
                instance = candidate;
                break;
            }
        }

        const used = instance ?? this.createInstance(contributionPlugins, key);
        this.instances.delete(used.id);
        this.sessionInstances.set(used.id, used);
        used.configure(jwt, project);
        return used;
    }

    /**
     * Returns an instance a session was holding.
     *
     * The instance is reset and readmitted to the pool when there is room, so the next request
     * reuses it rather than paying to build another. When the pool has meanwhile filled up, it
     * is dropped instead — the session budget is freed either way.
     *
     * @param instance The instance the closing session held
     */
    releaseFromSession(instance: LangiumInstance<T>): void {
        this.sessionInstances.delete(instance.id);
        if (instance.busy) {
            instance.reset();
        }
        if (this.instances.size >= this.config.maxInstances) {
            return;
        }
        this.instances.set(instance.id, instance);
        this.serveNextWaiter(instance);
    }

    /**
     * Hands a just-freed instance to the request that has been waiting longest, if any.
     *
     * @param instance The instance that became available
     */
    private serveNextWaiter(instance: LangiumInstance<T>): void {
        while (this.instanceWaitQueue.length > 0) {
            const waiter = this.instanceWaitQueue.shift()!;
            if (waiter.settled) {
                continue;
            }
            waiter.settled = true;
            waiter.resolve(instance);
            return;
        }
    }

    /**
     * Releases an instance after request handling is complete.
     * Clears the JWT and marks the instance as available.
     *
     * @param instance The instance to release
     */
    release(instance: LangiumInstance<T>): void {
        instance.reset();
        this.serveNextWaiter(instance);
    }

    /**
     * Creates a new Langium instance with the given configuration.
     *
     * @param contributionPlugins The contribution plugins for this instance
     * @param key The contribution plugin key
     * @returns Promise resolving to the created Langium instance
     */
    private createInstance(
        contributionPlugins: ServerContributionPlugin[],
        key: ContributionPluginKey
    ): LangiumInstance<T> {
        const plugin = this.config.languagePluginProvider.create(contributionPlugins);

        const languageModule = createModule([plugin], {
            langium,
            "langium/grammar": langiumGrammar
        });

        const generatedSharedModule: Module<
            LangiumSharedCoreServices & ServiceAdditionalSharedServices,
            LangiumGeneratedSharedCoreServices &
                ExternalReferenceSharedAdditionalServices &
                ServiceAdditionalSharedServices
        > = {
            AstReflection: () => languageModule.reflection,
            ServerApi: () => new HttpServerApi(this.config.backendUrl),
            references: {
                ExternalReferenceResolver: (services) => new BackendExternalReferencesResolver(services)
            },
            serializer: {
                JsonAstSerializer: (services) => new JsonAstSerializer(services)
            },
            workspace: {
                IndexManager: (services) => new ExtendedIndexManager(services)
            }
        };

        const fileSystemProvider = new EmptyFileSystemProvider();

        const glspModule = createGLSPModule(globalThis.pluginContext!);

        const shared = inject(
            createDefaultSharedModule({ fileSystemProvider: () => fileSystemProvider }),
            generatedSharedModule,
            glspModule
        );

        const grammar = languageModule.grammars.get(plugin)!;
        const fileExtensions = this.config.extension ? [this.config.extension] : [];
        const languageMetaData: LanguageMetaData = {
            languageId: this.config.languageId,
            fileExtensions,
            caseInsensitive: false,
            mode: "development"
        };

        const generatedModule: Module<LangiumCoreServices, LangiumGeneratedCoreServices> = {
            Grammar: () => grammar,
            LanguageMetaData: () => languageMetaData,
            parser: {}
        };

        const services = langium.inject(
            createDefaultModule({ shared }),
            generatedModule,
            plugin.module,
            this.config.serviceModule
        ) as LanguageServices & { shared: ServiceAdditionalSharedServices } & T;

        shared.ServiceRegistry.register(services);

        if (plugin.postCreate) {
            plugin.postCreate(services, { fileSystemProvider: () => fileSystemProvider });
        }

        const newInstance = new LangiumInstance(`instance-${++this.instanceCounter}`, services, key);
        this.instances.set(newInstance.id, newInstance);
        return newInstance;
    }
}
