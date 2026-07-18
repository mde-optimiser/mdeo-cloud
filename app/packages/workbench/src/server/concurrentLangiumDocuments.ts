import type { URI } from "langium";
import { DefaultLangiumDocuments, type LangiumDocument, UriTrie, URI as LangiumUri } from "langium";
import type { CancellationToken } from "vscode-languageserver";
import { getProjectIdFromPath } from "@mdeo/language-common";

/**
 * Extension to langium documents that allows for concurrent getOrCreateDocument calls for the same URI.
 * Prevents race conditions when multiple concurrent calls try to create the same document.
 *
 * In addition, it scopes the document registry to the worker's current project.
 * The in-memory file system is shared across projects and is not fully cleared on
 * a project switch, so documents belonging to a previously opened project can
 * linger. By refusing to register any document outside the active project here —
 * the single registry every consumer reads from — completions, action dialogs,
 * the symbol index and scope resolution all stay restricted to the current project.
 */
export class ConcurrentLangiumDocuments extends DefaultLangiumDocuments {
    /**
     * Tracks in-flight document creation promises to prevent duplicate creation attempts.
     */
    protected readonly promiseTrie = new UriTrie<Promise<LangiumDocument>>();

    /**
     * Collects the project IDs the worker is scoped to, derived from its workspace
     * folders (`file:///{projectId}/files`). The worker is created per project, so
     * in practice this is a single project. Returns an empty set before the
     * workspace has been initialized (no folders yet), in which case no scoping is
     * applied.
     *
     * @returns The set of active project IDs
     */
    protected getProjectScope(): Set<string> {
        const folders = this.services.workspace.WorkspaceManager.workspaceFolders ?? [];
        const projectIds = new Set<string>();
        for (const folder of folders) {
            const projectId = getProjectIdFromPath(LangiumUri.parse(folder.uri).path);
            if (projectId != undefined) {
                projectIds.add(projectId);
            }
        }
        return projectIds;
    }

    /**
     * Whether the given URI belongs to one of the worker's active projects.
     *
     * @param uri The document URI to check
     * @returns `true` if the URI is in an active project (or the project scope is
     *          not yet known), `false` otherwise
     */
    protected isInProjectScope(uri: URI): boolean {
        const scope = this.getProjectScope();
        if (scope.size === 0) {
            return true;
        }
        const projectId = getProjectIdFromPath(uri.path);
        return projectId != undefined && scope.has(projectId);
    }

    override addDocument(document: LangiumDocument): void {
        if (!this.isInProjectScope(document.uri)) {
            return;
        }
        super.addDocument(document);
    }

    override async getOrCreateDocument(uri: URI, cancellationToken?: CancellationToken): Promise<LangiumDocument> {
        const document = this.getDocument(uri);
        if (document) {
            return document;
        }

        const uriString = uri.toString();

        const existingPromise = this.promiseTrie.find(uriString);
        if (existingPromise) {
            return existingPromise;
        }

        const creationPromise = (async () => {
            try {
                const newDocument = await this.langiumDocumentFactory.fromUri(uri, cancellationToken);
                this.addDocument(newDocument);
                return newDocument;
            } finally {
                this.promiseTrie.delete(uriString);
            }
        })();

        this.promiseTrie.insert(uriString, creationPromise);
        return creationPromise;
    }
}
