import type { ServerContributionPlugin } from "@mdeo/plugin";
import type { DirectoryEntry } from "./types.js";
import type { FileDependency, DataDependency, FileDataResult } from "../handler/types.js";

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
     * @param languageId The language ID of the target plugin
     * @param key The request handler key
     * @param body The request body to forward
     * @returns The response data from the plugin request handler
     */
    sendPluginRequest(languageId: string, key: string, body: unknown): Promise<unknown>;

    /**
     * Updates execution metadata in the backend.
     *
     * @param executionId The execution ID
     * @param metadata Small JSON metadata object
     */
    updateExecutionMetadata(executionId: string, metadata: Record<string, unknown>): Promise<void>;
}

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
     * Tracked file dependencies during current computation
     */
    private trackedFileDependencies: FileDependency[] = [];

    /**
     * Tracked data dependencies during current computation
     */
    private trackedDataDependencies: DataDependency[] = [];

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
     * Clears the JWT token and project context after request handling.
     * Further, it resets tracked requests.
     * Should be called after the request is complete to prevent token leakage and stale context.
     */
    reset(): void {
        this.jwt = undefined;
        this.project = undefined;
        this.trackedFileDependencies = [];
        this.trackedDataDependencies = [];
        this.fileDataCache.clear();
    }

    private getAuthHeaders(): HeadersInit {
        if (!this.jwt) {
            throw new Error("JWT not set. Call setJwt() before making API requests.");
        }
        return {
            Authorization: `Bearer ${this.jwt}`,
            "Content-Type": "application/json"
        };
    }

    async readFile(path: string): Promise<{ content: string; version: number }> {
        const encodedPath = encodeURIComponent(path);
        const response = await fetch(`${this.projectBackendUrl}/files/${encodedPath}`, {
            method: "GET",
            headers: this.getAuthHeaders()
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
        if (!this.fileDataCache.has(key)) {
            this.fileDataCache.set(key, new Map());
        }
        const keyCache = this.fileDataCache.get(key)!;
        if (keyCache.has(path)) {
            return keyCache.get(path)!;
        }
        const encodedPath = encodeURIComponent(path);
        const encodedKey = encodeURIComponent(key);
        const response = await fetch(`${this.projectBackendUrl}/file-data/${encodedKey}?path=${encodedPath}`, {
            method: "GET",
            headers: this.getAuthHeaders()
        });

        if (!response.ok) {
            throw new Error(`Failed to get file data ${path}:${key}: ${await describeFailedResponse(response)}`);
        }

        const result = await response.json();
        const fileData: FileData = {
            data: result.data,
            version: result.version
        };
        keyCache.set(path, fileData);

        this.trackedDataDependencies.push({
            path,
            key,
            version: result.version
        });

        return fileData;
    }

    async listDirectory(path: string): Promise<DirectoryEntry[]> {
        const encodedPath = encodeURIComponent(path);
        const response = await fetch(`${this.projectBackendUrl}/files/dirs/${encodedPath}`, {
            method: "GET",
            headers: this.getAuthHeaders()
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

    async sendPluginRequest(languageId: string, key: string, body: any): Promise<unknown> {
        const encodedLanguageId = encodeURIComponent(languageId);
        const encodedKey = encodeURIComponent(key);
        const response = await fetch(`${this.projectBackendUrl}/request/${encodedLanguageId}/${encodedKey}`, {
            method: "POST",
            headers: this.getAuthHeaders(),
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
            headers: this.getAuthHeaders()
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
            body: JSON.stringify({ metadata })
        });

        if (!response.ok) {
            throw new Error(
                `Failed to update execution metadata ${executionId}: ${await describeFailedResponse(response)}`
            );
        }
    }
}
