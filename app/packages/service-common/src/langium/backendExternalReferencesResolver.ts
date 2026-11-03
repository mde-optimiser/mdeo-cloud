import type { ExternalReferenceResolver, ExternalReferences } from "@mdeo/language-common";
import { URI, type LangiumDocuments, type LangiumSharedCoreServices } from "langium";
import type { ServiceAdditionalSharedServices } from "./types.js";
import type { ServerApi } from "../service/serverApi.js";
import type { CancellationTokenSource } from "vscode-jsonrpc";
import type { AstData, JsonAstSerializer } from "./jsonAstSerializer.js";
import { AST_HANDLER_KEY } from "../handler/astHandler.js";

/**
 * External reference resolver that utilizes backend services to resolve references
 */
export class BackendExternalReferencesResolver implements ExternalReferenceResolver {
    /**
     * The server API for backend communication
     */
    protected readonly serverApi: ServerApi;

    /**
     * The Langium documents collection
     */
    protected readonly langiumDocuments: LangiumDocuments;

    /**
     * The cancellation token source for managing request cancellation
     * Does a cancel when loading external documents
     */
    private cancelationTokenSource: CancellationTokenSource | undefined;

    /**
     * The AST serializer for deserializing AST nodes
     */
    protected readonly astSerializer: JsonAstSerializer;

    /**
     * Creates a new backend external references resolver
     *
     * @param services The combined Langium core and service additional services
     */
    constructor(services: LangiumSharedCoreServices & ServiceAdditionalSharedServices) {
        this.serverApi = services.ServerApi;
        this.langiumDocuments = services.workspace.LangiumDocuments;
        this.astSerializer = services.serializer.JsonAstSerializer;
    }

    async loadExternalDocuments(externalReferences: ExternalReferences): Promise<void> {
        await Promise.all(
            externalReferences.local.map(async (uri) => {
                try {
                    const source = await this.serverApi.readFile(uri.path);
                    this.langiumDocuments.createDocument(uri, source.content);
                    // a new document: the build has to start over to include it
                    this.cancelationTokenSource?.cancel();
                } catch {
                    // ignore, file does not exist; cancelling the build here would only start
                    // it over, try the same file again, and never finish
                }
            })
        );

        let pathsToLoad = this.filterPathsToLoad(externalReferences.external.map((ref) => ref.path));
        const loadedPaths = new Set<string>();
        const astDatas: AstData[] = [];
        while (pathsToLoad.size > 0) {
            const newAstDatas = await this.loadExternalAsts(pathsToLoad);
            astDatas.push(...newAstDatas);
            for (const path of pathsToLoad) {
                loadedPaths.add(path);
            }
            pathsToLoad = this.filterPathsToLoad(
                newAstDatas.flatMap((data) => data.linkDependencies).filter((path) => !loadedPaths.has(path))
            );
        }
        this.astSerializer.deserializeDocuments(astDatas);
    }

    /**
     * Loads external ASTs from the backend for the given paths
     *
     * @param paths The set of paths to load
     * @returns The loaded AST datas
     */
    private async loadExternalAsts(paths: Set<string>): Promise<AstData[]> {
        const result: AstData[] = [];
        await Promise.all(
            [...paths].map(async (path) => {
                try {
                    const data = (await this.serverApi.getFileData(path, AST_HANDLER_KEY)).data as AstData;
                    result.push(data);
                } catch {
                    // ignore, file does not exist or cannot be loaded
                }
            })
        );
        return result;
    }

    /**
     * Filters the given paths to only those that need to be loaded
     *
     * @param paths The paths to filter
     * @returns The set of paths that need to be loaded
     */
    private filterPathsToLoad(paths: string[]): Set<string> {
        const urisToLoad = new Set<string>();
        for (const path of paths) {
            const documentUri = URI.file(path);
            if (!this.langiumDocuments.hasDocument(documentUri)) {
                urisToLoad.add(path);
            }
        }
        return urisToLoad;
    }

    /**
     * Sets the cancellation token source for managing request cancellation
     *
     * @param source The cancellation token source
     */
    setCancellationTokenSource(source: CancellationTokenSource | undefined): void {
        this.cancelationTokenSource = source;
    }
}
