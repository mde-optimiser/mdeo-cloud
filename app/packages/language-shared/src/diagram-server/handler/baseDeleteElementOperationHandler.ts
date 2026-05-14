import type { Command, DiagramConfiguration, EdgeTypeHint, GModelElement, ShapeTypeHint } from "@eclipse-glsp/server";
import { sharedImport } from "../../sharedImport.js";
import { BaseOperationHandler } from "./baseOperationHandler.js";
import { OperationHandlerCommand } from "./operationHandlerCommand.js";
import type { WorkspaceEdit } from "vscode-languageserver-types";
import type { EdgeMetadata, NodeMetadata } from "../metadata.js";
import type { ContextItemProvider } from "../../context-actions/contextItemProvider.js";
import type { ContextActionRequestContext } from "../../context-actions/contextActionRequestContext.js";
import type { ContextItem } from "@mdeo/protocol-common";

const { injectable, inject } = sharedImport("inversify");
const { DeleteElementOperation } = sharedImport("@eclipse-glsp/protocol");
const { DiagramConfiguration: DiagramConfigurationKey } = sharedImport("@eclipse-glsp/server");

/**
 * Result of a delete operation containing the workspace edit and affected elements.
 */
export interface DeleteOperationResult {
    /**
     * The workspace edit to apply for the deletion.
     */
    workspaceEdit: WorkspaceEdit;

    /**
     * Array of deleted GModel elements (nodes and edges).
     */
    deletedElements: GModelElement[];
}

/**
 * Base operation handler for delete operations that automatically handles metadata cleanup.
 * Recursively collects all child element IDs and removes their metadata.
 */
@injectable()
export abstract class BaseDeleteElementOperationHandler extends BaseOperationHandler implements ContextItemProvider {
    override readonly operationType = DeleteElementOperation.KIND;

    /**
     * Diagram configuration used to resolve deletable type hints.
     */
    @inject(DiagramConfigurationKey)
    protected readonly diagramConfiguration!: DiagramConfiguration;

    /**
     * Returns delete context items only for elements marked deletable in diagram type hints.
     *
     * @param element The selected diagram element
     * @param _context Additional request context
     * @returns Delete context item when deletable, otherwise an empty array
     */
    getContextItems(element: GModelElement, _context: ContextActionRequestContext): ContextItem[] {
        if (!this.isElementDeletable(element.type)) {
            return [];
        }

        return [
            {
                id: `delete-${element.id}`,
                label: "Delete",
                icon: "trash",
                sortString: "z",
                action: DeleteElementOperation.create([element.id])
            }
        ];
    }

    override async createCommand(operation: any): Promise<Command> {
        const result = await this.executeDelete(operation);

        const deletedIds = this.collectAllElementIds(result.deletedElements);
        const metadataEdits = this.createMetadataEdits(deletedIds);

        return new OperationHandlerCommand(this.modelState, result.workspaceEdit, metadataEdits);
    }

    /**
     * Executes the delete operation and returns the workspace edit and deleted elements.
     *
     * @param operation The delete operation
     * @returns The delete operation result
     */
    protected abstract executeDelete(operation: any): Promise<DeleteOperationResult>;

    /**
     * Recursively collects all element IDs from the given elements and their children.
     *
     * @param elements The elements to collect IDs from
     * @returns Set of all element IDs including children
     */
    private collectAllElementIds(elements: GModelElement[]): Set<string> {
        const ids = new Set<string>();

        const visit = (element: GModelElement) => {
            ids.add(element.id);
            if ("children" in element && Array.isArray(element.children)) {
                for (const child of element.children) {
                    visit(child);
                }
            }
        };

        for (const element of elements) {
            visit(element);
        }

        return ids;
    }

    /**
     * Creates metadata edits to remove metadata for all deleted element IDs.
     *
     * @param deletedIds Set of deleted element IDs
     * @returns Metadata edits object
     */
    private createMetadataEdits(deletedIds: Set<string>): {
        nodes?: Record<string, NodeMetadata | null>;
        edges?: Record<string, EdgeMetadata | null>;
    } {
        const currentMetadata = this.modelState.metadata;
        const nodes: Record<string, null> = {};
        const edges: Record<string, null> = {};

        for (const id of deletedIds) {
            if (currentMetadata.nodes[id] != undefined) {
                nodes[id] = null;
            }
            if (currentMetadata.edges[id] != undefined) {
                edges[id] = null;
            }
        }

        return {
            nodes: Object.keys(nodes).length > 0 ? nodes : undefined,
            edges: Object.keys(edges).length > 0 ? edges : undefined
        };
    }

    /**
     * Checks whether a given element type is marked as deletable in diagram hints.
     *
     * @param elementType The diagram element type id
     * @returns True when the type hint explicitly marks the element deletable
     */
    protected isElementDeletable(elementType: string): boolean {
        const shapeHint = this.findShapeHint(elementType);
        if (shapeHint != undefined) {
            return shapeHint.deletable === true;
        }

        const edgeHint = this.findEdgeHint(elementType);
        if (edgeHint != undefined) {
            return edgeHint.deletable === true;
        }

        return false;
    }

    /**
     * Finds a shape type hint by element type id.
     *
     * @param elementType The shape element type id
     * @returns Matching shape hint if available
     */
    private findShapeHint(elementType: string): ShapeTypeHint | undefined {
        return this.diagramConfiguration.shapeTypeHints.find((hint) => hint.elementTypeId === elementType);
    }

    /**
     * Finds an edge type hint by element type id.
     *
     * @param elementType The edge element type id
     * @returns Matching edge hint if available
     */
    private findEdgeHint(elementType: string): EdgeTypeHint | undefined {
        return this.diagramConfiguration.edgeTypeHints.find((hint) => hint.elementTypeId === elementType);
    }
}
