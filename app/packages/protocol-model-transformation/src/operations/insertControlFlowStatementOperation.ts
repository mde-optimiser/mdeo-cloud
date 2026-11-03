import type { Operation } from "@eclipse-glsp/protocol";

/**
 * Supported insertion kinds for control-flow edge context actions.
 */
export type InsertControlFlowStatementKind =
    | "match"
    | "for-match"
    | "if-match"
    | "while-match"
    | "until-match"
    | "if"
    | "while"
    | "stop"
    | "kill"
    | "call"
    | "if-call";

/**
 * Operation to insert a new control-flow statement between two connected nodes.
 */
export interface InsertControlFlowStatementOperation extends Operation {
    /**
     * Operation kind discriminator.
     */
    kind: "insertControlFlowStatement";

    /**
     * Identifier of the control-flow edge where insertion is requested.
     */
    edgeId: string;

    /**
     * Statement kind to insert.
     */
    statementKind: InsertControlFlowStatementKind;

    /**
     * The ID of the source node of the edge.
     * Used by the handler to determine the insertion position in the source file.
     */
    sourceNodeId?: string;

    /**
     * The ID of the target node of the edge.
     * Used by the handler to determine the insertion position in the source file.
     */
    targetNodeId?: string;

    /**
     * For `call` and `if-call`: the name of the imported transformation to call.
     */
    callee?: string;
}

/**
 * Namespace helpers for insert-control-flow-statement operations.
 */
export namespace InsertControlFlowStatementOperation {
    /**
     * Operation kind constant.
     */
    export const KIND = "insertControlFlowStatement";

    /**
     * Payload for creating an insert-control-flow-statement operation.
     */
    export interface Options {
        /**
         * Identifier of the control-flow edge where insertion is requested.
         */
        edgeId: string;

        /**
         * Statement kind to insert.
         */
        statementKind: InsertControlFlowStatementKind;

        /**
         * The ID of the source node of the edge.
         */
        sourceNodeId?: string;

        /**
         * The ID of the target node of the edge.
         */
        targetNodeId?: string;

        /**
         * For `call` and `if-call`: the name of the imported transformation to call.
         */
        callee?: string;
    }

    /**
     * Creates an insert-control-flow-statement operation.
     *
     * @param options Operation payload
     * @returns Operation instance
     */
    export function create(options: Options): InsertControlFlowStatementOperation {
        return {
            kind: KIND,
            isOperation: true,
            edgeId: options.edgeId,
            statementKind: options.statementKind,
            sourceNodeId: options.sourceNodeId,
            targetNodeId: options.targetNodeId,
            ...(options.callee != undefined ? { callee: options.callee } : {})
        };
    }

    /**
     * Checks whether an operation is an insert-control-flow-statement operation.
     *
     * @param operation Operation to check
     * @returns True when operation kind matches
     */
    export function is(operation: Operation): operation is InsertControlFlowStatementOperation {
        return operation.kind === KIND;
    }
}
