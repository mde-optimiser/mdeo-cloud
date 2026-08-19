import type { MaybePromise } from "@eclipse-glsp/protocol";
import type { Command } from "@eclipse-glsp/server";
import { sharedImport } from "../../sharedImport.js";
import { BaseOperationHandler } from "./baseOperationHandler.js";
import { OperationHandlerCommand } from "./operationHandlerCommand.js";
import type { EdgeMetadata } from "../metadata.js";
import type { EdgeLayoutMetadata } from "@mdeo/protocol-common";
import { UpdateRoutingInformationOperation } from "@mdeo/protocol-common";

const { injectable } = sharedImport("inversify");

/**
 * Handler for update routing information operations.
 * Processes operations that change the routing points and anchors of edges.
 */
@injectable()
export class UpdateRoutingInformationOperationHandler extends BaseOperationHandler {
    /**
     * The operation type this handler processes
     */
    override readonly operationType = UpdateRoutingInformationOperation.KIND;

    /**
     * Creates a command to execute the update routing information operation.
     *
     * @param operation The update routing information operation to process
     * @returns The command to execute, or undefined if the operation cannot be handled
     */
    override createCommand(operation: UpdateRoutingInformationOperation): MaybePromise<Command | undefined> {
        return new OperationHandlerCommand(this.modelState, undefined, {
            edges: Object.fromEntries(
                operation.updates.map(({ elementId, routingPoints, sourceAnchor, targetAnchor }) => {
                    const meta: Partial<EdgeLayoutMetadata> = {};

                    if (routingPoints !== undefined) {
                        meta.routingPoints = routingPoints;
                    }
                    if (sourceAnchor !== undefined) {
                        meta.sourceAnchor = sourceAnchor;
                    }
                    if (targetAnchor !== undefined) {
                        meta.targetAnchor = targetAnchor;
                    }

                    return [
                        elementId,
                        {
                            meta
                        } satisfies Partial<EdgeMetadata>
                    ];
                })
            )
        });
    }
}
