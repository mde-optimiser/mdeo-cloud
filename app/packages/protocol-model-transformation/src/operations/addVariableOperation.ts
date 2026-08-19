import type { Operation } from "@eclipse-glsp/protocol";
import type { NewLabelOperation } from "@mdeo/protocol-common";

/**
 * Operation to add a variable to a match node.
 */
export interface AddVariableOperation extends NewLabelOperation {
    /**
     * Operation kind discriminator.
     */
    kind: "addVariable";

    /**
     * Identifier of the match node to add the variable to.
     */
    matchNodeId: string;
}

/**
 * Namespace helpers for add-variable operations.
 */
export namespace AddVariableOperation {
    /**
     * Operation kind constant.
     */
    export const KIND = "addVariable";

    /**
     * Identifier carried in `newLabelOperationKind` by a new variable placeholder label,
     * by which the label view recognises a label it has to commit through this operation.
     */
    export const NEW_LABEL_KIND = "add-variable";

    /**
     * Payload for creating an add-variable operation.
     */
    export interface Options {
        /**
         * Identifier of the match node to add the variable to.
         */
        matchNodeId: string;

        /**
         * The full edited variable-declaration text
         * (e.g. {@code var name[: type] = expression}).
         * The server reads this verbatim and inserts it into the source file.
         */
        labelText?: string;
    }

    /**
     * Creates an add-variable operation.
     *
     * @param options Operation payload
     * @returns Operation instance
     */
    export function create(options: Options): AddVariableOperation {
        return {
            kind: KIND,
            isOperation: true,
            matchNodeId: options.matchNodeId,
            parentElementId: options.matchNodeId,
            labelText: options.labelText ?? ""
        };
    }

    /**
     * Checks whether an operation is an add-variable operation.
     *
     * @param operation Operation to check
     * @returns True when operation kind matches
     */
    export function is(operation: Operation): operation is AddVariableOperation {
        return operation.kind === KIND;
    }
}
