import type { Command } from "@eclipse-glsp/server";
import type { CreateNodeOperation } from "@eclipse-glsp/protocol";
import type { WorkspaceEdit } from "vscode-languageserver-types";
import type { InsertSpecification } from "../modelIdInsert.js";
import { sharedImport } from "../../sharedImport.js";
import { BaseOperationHandler } from "./baseOperationHandler.js";
import { OperationHandlerCommand } from "./operationHandlerCommand.js";
import { ExistingNamesProvider } from "../existingNamesProvider.js";
import { computeInsertionMetadata, type InsertedElementMetadata } from "./insertionMetadataHelper.js";
import type { ModelIdProvider } from "../modelIdProvider.js";
import { ModelIdProvider as ModelIdProviderKey } from "../modelIdProvider.js";

const { injectable, inject } = sharedImport("inversify");

/**
 * Result of a create node operation.
 */
export interface CreateNodeResult {
    /**
     * The workspace edit to apply to update the source model.
     */
    workspaceEdit: WorkspaceEdit;
    /**
     * Insert specifications describing what was added to the model.
     * Used together with {@link insertedElements} for automatic id computation
     * via the model id provider.
     */
    insertSpecifications: InsertSpecification[];
    /**
     * Metadata for each inserted AstNode, keyed by the same AstNode references
     * used in the {@link insertSpecifications}. The base handler resolves id
     * computation and builds metadata edits automatically.
     */
    insertedElements: InsertedElementMetadata[];
}

/**
 * Base handler for applying create node operations from the client.
 * Subclasses must implement the createNode method to define
 * language-specific node creation behavior.
 *
 * <p>Ids are computed automatically via the injected {@link ModelIdProvider}
 * using the {@link CreateNodeResult.insertSpecifications} and
 * {@link CreateNodeResult.insertedElements} provided by subclasses.
 */
@injectable()
export abstract class BaseCreateNodeOperationHandler extends BaseOperationHandler {
    @inject(ExistingNamesProvider)
    protected existingNamesProvider!: ExistingNamesProvider;

    @inject(ModelIdProviderKey)
    protected idProvider!: ModelIdProvider;

    override async createCommand(operation: CreateNodeOperation): Promise<Command | undefined> {
        const result = await this.createNode(operation);

        if (result == undefined) {
            return undefined;
        }

        const metadata = computeInsertionMetadata(
            this.modelState.sourceModel!,
            this.idProvider,
            result.insertSpecifications,
            result.insertedElements,
            this.modelState.metadata,
            (id) => this.mapInsertedId(id)
        );
        return new OperationHandlerCommand(this.modelState, result.workspaceEdit, metadata);
    }

    /**
     * Maps the id of a newly inserted element to the id its metadata is stored under.
     * Override when the diagram shows elements under other ids than the model id provider
     * assigns, e.g. when one model is shown in several diagrams.
     *
     * @param id The id assigned by the model id provider
     * @returns The id the metadata of the element is stored under
     */
    protected mapInsertedId(id: string): string {
        return id;
    }

    /**
     * Creates the new node and returns the result with node ID, type, and workspace edit.
     * Subclasses must implement this method to define language-specific node creation logic.
     *
     * @param operation The create node operation
     * @returns The create node result containing node ID, type, and workspace edit, or undefined if not applicable
     */
    abstract createNode(operation: CreateNodeOperation): Promise<CreateNodeResult | undefined>;

    /**
     * Finds a unique name by checking names already present in the source model
     * via the injected {@link ExistingNamesProvider}.
     *
     * @param name The base name to check for uniqueness
     * @returns The unique name, potentially with a numerical suffix appended
     */
    protected async findUniqueName(name: string): Promise<string> {
        const names = await this.existingNamesProvider.getExistingNames();
        if (!names.has(name)) {
            return name;
        }
        let suffix = 1;
        while (names.has(`${name}${suffix}`)) {
            suffix++;
        }
        return name + suffix.toString();
    }
}
