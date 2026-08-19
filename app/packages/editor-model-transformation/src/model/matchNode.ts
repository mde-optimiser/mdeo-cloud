import { GEdge, GIssueMarker, GNode, nodeLayoutMetadataFeature, sharedImport } from "@mdeo/editor-shared";
import type { MinimumSizeAware } from "@mdeo/editor-shared";
import type { GModelElement, Point, Bounds, Dimension } from "@eclipse-glsp/sprotty";
import { GMatchNodeView } from "../views/matchNodeView.js";
import { GMatchNodeCompartments } from "./matchNodeCompartments.js";
import { GControlFlowEdge } from "./controlFlowEdge.js";

const {
    connectableFeature,
    deletableFeature,
    selectFeature,
    boundsFeature,
    moveFeature,
    fadeFeature,
    layoutContainerFeature,
    Bounds: BoundsUtil,
    isBounds,
    isBoundsAware
} = sharedImport("@eclipse-glsp/sprotty");
const { containerFeature, resizeFeature } = sharedImport("@eclipse-glsp/client");

/**
 * Render information derived from a match node, used by both the view and the
 * coordinate-conversion helpers on the model.
 */
export interface MatchNodeRenderInfo {
    /**
     * All non-container children (pattern instances + edge children combined)
     */
    innerChildren: GModelElement[];
    /**
     * The optional container node (variables / where-clause compartments)
     */
    containerNode: GMatchNodeCompartments | undefined;
    /**
     * Bounding box of all inner children in their local (pre-translate) coordinate space
     */
    innerChildrenBounds: Bounds;
    /**
     * The SVG `translate(x, y)` offset applied to the inner children group inside
     * the view.  Does **not** include this node's own position.
     */
    innerChildrenTranslation: Point;
    /**
     * The size the frame needs in order to show the pattern graph and the compartments
     * below it, i.e. the size the node falls back to when it was never resized by hand.
     */
    requiredFrameSize: Dimension;
    /**
     * The size the frame is drawn at: the required size, unless the user dragged the node
     * to something larger.
     */
    frameSize: Dimension;
    /**
     * The height of the pattern area, i.e. the y coordinate at which the compartments begin.
     * Extra height gained by a manual resize goes to the pattern area, so that the
     * compartments stay at the bottom edge of the frame.
     */
    patternAreaHeight: number;
}

/**
 * Client-side model for a match node in the transformation diagram.
 * Match nodes contain pattern elements (instances, links) as children,
 * and may also contain constraint compartments (variables, where clauses).
 */
export class GMatchNode extends GNode implements MinimumSizeAware {
    /**
     * Default features enabled for match nodes
     */
    static readonly DEFAULT_FEATURES = [
        connectableFeature,
        deletableFeature,
        selectFeature,
        boundsFeature,
        moveFeature,
        fadeFeature,
        resizeFeature,
        nodeLayoutMetadataFeature,
        layoutContainerFeature,
        containerFeature
    ];

    /**
     * Whether this is a "for match" (multiple matches iteration)
     */
    multiple!: boolean;

    /**
     * Computes the bounding box of all given inner children (pattern instances and
     * edges), including routing points and bounds-aware sub-children of edges.
     */
    private computeInnerChildrenBounds(innerChildren: GModelElement[]): Bounds {
        if (innerChildren.length === 0) {
            return { x: 0, y: 0, width: GMatchNodeView.MIN_CONTENT_SIZE, height: GMatchNodeView.MIN_CONTENT_SIZE };
        }

        let combined: Bounds | undefined;
        const expandWithBounds = (bounds: Bounds) => {
            combined = combined === undefined ? bounds : BoundsUtil.combine(combined, bounds);
        };

        for (const child of innerChildren) {
            if (isBoundsAware(child)) {
                expandWithBounds(child.bounds);
            }

            if (child instanceof GEdge) {
                for (const routingPoint of child.meta.routingPoints) {
                    expandWithBounds({ x: routingPoint.x, y: routingPoint.y, width: 0, height: 0 });
                }
            }
        }

        return (
            combined ?? { x: 0, y: 0, width: GMatchNodeView.MIN_CONTENT_SIZE, height: GMatchNodeView.MIN_CONTENT_SIZE }
        );
    }

    /**
     * Returns the size of the compartment area drawn below the pattern graph.
     *
     * @param containerNode The compartments child, or `undefined` when the node has none
     * @returns The compartment size, zero while its bounds are not yet known
     */
    private compartmentsSize(containerNode: GMatchNodeCompartments | undefined): Dimension {
        const bounds = containerNode?.bounds;
        if (bounds == undefined || bounds.width < 0 || bounds.height < 0) {
            return { width: 0, height: 0 };
        }
        return { width: bounds.width, height: bounds.height };
    }

    /**
     * Returns the size the frame needs in order to show everything it contains.
     *
     * @param innerChildrenBounds The bounding box of the pattern graph
     * @param compartments The size of the compartment area below it
     * @returns The required frame size
     */
    private computeRequiredFrameSize(innerChildrenBounds: Bounds, compartments: Dimension): Dimension {
        const padding = GMatchNodeView.INNER_PADDING * 2;
        return {
            width: Math.max(
                innerChildrenBounds.width + padding,
                GMatchNodeView.MIN_CONTENT_SIZE + padding,
                compartments.width
            ),
            height: innerChildrenBounds.height + padding + compartments.height
        };
    }

    /**
     * The distance the shadow copy of a `for match` is drawn at, and therefore the amount by
     * which the bounds of the node exceed its frame.  Zero for a plain match.
     */
    private get shadowOffset(): number {
        return this.multiple ? GMatchNodeView.SHADOW_OFFSET : 0;
    }

    /**
     * Returns the frame size the user dragged the node to.
     *
     * A resize stores the bounds of the node, which for a `for match` include the shadow copy
     * behind the frame, so the offset is taken off again here: what the user sized is the
     * frame, the shadow only follows it.
     *
     * @returns The manual frame size, at most zero in a dimension that was never resized
     */
    private manualFrameSize(): Dimension {
        const offset = this.shadowOffset;
        return {
            width: (this.meta?.prefWidth ?? 0) - offset,
            height: (this.meta?.prefHeight ?? 0) - offset
        };
    }

    /**
     * Returns the smallest size this node may be resized to.
     *
     * The pattern graph is drawn at the positions its elements were laid out at, so a frame
     * smaller than the graph would cut elements off rather than rearrange them: the graph is
     * what the node shows, and a match node is therefore never smaller than the graph it holds.
     *
     * @returns The required size in bounds coordinates, which include the shadow copy of a
     *          `for match`
     */
    getMinimumSize(): Dimension {
        const { requiredFrameSize } = this.getRenderInfo();
        const offset = this.shadowOffset;
        return {
            width: requiredFrameSize.width + offset,
            height: requiredFrameSize.height + offset
        };
    }

    /**
     * Returns render information for this match node, shared between the view
     * (for rendering) and the coordinate helpers (for parentToLocal / localToParent).
     */
    getRenderInfo(): MatchNodeRenderInfo {
        const innerChildren: GModelElement[] = [];
        let containerNode: GMatchNodeCompartments | undefined;

        for (const child of this.children) {
            if (child instanceof GMatchNodeCompartments) {
                containerNode = child;
            } else if (!(child instanceof GIssueMarker)) {
                innerChildren.push(child);
            }
        }

        const innerChildrenBounds = this.computeInnerChildrenBounds(innerChildren);
        const innerChildrenTranslation: Point = {
            x: GMatchNodeView.INNER_PADDING - innerChildrenBounds.x,
            y: GMatchNodeView.INNER_PADDING - innerChildrenBounds.y
        };

        const compartments = this.compartmentsSize(containerNode);
        const requiredFrameSize = this.computeRequiredFrameSize(innerChildrenBounds, compartments);
        const manualFrameSize = this.manualFrameSize();
        const frameSize: Dimension = {
            width: Math.max(requiredFrameSize.width, manualFrameSize.width),
            height: Math.max(requiredFrameSize.height, manualFrameSize.height)
        };

        return {
            innerChildren,
            containerNode,
            innerChildrenBounds,
            innerChildrenTranslation,
            requiredFrameSize,
            frameSize,
            patternAreaHeight: frameSize.height - compartments.height
        };
    }

    /**
     * Returns the SVG translate offset applied to child nodes in the view,
     * shifted by this node's own position to yield a parent-space transform.
     */
    private get childOffset(): Point {
        const { innerChildrenTranslation: t } = this.getRenderInfo();
        const position = this.position;
        return {
            x: t.x + position.x,
            y: t.y + position.y
        };
    }

    /**
     * Converts a point/bounds from this element's parent coordinate system to the
     * local coordinate system used by children (subtracts the SVG translate offset).
     */
    override parentToLocal(point: Point | Bounds): Bounds {
        const { x: tx, y: ty } = this.childOffset;
        if (isBounds(point)) {
            return { x: point.x - tx, y: point.y - ty, width: point.width, height: point.height };
        }
        return { x: point.x - tx, y: point.y - ty, width: -1, height: -1 };
    }

    /**
     * Converts a point/bounds from the local coordinate system of children back to
     * this element's parent coordinate system (adds the SVG translate offset).
     */
    override localToParent(point: Point | Bounds): Bounds {
        const { x: tx, y: ty } = this.childOffset;
        if (isBounds(point)) {
            return { x: point.x + tx, y: point.y + ty, width: point.width, height: point.height };
        }
        return { x: point.x + tx, y: point.y + ty, width: -1, height: -1 };
    }

    override canConnect(edge: GEdge): boolean {
        return edge instanceof GControlFlowEdge;
    }
}

/**
 * Type guard to check if an element is a match node.
 *
 * @param element The model element to check
 * @returns True if the element is a GMatchNode
 */
export function isMatchNode(element: GModelElement): element is GMatchNode {
    return element instanceof GMatchNode;
}
