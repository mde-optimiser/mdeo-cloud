import type { Operation, Dimension, Point } from "@eclipse-glsp/protocol";

/**
 * Partial version of GLSP's ChangeBoundsOperation
 * Allows changing only size or position, and allows partial size changes as well.
 */
export interface PartialChangeBoundsOperation extends Operation {
    kind: "partialChangeBounds";

    /**
     * The new partial bounds
     */
    newBounds: PartialElementAndBounds[];
}

/**
 * Partial version of GLSP's ElementsAndBounds
 * Both size and position are optional, and size can be partial as well.
 */
export interface PartialElementAndBounds {
    /**
     * The identifier of the element.
     */
    elementId: string;

    /**
     * The new size of the element.
     */
    newSize?: Partial<Dimension>;

    /**
     * The new position of the element.
     */
    newPosition?: Point;
}

/**
 * Namespace helpers for partial-change-bounds operations.
 */
export namespace PartialChangeBoundsOperation {
    /**
     * Operation kind constant.
     */
    export const KIND = "partialChangeBounds";
}
