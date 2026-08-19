import type { Operation } from "@eclipse-glsp/protocol";
import type { NewLabelOperation } from "@mdeo/protocol-common";

/**
 * Operation to add a where clause to a match node or to one of its application condition
 * blocks.
 */
export interface AddWhereClauseOperation extends NewLabelOperation {
    /**
     * Operation kind discriminator.
     */
    kind: "addWhereClause";

    /**
     * Identifier of the match node to add the where clause to.
     */
    matchNodeId: string;

    /**
     * Index of the `forbid` / `require` block of that match to add the clause to, counted in
     * declaration order. When absent the clause is added to the match pattern itself.
     */
    conditionIndex?: number;
}

/**
 * Namespace helpers for add-where-clause operations.
 */
export namespace AddWhereClauseOperation {
    /**
     * Operation kind constant.
     */
    export const KIND = "addWhereClause";

    /**
     * Identifier carried in `newLabelOperationKind` by a new where-clause placeholder label,
     * by which the label view recognises a label it has to commit through this operation.
     */
    export const NEW_LABEL_KIND = "add-where-clause";

    /**
     * Payload for creating an add-where-clause operation.
     */
    export interface Options {
        /**
         * Identifier of the match node to add the where clause to.
         */
        matchNodeId: string;

        /**
         * The full edited where-clause text (e.g. {@code where a.b == c.d}).
         * The server reads this verbatim and inserts it into the source file.
         */
        labelText?: string;

        /**
         * Index of the application condition block to add the clause to, in declaration
         * order. Omitted when the clause belongs to the match pattern itself.
         */
        conditionIndex?: number;
    }

    /**
     * Creates an add-where-clause operation.
     *
     * @param options Operation payload
     * @returns Operation instance
     */
    export function create(options: Options): AddWhereClauseOperation {
        return {
            kind: KIND,
            isOperation: true,
            matchNodeId: options.matchNodeId,
            parentElementId: options.matchNodeId,
            labelText: options.labelText ?? "",
            conditionIndex: options.conditionIndex
        };
    }

    /**
     * Checks whether an operation is an add-where-clause operation.
     *
     * @param operation Operation to check
     * @returns True when operation kind matches
     */
    export function is(operation: Operation): operation is AddWhereClauseOperation {
        return operation.kind === KIND;
    }
}
