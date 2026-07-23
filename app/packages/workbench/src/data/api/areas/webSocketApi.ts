import type { Execution } from "../../execution/execution";
import { showSuccess, showError, showInfo, showWarning } from "@/lib/notifications";

/**
 * Base interface for all WebSocket messages
 */
interface WebSocketMessage {
    messageType: string;
}

/**
 * Message to subscribe to a project's events
 */
interface SubscribeMessage extends WebSocketMessage {
    messageType: "event/subscribe";
    projectId: string;
}

/**
 * Message to unsubscribe from a project's events
 */
interface UnsubscribeMessage extends WebSocketMessage {
    messageType: "event/unsubscribe";
    projectId: string;
}

/**
 * Notification when an execution state changes
 */
interface ExecutionStateChangedMessage extends WebSocketMessage {
    messageType: "event/executionStateChanged";
    execution: Execution;
}

/**
 * Request to authorize file operations for a project via WebSocket.
 */
interface SubscribeFilesMessage extends WebSocketMessage {
    messageType: "file/subscribeFiles";
    requestId: string;
    projectId: string;
}

/**
 * Request to load a full project (directory structure, file data, metadata).
 */
interface LoadProjectMessage extends WebSocketMessage {
    messageType: "file/loadProject";
    requestId: string;
    projectId: string;
}

/**
 * Success response for a file system request.
 */
interface FileResponseMessage extends WebSocketMessage {
    messageType: "file/response";
    requestId: string;
    data?: unknown;
}

/**
 * Error response for a file system request.
 */
interface FileErrorMessage extends WebSocketMessage {
    messageType: "file/error";
    requestId: string;
    code: string;
    message: string;
}

/**
 * Streaming message: full directory structure of a project.
 */
export interface ProjectLoadDirectoryStructureMessage extends WebSocketMessage {
    messageType: "file/projectLoadDirectoryStructure";
    requestId: string;
    entries: Array<{ path: string; type: number }>;
}

/**
 * Streaming message: single file's content and version.
 */
export interface ProjectLoadFileDataMessage extends WebSocketMessage {
    messageType: "file/projectLoadFileData";
    requestId: string;
    path: string;
    content: string;
    version: number;
}

/**
 * Streaming message: single file's metadata.
 */
export interface ProjectLoadFileMetadataMessage extends WebSocketMessage {
    messageType: "file/projectLoadFileMetadata";
    requestId: string;
    path: string;
    metadata: object;
}

/**
 * Marker message: project load stream is complete.
 */
interface ProjectLoadCompleteMessage extends WebSocketMessage {
    messageType: "file/projectLoadComplete";
    requestId: string;
}

/**
 * Request to initialize a project session on connect.
 * The server validates access, caches file-operation permissions, subscribes
 * the connection to execution-state events, and replies with [InitReplyMessage].
 */
interface InitRequestMessage extends WebSocketMessage {
    messageType: "init/request";
    requestId: string;
    projectId: string;
}

/**
 * Reply from the server after a successful init/request.
 * Contains project metadata and the current user's effective permissions.
 */
export interface InitReplyMessage extends WebSocketMessage {
    messageType: "init/reply";
    requestId: string;
    project: { id: string; name: string };
    canWrite: boolean;
    canExecute: boolean;
}

/**
 * Connection state for the WebSocket
 */
export type ConnectionState = "disconnected" | "connecting" | "connected";

/**
 * Callback function for execution state changes
 */
export type ExecutionStateChangeCallback = (execution: Execution) => void;

/**
 * Callback for streaming project load events
 */
export interface ProjectLoadCallbacks {
    /**
     * Called once with the full directory structure
     */
    onDirectoryStructure: (entries: Array<{ path: string; type: number }>) => void;
    /**
     * Called once per file with its content and version
     */
    onFileData: (path: string, content: string, version: number) => void;
    /**
     * Called once per file with its metadata
     */
    onFileMetadata: (path: string, metadata: object) => void;
}

/**
 * Pending request awaiting a response
 */
interface PendingRequest {
    resolve: (data: unknown) => void;
    reject: (error: { code: string; message: string }) => void;
}

/**
 * Configuration options for WebSocketApi
 */
export interface WebSocketApiOptions {
    /**
     * Base URL for WebSocket connection (e.g., "/api")
     */
    baseUrl: string;
    /**
     * Delay in milliseconds before attempting reconnect (default: 3000)
     */
    reconnectDelay?: number;
    /**
     * Maximum reconnect delay in milliseconds (default: 30000)
     */
    maxReconnectDelay?: number;
    /**
     * Whether to show toast notifications for state changes (default: true)
     */
    showNotifications?: boolean;
}

/**
 * API for managing WebSocket connections for real-time notifications
 * and file system operations.
 * Handles connection establishment, automatic reconnection, project subscriptions,
 * and request/response-based file operations.
 */
export class WebSocketApi {
    private socket: WebSocket | null = null;
    private connectionState: ConnectionState = "disconnected";
    private currentProjectId: string | null = null;
    private reconnectAttempts = 0;
    private reconnectTimeoutId: ReturnType<typeof setTimeout> | null = null;
    private readonly stateChangeCallbacks: Set<ExecutionStateChangeCallback> = new Set();
    private readonly reconnectDelay: number;
    private readonly maxReconnectDelay: number;
    private readonly showNotifications: boolean;
    private readonly wsUrl: string;
    private readonly executionStates: Map<string, string> = new Map();
    private readonly pendingRequests: Map<string, PendingRequest> = new Map();
    private readonly pendingProjectLoads: Map<string, ProjectLoadCallbacks> = new Map();
    private requestIdCounter = 0;
    /**
     * Resolves once the socket is open, rejects if that connection attempt fails;
     * replaced on each connect(). Without a reject path here, any caller awaiting
     * a failed attempt via ensureConnected() would hang forever instead of seeing
     * an error, which in turn left ModelState.saveMetadata()'s await stuck mid-call
     * and metadataSaveState permanently at SAVING for the rest of the session.
     */
    private connectedPromise: Promise<void> = Promise.resolve();
    private connectedResolve: (() => void) | null = null;
    private connectedReject: ((reason: unknown) => void) | null = null;
    /**
     * Set while notifying the user of an unexpected disconnect, so the notification
     * only fires once per outage rather than on every retry in the reconnect loop.
     */
    private hasNotifiedDisconnect = false;

    /**
     * Creates a new WebSocketApi instance
     *
     * @param options Configuration options for the WebSocket connection
     */
    constructor(options: WebSocketApiOptions) {
        this.reconnectDelay = options.reconnectDelay ?? 3000;
        this.maxReconnectDelay = options.maxReconnectDelay ?? 30000;
        this.showNotifications = options.showNotifications ?? true;
        this.wsUrl = this.buildWebSocketUrl(options.baseUrl);
    }

    /**
     * Builds the WebSocket URL from the base URL
     *
     * @param baseUrl The base API URL
     * @returns The full WebSocket URL
     */
    private buildWebSocketUrl(baseUrl: string): string {
        const protocol = window.location.protocol === "https:" ? "wss:" : "ws:";
        const host = window.location.host;
        const wsPath = baseUrl.replace(/^\//, "") + "/ws";
        return `${protocol}//${host}/${wsPath}`;
    }

    /**
     * Gets the current connection state
     *
     * @returns The current connection state
     */
    getConnectionState(): ConnectionState {
        return this.connectionState;
    }

    /**
     * Registers a callback to be called when execution state changes
     *
     * @param callback The callback function to register
     */
    onExecutionStateChange(callback: ExecutionStateChangeCallback): void {
        this.stateChangeCallbacks.add(callback);
    }

    /**
     * Unregisters a previously registered callback
     *
     * @param callback The callback function to unregister
     */
    offExecutionStateChange(callback: ExecutionStateChangeCallback): void {
        this.stateChangeCallbacks.delete(callback);
    }

    /**
     * Connects to the WebSocket server and optionally subscribes to a project
     *
     * @param projectId Optional project ID to subscribe to after connecting
     */
    connect(projectId?: string): void {
        if (this.connectionState === "connecting" || this.connectionState === "connected") {
            if (projectId && projectId !== this.currentProjectId) {
                this.subscribeToProject(projectId);
            }
            return;
        }

        this.connectionState = "connecting";
        this.currentProjectId = projectId ?? null;
        this.connectedPromise = new Promise<void>((resolve, reject) => {
            this.connectedResolve = resolve;
            this.connectedReject = reject;
        });
        // A scheduled reconnect attempt can fail with nothing currently awaiting
        // ensureConnected(), which would otherwise surface as an unhandled rejection
        // in the console. This attaches a handler without affecting the rejection
        // that actual awaiters of connectedPromise still receive.
        this.connectedPromise.catch(() => {});

        this.socket = new WebSocket(this.wsUrl);
        this.setupSocketEventHandlers();
    }

    /**
     * Sets up event handlers for the WebSocket connection
     */
    private setupSocketEventHandlers(): void {
        if (!this.socket) {
            return;
        }

        this.socket.onopen = () => this.handleOpen();
        this.socket.onmessage = (event) => this.handleMessage(event);
        this.socket.onclose = (event) => this.handleClose(event);
        this.socket.onerror = (event) => this.handleError(event);
    }

    /**
     * Handles successful WebSocket connection.
     * Sends an init/request to initialize permissions and subscribe to execution
     * events in a single round-trip, replacing the separate event/subscribe flow.
     */
    private handleOpen(): void {
        this.connectionState = "connected";
        this.reconnectAttempts = 0;

        if (this.hasNotifiedDisconnect) {
            this.hasNotifiedDisconnect = false;
            if (this.showNotifications) {
                showSuccess("Connection restored", { description: "Your changes are saving again." });
            }
        }

        if (this.connectedResolve) {
            this.connectedResolve();
            this.connectedResolve = null;
            this.connectedReject = null;
        }

        if (this.currentProjectId) {
            this.sendInitRequest(this.currentProjectId);
        }
    }

    /**
     * Handles incoming WebSocket messages
     *
     * @param event The message event
     */
    private handleMessage(event: MessageEvent): void {
        const message = JSON.parse(event.data) as WebSocketMessage;
        this.routeMessage(message);
    }

    /**
     * Routes a message to the appropriate handler based on type
     *
     * @param message The parsed WebSocket message
     */
    private routeMessage(message: WebSocketMessage): void {
        switch (message.messageType) {
            case "event/executionStateChanged":
                this.handleExecutionStateChanged(message as ExecutionStateChangedMessage);
                break;
            case "file/response":
                this.handleFileResponse(message as FileResponseMessage);
                break;
            case "file/error":
                this.handleFileError(message as FileErrorMessage);
                break;
            case "file/projectLoadDirectoryStructure":
                this.handleProjectLoadDirectoryStructure(message as ProjectLoadDirectoryStructureMessage);
                break;
            case "file/projectLoadFileData":
                this.handleProjectLoadFileData(message as ProjectLoadFileDataMessage);
                break;
            case "file/projectLoadFileMetadata":
                this.handleProjectLoadFileMetadata(message as ProjectLoadFileMetadataMessage);
                break;
            case "file/projectLoadComplete":
                this.handleProjectLoadComplete(message as ProjectLoadCompleteMessage);
                break;
            case "init/reply":
                this.handleInitReply(message as InitReplyMessage);
                break;
            default:
                throw new Error(`Unknown WebSocket message type: ${message.messageType}`);
        }
    }

    /**
     * Handles execution state change notifications
     *
     * @param message The execution state changed message
     */
    private handleExecutionStateChanged(message: ExecutionStateChangedMessage): void {
        const execution = message.execution;
        const previousState = this.executionStates.get(execution.id);
        const stateChanged = previousState !== execution.state;

        this.executionStates.set(execution.id, execution.state);

        this.notifyCallbacks(execution);

        if (stateChanged) {
            this.showStateChangeNotification(execution);
        }
    }

    /**
     * Notifies all registered callbacks of an execution state change
     *
     * @param execution The updated execution
     */
    private notifyCallbacks(execution: Execution): void {
        for (const callback of this.stateChangeCallbacks) {
            callback(execution);
        }
    }

    /**
     * Shows a toast notification for an execution state change
     *
     * @param execution The updated execution
     */
    private showStateChangeNotification(execution: Execution): void {
        if (!this.showNotifications) {
            return;
        }

        const message = this.buildNotificationMessage(execution);
        if (!message) {
            return;
        }

        this.displayNotification(execution.state, message, execution.name);
    }

    /**
     * Builds the notification message based on execution state
     *
     * @param execution The execution
     * @returns The notification message or null if no notification should be shown
     */
    private buildNotificationMessage(execution: Execution): string | null {
        switch (execution.state) {
            case "completed":
                return "Execution completed successfully";
            case "failed":
                return "Execution failed";
            case "cancelled":
                return "Execution was cancelled";
            case "running":
                return "Execution started running";
            default:
                return null;
        }
    }

    /**
     * Displays a notification based on execution state
     *
     * @param state The execution state
     * @param message The notification message
     * @param executionName The name of the execution
     */
    private displayNotification(state: string, message: string, executionName: string): void {
        const description = executionName;

        switch (state) {
            case "completed":
                showSuccess(message, { description });
                break;
            case "failed":
                showError(message, { description });
                break;
            case "cancelled":
            case "running":
                showInfo(message, { description });
                break;
        }
    }

    /**
     * Handles WebSocket connection close.
     * Rejects all pending requests and schedules reconnect if appropriate.
     *
     * @param event The close event
     */
    private handleClose(event: CloseEvent): void {
        this.connectionState = "disconnected";
        this.socket = null;

        // Reject all pending requests
        for (const [, pending] of this.pendingRequests) {
            pending.reject({ code: "Unavailable", message: "WebSocket connection closed" });
        }
        this.pendingRequests.clear();
        this.pendingProjectLoads.clear();

        // Fail this connection attempt so anyone awaiting ensureConnected() doesn't hang forever.
        if (this.connectedReject) {
            this.connectedReject({ code: "Unavailable", message: "WebSocket connection closed" });
            this.connectedResolve = null;
            this.connectedReject = null;
        }

        if (!event.wasClean && this.currentProjectId) {
            if (!this.hasNotifiedDisconnect) {
                this.hasNotifiedDisconnect = true;
                if (this.showNotifications) {
                    showWarning("Connection lost", {
                        description: "Changes won't be saved until the connection is restored."
                    });
                }
            }
            this.scheduleReconnect();
        }
    }

    /**
     * Handles WebSocket errors. Intentionally a no-op: the browser always follows a
     * failed connection's error event with a close event, and handleClose already
     * does the real cleanup, reconnect scheduling, and user notification. This just
     * needs to exist so the socket's onerror handler doesn't throw.
     *
     * @param _event The error event
     */
    private handleError(_event: Event): void {
        // Intentionally empty; see handleClose.
    }

    /**
     * Schedules a reconnection attempt with exponential backoff
     */
    private scheduleReconnect(): void {
        if (this.reconnectTimeoutId) {
            clearTimeout(this.reconnectTimeoutId);
        }

        const delay = this.calculateReconnectDelay();
        this.reconnectAttempts++;

        this.reconnectTimeoutId = setTimeout(() => {
            this.reconnectTimeoutId = null;
            this.connect(this.currentProjectId ?? undefined);
        }, delay);
    }

    /**
     * Calculates the reconnect delay using exponential backoff
     *
     * @returns The delay in milliseconds
     */
    private calculateReconnectDelay(): number {
        const exponentialDelay = this.reconnectDelay * Math.pow(2, this.reconnectAttempts);
        return Math.min(exponentialDelay, this.maxReconnectDelay);
    }

    /**
     * Subscribes to a project's events
     *
     * @param projectId The project ID to subscribe to
     */
    subscribeToProject(projectId: string): void {
        if (this.currentProjectId && this.currentProjectId !== projectId) {
            this.unsubscribeFromProject(this.currentProjectId);
        }

        this.currentProjectId = projectId;

        if (this.connectionState !== "connected") {
            this.connect(projectId);
            return;
        }

        this.sendSubscribeMessage(projectId);
    }

    /**
     * Sends a subscribe message to the server
     *
     * @param projectId The project ID to subscribe to
     */
    private sendSubscribeMessage(projectId: string): void {
        const message: SubscribeMessage = {
            messageType: "event/subscribe",
            projectId
        };
        this.sendMessage(message);
    }

    /**
     * Unsubscribes from a project's events
     *
     * @param projectId The project ID to unsubscribe from
     */
    unsubscribeFromProject(projectId: string): void {
        if (this.connectionState !== "connected") {
            return;
        }

        const message: UnsubscribeMessage = {
            messageType: "event/unsubscribe",
            projectId
        };
        this.sendMessage(message);

        if (this.currentProjectId === projectId) {
            this.currentProjectId = null;
        }
    }

    /**
     * Sends a message through the WebSocket connection
     *
     * @param message The message to send
     */
    private sendMessage(message: WebSocketMessage): void {
        if (!this.socket || this.connectionState !== "connected") {
            throw new Error("WebSocket is not connected");
        }

        this.socket.send(JSON.stringify(message));
    }

    /**
     * Handles a success response for a file system request.
     *
     * @param message The file response message
     */
    private handleFileResponse(message: FileResponseMessage): void {
        const pending = this.pendingRequests.get(message.requestId);
        if (pending) {
            this.pendingRequests.delete(message.requestId);
            pending.resolve(message.data);
        }
    }

    /**
     * Handles an error response for a file system request.
     *
     * @param message The file error message
     */
    private handleFileError(message: FileErrorMessage): void {
        const pending = this.pendingRequests.get(message.requestId);
        if (pending) {
            this.pendingRequests.delete(message.requestId);
            pending.reject({ code: message.code, message: message.message });
        }
    }

    /**
     * Handles the directory structure part of a project load stream.
     *
     * @param message The directory structure message
     */
    private handleProjectLoadDirectoryStructure(message: ProjectLoadDirectoryStructureMessage): void {
        const callbacks = this.pendingProjectLoads.get(message.requestId);
        if (callbacks) {
            callbacks.onDirectoryStructure(message.entries);
        }
    }

    /**
     * Handles a file data part of a project load stream.
     *
     * @param message The file data message
     */
    private handleProjectLoadFileData(message: ProjectLoadFileDataMessage): void {
        const callbacks = this.pendingProjectLoads.get(message.requestId);
        if (callbacks) {
            callbacks.onFileData(message.path, message.content, message.version);
        }
    }

    /**
     * Handles a file metadata part of a project load stream.
     *
     * @param message The file metadata message
     */
    private handleProjectLoadFileMetadata(message: ProjectLoadFileMetadataMessage): void {
        const callbacks = this.pendingProjectLoads.get(message.requestId);
        if (callbacks) {
            callbacks.onFileMetadata(message.path, message.metadata);
        }
    }

    /**
     * Handles the completion marker of a project load stream.
     * Resolves the associated pending request.
     *
     * @param message The project load complete message
     */
    private handleProjectLoadComplete(message: ProjectLoadCompleteMessage): void {
        this.pendingProjectLoads.delete(message.requestId);
        const pending = this.pendingRequests.get(message.requestId);
        if (pending) {
            this.pendingRequests.delete(message.requestId);
            pending.resolve(undefined);
        }
    }

    /**
     * Handles an init/reply from the server.
     * Resolves the pending init request (if tracked) with the full reply so that
     * callers of [initProject] can inspect project settings and permissions.
     * Fire-and-forget inits from [handleOpen] are silently ignored.
     *
     * @param message The init reply message
     */
    private handleInitReply(message: InitReplyMessage): void {
        const pending = this.pendingRequests.get(message.requestId);
        if (pending) {
            this.pendingRequests.delete(message.requestId);
            pending.resolve(message);
        }
    }

    /**
     * Sends an init/request on connection open to initialize the project session.
     * Caches file-operation permissions and subscribes to execution events in one
     * round-trip. Fire-and-forget: replies are handled by [handleInitReply].
     *
     * @param projectId The project ID to initialize
     */
    private sendInitRequest(projectId: string): void {
        const requestId = this.nextRequestId();
        const message: InitRequestMessage = {
            messageType: "init/request",
            requestId,
            projectId
        };
        this.sendMessage(message);
    }

    /**
     * Initializes a project session and awaits the server's reply with project settings.
     * Use this when you need the project metadata or permission flags from the server.
     * For the automatic on-connect initialization, [handleOpen] uses [sendInitRequest].
     *
     * @param projectId The project ID to initialize
     * @returns The init reply containing project metadata and user permissions
     */
    async initProject(projectId: string): Promise<InitReplyMessage> {
        if (this.currentProjectId !== projectId) {
            this.currentProjectId = projectId;
        }
        const requestId = this.nextRequestId();
        return (await this.sendRequest({
            messageType: "init/request",
            requestId,
            projectId
        })) as InitReplyMessage;
    }

    // ─── Request / Response Helpers ────────────────────────────────────

    /**
     * Generates a unique request ID for correlating requests and responses.
     *
     * @returns A unique request ID string
     */
    private nextRequestId(): string {
        return `req-${++this.requestIdCounter}-${Date.now()}`;
    }

    /**
     * Sends a request message and returns a promise that resolves with the response data.
     * Waits for the WebSocket to be connected before sending.
     *
     * @param message The request message to send (must include messageType and requestId)
     * @returns A promise that resolves with the response data or rejects with an error
     */
    private async sendRequest(message: {
        requestId: string;
        messageType: string;
        [key: string]: unknown;
    }): Promise<unknown> {
        await this.ensureConnected();

        return new Promise<unknown>((resolve, reject) => {
            this.pendingRequests.set(message.requestId, { resolve, reject });
            if (!this.socket || this.connectionState !== "connected") {
                reject({ code: "Unavailable", message: "WebSocket is not connected" });
                this.pendingRequests.delete(message.requestId);
                return;
            }
            this.socket.send(JSON.stringify(message));
        });
    }

    /**
     * Ensures the WebSocket is connected, connecting if necessary.
     * Returns a promise that resolves once the connection is established.
     *
     * @returns A promise that resolves when the WebSocket is connected
     */
    async ensureConnected(): Promise<void> {
        if (this.connectionState === "connected") {
            return;
        }
        if (this.connectionState !== "connecting") {
            this.connect(this.currentProjectId ?? undefined);
        }
        return this.connectedPromise;
    }

    /**
     * Authorizes file operations for a project on this WebSocket connection.
     * The server caches the permission for approximately 5 minutes.
     *
     * @param projectId The project ID to authorize
     * @returns A promise that resolves when authorization is complete
     */
    async subscribeFiles(projectId: string): Promise<void> {
        const requestId = this.nextRequestId();
        const message: SubscribeFilesMessage = {
            messageType: "file/subscribeFiles",
            requestId,
            projectId
        };
        await this.sendRequest({ ...message });
    }

    /**
     * Loads the full project by streaming directory structure, file data, and metadata.
     * Invokes the provided callbacks as streaming messages arrive.
     *
     * @param projectId The project ID to load
     * @param callbacks Callbacks invoked for each part of the project load stream
     * @returns A promise that resolves when the project load is complete
     */
    async loadProject(projectId: string, callbacks: ProjectLoadCallbacks): Promise<void> {
        const requestId = this.nextRequestId();
        const message: LoadProjectMessage = {
            messageType: "file/loadProject",
            requestId,
            projectId
        };

        this.pendingProjectLoads.set(requestId, callbacks);
        await this.sendRequest({ ...message });
    }

    /**
     * Reads a file's content and version over WebSocket.
     *
     * @param projectId The project ID
     * @param path The file path
     * @returns The file content (as text) and version
     */
    async readFile(projectId: string, path: string): Promise<{ content: string; version: number }> {
        const requestId = this.nextRequestId();
        return (await this.sendRequest({
            messageType: "file/readFile",
            requestId,
            projectId,
            path
        })) as { content: string; version: number };
    }

    /**
     * Writes content to a file over WebSocket.
     *
     * @param projectId The project ID
     * @param path The file path
     * @param contentBase64 The file content encoded as Base64
     * @param create Whether to create the file if it doesn't exist
     * @param overwrite Whether to overwrite if the file exists
     */
    async writeFile(
        projectId: string,
        path: string,
        contentBase64: string,
        create: boolean,
        overwrite: boolean
    ): Promise<void> {
        const requestId = this.nextRequestId();
        await this.sendRequest({
            messageType: "file/writeFile",
            requestId,
            projectId,
            path,
            content: contentBase64,
            create,
            overwrite
        });
    }

    /**
     * Creates a directory over WebSocket.
     *
     * @param projectId The project ID
     * @param path The directory path to create
     */
    async mkdirWs(projectId: string, path: string): Promise<void> {
        const requestId = this.nextRequestId();
        await this.sendRequest({ messageType: "file/mkdir", requestId, projectId, path });
    }

    /**
     * Reads directory contents over WebSocket.
     *
     * @param projectId The project ID
     * @param path The directory path
     * @returns Array of directory entries (name, type)
     */
    async readdirWs(projectId: string, path: string): Promise<Array<{ name: string; type: number }>> {
        const requestId = this.nextRequestId();
        return (await this.sendRequest({
            messageType: "file/readdir",
            requestId,
            projectId,
            path
        })) as Array<{ name: string; type: number }>;
    }

    /**
     * Gets the file type (file/directory) of a path over WebSocket.
     *
     * @param projectId The project ID
     * @param path The path to stat
     * @returns The FileType constant or null
     */
    async statWs(projectId: string, path: string): Promise<number | null> {
        const requestId = this.nextRequestId();
        return (await this.sendRequest({
            messageType: "file/stat",
            requestId,
            projectId,
            path
        })) as number | null;
    }

    /**
     * Deletes a file or directory over WebSocket.
     *
     * @param projectId The project ID
     * @param path The path to delete
     * @param recursive Whether to delete recursively
     */
    async deleteFileWs(projectId: string, path: string, recursive: boolean): Promise<void> {
        const requestId = this.nextRequestId();
        await this.sendRequest({
            messageType: "file/deleteFile",
            requestId,
            projectId,
            path,
            recursive
        });
    }

    /**
     * Renames/moves a file or directory over WebSocket.
     *
     * @param projectId The project ID
     * @param from The source path
     * @param to The destination path
     * @param overwrite Whether to overwrite the destination
     */
    async renameWs(projectId: string, from: string, to: string, overwrite: boolean): Promise<void> {
        const requestId = this.nextRequestId();
        await this.sendRequest({
            messageType: "file/rename",
            requestId,
            projectId,
            from,
            to,
            overwrite
        });
    }

    /**
     * Gets the version of a file over WebSocket.
     *
     * @param projectId The project ID
     * @param path The file path
     * @returns The file version number
     */
    async getFileVersionWs(projectId: string, path: string): Promise<number> {
        const requestId = this.nextRequestId();
        return (await this.sendRequest({
            messageType: "file/getFileVersion",
            requestId,
            projectId,
            path
        })) as number;
    }

    /**
     * Reads metadata for a file over WebSocket.
     *
     * @param projectId The project ID
     * @param path The file path
     * @returns The metadata object
     */
    async readMetadataWs(projectId: string, path: string): Promise<object> {
        const requestId = this.nextRequestId();
        return (await this.sendRequest({
            messageType: "file/readMetadata",
            requestId,
            projectId,
            path
        })) as object;
    }

    /**
     * Writes metadata for a file over WebSocket.
     *
     * @param projectId The project ID
     * @param path The file path
     * @param metadata The metadata object to write
     */
    async writeMetadataWs(projectId: string, path: string, metadata: object): Promise<void> {
        const requestId = this.nextRequestId();
        await this.sendRequest({
            messageType: "file/writeMetadata",
            requestId,
            projectId,
            path,
            metadata
        });
    }

    /**
     * Disconnects the WebSocket connection.
     * Cancels pending reconnect, rejects pending requests, and closes the socket.
     */
    disconnect(): void {
        if (this.reconnectTimeoutId) {
            clearTimeout(this.reconnectTimeoutId);
            this.reconnectTimeoutId = null;
        }

        this.currentProjectId = null;

        for (const [, pending] of this.pendingRequests) {
            pending.reject({ code: "Unavailable", message: "WebSocket disconnected" });
        }
        this.pendingRequests.clear();
        this.pendingProjectLoads.clear();

        if (this.socket) {
            this.socket.close();
            this.socket = null;
        }

        this.connectionState = "disconnected";
    }
}
