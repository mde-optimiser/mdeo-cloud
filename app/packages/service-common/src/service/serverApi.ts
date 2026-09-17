import type { ServerContributionPlugin } from "@mdeo/plugin";
import { TIMEOUT_HEADER } from "../util/requestLimits.js";
import type { DirectoryEntry } from "./types.js";
import type { FileDependency, DataDependency, FileDataResult } from "../handler/types.js";

/**
 * Header asking the backend to hand the caller's own token to the plugin a request goes to.
 */
export const DELEGATE_TOKEN_HEADER = "x-mdeo-delegate-token";

/**
 * How a plugin request is sent.
 */
export interface PluginRequestOptions {
    /**
     * Whether the target plugin gets this request's own token instead of a read-only one.
     */
    delegate?: boolean;
}

/**
 * Tracked requests made during a file data computation
 */
export type TrackedRequests = Pick<FileDataResult, "fileDependencies" | "dataDependencies">;

/**
 * Interface for file data returned from the server
 */
export interface FileData {
    /**
     * The version of the file data
     */
    version: number;
    /**
     * The actual file data
     */
    data: unknown;
}

/**
 * Maximum number of characters of a failed response body included in an error message.
 */
const MAX_ERROR_BODY_LENGTH = 500;

/**
 * Describes a failed response for use in an error message.
 *
 * The body is included because the backend reports why a call failed in it, and without it a
 * failure deep in a computation is reduced to a bare status code that says nothing about its cause.
 *
 * @param response The failed response
 * @returns The status, status text and (truncated) body of the response
 */
async function describeFailedResponse(response: Response): Promise<string> {
    let body: string;
    try {
        body = (await response.text()).trim();
    } catch {
        body = "";
    }
    const truncated = body.length > MAX_ERROR_BODY_LENGTH ? `${body.slice(0, MAX_ERROR_BODY_LENGTH)}...` : body;
    return truncated.length > 0
        ? `${response.status} ${response.statusText}: ${truncated}`
        : `${response.status} ${response.statusText}`;
}

/**
 * Interface for the server API injected into Langium services
 */
export interface ServerApi {
    /**
     * Reads a file from the backend
     * @param path The path of the file to read
     * @returns The file content and version
     */
    readFile(path: string): Promise<{ content: string; version: number }>;

    /**
     * Gets file data from the backend
     * @param path The path of the file
     * @param key The data key
     * @returns The computed file data and version
     */
    getFileData(path: string, key: string): Promise<{ data: unknown; version: number }>;

    /**
     * Lists files in a directory
     * @param path The directory path
     * @returns Array of file/directory entries
     */
    listDirectory(path: string): Promise<DirectoryEntry[]>;

    /**
     * Gets the tracked requests made during the current computation
     *
     * @returns The tracked requests
     */
    getTrackedRequests(): TrackedRequests;

    /**
     * Gets all fetched file data by key
     *
     * @param key The data key
     * @returns Map of file paths to their corresponding file data
     */
    getFileDataByKey(key: string): Map<string, FileData>;

    /**
     * Sends a request to a plugin's request handler via the backend proxy.
     * The backend forwards the request to the appropriate plugin service.
     * Contribution plugins are automatically determined server-side.
     *
     * The target plugin gets a token that only reads the project and sends further plugin requests,
     * unless `delegate` is set: then it gets this request's own token, with everything that token may
     * do. Delegate only to hand over work the token was issued for, such as starting an execution.
     *
     * @param languageId The language ID of the target plugin
     * @param key The request handler key
     * @param body The request body to forward
     * @param options Whether to hand this request's token to the target plugin
     * @returns The response data from the plugin request handler
     */
    sendPluginRequest(languageId: string, key: string, body: unknown, options?: PluginRequestOptions): Promise<unknown>;

    /**
     * Updates execution metadata in the backend.
     *
     * @param executionId The execution ID
     * @param metadata Small JSON metadata object
     */
    updateExecutionMetadata(executionId: string, metadata: Record<string, unknown>): Promise<void>;
}

/**
 * Most file data entries one batch request asks for; the backend refuses larger batches.
 */
const MAX_FILE_DATA_BATCH_SIZE = 256;

/**
 * One file data request waiting to be sent.
 */
interface PendingFileData {
    path: string;
    key: string;
    resolve(data: FileData): void;
    reject(error: unknown): void;
}

/**
 * One answer of a batch file data request.
 */
type FileDataBatchResult = { data: unknown; version: number } | { error: { code: string; message: string } };

/**
 * Implementation of the ServerApi that communicates with the backend via HTTP.
 * The JWT token is set per-request to ensure proper authorization.
 */
export class HttpServerApi implements ServerApi {
    /**
     * The backend URL
     */
    private readonly backendUrl: string;

    /**
     * The auth JWT token for requests
     */
    private jwt: string | undefined = undefined;

    /**
     * The project context for the API calls
     */
    private project: string | undefined = undefined;

    /**
     * Aborts every call when the request this API serves is abandoned
     */
    private signal: AbortSignal | undefined = undefined;

    /**
     * When the request this API serves stops being waited for, in epoch milliseconds
     */
    private deadline: number | undefined = undefined;

    /**
     * Tracked file dependencies during current computation
     */
    private trackedFileDependencies: FileDependency[] = [];

    /**
     * Tracked data dependencies during current computation
     */
    private trackedDataDependencies: DataDependency[] = [];

    /**
     * File data requests made since the last flush, sent together
     */
    private pendingFileData: PendingFileData[] = [];

    /**
     * File data requests on their way, so asking twice for the same data sends one request
     */
    private readonly fileDataInFlight = new Map<string, Promise<FileData>>();

    /**
     * Cache of fetched file data by key
     */
    private fileDataCache: Map<string, Map<string, FileData>> = new Map();

    /**
     * The project-specific backend URL
     */
    get projectBackendUrl(): string | undefined {
        return this.project ? `${this.backendUrl}/projects/${encodeURIComponent(this.project)}` : undefined;
    }

    /**
     * Creates a new HttpServerApi instance
     *
     * @param backendUrl The backend base URL
     */
    constructor(backendUrl: string) {
        this.backendUrl = backendUrl.endsWith("/") ? backendUrl.slice(0, -1) : backendUrl;
    }

    /**
     * Sets the JWT token for subsequent API calls.
     * This should be called before handling each request.
     *
     * @param jwt The JWT token to use for authorization
     */
    setContext(jwt: string, project: string): void {
        this.jwt = jwt;
        this.project = project;
    }

    /**
     * Ties every following call to the limits of the request being handled: calls are aborted with
     * it, and pass the time it has left on to the backend.
     *
     * @param signal Aborts when the request is abandoned
     * @param deadline When the caller stops waiting, in epoch milliseconds, if it said
     */
    setRequestLimits(signal: AbortSignal | undefined, deadline: number | undefined): void {
        this.signal = signal;
        this.deadline = deadline;
    }

    /**
     * Clears the JWT token and project context after request handling.
     * Further, it resets tracked requests.
     * Should be called after the request is complete to prevent token leakage and stale context.
     */
    reset(): void {
        this.jwt = undefined;
        this.project = undefined;
        this.signal = undefined;
        this.deadline = undefined;
        this.trackedFileDependencies = [];
        this.trackedDataDependencies = [];
        this.fileDataCache.clear();
    }

    private getAuthHeaders(): HeadersInit {
        if (!this.jwt) {
            throw new Error("JWT not set. Call setJwt() before making API requests.");
        }
        const headers: Record<string, string> = {
            Authorization: `Bearer ${this.jwt}`,
            "Content-Type": "application/json"
        };
        if (this.deadline != undefined) {
            headers[TIMEOUT_HEADER] = String(Math.max(1, this.deadline - Date.now()));
        }
        return headers;
    }

    async readFile(path: string): Promise<{ content: string; version: number }> {
        const encodedPath = encodeURIComponent(path);
        const response = await fetch(`${this.projectBackendUrl}/files/${encodedPath}`, {
            method: "GET",
            headers: this.getAuthHeaders(),
            signal: this.signal
        });

        if (!response.ok) {
            throw new Error(`Failed to read file ${path}: ${await describeFailedResponse(response)}`);
        }

        const result = await response.json();
        const fileData = {
            content: result.content,
            version: result.version
        };

        this.trackedFileDependencies.push({
            path,
            version: fileData.version
        });

        return fileData;
    }

    async getFileData(path: string, key: string): Promise<FileData> {
        const cached = this.fileDataCache.get(key)?.get(path);
        if (cached != undefined) {
            return cached;
        }
        const id = `${key}\u0000${path}`;
        const inFlight = this.fileDataInFlight.get(id);
        if (inFlight != undefined) {
            return inFlight;
        }

        // Requests made in the same tick, as a Promise.all over several files makes them, travel
        // to the backend as one batch.
        const request = new Promise<FileData>((resolve, reject) => {
            this.pendingFileData.push({ path, key, resolve, reject });
            if (this.pendingFileData.length === 1) {
                queueMicrotask(() => void this.flushFileData());
            }
        });
        this.fileDataInFlight.set(id, request);
        try {
            return await request;
        } finally {
            this.fileDataInFlight.delete(id);
        }
    }

    /**
     * Sends every file data request made since the last flush: alone when there is one, as batches
     * otherwise.
     */
    private async flushFileData(): Promise<void> {
        const pending = this.pendingFileData;
        this.pendingFileData = [];
        if (pending.length === 1) {
            await this.settle(pending[0], () => this.fetchFileData(pending[0].path, pending[0].key));
            return;
        }
        for (let start = 0; start < pending.length; start += MAX_FILE_DATA_BATCH_SIZE) {
            const chunk = pending.slice(start, start + MAX_FILE_DATA_BATCH_SIZE);
            let results: FileDataBatchResult[] | undefined;
            try {
                results = await this.fetchFileDataBatch(chunk);
            } catch (error) {
                chunk.forEach((entry) => entry.reject(error));
                continue;
            }
            if (results == undefined) {
                // A backend without the batch endpoint gets the requests one by one.
                await Promise.all(
                    chunk.map((entry) => this.settle(entry, () => this.fetchFileData(entry.path, entry.key)))
                );
                continue;
            }
            chunk.forEach((entry, index) => {
                const result = results![index];
                if (result == undefined || "error" in result) {
                    const reason = result == undefined ? "no answer" : `${result.error.code}: ${result.error.message}`;
                    entry.reject(new Error(`Failed to get file data ${entry.path}:${entry.key}: ${reason}`));
                } else {
                    entry.resolve(this.remember(entry.path, entry.key, result));
                }
            });
        }
    }

    private async settle(entry: PendingFileData, load: () => Promise<FileData>): Promise<void> {
        try {
            entry.resolve(await load());
        } catch (error) {
            entry.reject(error);
        }
    }

    private async fetchFileData(path: string, key: string): Promise<FileData> {
        const encodedPath = encodeURIComponent(path);
        const encodedKey = encodeURIComponent(key);
        const response = await fetch(`${this.projectBackendUrl}/file-data/${encodedKey}?path=${encodedPath}`, {
            method: "GET",
            headers: this.getAuthHeaders(),
            signal: this.signal
        });

        if (!response.ok) {
            throw new Error(`Failed to get file data ${path}:${key}: ${await describeFailedResponse(response)}`);
        }

        return this.remember(path, key, await response.json());
    }

    /**
     * Asks for several file data entries in one request.
     *
     * @returns The answers in request order, or undefined when the backend has no batch endpoint
     */
    private async fetchFileDataBatch(entries: PendingFileData[]): Promise<FileDataBatchResult[] | undefined> {
        const response = await fetch(`${this.projectBackendUrl}/file-data-batch`, {
            method: "POST",
            headers: this.getAuthHeaders(),
            signal: this.signal,
            body: JSON.stringify({ requests: entries.map(({ path, key }) => ({ path, key })) })
        });
        if (response.status === 404 || response.status === 405) {
            return undefined;
        }
        if (!response.ok) {
            throw new Error(`Failed to get file data: ${await describeFailedResponse(response)}`);
        }
        return ((await response.json()) as { results: FileDataBatchResult[] }).results;
    }

    /**
     * Caches a file data answer for the rest of the request and records it as a dependency.
     */
    private remember(path: string, key: string, result: { data: unknown; version: number }): FileData {
        const fileData: FileData = { data: result.data, version: result.version };
        if (!this.fileDataCache.has(key)) {
            this.fileDataCache.set(key, new Map());
        }
        this.fileDataCache.get(key)!.set(path, fileData);
        this.trackedDataDependencies.push({ path, key, version: result.version });
        return fileData;
    }

    async listDirectory(path: string): Promise<DirectoryEntry[]> {
        const encodedPath = encodeURIComponent(path);
        const response = await fetch(`${this.projectBackendUrl}/files/dirs/${encodedPath}`, {
            method: "GET",
            headers: this.getAuthHeaders(),
            signal: this.signal
        });

        if (!response.ok) {
            throw new Error(`Failed to list directory ${path}: ${await describeFailedResponse(response)}`);
        }

        const result = await response.json();
        return result.entries.map((entry: { name: string; isFile: boolean; isDirectory: boolean }) => ({
            name: entry.name,
            isFile: entry.isFile,
            isDirectory: entry.isDirectory
        }));
    }

    getTrackedRequests(): TrackedRequests {
        return {
            fileDependencies: [...this.trackedFileDependencies],
            dataDependencies: [...this.trackedDataDependencies]
        };
    }

    getFileDataByKey(key: string): Map<string, FileData> {
        return this.fileDataCache.get(key) ?? new Map();
    }

    async sendPluginRequest(
        languageId: string,
        key: string,
        body: unknown,
        options?: PluginRequestOptions
    ): Promise<unknown> {
        const encodedLanguageId = encodeURIComponent(languageId);
        const encodedKey = encodeURIComponent(key);
        const headers: Record<string, string> = { ...(this.getAuthHeaders() as Record<string, string>) };
        if (options?.delegate) {
            headers[DELEGATE_TOKEN_HEADER] = "true";
        }
        const response = await fetch(`${this.projectBackendUrl}/request/${encodedLanguageId}/${encodedKey}`, {
            method: "POST",
            headers,
            signal: this.signal,
            body: JSON.stringify(body)
        });

        if (!response.ok) {
            throw new Error(
                `Plugin request failed for ${languageId}/${key}: ${await describeFailedResponse(response)}`
            );
        }

        const result = await response.json();
        return result.data;
    }

    /**
     * Fetches the contribution plugins a `lang:` session has to load.
     *
     * Requests carry these in their body because the backend relays them. A session is dialed
     * directly, so they are fetched with the session token while the session opens.
     *
     * @param languageId The language the session addresses
     * @param sessionName The session being opened
     * @returns The contribution plugins registered for the language in the project
     */
    async getSessionContributionPlugins(languageId: string, sessionName: string): Promise<ServerContributionPlugin[]> {
        const path = `sessions/lang/${encodeURIComponent(languageId)}/${encodeURIComponent(sessionName)}`;
        const response = await fetch(`${this.projectBackendUrl}/${path}/contribution-plugins`, {
            method: "GET",
            headers: this.getAuthHeaders(),
            signal: this.signal
        });

        if (!response.ok) {
            throw new Error(
                `Failed to fetch contribution plugins for lang:${languageId}: ${await describeFailedResponse(response)}`
            );
        }

        return (await response.json()) as ServerContributionPlugin[];
    }

    async updateExecutionMetadata(executionId: string, metadata: Record<string, unknown>): Promise<void> {
        const encodedExecutionId = encodeURIComponent(executionId);
        const response = await fetch(`${this.backendUrl}/executions/${encodedExecutionId}/metadata`, {
            method: "PATCH",
            headers: this.getAuthHeaders(),
            signal: this.signal,
            body: JSON.stringify({ metadata })
        });

        if (!response.ok) {
            throw new Error(
                `Failed to update execution metadata ${executionId}: ${await describeFailedResponse(response)}`
            );
        }
    }
}
