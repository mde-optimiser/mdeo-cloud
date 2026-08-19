import type { Operation, Point } from "@eclipse-glsp/protocol";
import type { EdgeAnchor } from "../metadataTypes.js";
import type { CreateEdgeSchema } from "../createEdgeSchema.js";

/**
 * Operation to create a new edge between two elements.
 * This operation is sent to the server when the user finishes creating an edge
 * via the create-edge tool.
 */
export interface CreateEdgeOperation extends Operation {
    kind: "createEdge";
    /**
     * The type identifier of the edge to create (e.g. "edge:link").
     */
    elementTypeId: string;
    /**
     * The ID of the source element.
     */
    sourceElementId: string;
    /**
     * The ID of the target element.
     */
    targetElementId: string;
    /**
     * The routing points for the new edge.
     */
    routingPoints: Point[];
    /**
     * The source anchor for the new edge.
     */
    sourceAnchor: EdgeAnchor | undefined;
    /**
     * The target anchor for the new edge.
     */
    targetAnchor: EdgeAnchor | undefined;
    /**
     * Additional parameters provided by the create-edge provider.
     * These can influence edge creation behavior on the server side.
     */
    params: Record<string, unknown>;

    /**
     * Full schema selected by the create-edge provider.
     * Used by backend handlers for disambiguation and insertion semantics.
     */
    schema: CreateEdgeSchema;
}

/**
 * Namespace helpers for create-edge operations.
 */
export namespace CreateEdgeOperation {
    /**
     * Operation kind constant. It mirrors the kind of the GLSP operation this one stands in
     * for, so that the handlers registering for it and the tool sending it agree on one name.
     */
    export const KIND = "createEdge";
}
