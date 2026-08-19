import type { MaybePromise } from "@eclipse-glsp/protocol";
import type { Command } from "@eclipse-glsp/server";
import { sharedImport } from "../../sharedImport.js";
import { BaseOperationHandler } from "./baseOperationHandler.js";
import { OperationHandlerCommand } from "./operationHandlerCommand.js";
import type { NodeMetadata } from "../metadata.js";
import type { NodeLayoutMetadata } from "@mdeo/protocol-common";
import { PartialChangeBoundsOperation } from "@mdeo/protocol-common";

const { injectable } = sharedImport("inversify");

/**
 * Handler for partial change bounds operations.
 * Processes operations that change the position or size of diagram elements,
 * allowing partial updates where position and size are optional.
 */
@injectable()
export class PartialChangeBoundsOperationHandler extends BaseOperationHandler {
    /**
     * The operation type this handler processes
     */
    override readonly operationType = PartialChangeBoundsOperation.KIND;

    /**
     * Creates a command to execute the partial change bounds operation.
     *
     * @param operation The partial change bounds operation to process
     * @returns The command to execute, or undefined if the operation cannot be handled
     */
    override createCommand(operation: PartialChangeBoundsOperation): MaybePromise<Command | undefined> {
        return new OperationHandlerCommand(this.modelState, undefined, {
            nodes: Object.fromEntries(
                operation.newBounds.map(({ elementId, newSize, newPosition }) => [
                    elementId,
                    {
                        meta: {
                            ...(newPosition != undefined && { position: newPosition }),
                            ...(newSize?.width != undefined && { prefWidth: newSize.width }),
                            ...(newSize?.height != undefined && { prefHeight: newSize.height })
                        } satisfies Partial<NodeLayoutMetadata>
                    } satisfies Partial<NodeMetadata>
                ])
            )
        });
    }
}
