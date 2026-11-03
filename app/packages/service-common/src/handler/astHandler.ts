import { URI, type LangiumDocument } from "langium";
import type { FileDataHandler, FileDataResult } from "./types.js";
import type { JsonAstSerializer } from "../langium/jsonAstSerializer.js";
import { type AstData } from "../langium/jsonAstSerializer.js";
import {
    hasErrors,
    type ExternalReferenceAdditionalServices,
    type ExternalReferenceCollector,
    type ExternalReferences
} from "@mdeo/language-common";
import type { TrackedRequests } from "../service/serverApi.js";

/**
 * Key for the AST handler
 */
export const AST_HANDLER_KEY = "ast";

/**
 * Handler for computing the AST of a metamodel file.
 * Parses the document, builds it, and serializes the AST to JSON.
 *
 * @param context The file data context with path, content, and services
 * @returns Promise resolving to the file data result with serialized AST
 */
export const astHandler: FileDataHandler<AstData, ExternalReferenceAdditionalServices> = async (context) => {
    const { instance, services, serverApi, fileInfo } = context;

    if (fileInfo == undefined) {
        throw new Error("AST handler does not support directory requests");
    }

    const document = await instance.buildDocument(fileInfo.uri);

    const trackedRequests = serverApi.getTrackedRequests();

    for (const dependency of trackedRequests.dataDependencies) {
        if (dependency.key !== AST_HANDLER_KEY) {
            throw new Error(`Unexpected data dependency key: ${dependency.key}`);
        }
    }

    const trackedRequestsLookup: TrackedRequestsLookup = {
        fileDependencies: new Map(trackedRequests.fileDependencies.map((dependency) => [dependency.path, dependency])),
        dataDependencies: new Map(trackedRequests.dataDependencies.map((dependency) => [dependency.path, dependency]))
    };

    const additionalDocuments = trackedRequests.fileDependencies
        .map((dependency) => services.shared.workspace.LangiumDocuments.getDocument(URI.file(dependency.path)))
        .filter((doc) => doc != undefined);

    const dependencyAnalyzer = new DependencyAnalyzer(
        [document, ...additionalDocuments],
        services.references.ExternalReferenceCollector,
        [...serverApi.getFileDataByKey(AST_HANDLER_KEY).values()].map(({ data }) => data as AstData)
    );
    const serializer = services.shared.serializer.JsonAstSerializer;

    return {
        ...serializeDocument(document, serializer, trackedRequestsLookup, dependencyAnalyzer),
        additionalFileData: additionalDocuments.map((doc) => ({
            ...serializeDocument(doc, serializer, trackedRequestsLookup, dependencyAnalyzer),
            path: doc.uri.path,
            key: AST_HANDLER_KEY,
            sourceVersion: trackedRequestsLookup.fileDependencies.get(doc.uri.path)?.version
        }))
    };
};

/**
 * Serializes a Langium document into AST data, including tracking dependencies.
 *
 * @param document the Langium document to serialize
 * @param serializer the JSON AST serializer
 * @param trackedRequests the tracked requests lookup
 * @param dependencyAnalyzer the dependency analyzer for error checking
 * @returns the file data result containing the serialized AST and dependencies
 */
function serializeDocument(
    document: LangiumDocument,
    serializer: JsonAstSerializer,
    trackedRequests: TrackedRequestsLookup,
    dependencyAnalyzer: DependencyAnalyzer
): Omit<FileDataResult<AstData>, "additionalFileData"> {
    const serializedAst = serializer.serialize(document);
    const references = dependencyAnalyzer.getReferences(document.uri);

    const astData: AstData = {
        ...dependencyAnalyzer.getErrors(document.uri),
        ast: serializedAst,
        path: document.uri.path,
        linkDependencies: [...references.local.map((uri) => uri.path), ...references.external.map((uri) => uri.path)],
        exportedElements: serializer.serializeExportedElements(document)
    };

    const result: Omit<FileDataResult<AstData>, "additionalFileData"> = {
        data: astData,
        fileDependencies: [],
        dataDependencies: []
    };

    for (const ref of references.local) {
        const fileDep = trackedRequests.fileDependencies.get(ref.path);
        if (fileDep != undefined) {
            result.fileDependencies.push(fileDep);
        } else {
            result.fileDependencies.push({ path: ref.path });
        }
    }

    for (const ref of references.external) {
        const dataDep = trackedRequests.dataDependencies.get(ref.path);
        if (dataDep != undefined) {
            result.dataDependencies.push(dataDep);
        } else {
            result.dataDependencies.push({ path: ref.path, key: AST_HANDLER_KEY });
        }
    }

    return result;
}

/**
 * Checks if the given document has any errors (lexer, parser, or diagnostics).
 * Also allows to access transitive error information through dependencies.
 */
class DependencyAnalyzer {
    /**
     * Map of file paths to their error and reference data
     */
    private readonly fileData: Map<
        string,
        {
            hasErrors: boolean;
            externalReferences: ExternalReferences;
            hasExternalErrors: boolean;
        }
    > = new Map();

    /**
     * Map of file paths to their AST data
     */
    private readonly astDataLookup: Map<string, AstData> = new Map();

    /**
     * Creates a new DependencyAnalyzer.
     *
     * @param documents the Langium documents to analyze
     * @param referenceCollector the external reference collector
     * @param astDatas the AST data for the documents
     */
    constructor(documents: LangiumDocument[], referenceCollector: ExternalReferenceCollector, astDatas: AstData[]) {
        for (const astData of astDatas) {
            this.astDataLookup.set(astData.path, astData);
        }
        for (const document of documents) {
            const path = document.uri.path;
            const references = referenceCollector.findExternalReferences([document]);
            this.fileData.set(path, {
                hasErrors: hasErrors(document),
                externalReferences: references,
                hasExternalErrors: references.external.some(
                    (refUri) => this.astDataLookup.get(refUri.path)?.hasTransitiveErrors !== false
                )
            });
        }
    }

    /**
     * Gets the external references for the given file path.
     *
     * @param uri The URI of the file
     * @returns The external references for the file
     * @throws Error if no references are found for the given path
     */
    getReferences(uri: URI): ExternalReferences {
        const path = uri.path;
        const refs = this.fileData.get(path)?.externalReferences;
        if (refs == undefined) {
            throw new Error(`No external references found for path: ${path}`);
        }
        return refs;
    }

    /**
     * Gets whether the given file has errors or transitive errors.
     *
     * @param uri The URI of the file
     * @returns An object indicating if the file has errors and transitive errors
     * @throws Error if no error information is found for the given path
     */
    getErrors(uri: URI): Pick<AstData, "hasErrors" | "hasTransitiveErrors"> {
        const hasLocalErrors = this.fileData.get(uri.path)?.hasErrors;
        if (hasLocalErrors == undefined) {
            throw new Error(`No error information found for path: ${uri.path}`);
        }
        if (hasLocalErrors) {
            return {
                hasErrors: true,
                hasTransitiveErrors: true
            };
        }

        let hasTransitiveErrors = false;
        const checkedFiles = new Set<string>([uri.path]);
        const toCheck = [uri.path];
        while (toCheck.length > 0) {
            const currentPath = toCheck.pop()!;
            const fileData = this.fileData.get(currentPath);
            if (fileData == undefined) {
                throw new Error(`No file data found for path: ${currentPath}`);
            }
            if (fileData.hasErrors || fileData.hasExternalErrors) {
                hasTransitiveErrors = true;
                break;
            }
            for (const ref of fileData.externalReferences.local) {
                if (!checkedFiles.has(ref.path)) {
                    checkedFiles.add(ref.path);
                    toCheck.push(ref.path);
                }
            }
        }
        return {
            hasErrors: false,
            hasTransitiveErrors
        };
    }
}

/**
 * Tracked requests lookup data structure
 */
type TrackedRequestsLookup = {
    [Property in keyof TrackedRequests]: Map<string, TrackedRequests[Property][number]>;
};
