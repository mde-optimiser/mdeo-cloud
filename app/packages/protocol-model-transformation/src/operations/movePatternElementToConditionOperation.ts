import type { Operation } from "@eclipse-glsp/protocol";

/**
 * Identifier of the destination of a {@link MovePatternElementToConditionOperation}.
 *
 * Either the reserved value {@link MAIN_PATTERN_TARGET}, one of the reserved values for
 * creating a new block, the index of an existing condition block within its pattern, as a
 * string, or a model scope block destination ({@link MODEL_BLOCK_TARGET_PREFIX},
 * {@link NEW_MODEL_BLOCK_TARGET_PREFIX}).
 */
export type MoveConditionTarget = string;

/**
 * Destination value moving the element back into the enclosing match pattern.
 */
export const MAIN_PATTERN_TARGET = "main";

/**
 * Destination value creating a new `forbid` block for the moved elements.
 */
export const NEW_FORBID_TARGET = "new-forbid";

/**
 * Destination value creating a new `require` block for the moved elements.
 */
export const NEW_REQUIRE_TARGET = "new-require";

/**
 * Prefix of a destination naming an existing model scope block (`in donor { … }`) of the
 * pattern: followed by the block's index among the pattern's scope blocks, e.g. `in:0`.
 */
export const MODEL_BLOCK_TARGET_PREFIX = "in:";

/**
 * Prefix of a destination creating a new model scope block for the moved elements: followed by
 * the name of the model value, e.g. `in-new:donor`.
 */
export const NEW_MODEL_BLOCK_TARGET_PREFIX = "in-new:";

/**
 * Operation moving a pattern element — together with everything connected to it — into
 * another application condition block, into a new one, or back into the match pattern.
 *
 * The whole connected component travels with the element: a condition block describes one
 * graph, so moving a node out of it without its links (and without the nodes those links
 * reach) would leave both blocks with a dangling reference.
 */
export interface MovePatternElementToConditionOperation extends Operation {
    /**
     * Operation kind discriminator.
     */
    kind: "movePatternElementToCondition";

    /**
     * Identifier of the element whose component is moved.
     */
    elementId: string;

    /**
     * The destination block.
     */
    target: MoveConditionTarget;
}

/**
 * Namespace helpers for move-pattern-element-to-condition operations.
 */
export namespace MovePatternElementToConditionOperation {
    /**
     * Operation kind constant.
     */
    export const KIND = "movePatternElementToCondition";

    /**
     * Payload for creating a move-pattern-element-to-condition operation.
     */
    export interface Options {
        /**
         * Identifier of the element whose component is moved.
         */
        elementId: string;

        /**
         * The destination block.
         */
        target: MoveConditionTarget;
    }

    /**
     * Creates a move-pattern-element-to-condition operation.
     *
     * @param options Operation payload
     * @returns Operation instance
     */
    export function create(options: Options): MovePatternElementToConditionOperation {
        return {
            kind: KIND,
            isOperation: true,
            elementId: options.elementId,
            target: options.target
        };
    }

    /**
     * Checks whether an operation is a move-pattern-element-to-condition operation.
     *
     * @param operation Operation to check
     * @returns True when operation kind matches
     */
    export function is(operation: Operation): operation is MovePatternElementToConditionOperation {
        return operation.kind === KIND;
    }
}
