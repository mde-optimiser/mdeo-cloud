import type { Operation } from "@eclipse-glsp/protocol";
import type { NewLabelOperation } from "@mdeo/protocol-common";

/**
 * Operation adding a line to the header of a transformation: an `in` / `out` declaration (or a
 * path import, `import "./x.mt" as x`). It is the commit of a new row of the start node (an
 * input) or of an end node (an output), whose text is inserted as it was written.
 */
export interface AddHeaderDeclarationOperation extends NewLabelOperation {
    /**
     * Operation kind discriminator.
     */
    kind: "addHeaderDeclaration";

    /**
     * Identifier of the start or end node the row was added to.
     */
    nodeId: string;
}

/**
 * Namespace helpers for add-header-declaration operations.
 */
export namespace AddHeaderDeclarationOperation {
    /**
     * Operation kind constant.
     */
    export const KIND = "addHeaderDeclaration";

    /**
     * Identifier carried in `newLabelOperationKind` by a new row of a start or end node, by which
     * the label view recognises a label it has to commit through this operation.
     */
    export const NEW_LABEL_KIND = "add-header-declaration";

    /**
     * Payload for creating an add-header-declaration operation.
     */
    export interface Options {
        /**
         * Identifier of the start or end node the row was added to.
         */
        nodeId: string;

        /**
         * The line as written, e.g. `in maxLoad: int = 3` or `import "./move.mt" as move`.
         */
        labelText?: string;
    }

    /**
     * Creates an add-header-declaration operation.
     *
     * @param options Operation payload
     * @returns Operation instance
     */
    export function create(options: Options): AddHeaderDeclarationOperation {
        return {
            kind: KIND,
            isOperation: true,
            nodeId: options.nodeId,
            parentElementId: options.nodeId,
            labelText: options.labelText ?? ""
        };
    }

    /**
     * Checks whether an operation is an add-header-declaration operation.
     *
     * @param operation Operation to check
     * @returns True when operation kind matches
     */
    export function is(operation: Operation): operation is AddHeaderDeclarationOperation {
        return operation.kind === KIND;
    }
}
