/**
 * File types based on URI structure
 */
export enum FileCategory {
    /**
     * Regular file within the project
     */
    RegularFile,
    /**
     * Execution summary file
     */
    ExecutionSummary,
    /**
     * Execution result file
     */
    ExecutionResultFile
}

/**
 * Base interface for parsed URI results
 */
export interface BaseParsedUri {
    /**
     * The project ID extracted from the URI
     */
    projectId: string;

    /**
     * The category of the file based on the URI structure
     */
    category: FileCategory;
    /**
     * The execution ID (only present for execution-related files)
     */
    executionId?: string;
}

/**
 * Result of parsing a URI for a regular file.
 */
export interface RegularFileParsedUri extends BaseParsedUri {
    category: FileCategory.RegularFile;
    /**
     * The file path within the project
     */
    path: string;
}

/**
 * Result of parsing a URI for an execution result file.
 */
export interface ExecutionSummaryParsedUri extends BaseParsedUri {
    category: FileCategory.ExecutionSummary;
    /**
     * The execution ID (only present for execution-related files)
     */
    executionId: string;
}

/**
 * Result of parsing a URI for an execution result file.
 */
export interface ExecutionResultFileParsedUri extends BaseParsedUri {
    category: FileCategory.ExecutionResultFile;
    /**
     * The execution ID (only present for execution-related files)
     */
    executionId: string;
    /**
     * The file path within the execution results
     */
    path: string;
}

/**
 * Result of parsing a URI.
 * Contains the extracted project ID, file path, category, and optional execution ID.
 */
export type ParsedUri = RegularFileParsedUri | ExecutionSummaryParsedUri | ExecutionResultFileParsedUri;

/**
 * Extracts the project ID from a URI path.
 *
 * File URIs encode their owning project as the first path segment
 * (`/{projectId}/files/...` or `/{projectId}/executions/...`).
 *
 * @param path The URI path (e.g. `uri.path`)
 * @returns The project ID, or undefined if the path has no leading segment
 */
export function getProjectIdFromPath(path: string): string | undefined {
    const projectId = path.substring(1).split("/")[0];
    return projectId ? projectId : undefined;
}

/**
 * Parse a URI into projectId, path, and file category.
 * Supports three formats:
 * - schema://projectId/files/path (regular files)
 * - schema://projectId/executions/executionId/summary.md (execution summaries)
 * - schema://projectId/executions/executionId/files/path (execution result files)
 *
 * @param uri The URI to parse
 * @returns Parsed URI information
 */
export function parseUri(uri: { path: string }): ParsedUri {
    const fullPath = uri.path;
    const parts = fullPath.substring(1).split("/");
    const projectId = parts[0];

    if (projectId == undefined) {
        throw new Error("Invalid URI: missing project ID");
    }

    if (parts[1] === "executions") {
        const executionId = parts[2];
        if (executionId == undefined) {
            throw new Error("Invalid URI: missing execution ID");
        }
        if (parts[3] === "files") {
            const path = "/" + parts.slice(4).join("/");
            return { projectId, path, category: FileCategory.ExecutionResultFile, executionId };
        } else {
            return {
                projectId,
                category: FileCategory.ExecutionSummary,
                executionId
            };
        }
    }

    if (parts[1] === "files") {
        const path = "/" + parts.slice(2).join("/");
        return { projectId, path, category: FileCategory.RegularFile };
    }

    const path = "/" + parts.slice(1).join("/");
    return { projectId, path, category: FileCategory.RegularFile };
}
