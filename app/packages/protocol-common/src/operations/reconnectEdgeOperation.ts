import type { Operation, Point } from "@eclipse-glsp/protocol";
import type { EdgeAnchor } from "../metadataTypes.js";

/**
 * Operation to reconnect an edge to a new source or target element.
 * This operation is sent to the server when the user finishes reconnecting an edge.
 */
export interface ReconnectEdgeOperation extends Operation {
    kind: "reconnectEdge";
    /**
     * The ID of the edge element to reconnect.
     */
    edgeElementId: string;
    /**
     * The ID of the new source element.
     */
    sourceElementId: string;
    /**
     * The ID of the new target element.
     */
    targetElementId: string;
    /**
     * The new routing points for the edge.
     */
    routingPoints: Point[];
    /**
     * The new source anchor for the edge.
     */
    sourceAnchor: EdgeAnchor | undefined;
    /**
     * The new target anchor for the edge.
     */
    targetAnchor: EdgeAnchor | undefined;
}

/**
 * Namespace helpers for reconnect-edge operations.
 */
export namespace ReconnectEdgeOperation {
    /**
     * Operation kind constant.
     */
    export const KIND = "reconnectEdge";
}
