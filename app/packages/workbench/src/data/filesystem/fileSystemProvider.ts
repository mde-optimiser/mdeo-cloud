import type { IFileSystemProviderWithFileReadWriteCapability } from "@codingame/monaco-vscode-files-service-override";
import { FileSystemProviderCapabilities, FileType } from "@codingame/monaco-vscode-files-service-override";
import type { BackendApi } from "../api/backendApi";
import type {
    IFileDeleteOptions,
    IFileOverwriteOptions,
    IFileWriteOptions
} from "@codingame/monaco-vscode-api/vscode/vs/platform/files/common/files";
import type { IDisposable } from "@codingame/monaco-vscode-api/vscode/vs/base/common/lifecycle";
import type { Event } from "@codingame/monaco-vscode-api/vscode/vs/base/common/event";
import { Emitter } from "@codingame/monaco-vscode-api/vscode/vs/base/common/event";
import type { URI } from "@codingame/monaco-vscode-api/vscode/vs/base/common/uri";
import type { IFileChange } from "@codingame/monaco-vscode-api/vscode/vs/platform/files/common/files";
import type { IStat } from "@codingame/monaco-vscode-api/vscode/vs/platform/files/common/files";
import { FileChangeType } from "@codingame/monaco-vscode-api/vscode/vs/platform/files/common/files";
import {
    createFileSystemProviderError,
    FileSystemProviderErrorCode
} from "@codingame/monaco-vscode-api/vscode/vs/platform/files/common/files";
import type { FileSystemError, ProjectError, ExecutionError } from "../api/apiResult";
import { FileSystemErrorCode, ProjectErrorCode, CommonErrorCode, ExecutionErrorCode } from "../api/apiResult";
import { Uri } from "vscode";
import { FileCategory, parseUri } from "@mdeo/language-common";

/**
 * File system provider that forwards operations to a backend API.
 *
 * URIs support three formats:
 * 1. Regular files: schema://projectId/files/path
 * 2. Execution summaries: schema://projectId/executions/executionId.md
 * 3. Execution result files: schema://projectId/executions/files/path
 *
 * This provider extracts the projectId and path from the URI and forwards
 * the operation to the backend API or execution API based on the path structure.
 */
export class BackendFileSystemProvider implements IFileSystemProviderWithFileReadWriteCapability {
    readonly capabilities = FileSystemProviderCapabilities.FileReadWrite;

    private readonly _onDidChangeFile = new Emitter<readonly IFileChange[]>();
    readonly onDidChangeFile: Event<readonly IFileChange[]> = this._onDidChangeFile.event;

    readonly onDidChangeCapabilities = new Emitter<void>().event;

    constructor(private readonly backendApi: BackendApi) {
        // A git push changes the project's files without any of the writes
        // below going through this provider, so nothing would otherwise tell
        // the editor that what it has open is now out of date. The cache is
        // already dropped by the time this runs; firing a change event for
        // each affected path is what makes the workbench read the new content
        // back through readFile.
        //
        // This covers files the client had already seen, which is what the
        // coarse notification can support: a push that adds or removes files
        // is not announced per path, and the explorer picks those up on its
        // next readdir, which now reaches the server rather than the cache.
        this.backendApi.files.onCachedFilesInvalidated((projectId, paths) => {
            const changes = paths.map((path) => ({
                type: FileChangeType.UPDATED,
                resource: Uri.parse(`file:///${projectId}/files/${path}`)
            }));
            if (changes.length > 0) {
                this._onDidChangeFile.fire(changes);
            }
        });
    }

    async stat(resource: URI): Promise<IStat> {
        const parsed = parseUri(Uri.from(resource));
        const { projectId, category } = parsed;

        if (category === FileCategory.ExecutionSummary) {
            return {
                type: FileType.File,
                mtime: Date.now(),
                ctime: Date.now(),
                size: 0
            };
        }

        if (category === FileCategory.ExecutionResultFile) {
            return {
                type: FileType.File,
                mtime: Date.now(),
                ctime: Date.now(),
                size: 0
            };
        }

        const path = parsed.path;
        if (path === "/" || path === "") {
            return {
                type: FileType.Directory,
                mtime: Date.now(),
                ctime: Date.now(),
                size: 0
            };
        }

        const fileTypeResult = await this.backendApi.files.stat(projectId, path);
        if (!fileTypeResult.success) {
            throw apiErrorToVSCodeError(fileTypeResult.error);
        }
        if (fileTypeResult.value == undefined) {
            throw createFileSystemProviderError(
                `File not found: ${resource.toString()}`,
                FileSystemProviderErrorCode.FileNotFound
            );
        }

        if (fileTypeResult.value === FileType.File) {
            const contentResult = await this.backendApi.files.readFile(projectId, path);
            if (!contentResult.success) {
                throw apiErrorToVSCodeError(contentResult.error);
            }
            return {
                type: FileType.File,
                mtime: Date.now(),
                ctime: Date.now(),
                size: contentResult.value.content.byteLength
            };
        } else {
            return {
                type: FileType.Directory,
                mtime: Date.now(),
                ctime: Date.now(),
                size: 0
            };
        }
    }

    async readdir(resource: URI): Promise<[string, FileType][]> {
        const parsed = parseUri(Uri.from(resource));
        const { projectId, category } = parsed;

        if (category !== FileCategory.RegularFile) {
            throw createFileSystemProviderError(
                `Directory listing not supported for this path: ${resource.toString()}`,
                FileSystemProviderErrorCode.FileNotADirectory
            );
        }

        const path = parsed.path;
        const result = await this.backendApi.files.readdir(projectId, path);
        if (!result.success) {
            throw apiErrorToVSCodeError(result.error);
        }
        return result.value;
    }

    async readFile(resource: URI): Promise<Uint8Array> {
        const parsed = parseUri(Uri.from(resource));
        const { projectId, category } = parsed;

        if (category === FileCategory.ExecutionSummary) {
            const executionId = parsed.executionId;
            if (!executionId) {
                throw createFileSystemProviderError(
                    `Invalid execution summary URI: ${resource.toString()}`,
                    FileSystemProviderErrorCode.FileNotFound
                );
            }
            const result = await this.backendApi.executions.getSummary(projectId, executionId);
            if (!result.success) {
                throw apiErrorToVSCodeError(result.error);
            }
            return result.value;
        }

        if (category === FileCategory.ExecutionResultFile) {
            const result = await this.backendApi.executions.getFile(projectId, parsed.executionId, parsed.path);
            if (!result.success) {
                throw apiErrorToVSCodeError(result.error);
            }
            return result.value;
        }

        const path = parsed.path;
        const result = await this.backendApi.files.readFile(projectId, path);
        if (!result.success) {
            throw apiErrorToVSCodeError(result.error);
        }
        return result.value.content;
    }

    async writeFile(resource: URI, content: Uint8Array, opts: IFileWriteOptions): Promise<void> {
        const parsed = parseUri(Uri.from(resource));
        const { projectId, category } = parsed;

        if (category !== FileCategory.RegularFile) {
            throw createFileSystemProviderError(
                `Write not supported for this file type: ${resource.toString()}`,
                FileSystemProviderErrorCode.NoPermissions
            );
        }

        const path = parsed.path;
        let exists: boolean;
        try {
            const statResult = await this.backendApi.files.stat(projectId, path);
            exists = statResult.success;
        } catch {
            exists = false;
        }

        const result = await this.backendApi.files.writeFile(projectId, path, content, opts);
        if (!result.success) {
            throw apiErrorToVSCodeError(result.error);
        }

        if (!exists && opts.create) {
            this._onDidChangeFile.fire([{ type: FileChangeType.ADDED, resource }]);
        } else {
            this._onDidChangeFile.fire([{ type: FileChangeType.UPDATED, resource }]);
        }
    }

    async mkdir(resource: URI): Promise<void> {
        const parsed = parseUri(Uri.from(resource));
        const { projectId, category } = parsed;

        if (category !== FileCategory.RegularFile) {
            throw createFileSystemProviderError(
                `mkdir not supported for this path type: ${resource.toString()}`,
                FileSystemProviderErrorCode.NoPermissions
            );
        }

        const path = parsed.path;
        const result = await this.backendApi.files.mkdir(projectId, path);
        if (!result.success) {
            throw apiErrorToVSCodeError(result.error);
        }

        this._onDidChangeFile.fire([{ type: FileChangeType.ADDED, resource }]);
    }

    async delete(resource: URI, opts: IFileDeleteOptions): Promise<void> {
        const parsed = parseUri(Uri.from(resource));
        const { projectId, category } = parsed;

        if (category !== FileCategory.RegularFile) {
            throw createFileSystemProviderError(
                `Delete not supported for this file type: ${resource.toString()}`,
                FileSystemProviderErrorCode.NoPermissions
            );
        }

        const path = parsed.path;
        const result = await this.backendApi.files.delete(projectId, path, opts);
        if (!result.success) {
            throw apiErrorToVSCodeError(result.error);
        }

        this._onDidChangeFile.fire([{ type: FileChangeType.DELETED, resource }]);
    }

    async rename(from: URI, to: URI, opts: IFileOverwriteOptions): Promise<void> {
        const fromParsed = parseUri(Uri.from(from));
        const toParsed = parseUri(Uri.from(to));

        if (fromParsed.category !== FileCategory.RegularFile || toParsed.category !== FileCategory.RegularFile) {
            throw createFileSystemProviderError(
                `Rename not supported for this file type: ${from.toString()} -> ${to.toString()}`,
                FileSystemProviderErrorCode.NoPermissions
            );
        }

        if (fromParsed.projectId !== toParsed.projectId) {
            throw new Error("Cannot rename across projects");
        }

        const result = await this.backendApi.files.rename(fromParsed.projectId, fromParsed.path, toParsed.path, opts);
        if (!result.success) {
            throw apiErrorToVSCodeError(result.error);
        }

        this._onDidChangeFile.fire([
            { type: FileChangeType.DELETED, resource: from },
            { type: FileChangeType.ADDED, resource: to }
        ]);
    }

    watch(_resource: URI, _opts: { recursive: boolean; excludes: readonly string[] }): IDisposable {
        return {
            dispose: () => {}
        };
    }

    dispose(): void {
        this._onDidChangeFile.dispose();
    }
}

/**
 * Converts an API error to a VSCode FileSystemProvider error.
 *
 * @param error The error from the API.
 * @returns An Error object compatible with VSCode FileSystemProvider.
 */
function apiErrorToVSCodeError(error: FileSystemError | ProjectError | ExecutionError): Error {
    let errorCode: FileSystemProviderErrorCode;

    switch (error.code) {
        case FileSystemErrorCode.FileNotFound:
        case ProjectErrorCode.ProjectNotFound:
        case ExecutionErrorCode.ExecutionNotFound:
            errorCode = FileSystemProviderErrorCode.FileNotFound;
            break;
        case FileSystemErrorCode.FileExists:
            errorCode = FileSystemProviderErrorCode.FileExists;
            break;
        case FileSystemErrorCode.FileIsADirectory:
            errorCode = FileSystemProviderErrorCode.FileIsADirectory;
            break;
        case FileSystemErrorCode.FileNotADirectory:
            errorCode = FileSystemProviderErrorCode.FileNotADirectory;
            break;
        case CommonErrorCode.Unavailable:
            errorCode = FileSystemProviderErrorCode.Unavailable;
            break;
        case FileSystemErrorCode.DirectoryNotEmpty:
        case ExecutionErrorCode.ExecutionNotCompleted:
        case ExecutionErrorCode.ExecutionAlreadyTerminal:
        case CommonErrorCode.Unknown:
        default:
            errorCode = FileSystemProviderErrorCode.Unknown;
            break;
    }

    return createFileSystemProviderError(error.message, errorCode);
}
