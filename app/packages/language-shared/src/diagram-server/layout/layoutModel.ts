/**
 * Neutral, algorithm independent description of a layout problem and its result.
 *
 * Pipeline: engine.buildInput() -> LayoutInput -> algorithm (layered | tala | force) -> LayoutResult
 * -> joinSharedEdgeEnds(input, result) -> write-back into the graph metadata.
 */

/**
 * A simple 2D point.
 */
export interface LayoutPoint {
    x: number;
    y: number;
}

/**
 * The available layout algorithms.
 */
export type LayoutAlgorithm = "layered" | "tala" | "force";

/**
 * Padding of a container node (space between its border and its children).
 */
export interface LayoutPadding {
    top: number;
    right: number;
    bottom: number;
    left: number;
}

/**
 * A node of the layout input.
 */
export interface LayoutInputNode {
    /**
     * The id of the node (GNode id).
     */
    id: string;
    /**
     * The id of the containing node (e.g. a match node), undefined for top level nodes.
     */
    parentId?: string;
    /**
     * The measured width. For containers this is a minimum width; the algorithm computes the final size.
     */
    width: number;
    /**
     * The measured height. For containers this is a minimum height; the algorithm computes the final size.
     */
    height: number;
    /**
     * Padding for containers (top includes e.g. the compartment / header height).
     */
    padding?: LayoutPadding;
    /**
     * Optional hint for the preferred flow direction inside a container (children of this node).
     */
    direction?: "down" | "right";
}

/**
 * Extent of an edge marker (arrow head, diamond, ...), measured along the end segment.
 */
export interface LayoutMarker {
    /**
     * Length of the marker along the edge.
     */
    length: number;
    /**
     * Width of the marker across the edge.
     */
    width: number;
}

/**
 * Specification of a single edge label (one per rendered label node).
 */
export interface EdgeLabelSpec {
    /**
     * The id of the label GNode.
     */
    id: string;
    /**
     * At which part of the edge the label is placed.
     */
    end: "source" | "target" | "middle";
    /**
     * The side relative to the edge direction (source -> target) as the client view renders it.
     */
    side: "left" | "right";
    /**
     * The measured, unrotated width of the text box.
     */
    width: number;
    /**
     * The measured, unrotated height of the text box.
     */
    height: number;
    /**
     * The label text, used to detect identical labels.
     */
    text: string;
}

/**
 * An edge of the layout input.
 */
export interface LayoutInputEdge {
    /**
     * The id of the edge (GEdge id).
     */
    id: string;
    /**
     * The id of the source node.
     */
    sourceId: string;
    /**
     * The id of the target node.
     */
    targetId: string;
    /**
     * The labels of the edge.
     */
    labels: EdgeLabelSpec[];
    /**
     * The marker at the source end, if any.
     */
    sourceMarker?: LayoutMarker;
    /**
     * The marker at the target end, if any.
     */
    targetMarker?: LayoutMarker;
    /**
     * Lay the edge out against its direction (the target comes before the source in the flow), e.g. inheritance
     * edges so that supertypes are drawn above their subtypes. The route still runs from source to target.
     */
    reversed?: boolean;
    /**
     * Edges with the same key and the same target share their target end: they join into one trunk that enters the
     * target at one point (e.g. inheritance edges into a supertype). Supported by the layered algorithm.
     */
    targetMergeKey?: string;
}

/**
 * The neutral input of a layout run.
 */
export interface LayoutInput {
    /**
     * All nodes (top level and nested), parents listed before their children.
     */
    nodes: LayoutInputNode[];
    /**
     * All edges (edges between nested nodes included).
     */
    edges: LayoutInputEdge[];
    /**
     * Preferred main flow direction of the top level graph.
     */
    direction?: "down" | "right";
}

/**
 * Absolute bounds of a node after layout.
 */
export interface LayoutResultNode {
    x: number;
    y: number;
    width: number;
    height: number;
}

/**
 * The route of an edge after layout.
 */
export interface LayoutResultEdge {
    /**
     * Absolute points: points[0] on the source border, the last point on the target border.
     * Orthogonal for layered and tala.
     */
    points: LayoutPoint[];
}

/**
 * The result of a layout run, in absolute coordinates.
 */
export interface LayoutResult {
    nodes: Map<string, LayoutResultNode>;
    edges: Map<string, LayoutResultEdge>;
}
