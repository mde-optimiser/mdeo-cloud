import {
    type IFileDeleteOptions,
    type IFileOverwriteOptions,
    type IFileWriteOptions,
    FileType
} from "@codingame/monaco-vscode-api/vscode/vs/platform/files/common/files";
import { ApiResult, type FileSystemError, FileSystemErrorCode, CommonErrorCode } from "../apiResult";
import type { Project } from "../../project/project";
import type { WebSocketApi, ProjectLoadCallbacks } from "./webSocketApi";

/**
 * Callback for files of the cached project changing outside this client,
 * carrying the paths whose cached state was dropped as a result.
 */
export type CachedFilesInvalidatedCallback = (projectId: string, paths: string[]) => void;

/**
 * Response from reading a file, including content and version
 */
export interface FileReadResult {
    content: Uint8Array;
    version: number;
}

/**
 * Information cached about a file or directory
 */
interface CachedFileInfo {
    exists: boolean;
    type?: FileType;
    content?: Uint8Array;
    version?: number;
    metadata?: object;
    dirEntries?: [string, FileType][];
}

/**
 * WebSocket-based file system API for project file operations.
 * Provides the same public interface as the former HTTP-based FilesApi,
 * but routes all operations through the existing WebSocket channel.
 * Maintains an internal cache for performance optimization.
 */
export class WsFilesApi {
    private cachedProjectId: string | undefined = undefined;
    private fileTreeCache: Map<string, CachedFileInfo> = new Map();
    private readonly invalidationCallbacks: Set<CachedFilesInvalidatedCallback> = new Set();

    /**
     * Creates a new WsFilesApi instance
     *
     * @param websocket The WebSocket API providing the transport layer
     */
    constructor(private readonly websocket: WebSocketApi) {
        // Subscribed here rather than by whoever consumes the event, so the
        // cache is always dropped before anything reacts to the change: a
        // listener that reloads a file has to read through to the server, not
        // out of entries describing the state from before the push.
        this.websocket.onFilesChanged((projectId) => this.handleFilesChanged(projectId));
    }

    /**
     * Registers a callback for cached files being dropped because the project
     * changed elsewhere, for instance because someone pushed to it over git.
     *
     * @param callback Called with the project and the file paths whose cached
     *   state was dropped
     */
    onCachedFilesInvalidated(callback: CachedFilesInvalidatedCallback): void {
        this.invalidationCallbacks.add(callback);
    }

    /**
     * Unregisters a previously registered invalidation callback
     *
     * @param callback The callback function to unregister
     */
    offCachedFilesInvalidated(callback: CachedFilesInvalidatedCallback): void {
        this.invalidationCallbacks.delete(callback);
    }

    /**
     * Invalidates everything cached for a project that changed elsewhere.
     *
     * The notification is deliberately coarse - it says only that something
     * changed - so every entry is treated as suspect rather than any attempt
     * to work out which ones are still good. Listeners are told which file
     * paths were invalidated so they can reload what they have open.
     *
     * A file entry keeps its cached version rather than being dropped
     * outright: only the content is cleared, which is what forces the next
     * read to go to the server instead of serving what might now be stale,
     * while still leaving a version behind for the next write's
     * expectedVersion to be conditioned on. Clearing both would make that
     * write unconditional instead, silently overwriting whatever the change
     * that triggered this notification actually did - reintroducing the
     * exact race expectedVersion exists to catch, immediately after this
     * client was told something had changed. A directory listing has no
     * version to preserve this way and is dropped outright, since the
     * pushed change may itself have added or removed entries from it.
     *
     * @param projectId The project whose files changed
     */
    private handleFilesChanged(projectId: string): void {
        if (this.cachedProjectId !== projectId) {
            return;
        }

        const paths: string[] = [];
        for (const [path, info] of [...this.fileTreeCache.entries()]) {
            if (info.type === FileType.File) {
                paths.push(path);
                this.fileTreeCache.set(path, { ...info, content: undefined });
            } else {
                this.fileTreeCache.delete(path);
            }
        }

        for (const callback of this.invalidationCallbacks) {
            callback(projectId, paths);
        }
    }

    /**
     * Reads the contents of a file along with its version.
     *
     * @param projectId The ID of the project containing the file
     * @param path The path to the file relative to the project root
     * @returns A promise resolving to the file contents and version
     */
    async readFile(projectId: string, path: string): Promise<ApiResult<FileReadResult, FileSystemError>> {
        const normalizedPath = this.normalizePath(path);
        const cachedResult = this.checkFileCache(projectId, normalizedPath);
        if (cachedResult !== null) {
            return cachedResult;
        }

        try {
            const response = await this.websocket.readFile(projectId, path);
            const content = new TextEncoder().encode(response.content);
            const version = response.version;

            if (this.cachedProjectId === projectId) {
                const existing = this.fileTreeCache.get(normalizedPath);
                this.fileTreeCache.set(normalizedPath, {
                    exists: true,
                    type: FileType.File,
                    content,
                    version,
                    metadata: existing?.metadata
                });
            }

            return ApiResult.success({ content, version });
        } catch (error) {
            return this.handleWsError<FileReadResult>(projectId, normalizedPath, error);
        }
    }

    /**
     * Writes content to a file.
     *
     * @param projectId The ID of the project containing the file
     * @param path The path to the file relative to the project root
     * @param content The content to write as a Uint8Array
     * @param opts Write options (create, overwrite)
     * @returns A promise resolving to success or an error
     */
    async writeFile(
        projectId: string,
        path: string,
        content: Uint8Array,
        opts: IFileWriteOptions
    ): Promise<ApiResult<void, FileSystemError>> {
        const normalizedPath = this.normalizePath(path);
        // The version this client last saw for the file, when it has seen one
        // at all. Sending it is what turns a write from an unconditional
        // overwrite into one that fails if the file moved on in the meantime -
        // which is exactly what happens to a tab that has been open since
        // before someone pushed to the project over git.
        const expectedVersion =
            this.cachedProjectId === projectId ? this.fileTreeCache.get(normalizedPath)?.version : undefined;

        try {
            const contentBase64 = uint8ArrayToBase64(content);
            await this.websocket.writeFile(
                projectId,
                path,
                contentBase64,
                opts.create ?? true,
                opts.overwrite ?? false,
                expectedVersion
            );

            if (this.cachedProjectId === projectId) {
                // An accepted conditional write leaves the file at exactly one
                // version past the one it was conditioned on, so the chain of
                // known versions carries across consecutive saves rather than
                // needing a read between each. Without a version to go on the
                // entry keeps none, and the next read fills it back in.
                this.updateWriteCache(path, content, expectedVersion === undefined ? undefined : expectedVersion + 1);
            }

            return ApiResult.success(undefined);
        } catch (error) {
            return this.handleWsError<void>(projectId, normalizedPath, error);
        }
    }

    /**
     * Creates a new directory.
     *
     * @param projectId The ID of the project
     * @param path The path for the new directory
     * @returns A promise resolving to success or an error
     */
    async mkdir(projectId: string, path: string): Promise<ApiResult<void, FileSystemError>> {
        try {
            await this.websocket.mkdirWs(projectId, path);

            if (this.cachedProjectId === projectId) {
                const normalizedPath = this.normalizePath(path);
                this.fileTreeCache.set(normalizedPath, {
                    exists: true,
                    type: FileType.Directory,
                    dirEntries: []
                });
                this.invalidateParentDirCache(normalizedPath);
            }

            return ApiResult.success(undefined);
        } catch (error) {
            return this.handleWsError<void>(projectId, this.normalizePath(path), error);
        }
    }

    /**
     * Reads the contents of a directory.
     *
     * @param projectId The ID of the project
     * @param path The path to the directory
     * @returns A promise resolving to an array of [name, FileType] tuples
     */
    async readdir(projectId: string, path: string): Promise<ApiResult<[string, FileType][], FileSystemError>> {
        const normalizedPath = this.normalizePath(path);
        const cachedResult = this.checkDirCache(projectId, normalizedPath);
        if (cachedResult !== null) {
            return cachedResult;
        }

        try {
            const entries = await this.websocket.readdirWs(projectId, path);
            const result: [string, FileType][] = entries.map((e) => [e.name, e.type as FileType]);

            if (this.cachedProjectId === projectId) {
                const existing = this.fileTreeCache.get(normalizedPath);
                this.fileTreeCache.set(normalizedPath, {
                    exists: true,
                    type: FileType.Directory,
                    dirEntries: result,
                    metadata: existing?.metadata
                });
            }

            return ApiResult.success(result);
        } catch (error) {
            return this.handleWsError<[string, FileType][]>(projectId, normalizedPath, error);
        }
    }

    /**
     * Gets the type of a file or directory.
     *
     * @param projectId The ID of the project
     * @param path The path to stat
     * @returns A promise resolving to the FileType or undefined
     */
    async stat(projectId: string, path: string): Promise<ApiResult<FileType | undefined, FileSystemError>> {
        const normalizedPath = this.normalizePath(path);
        const cachedResult = this.checkStatCache(projectId, normalizedPath);
        if (cachedResult !== null) {
            return cachedResult;
        }

        try {
            const fileType = await this.websocket.statWs(projectId, path);
            const value = fileType != null ? (fileType as FileType) : undefined;

            if (this.cachedProjectId === projectId) {
                this.updateStatCache(normalizedPath, value);
            }

            return ApiResult.success(value);
        } catch (error) {
            return this.handleWsError<FileType | undefined>(projectId, normalizedPath, error);
        }
    }

    /**
     * Deletes a file or directory.
     *
     * @param projectId The ID of the project
     * @param path The path to delete
     * @param opts Delete options (recursive)
     * @returns A promise resolving to success or an error
     */
    async delete(projectId: string, path: string, opts: IFileDeleteOptions): Promise<ApiResult<void, FileSystemError>> {
        try {
            await this.websocket.deleteFileWs(projectId, path, opts.recursive ?? false);

            if (this.cachedProjectId === projectId) {
                this.updateDeleteCache(path, opts);
            }

            return ApiResult.success(undefined);
        } catch (error) {
            return this.handleWsError<void>(projectId, this.normalizePath(path), error);
        }
    }

    /**
     * Renames or moves a file or directory.
     *
     * @param projectId The ID of the project
     * @param from The current path
     * @param to The new path
     * @param opts Rename options (overwrite)
     * @returns A promise resolving to success or an error
     */
    async rename(
        projectId: string,
        from: string,
        to: string,
        opts: IFileOverwriteOptions
    ): Promise<ApiResult<void, FileSystemError>> {
        try {
            await this.websocket.renameWs(projectId, from, to, opts.overwrite ?? false);

            if (this.cachedProjectId === projectId) {
                this.updateRenameCache(from, to);
            }

            return ApiResult.success(undefined);
        } catch (error) {
            return this.handleWsError<void>(projectId, this.normalizePath(from), error);
        }
    }

    /**
     * Reads metadata for a file.
     *
     * @param projectId The ID of the project
     * @param path The path to the file
     * @returns A promise resolving to the metadata object
     */
    async readMetadata(projectId: string, path: string): Promise<ApiResult<object, FileSystemError>> {
        const normalizedPath = this.normalizePath(path);
        const cachedResult = this.checkMetadataCache(projectId, normalizedPath);
        if (cachedResult !== null) {
            return cachedResult;
        }

        try {
            const metadata = await this.websocket.readMetadataWs(projectId, path);

            if (this.cachedProjectId === projectId) {
                const existing = this.fileTreeCache.get(normalizedPath);
                this.fileTreeCache.set(normalizedPath, {
                    exists: true,
                    type: existing?.type ?? FileType.File,
                    content: existing?.content,
                    metadata,
                    dirEntries: existing?.dirEntries
                });
            }

            return ApiResult.success(metadata);
        } catch (error) {
            return this.handleWsError<object>(projectId, normalizedPath, error);
        }
    }

    /**
     * Writes metadata for a file.
     *
     * @param projectId The ID of the project
     * @param path The path to the file
     * @param metadata The metadata object to write
     * @returns A promise resolving to success or an error
     */
    async writeMetadata(projectId: string, path: string, metadata: object): Promise<ApiResult<void, FileSystemError>> {
        try {
            await this.websocket.writeMetadataWs(projectId, path, metadata);

            if (this.cachedProjectId === projectId) {
                const normalizedPath = this.normalizePath(path);
                const existing = this.fileTreeCache.get(normalizedPath);
                if (existing != undefined) {
                    this.fileTreeCache.set(normalizedPath, { ...existing, metadata });
                }
            }

            return ApiResult.success(undefined);
        } catch (error) {
            return this.handleWsError<void>(projectId, this.normalizePath(path), error);
        }
    }

    /**
     * Loads the full project over the WebSocket channel in a single request.
     * The server streams the directory structure, all file contents, and all metadata.
     * Replaces the recursive HTTP-based precache approach.
     * Before loading, authorizes the WebSocket connection for this project.
     *
     * @param project The project to load
     */
    async precache(project: Project): Promise<void> {
        if (this.cachedProjectId !== project.id) {
            this.fileTreeCache.clear();
            this.cachedProjectId = project.id;
        }

        // Ensure the WebSocket connection has file permissions for this project
        await this.websocket.subscribeFiles(project.id);

        const callbacks: ProjectLoadCallbacks = {
            onDirectoryStructure: (entries) => {
                for (const entry of entries) {
                    const normalizedPath = this.normalizePath(entry.path);
                    const type = entry.type === 1 ? FileType.File : FileType.Directory;
                    const existing = this.fileTreeCache.get(normalizedPath);
                    this.fileTreeCache.set(normalizedPath, {
                        exists: true,
                        type,
                        content: existing?.content,
                        metadata: existing?.metadata,
                        dirEntries: existing?.dirEntries
                    });
                }

                // Build dirEntries for each directory
                this.buildDirEntriesFromStructure(entries);
            },
            onFileData: (path, content, version) => {
                const normalizedPath = this.normalizePath(path);
                const existing = this.fileTreeCache.get(normalizedPath);
                this.fileTreeCache.set(normalizedPath, {
                    exists: true,
                    type: FileType.File,
                    content: new TextEncoder().encode(content),
                    version,
                    metadata: existing?.metadata
                });
            },
            onFileMetadata: (path, metadata) => {
                const normalizedPath = this.normalizePath(path);
                const existing = this.fileTreeCache.get(normalizedPath);
                if (existing) {
                    this.fileTreeCache.set(normalizedPath, { ...existing, metadata });
                }
            }
        };

        await this.websocket.loadProject(project.id, callbacks);
    }

    // ─── Cache Helpers ─────────────────────────────────────────────────

    /**
     * Builds the dirEntries data for each directory from a flat list of entries.
     *
     * @param entries The flat list of project file entries
     */
    private buildDirEntriesFromStructure(entries: Array<{ path: string; type: number }>): void {
        // Group children by parent
        const childrenByParent = new Map<string, [string, FileType][]>();
        for (const entry of entries) {
            const normalizedPath = this.normalizePath(entry.path);
            const parentPath = this.getParentPath(normalizedPath);
            if (parentPath === null) {
                continue;
            }
            if (!childrenByParent.has(parentPath)) {
                childrenByParent.set(parentPath, []);
            }
            const name = this.getBasename(normalizedPath);
            const type = entry.type === 1 ? FileType.File : FileType.Directory;
            childrenByParent.get(parentPath)!.push([name, type]);
        }

        for (const [parentPath, children] of childrenByParent) {
            const existing = this.fileTreeCache.get(parentPath);
            if (existing) {
                this.fileTreeCache.set(parentPath, { ...existing, dirEntries: children });
            }
        }
    }

    /**
     * Gets the parent path of a normalized path.
     *
     * @param path The normalized path
     * @returns The parent path, or null for root entries
     */
    private getParentPath(path: string): string | null {
        if (path === "") return null;
        const lastSlash = path.lastIndexOf("/");
        return lastSlash === -1 ? "" : path.substring(0, lastSlash);
    }

    /**
     * Gets the basename (last segment) of a path.
     *
     * @param path The path
     * @returns The basename
     */
    private getBasename(path: string): string {
        const lastSlash = path.lastIndexOf("/");
        return lastSlash === -1 ? path : path.substring(lastSlash + 1);
    }

    /**
     * Converts a WebSocket error to an ApiResult failure.
     *
     * @param projectId The project ID for cache invalidation
     * @param normalizedPath The normalized path for cache invalidation
     * @param error The error from the WebSocket
     * @returns An ApiResult failure
     */
    private handleWsError<T>(projectId: string, normalizedPath: string, error: unknown): ApiResult<T, FileSystemError> {
        const wsError = error as { code?: string; message?: string };
        const code = wsError?.code ?? CommonErrorCode.Unknown;
        const message = wsError?.message ?? String(error);

        // Cache FileNotFound results
        if (this.cachedProjectId === projectId && code === FileSystemErrorCode.FileNotFound) {
            this.fileTreeCache.set(normalizedPath, { exists: false });
        }

        // A version conflict says this client's idea of the file's content is
        // stale, but the version just rejected is still worth keeping,
        // precisely because it was rejected: dropping the whole entry would
        // make the next write's expectedVersion undefined, turning a retry
        // into the same unconditional overwrite this check exists to
        // prevent - a user who sees "modified concurrently" and immediately
        // saves again would silently clobber whatever change caused the
        // conflict in the first place. Clearing only the content forces the
        // next read to fetch the file's real current state from the server,
        // and leaving the stale version in place until then means a write
        // attempted before that read still fails safely, with a fresh
        // conflict, rather than succeeding on stale grounds.
        if (this.cachedProjectId === projectId && code === FileSystemErrorCode.VersionConflict) {
            const existing = this.fileTreeCache.get(normalizedPath);
            if (existing !== undefined) {
                this.fileTreeCache.set(normalizedPath, { ...existing, content: undefined });
            }
        }

        return { success: false, error: { code: code as any, message } };
    }

    private checkFileCache(
        projectId: string,
        normalizedPath: string
    ): ApiResult<FileReadResult, FileSystemError> | null {
        if (this.cachedProjectId !== projectId) {
            return null;
        }

        const cached = this.fileTreeCache.get(normalizedPath);
        if (cached === undefined) {
            return null;
        }

        if (!cached.exists) {
            return ApiResult.fileSystemFailure(FileSystemErrorCode.FileNotFound, "File not found");
        }
        if (cached.type !== FileType.File) {
            return ApiResult.fileSystemFailure(
                FileSystemErrorCode.FileIsADirectory,
                "Cannot read file: path is a directory"
            );
        }
        if (cached.content !== undefined && cached.version !== undefined) {
            return ApiResult.success({ content: cached.content, version: cached.version });
        }
        return null;
    }

    private updateWriteCache(path: string, content: Uint8Array, version?: number): void {
        const normalizedPath = this.normalizePath(path);
        const existing = this.fileTreeCache.get(normalizedPath);
        this.fileTreeCache.set(normalizedPath, {
            exists: true,
            type: FileType.File,
            content: content,
            version,
            metadata: existing?.metadata
        });
        this.invalidateParentDirCache(normalizedPath);
    }

    private checkDirCache(
        projectId: string,
        normalizedPath: string
    ): ApiResult<[string, FileType][], FileSystemError> | null {
        if (this.cachedProjectId !== projectId) {
            return null;
        }

        const cached = this.fileTreeCache.get(normalizedPath);
        if (cached === undefined) {
            return null;
        }

        if (!cached.exists) {
            return ApiResult.fileSystemFailure(FileSystemErrorCode.FileNotFound, "Directory not found");
        }
        if (cached.type !== FileType.Directory) {
            return ApiResult.fileSystemFailure(
                FileSystemErrorCode.FileNotADirectory,
                "Cannot read directory: path is a file"
            );
        }
        if (cached.dirEntries !== undefined) {
            return ApiResult.success(cached.dirEntries);
        }
        return null;
    }

    private checkStatCache(
        projectId: string,
        normalizedPath: string
    ): ApiResult<FileType | undefined, FileSystemError> | null {
        if (this.cachedProjectId !== projectId) {
            return null;
        }

        const cached = this.fileTreeCache.get(normalizedPath);
        if (cached === undefined) {
            return null;
        }

        if (!cached.exists) {
            return ApiResult.fileSystemFailure(FileSystemErrorCode.FileNotFound, "File or directory not found");
        }
        if (cached.type !== undefined) {
            return ApiResult.success(cached.type);
        }
        return null;
    }

    private updateStatCache(normalizedPath: string, value: FileType | undefined): void {
        if (value != undefined) {
            const existing = this.fileTreeCache.get(normalizedPath);
            this.fileTreeCache.set(normalizedPath, {
                exists: true,
                type: value,
                content: existing?.content,
                metadata: existing?.metadata,
                dirEntries: existing?.dirEntries
            });
        } else {
            this.fileTreeCache.set(normalizedPath, { exists: false });
        }
    }

    private updateDeleteCache(path: string, opts: IFileDeleteOptions): void {
        const normalizedPath = this.normalizePath(path);
        this.fileTreeCache.set(normalizedPath, { exists: false });

        if (opts.recursive) {
            const pathPrefix = normalizedPath ? normalizedPath + "/" : "";
            for (const [cachedPath] of this.fileTreeCache) {
                if (cachedPath.startsWith(pathPrefix)) {
                    this.fileTreeCache.set(cachedPath, { exists: false });
                }
            }
        }

        this.invalidateParentDirCache(normalizedPath);
    }

    private updateRenameCache(from: string, to: string): void {
        const normalizedFrom = from.startsWith("/") ? from.slice(1) : from;
        const normalizedTo = to.startsWith("/") ? to.slice(1) : to;

        const fromEntry = this.fileTreeCache.get(normalizedFrom);

        if (fromEntry && fromEntry.exists) {
            this.fileTreeCache.set(normalizedTo, fromEntry);

            if (fromEntry.type === FileType.Directory) {
                this.moveDirectoryInCache(normalizedFrom, normalizedTo);
            }
        }

        this.fileTreeCache.set(normalizedFrom, { exists: false });

        this.invalidateParentDirCache(normalizedFrom);
        this.invalidateParentDirCache(normalizedTo);
    }

    private moveDirectoryInCache(normalizedFrom: string, normalizedTo: string): void {
        const fromPrefix = normalizedFrom ? normalizedFrom + "/" : "";
        const toPrefix = normalizedTo ? normalizedTo + "/" : "";

        for (const [cachedPath, entry] of this.fileTreeCache) {
            if (cachedPath.startsWith(fromPrefix)) {
                const relativePath = cachedPath.slice(fromPrefix.length);
                const newPath = toPrefix + relativePath;
                this.fileTreeCache.set(newPath, entry);
                this.fileTreeCache.set(cachedPath, { exists: false });
            }
        }
    }

    private checkMetadataCache(projectId: string, normalizedPath: string): ApiResult<object, FileSystemError> | null {
        if (this.cachedProjectId !== projectId) {
            return null;
        }

        const cached = this.fileTreeCache.get(normalizedPath);
        if (cached === undefined) {
            return null;
        }

        if (!cached.exists) {
            return ApiResult.fileSystemFailure(FileSystemErrorCode.FileNotFound, "File not found");
        }
        if (cached.metadata !== undefined) {
            return ApiResult.success(cached.metadata);
        }
        return null;
    }

    private invalidateParentDirCache(path: string): void {
        const lastSlashIndex = path.lastIndexOf("/");
        if (lastSlashIndex === -1) {
            const rootCache = this.fileTreeCache.get("");
            if (rootCache && rootCache.exists && rootCache.type === FileType.Directory) {
                this.fileTreeCache.set("", {
                    exists: true,
                    type: FileType.Directory,
                    dirEntries: undefined,
                    metadata: rootCache.metadata
                });
            }
        } else {
            const parentPath = path.slice(0, lastSlashIndex);
            const parentCache = this.fileTreeCache.get(parentPath);
            if (parentCache && parentCache.exists && parentCache.type === FileType.Directory) {
                this.fileTreeCache.set(parentPath, {
                    exists: true,
                    type: FileType.Directory,
                    dirEntries: undefined,
                    metadata: parentCache.metadata
                });
            }
        }
    }

    private normalizePath(path: string): string {
        return path.startsWith("/") ? path.slice(1) : path;
    }
}

/**
 * Converts a Uint8Array to a Base64-encoded string.
 *
 * @param data The byte array to encode
 * @returns The Base64-encoded string
 */
function uint8ArrayToBase64(data: Uint8Array): string {
    let binary = "";
    for (let i = 0; i < data.length; i++) {
        binary += String.fromCharCode(data[i]!);
    }
    return btoa(binary);
}
