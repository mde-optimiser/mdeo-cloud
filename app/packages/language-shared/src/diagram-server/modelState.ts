import { sharedImport } from "../sharedImport.js";
import type { GraphMetadata } from "./metadata.js";
import type { AstNode, URI as URIType } from "langium";
import { MetadataManager } from "./metadataManager.js";
import { LanguageServicesKey } from "./langiumServices.js";
import { type LanguageServices } from "@mdeo/language-common";

const { injectable, inject } = sharedImport("inversify");
const { DefaultModelState, SOURCE_URI_ARG } = sharedImport("@eclipse-glsp/server");
const { URI } = sharedImport("langium");

/**
 * Extended model state that manages both the graphical model and the source model.
 * The source model represents the domain-specific AST, while the graphical model
 * is the GLSP representation for rendering.
 *
 * @template T The type of the source model, must extend AstNode
 */
@injectable()
export class ModelState<T extends AstNode = AstNode> extends DefaultModelState {
    /**
     * Injected metadata manager for validating and managing graph metadata.
     */
    @inject(MetadataManager) protected metadataManager!: MetadataManager<T>;

    /**
     * Injected language services for accessing workspace and file system operations.
     */
    @inject(LanguageServicesKey) languageServices!: LanguageServices;

    /**
     * Metadata for graph elements including visual properties like positions and routing points.
     */
    protected _metadata: GraphMetadata = {
        nodes: {},
        edges: {}
    };

    /**
     * Last validated metadata for a error-free source model.
     */
    protected lastValidMetadata: GraphMetadata = {
        nodes: {},
        edges: {}
    };

    /**
     * Flag indicating whether the metadata has been validated against the source model.
     */
    private isMetadataValidated: boolean = false;

    /**
     * Flag indicating if metadata has changed during a save operation.
     */
    private metadataSaveState: MetadataSaveState = MetadataSaveState.SAVED;

    /**
     * The source model representing the domain-specific AST.
     */
    protected _sourceModel: T | undefined;

    /**
     * Gets the current graph metadata.
     */
    get metadata(): GraphMetadata {
        return this._metadata;
    }

    /**
     * Sets the graph metadata.
     */
    set metadata(value: GraphMetadata) {
        this._metadata = value;
        this.isMetadataValidated = false;
        this.saveMetadata();
    }

    /**
     * Asynchronously saves the current metadata to the file system.
     *
     * A failed write is caught and logged rather than left to reject silently: since
     * this is always called fire-and-forget from the `metadata` setter, an uncaught
     * rejection here would skip the state reset below and leave `metadataSaveState`
     * stuck at `SAVING` forever, silently no-opping every future save for the rest of
     * the session (the setter only re-attempts a write when the state is `SAVED`).
     * The in-memory `_metadata` already reflects every change regardless of whether a
     * given write succeeded, so resetting to `SAVED` on failure is enough to let the
     * next edit's write retry with the latest, still-cumulative state.
     */
    private async saveMetadata(): Promise<void> {
        if (this.metadataSaveState === MetadataSaveState.SAVED) {
            do {
                this.metadataSaveState = MetadataSaveState.SAVING;
                try {
                    await this.languageServices.shared.workspace.FileSystemProvider.writeMetadata(
                        URI.parse(this.sourceUri!),
                        this._metadata
                    );
                } catch (error) {
                    // eslint-disable-next-line no-console
                    console.error("[ModelState] Failed to save metadata, will retry on next change:", error);
                    this.metadataSaveState = MetadataSaveState.SAVED;
                    return;
                }
                if (this.metadataSaveState === MetadataSaveState.SAVING) {
                    this.metadataSaveState = MetadataSaveState.SAVED;
                }
            } while (this.metadataSaveState != MetadataSaveState.SAVED);
        } else {
            this.metadataSaveState = MetadataSaveState.SAVING_HAS_CHANGES;
        }
    }

    /**
     * Gets the current source model.
     */
    get sourceModel(): T | undefined {
        return this._sourceModel;
    }

    /**
     * Sets the source model.
     */
    set sourceModel(value: T) {
        this._sourceModel = value;
        this.isMetadataValidated = false;
    }

    /**
     * Asynchronously updates the source model.
     * This method can be overridden to perform additional operations
     * when the source model changes, such as validation or notifications.
     *
     * @param uri The URI of the source model
     * @param sourceModel The new source model to set
     * @param metadata The new graph metadata to set
     */
    async updateSourceModel(uri: URIType, sourceModel: T | undefined, metadata: GraphMetadata): Promise<void> {
        this.set(SOURCE_URI_ARG, uri.toString());
        this._sourceModel = sourceModel;
        this._metadata = metadata;
        this.isMetadataValidated = false;
    }

    /**
     * Gets validated metadata for the current source model.
     * If a metadata manager is available and a source model exists,
     * validates the current metadata against the source model.
     * Returns the validated metadata, or the current metadata if validation
     * indicates no changes are needed.
     *
     * @returns The validated graph metadata
     */
    async getValidatedMetadata(): Promise<GraphMetadata> {
        if (this.sourceModel == undefined) {
            throw new Error("Cannot validate metadata.");
        }
        if (this.isMetadataValidated) {
            return this._metadata;
        }
        const validatedMetadata = await this.metadataManager.validateMetadata(
            this._sourceModel!,
            this._metadata,
            this.lastValidMetadata
        );
        if (validatedMetadata != undefined) {
            this.metadata = validatedMetadata;
        }
        const document = this.sourceModel.$document!;
        if (document.parseResult.lexerErrors.length === 0 && document.parseResult.parserErrors.length === 0) {
            this.lastValidMetadata = this._metadata;
        }
        this.isMetadataValidated = true;
        return this._metadata;
    }
}

/**
 * Enum representing the save state of metadata.
 */
enum MetadataSaveState {
    /**
     * Currently saving, no changes during save.
     */
    SAVING,
    /**
     * All changes saved.
     */
    SAVED,
    /**
     * Changes occurred during save, another save needed.
     */
    SAVING_HAS_CHANGES
}
