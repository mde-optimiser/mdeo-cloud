import type { Command } from "@eclipse-glsp/server";
import type { AstNode } from "langium";
import type { WorkspaceEdit } from "vscode-languageserver-types";
import type { GNode } from "../model/node.js";
import { sharedImport } from "../../sharedImport.js";
import { BaseOperationHandler } from "./baseOperationHandler.js";
import { OperationHandlerCommand } from "./operationHandlerCommand.js";
import type { EdgeMetadata } from "../metadata.js";
import { GEdge } from "../model/edge.js";
import { ReconnectEdgeOperation } from "@mdeo/protocol-common";

const { injectable } = sharedImport("inversify");

/**
 * Container for old and new source/target nodes during reconnection.
 */
export interface ReconnectEndpoints {
    /**
     * The old source node before reconnection.
     */
    oldSource: GNode;
    /**
     * The new source node after reconnection.
     */
    newSource: GNode;
    /**
     * The old target node before reconnection.
     */
    oldTarget: GNode;
    /**
     * The new target node after reconnection.
     */
    newTarget: GNode;
}

/**
 * Result of a reconnect edge operation.
 */
export interface ReconnectEdgeResult {
    /**
     * The new ID of the edge after reconnection.
     * May be the same as the old ID if the edge identity doesn't change.
     * If undefined, metadata adjustments will not be performed (e.g., for duplicate IDs).
     */
    newEdgeId?: string;
    /**
     * The workspace edit to apply to update the source model.
     */
    workspaceEdit: WorkspaceEdit;
}

/**
 * Base handler for applying reconnect edge operations from the client.
 * Subclasses must implement the createReconnectEdit method to define
 * language-specific reconnection behavior.
 */
@injectable()
export abstract class BaseReconnectEdgeOperationHandler extends BaseOperationHandler {
    override readonly operationType = ReconnectEdgeOperation.KIND;

    override async createCommand(operation: ReconnectEdgeOperation): Promise<Command | undefined> {
        const edge = this.modelState.index.find(operation.edgeElementId);
        if (edge == undefined) {
            throw new Error(`Edge with ID ${operation.edgeElementId} not found in model.`);
        }
        if (!(edge instanceof GEdge)) {
            throw new Error(`Element with ID ${operation.edgeElementId} is not an edge.`);
        }

        const result = await this.createReconnectEditWithEndpoints(edge, operation);

        if (result == undefined) {
            return undefined;
        }

        const metadata =
            result.newEdgeId != undefined ? this.buildEdgeMetadata(edge, operation, result.newEdgeId) : undefined;

        return new OperationHandlerCommand(this.modelState, result.workspaceEdit, metadata);
    }

    /**
     * Creates the reconnect edit with endpoint information.
     * Gets the edge and its AST node, then calls the abstract createReconnectEdit method.
     *
     * @param edge The edge element
     * @param operation The reconnect edge operation
     * @returns The reconnect result, or undefined if the edge or AST node is not found
     */
    protected async createReconnectEditWithEndpoints(
        edge: GEdge,
        operation: ReconnectEdgeOperation
    ): Promise<ReconnectEdgeResult | undefined> {
        const sourceElement = this.index.getAstNode(edge);
        if (sourceElement == undefined) {
            throw new Error(`AST node for edge with ID ${operation.edgeElementId} not found.`);
        }

        const endpoints = this.extractEndpoints(edge, operation);
        return await this.createReconnectEdit(sourceElement, operation, edge, endpoints);
    }

    /**
     * Extracts old and new source/target nodes from the edge and operation.
     *
     * @param edge The edge element
     * @param operation The reconnect operation
     * @returns The reconnect endpoints
     */
    protected extractEndpoints(edge: any, operation: ReconnectEdgeOperation): ReconnectEndpoints {
        const oldSource = this.modelState.index.find(edge.sourceId) as GNode;
        const oldTarget = this.modelState.index.find(edge.targetId) as GNode;
        const newSource = this.modelState.index.find(operation.sourceElementId) as GNode;
        const newTarget = this.modelState.index.find(operation.targetElementId) as GNode;

        if (oldSource == undefined || oldTarget == undefined || newSource == undefined || newTarget == undefined) {
            throw new Error("Failed to find source or target nodes in model.");
        }

        return {
            oldSource,
            oldTarget,
            newSource,
            newTarget
        };
    }

    /**
     * Creates the reconnect workspace edit for the given AST node.
     * Subclasses must implement this method to define language-specific reconnection logic.
     *
     * @param node The AST node corresponding to the edge
     * @param operation The reconnect edge operation
     * @param edge The edge element from the graph model
     * @param endpoints The old and new source/target nodes
     * @returns The reconnect result containing new edge ID and workspace edit, or undefined if not applicable
     */
    abstract createReconnectEdit(
        node: AstNode,
        operation: ReconnectEdgeOperation,
        edge: GEdge,
        endpoints: ReconnectEndpoints
    ): Promise<ReconnectEdgeResult | undefined>;

    /**
     * Builds metadata edits for the edge after reconnection.
     * Updates the edge's routing information with the new anchors from the operation.
     *
     * @param edge The edge element
     * @param operation The reconnect edge operation
     * @param newEdgeId The new edge ID
     * @returns Metadata edits object
     */
    protected buildEdgeMetadata(
        edge: GEdge,
        operation: ReconnectEdgeOperation,
        newEdgeId: string
    ): { edges: Record<string, Partial<EdgeMetadata> | null> } {
        return {
            edges: {
                [operation.edgeElementId]: null,
                [newEdgeId]: {
                    meta: {
                        routingPoints: operation.routingPoints ?? [],
                        sourceAnchor: operation.sourceAnchor,
                        targetAnchor: operation.targetAnchor
                    },
                    from: operation.sourceElementId,
                    to: operation.targetElementId,
                    type: edge.type
                }
            }
        };
    }
}
