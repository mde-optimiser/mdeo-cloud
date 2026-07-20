import { zipSync, unzipSync, type Zippable } from "fflate";
import { VSBuffer } from "@codingame/monaco-vscode-api/vscode/vs/base/common/buffer";
import { Uri } from "vscode";
import { FileType } from "@codingame/monaco-vscode-files-service-override";
import type { MonacoApi } from "./monacoPlugin";
import type { FileSystemNode, Folder } from "@/data/filesystem/file";

/**
 * A single file extracted from an imported zip archive.
 */
export interface ImportedFile {
    /**
     * Path of the file relative to the archive root
     */
    path: string;
    /**
     * Raw file content
     */
    content: Uint8Array;
}

/**
 * Triggers a browser download for the given binary data.
 *
 * @param data The binary data to download
 * @param filename The name of the downloaded file
 * @param mimeType The MIME type of the download (defaults to a generic binary type)
 */
export function downloadBlob(data: Uint8Array | string, filename: string, mimeType = "application/octet-stream"): void {
    const blob = new Blob([data as BlobPart], { type: mimeType });
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = filename;
    document.body.appendChild(anchor);
    anchor.click();
    document.body.removeChild(anchor);
    URL.revokeObjectURL(url);
}

/**
 * Reads the raw bytes of a file from the workbench file service.
 *
 * @param monacoApi Monaco API instance providing access to the file service
 * @param uri Uri of the file to read
 * @returns The file content as a byte array
 */
async function readFileBytes(monacoApi: MonacoApi, uri: Uri): Promise<Uint8Array> {
    const result = await monacoApi.fileService.readFile(uri);
    return result.value.buffer;
}

/**
 * Recursively collects all files below a node into a flat path-to-content map.
 *
 * @param monacoApi Monaco API instance providing access to the file service
 * @param node The file or folder node to collect from
 * @param relativePath Path of the node relative to the archive root
 * @param collected Accumulator mapping relative paths to file content
 */
async function collectFiles(
    monacoApi: MonacoApi,
    node: FileSystemNode,
    relativePath: string,
    collected: Zippable
): Promise<void> {
    if (node.type === FileType.File) {
        collected[relativePath] = await readFileBytes(monacoApi, node.uri);
        return;
    }

    for (const child of node.children) {
        const childPath = relativePath ? `${relativePath}/${child.name}` : child.name;
        await collectFiles(monacoApi, child, childPath, collected);
    }
}

/**
 * Reads every file below a folder, packs them into a zip archive, and triggers a download.
 *
 * @param monacoApi Monaco API instance providing access to the file service
 * @param folder The folder whose contents should be zipped
 * @param archiveName The base name of the resulting archive (without extension)
 * @param extraFiles Additional files to include, keyed by their path within the archive
 */
export async function downloadFolderAsZip(
    monacoApi: MonacoApi,
    folder: Folder,
    archiveName: string,
    extraFiles: Record<string, Uint8Array> = {}
): Promise<void> {
    const files: Zippable = {};
    for (const child of folder.children) {
        await collectFiles(monacoApi, child, child.name, files);
    }
    for (const [path, content] of Object.entries(extraFiles)) {
        files[path] = content;
    }

    const zipped = zipSync(files);
    const safeName = archiveName.trim() || "download";
    downloadBlob(zipped, `${safeName}.zip`);
}

/**
 * Extracts the files contained in a zip archive.
 *
 * @param data The raw zip archive bytes
 * @returns The extracted files, excluding directory entries
 */
export function extractZip(data: Uint8Array): ImportedFile[] {
    const entries = unzipSync(data);
    const files: ImportedFile[] = [];
    for (const [path, content] of Object.entries(entries)) {
        if (path.endsWith("/")) {
            continue;
        }
        files.push({ path, content });
    }
    return files;
}

/**
 * Writes previously extracted files into a project's file tree.
 *
 * @param monacoApi Monaco API instance providing access to the file service
 * @param projectId The id of the target project
 * @param files The files to write into the project
 */
export async function importFilesIntoProject(
    monacoApi: MonacoApi,
    projectId: string,
    files: ImportedFile[]
): Promise<void> {
    for (const file of files) {
        const normalizedPath = file.path.replace(/^\/+/, "");
        if (!normalizedPath) {
            continue;
        }
        const uri = Uri.file(`/${projectId}/files/${normalizedPath}`);
        await monacoApi.fileService.createFile(uri, VSBuffer.wrap(file.content), { overwrite: true });
    }
}
