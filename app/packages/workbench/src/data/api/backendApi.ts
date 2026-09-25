import { ApiResult, CommonErrorCode } from "./apiResult";
import { AuthApi } from "./areas/authApi";
import { GitApi } from "./areas/gitApi";
import { UsersApi } from "./areas/usersApi";
import { ProjectsApi } from "./areas/rojectsApi";
import { WsFilesApi } from "./areas/wsFilesApi";
import { PluginsApi } from "./areas/pluginsApi";
import { ExecutionsApi } from "./areas/executionsApi";
import { FileDataApi } from "./areas/fileDataApi";
import { WebSocketApi } from "./areas/webSocketApi";

export type { User } from "./areas/authApi";
export type { ProjectUserInfo, UserProjectMembership } from "./areas/rojectsApi";
export type { FileReadResult } from "./areas/wsFilesApi";

/**
 * Interface for the core backend API functionality shared with area APIs.
 * Provides HTTP utilities and common operations.
 */
export interface BackendApiCore {
    /**
     * The base URL for all API requests
     */
    readonly baseUrl: string;

    /**
     * Makes an API request and parses the result
     *
     * @param url The URL to fetch
     * @param init Optional fetch init options
     * @returns The parsed API result
     */
    fetchApiResult<T>(url: string, init?: RequestInit): Promise<ApiResult<T, any>>;

    /**
     * Parses an error response from the backend
     *
     * @param response The HTTP response to parse
     * @returns An ApiResult containing the error information
     */
    parseErrorResponse<E>(response: Response): Promise<ApiResult<never, E>>;
}

/**
 * Main backend API class that provides access to all API areas.
 * Acts as the entry point for all backend communication.
 *
 * Usage:
 * - `backendApi.auth.login(...)` - Authentication operations
 * - `backendApi.users.getAll()` - User management
 * - `backendApi.projects.create(...)` - Project operations
 * - `backendApi.files.readFile(...)` - File system operations
 * - `backendApi.plugins.getAll()` - Plugin management
 * - `backendApi.executions.list(...)` - Execution management
 * - `backendApi.fileData.get(...)` - File data computation
 * - `backendApi.websocket.connect(...)` - WebSocket real-time notifications
 */
export class BackendApi implements BackendApiCore {
    /**
     * Authentication operations (login, logout, register, etc.)
     */
    readonly auth: AuthApi;

    /**
     * How git access is configured on this deployment.
     */
    readonly git: GitApi;

    /**
     * User management operations (list users, manage admin status)
     */
    readonly users: UsersApi;

    /**
     * Project operations (create, update, delete, manage owners)
     */
    readonly projects: ProjectsApi;

    /**
     * File system operations (read, write, delete, rename, etc.) via WebSocket
     */
    readonly files: WsFilesApi;

    /**
     * Plugin management operations (create, delete, refresh, project association)
     */
    readonly plugins: PluginsApi;

    /**
     * Execution management operations (create, list, cancel, delete)
     */
    readonly executions: ExecutionsApi;

    /**
     * File data computation operations (get computed file data)
     */
    readonly fileData: FileDataApi;

    /**
     * WebSocket API for real-time notifications
     */
    readonly websocket: WebSocketApi;

    /**
     * Creates a new BackendApi instance with all area APIs
     *
     * @param baseUrl The base URL of the backend API (default: "/api")
     */
    constructor(readonly baseUrl: string = "/api") {
        this.auth = new AuthApi(this);
        this.git = new GitApi(this);
        this.users = new UsersApi(this);
        this.projects = new ProjectsApi(this);
        this.websocket = new WebSocketApi({ baseUrl });
        this.files = new WsFilesApi(this.websocket);
        this.plugins = new PluginsApi(this);
        this.executions = new ExecutionsApi(this, this.websocket);
        this.fileData = new FileDataApi(this);
    }

    /**
     * Makes an API request and parses the result
     *
     * @param url The URL to fetch
     * @param init Optional fetch init options
     * @returns The parsed API result
     */
    async fetchApiResult<T>(url: string, init?: RequestInit): Promise<ApiResult<T, any>> {
        try {
            const response = await fetch(url, {
                ...init,
                credentials: "include"
            });

            if (!response.ok) {
                return this.handleErrorResponse(response);
            }

            return this.handleSuccessResponse<T>(response);
        } catch (error) {
            return ApiResult.fileSystemFailure(CommonErrorCode.Unavailable, String(error));
        }
    }

    /**
     * Parses an error response from the backend
     *
     * @param response The HTTP response to parse
     * @returns An ApiResult containing the error information
     */
    async parseErrorResponse<E>(response: Response): Promise<ApiResult<never, E>> {
        try {
            const data = await response.json();
            if (typeof data === "object" && "success" in data && !data.success) {
                return data as ApiResult<never, E>;
            }
            if (typeof data === "object" && "error" in data) {
                return { success: false, error: data.error };
            }
            return { success: false, error: { code: CommonErrorCode.Unknown, message: JSON.stringify(data) } as E };
        } catch {
            return { success: false, error: { code: CommonErrorCode.Unknown, message: response.statusText } as E };
        }
    }

    private async handleErrorResponse<T>(response: Response): Promise<ApiResult<T, any>> {
        if (response.status === 401) {
            return {
                success: false,
                error: { code: CommonErrorCode.Unavailable, message: "Not authenticated" }
            };
        }
        if (response.status === 403) {
            return {
                success: false,
                error: { code: CommonErrorCode.Unavailable, message: "Access denied" }
            };
        }
        return this.parseErrorResponse(response);
    }

    private async handleSuccessResponse<T>(response: Response): Promise<ApiResult<T, any>> {
        const contentType = response.headers.get("Content-Type");
        if (!contentType || response.status === 204) {
            return ApiResult.success(undefined as T);
        }

        const data = await response.json();
        return ApiResult.success(data as T);
    }
}
