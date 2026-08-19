import { ResetLayoutOperation } from "@mdeo/protocol-common";
import type { EdgeLayoutMetadata, NodeLayoutMetadata } from "@mdeo/protocol-common";
import type { Command } from "@eclipse-glsp/server";
import { sharedImport } from "../../sharedImport.js";
import { BaseOperationHandler } from "./baseOperationHandler.js";
import { OperationHandlerCommand } from "./operationHandlerCommand.js";

const { injectable } = sharedImport("inversify");

/**
 * Server-side handler for {@link ResetLayoutOperation}.
 *
 * Clears the manually persisted sizing and / or routing information for a
 * diagram element so that the next model build computes fresh auto-layout
 * values for it.
 */
@injectable()
export class ResetLayoutOperationHandler extends BaseOperationHandler {
    override readonly operationType = ResetLayoutOperation.KIND;

    override createCommand(operation: ResetLayoutOperation): Command {
        const elementId = operation.elementId;
        const scope = operation.scope ?? "all";
        const modelState = this.modelState;

        // Sizing lives on nodes and routing on edges, so which of the two maps the edit belongs in
        // follows from the element itself. Writing into the wrong map would leave the layout in
        // place and add an entry that belongs to no diagram element.
        const isEdge = modelState.metadata.edges[elementId] != undefined;

        // The keys are the fields of the metadata interfaces themselves, so that a renamed
        // field is a compile error here rather than an edit that resets nothing.
        const newMetadata: Partial<Record<keyof NodeLayoutMetadata | keyof EdgeLayoutMetadata, undefined>> = {};
        if (!isEdge && (scope === "bounds" || scope === "all")) {
            newMetadata.prefWidth = undefined;
            newMetadata.prefHeight = undefined;
        }
        if (isEdge && (scope === "routing" || scope === "all")) {
            newMetadata.routingPoints = undefined;
            newMetadata.sourceAnchor = undefined;
            newMetadata.targetAnchor = undefined;
        }

        const edits = { [elementId]: { meta: newMetadata } };

        return new OperationHandlerCommand(modelState, undefined, isEdge ? { edges: edits } : { nodes: edits });
    }
}
