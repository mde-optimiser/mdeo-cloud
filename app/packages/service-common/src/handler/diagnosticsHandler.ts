import { URI, type LangiumCoreServices } from "langium";
import { collectDiagnostics, hasErrors, type DocumentDiagnostic } from "@mdeo/language-common";
import type { FileDataHandler } from "./types.js";
import { AST_HANDLER_KEY } from "./astHandler.js";
import type { AstData } from "../langium/jsonAstSerializer.js";
import type { ServerApi } from "../service/serverApi.js";

/**
 * Key for the diagnostics handler
 */
export const DIAGNOSTICS_HANDLER_KEY = "diagnostics";

/**
 * The diagnostics of a file, for finding out why other file data of it is missing or marked as
 * having errors.
 */
export interface DiagnosticsData {
    /**
     * The path of the file.
     */
    path: string;
    /**
     * The diagnostics of the file itself, of every severity.
     */
    diagnostics: DocumentDiagnostic[];
    /**
     * The paths of the files the file depends on, directly or transitively, that have errors
     * themselves. Request the diagnostics of those files to see their errors. The dependencies of
     * a file that has errors are not looked into, as its own diagnostics name them.
     */
    dependenciesWithErrors: string[];
}

/**
 * Handler for the diagnostics of a file.
 *
 * Computed only when asked for, so the handlers the platform relies on can keep to the cheaper
 * {@link hasErrors} check.
 *
 * @param context The file data context with path, content, and services
 * @returns Promise resolving to the file data result with the diagnostics
 */
export const diagnosticsHandler: FileDataHandler<DiagnosticsData> = async (context) => {
    const { instance, services, serverApi, fileInfo } = context;

    if (fileInfo == undefined) {
        throw new Error("Diagnostics handler does not support directory requests");
    }

    const document = await instance.buildDocument(fileInfo.uri);
    const path = document.uri.path;

    return {
        data: {
            path,
            diagnostics: collectDiagnostics(document),
            dependenciesWithErrors: await findDependenciesWithErrors(path, services, serverApi)
        },
        ...serverApi.getTrackedRequests()
    };
};

/**
 * Finds the files a just built document depends on, directly or transitively, that have errors
 * themselves. The dependencies of a file that has errors are not looked into, as its own
 * diagnostics name them.
 *
 * @param path The path of the built document
 * @param services The services the document was built with
 * @param serverApi The server API the document's dependencies were requested through
 * @returns The paths of the dependencies with errors, sorted
 */
export async function findDependenciesWithErrors(
    path: string,
    services: LangiumCoreServices,
    serverApi: ServerApi
): Promise<string[]> {
    const dependenciesWithErrors: string[] = [];
    const checked = new Set<string>([path]);

    // Dependencies of the same language are loaded and built along with the file.
    for (const dependency of serverApi.getTrackedRequests().fileDependencies) {
        checked.add(dependency.path);
        const dependencyDocument = services.shared.workspace.LangiumDocuments.getDocument(URI.file(dependency.path));
        if (dependencyDocument != undefined && hasErrors(dependencyDocument)) {
            dependenciesWithErrors.push(dependency.path);
        }
    }

    // Other dependencies are only known by their AST data, which says whether they have errors
    // and whether any of their own dependencies do, but not which. Those are followed further.
    let toCheck = [...serverApi.getFileDataByKey(AST_HANDLER_KEY).values()].map(({ data }) => data as AstData);
    for (const astData of toCheck) {
        checked.add(astData.path);
    }
    while (toCheck.length > 0) {
        const next: string[] = [];
        for (const astData of toCheck) {
            if (astData.hasErrors) {
                dependenciesWithErrors.push(astData.path);
            } else if (astData.hasTransitiveErrors) {
                for (const linkDependency of astData.linkDependencies) {
                    if (!checked.has(linkDependency)) {
                        checked.add(linkDependency);
                        next.push(linkDependency);
                    }
                }
            }
        }
        const loaded = await Promise.allSettled(
            next.map(async (dependency) => (await serverApi.getFileData(dependency, AST_HANDLER_KEY)).data as AstData)
        );
        // A dependency that cannot be loaded is reported by the file linking to it.
        toCheck = loaded.flatMap((result) => (result.status === "fulfilled" ? [result.value] : []));
    }

    return dependenciesWithErrors.sort();
}
