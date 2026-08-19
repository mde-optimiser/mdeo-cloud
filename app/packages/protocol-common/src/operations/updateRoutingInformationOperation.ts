import type { Operation, Point } from "@eclipse-glsp/protocol";
import type { EdgeAnchor } from "../metadataTypes.js";

/**
 * Operation to update routing information for one or more edges.
 * This operation is sent to the server when the user finishes editing an edge's route.
 */
export interface UpdateRoutingInformationOperation extends Operation {
    kind: "updateRoutingInformation";
    /**
     * The routing updates to apply.
     */
    updates: EdgeRoutingUpdate[];
}

/**
 * Routing update for a single edge.
 */
export interface EdgeRoutingUpdate {
    /**
     * The ID of the edge element to update.
     */
    elementId: string;
    /**
     * The new routing points for the edge.
     * If undefined, the routing points are not changed.
     */
    routingPoints?: Point[];
    /**
     * The new start anchor for the edge.
     * If undefined, the start anchor is not changed.
     */
    sourceAnchor?: EdgeAnchor;
    /**
     * The new target anchor for the edge.
     * If undefined, the target anchor is not changed.
     */
    targetAnchor?: EdgeAnchor;
}

/**
 * Namespace helpers for update-routing-information operations.
 */
export namespace UpdateRoutingInformationOperation {
    /**
     * Operation kind constant.
     */
    export const KIND = "updateRoutingInformation";
}
