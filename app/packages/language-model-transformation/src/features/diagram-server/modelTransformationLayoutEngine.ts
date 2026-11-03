import type { GModelElement, GModelRoot } from "@eclipse-glsp/server";
import type { LayoutOperation } from "@mdeo/protocol-common";
import { BaseLayoutEngine, GEdge, GNode, sharedImport } from "@mdeo/language-shared";
import type { EdgeLabelSpec, ElkOptions, LayoutInput, NodeAlignment } from "@mdeo/language-shared";
import { GMatchNode } from "./model/matchNode.js";
import { GMatchNodeCompartments } from "./model/matchNodeCompartments.js";
import { GPatternInstanceNode } from "./model/patternInstanceNode.js";
import { GPatternLinkEdge } from "./model/patternLinkEdge.js";
import { GControlFlowLabelNode } from "./model/controlFlowLabelNode.js";
import { GControlFlowEdge } from "./model/controlFlowEdge.js";
import { GPatternLinkEndNode } from "./model/patternLinkEndNode.js";
import { GPatternLinkModifierNode } from "./model/patternLinkModifierNode.js";
import { GStartNode } from "./model/startNode.js";
import { GEndNode } from "./model/endNode.js";
import { GSplitNode } from "./model/splitNode.js";
import { GMergeNode } from "./model/mergeNode.js";
import { MATCH_NODE_INNER_PADDING } from "@mdeo/protocol-model-transformation";

const { injectable } = sharedImport("inversify");

/**
 * Minimum size of the pattern area of a match node (see the client's GMatchNodeView.MIN_CONTENT_SIZE).
 */
const MATCH_NODE_MIN_CONTENT_SIZE = 80;

/**
 * Offset of the shadow copy drawn behind a "for match" (see the client's GMatchNodeView.SHADOW_OFFSET).
 * The bounds of such a node include the shadow.
 */
const MATCH_NODE_SHADOW_OFFSET = 8;

/**
 * Generous average character width of compartment texts (used to estimate their natural width).
 */
const COMPARTMENT_CHAR_WIDTH = 9;

/**
 * Horizontal margin of a compartment (m-2 on both sides) plus a little slack.
 */
const COMPARTMENT_HORIZONTAL_MARGIN = 24;

/**
 * Length of the control flow arrow head.
 */
const CONTROL_FLOW_ARROW = { length: 12, width: 12 };

/**
 * Layout engine for model transformation diagrams.
 *
 * Match nodes are containers: their pattern instances and pattern links are laid out first (left to right), then the
 * control flow is laid out top down with the match nodes at the size the client draws them at.
 */
@injectable()
export class ModelTransformationLayoutEngine extends BaseLayoutEngine {
    /**
     * Returns center/center alignment for split, merge, start, and end nodes so that
     * the center point of these circular/diamond-shaped nodes is snapped to the grid.
     * All other nodes use the default top-left alignment.
     *
     * @param nodeId The ID of the node whose alignment should be determined
     * @returns The alignment for the node, center/center for circular/diamond nodes, default otherwise
     */
    protected override getNodeAlignment(nodeId: string): NodeAlignment {
        const element = this.modelState.index.find(nodeId);
        if (
            element instanceof GStartNode ||
            element instanceof GEndNode ||
            element instanceof GSplitNode ||
            element instanceof GMergeNode
        ) {
            return { vAlign: "center", hAlign: "center" };
        }
        return super.getNodeAlignment(nodeId);
    }

    protected override layeredOptions(containerId: string | undefined): ElkOptions {
        if (containerId == undefined) {
            return {
                "elk.spacing.nodeNode": "50",
                "elk.layered.spacing.nodeNodeBetweenLayers": "50"
            };
        }
        return {
            "elk.spacing.nodeNode": "40",
            "elk.layered.spacing.nodeNodeBetweenLayers": "60"
        };
    }

    /**
     * Builds the layout input: control flow nodes and match nodes on the top level, pattern instances and pattern
     * links inside their match node.
     *
     * @param model The GModelRoot to transform
     * @param operation The layout operation containing bounds information
     * @returns The layout input
     */
    protected override buildInput(model: GModelRoot, operation: LayoutOperation): LayoutInput {
        const bounds = operation.bounds;
        const input: LayoutInput = { nodes: [], edges: [], direction: "down" };

        for (const child of model.children) {
            if (child instanceof GMatchNode) {
                this.addMatchNode(child, bounds, input);
            } else if (child instanceof GNode) {
                input.nodes.push({ id: child.id, ...this.sizeOf(child.id, bounds) });
            }
        }
        for (const child of model.children) {
            if (child instanceof GEdge) {
                const labels: EdgeLabelSpec[] = child.children
                    .filter((label) => label instanceof GControlFlowLabelNode)
                    .map((label) => this.labelSpec(label, "middle", "left", bounds));
                input.edges.push({
                    id: child.id,
                    sourceId: child.sourceId,
                    targetId: child.targetId,
                    labels,
                    targetMarker: child instanceof GControlFlowEdge ? CONTROL_FLOW_ARROW : undefined
                });
            }
        }
        return input;
    }

    /**
     * Estimates the natural width of the compartments of a match node (where clauses, variables) from their texts.
     *
     * @param compartments The compartments element
     * @returns The estimated width
     */
    private estimateCompartmentsWidth(compartments: GModelElement): number {
        let longest = 0;
        const stack: GModelElement[] = [compartments];
        while (stack.length > 0) {
            const element = stack.pop()!;
            const text = (element as { text?: unknown }).text;
            if (typeof text === "string") {
                longest = Math.max(longest, text.length);
            }
            stack.push(...element.children);
        }
        return Math.ceil(longest * COMPARTMENT_CHAR_WIDTH + COMPARTMENT_HORIZONTAL_MARGIN);
    }

    /**
     * Adds a match node and its pattern elements to the input. The padding reproduces the client's frame:
     * the pattern area has {@link MATCH_NODE_INNER_PADDING} on every side, the compartments are drawn below it
     * and a "for match" is larger by the shadow offset.
     *
     * @param matchNode The match node
     * @param bounds The bounds information from the layout operation
     * @param input The input to add to
     */
    private addMatchNode(matchNode: GMatchNode, bounds: LayoutOperation["bounds"], input: LayoutInput): void {
        const shadow = matchNode.multiple ? MATCH_NODE_SHADOW_OFFSET : 0;
        let compartments = { width: 0, height: 0 };
        let hasChildren = false;
        const childEdges: GPatternLinkEdge[] = [];
        const index = input.nodes.length;
        for (const child of matchNode.children) {
            if (child instanceof GPatternInstanceNode) {
                hasChildren = true;
                input.nodes.push({ id: child.id, parentId: matchNode.id, ...this.sizeOf(child.id, bounds) });
            } else if (child instanceof GPatternLinkEdge) {
                childEdges.push(child);
            } else if (child instanceof GMatchNodeCompartments) {
                const measured = this.sizeOf(child.id, bounds);
                // the client stretches the compartments to the current frame, so the measured width is only an upper
                // bound of the width they need: estimate the natural width from the texts
                compartments = {
                    width: Math.min(measured.width, this.estimateCompartmentsWidth(child)),
                    height: measured.height
                };
            }
        }

        const padding = MATCH_NODE_INNER_PADDING;
        const prefWidth = matchNode.meta?.prefWidth ?? 0;
        const prefHeight = matchNode.meta?.prefHeight ?? 0;
        const node = hasChildren
            ? {
                  id: matchNode.id,
                  width:
                      Math.max(MATCH_NODE_MIN_CONTENT_SIZE + 2 * padding, compartments.width, prefWidth - shadow) +
                      shadow,
                  height: Math.max(0, prefHeight),
                  padding: {
                      top: padding,
                      left: padding,
                      right: padding + shadow,
                      bottom: padding + compartments.height + shadow
                  },
                  direction: "right" as const
              }
            : { id: matchNode.id, ...this.sizeOf(matchNode.id, bounds) };
        // parents are listed before their children
        input.nodes.splice(index, 0, node);

        for (const edge of childEdges) {
            const labels: EdgeLabelSpec[] = [];
            for (const label of edge.children) {
                if (label instanceof GPatternLinkEndNode) {
                    labels.push(this.labelSpec(label, label.end, "left", bounds));
                } else if (label instanceof GPatternLinkModifierNode) {
                    labels.push(this.labelSpec(label, "middle", "left", bounds));
                }
            }
            input.edges.push({ id: edge.id, sourceId: edge.sourceId, targetId: edge.targetId, labels });
        }
    }
}
