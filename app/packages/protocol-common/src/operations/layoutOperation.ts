import type { Dimension, LayoutOperation as GLSPLayoutOperation } from "@eclipse-glsp/protocol";

/**
 * The layout algorithms a diagram can be laid out with.
 * - `layered`: ELK layered (hierarchical, orthogonal edges)
 * - `tala`: orthoflow, a port of the TALA layout engine (orthogonal edges)
 * - `force`: force-directed / stress based layout
 */
export type LayoutAlgorithmKind = "layered" | "tala" | "force";

/**
 * All layout algorithms, in the order they are offered to the user.
 */
export const LAYOUT_ALGORITHMS: readonly LayoutAlgorithmKind[] = ["layered", "tala", "force"];

/**
 * Extended layout operation providing bounds information for all elements
 */
export interface LayoutOperation extends GLSPLayoutOperation {
    /**
     * The bounds of all elements in the model
     */
    bounds: Record<string, Dimension>;
    /**
     * The layout algorithm to use, the server default ("layered") if undefined
     */
    algorithm?: LayoutAlgorithmKind;
}
