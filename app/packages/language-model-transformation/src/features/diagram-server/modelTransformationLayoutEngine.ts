import type { GModelRoot } from "@eclipse-glsp/server";
import type { LayoutOperation } from "@mdeo/protocol-common";
import { BaseLayoutEngine, GEdge, GNode, sharedImport } from "@mdeo/language-shared";
import type { NodeAlignment } from "@mdeo/language-shared";
import type { ElkExtendedEdge, ElkNode } from "elkjs";
import { GMatchNode } from "./model/matchNode.js";
import { GMatchNodeCompartments } from "./model/matchNodeCompartments.js";
import { GPatternInstanceNode } from "./model/patternInstanceNode.js";
import { GPatternLinkEdge } from "./model/patternLinkEdge.js";
import { GControlFlowLabelNode } from "./model/controlFlowLabelNode.js";
import { GPatternLinkEndNode } from "./model/patternLinkEndNode.js";
import { GPatternLinkModifierLabel } from "./model/patternLinkModifierLabel.js";
import { GPatternLinkModifierNode } from "./model/patternLinkModifierNode.js";
import { GStartNode } from "./model/startNode.js";
import { GEndNode } from "./model/endNode.js";
import { GSplitNode } from "./model/splitNode.js";
import { GMergeNode } from "./model/mergeNode.js";
import { MATCH_NODE_INNER_PADDING } from "@mdeo/protocol-model-transformation";

const { injectable } = sharedImport("inversify");

/**
 * Layout engine for model transformation diagrams.
 * Uses ELK for automatic layout computation.
 *
 * Handles nested nodes: match nodes contain pattern elements as children.
 * Pattern instances are positioned globally but displayed relative to match node.
 * ELK returns relative positions for children, which must be converted to global positions.
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

    /**
     * Transforms the GModel to an ELK graph for layout computation.
     * Creates a hierarchical graph with match nodes containing pattern instances.
     *
     * Control flow edge labels are given ELK placement {@code CENTER} because
     * {@link GControlFlowEdgeView} renders them at the middle of the edge
     * ({@link EdgeAttachmentPosition.MIDDLE}). Using {@code CENTER} makes ELK
     * reserve space at the correct location, preventing labels from overlapping
     * intermediate nodes.
     *
     * @param model The GModelRoot to transform
     * @param operation The layout operation containing bounds information
     * @returns The ELK node representation of the graph
     */
    protected override transformToElk(model: GModelRoot, operation: LayoutOperation): ElkNode {
        const bounds = operation.bounds;
        const nodes: ElkNode[] = [];
        const edges: ElkExtendedEdge[] = [];

        for (const child of model.children) {
            if (child instanceof GMatchNode) {
                const matchNode = this.transformMatchNode(child, bounds);
                nodes.push(matchNode);
            } else if (child instanceof GNode) {
                nodes.push({
                    id: child.id,
                    width: bounds[child.id]?.width,
                    height: bounds[child.id]?.height
                });
            } else if (child instanceof GEdge) {
                edges.push({
                    id: child.id,
                    sources: [child.sourceId],
                    targets: [child.targetId],
                    labels: child.children
                        .filter((label) => label instanceof GControlFlowLabelNode)
                        .map((label) => ({
                            id: label.id,
                            text: "_",
                            width: bounds[label.id]?.width,
                            height: bounds[label.id]?.height,
                            layoutOptions: {
                                "elk.edgeLabels.placement": "CENTER"
                            }
                        }))
                });
            }
        }

        return {
            id: model.id,
            layoutOptions: {
                "elk.algorithm": "layered",
                "elk.direction": "DOWN",
                "elk.spacing.nodeNode": "50",
                "elk.layered.spacing.nodeNodeBetweenLayers": "50",
                "elk.spacing.edgeEdge": "40"
            },
            children: nodes,
            edges: edges
        };
    }

    /**
     * Transforms a match node to an ELK node with children.
     * Pattern instances become ELK child nodes, pattern links become ELK edges.
     *
     * @param matchNode The match node to transform
     * @param bounds The bounds information from the layout operation
     * @returns The ELK node representation
     */
    private transformMatchNode(matchNode: GMatchNode, bounds: LayoutOperation["bounds"]): ElkNode {
        const childNodes: ElkNode[] = [];
        const childEdges: ElkExtendedEdge[] = [];

        let compartmentHeight = 0;

        for (const child of matchNode.children) {
            if (child instanceof GPatternInstanceNode) {
                childNodes.push({
                    id: child.id,
                    width: bounds[child.id]?.width,
                    height: bounds[child.id]?.height
                });
            } else if (child instanceof GPatternLinkEdge) {
                childEdges.push({
                    id: child.id,
                    sources: [child.sourceId],
                    targets: [child.targetId],
                    labels: child.children
                        .filter(
                            (label) =>
                                label instanceof GPatternLinkEndNode ||
                                label instanceof GPatternLinkModifierNode ||
                                label instanceof GPatternLinkModifierLabel
                        )
                        .map((label) => {
                            if (label instanceof GPatternLinkEndNode) {
                                return {
                                    text: "_",
                                    width: bounds[label.id]?.width,
                                    height: bounds[label.id]?.height,
                                    layoutOptions: {
                                        "elk.edgeLabels.placement": label.end === "source" ? "HEAD" : "TAIL"
                                    }
                                };
                            }
                            return {
                                text: "_",
                                width: bounds[label.id]?.width,
                                height: bounds[label.id]?.height,
                                layoutOptions: {
                                    "elk.edgeLabels.placement": "CENTER"
                                }
                            };
                        })
                });
            } else if (child instanceof GMatchNodeCompartments) {
                compartmentHeight = bounds[child.id]?.height ?? 0;
            }
        }

        const bottomPadding = MATCH_NODE_INNER_PADDING + compartmentHeight;
        const topPadding = MATCH_NODE_INNER_PADDING * 2;

        return {
            id: matchNode.id,
            width: bounds[matchNode.id]?.width,
            height: bounds[matchNode.id]?.height,
            layoutOptions: {
                "elk.algorithm": "layered",
                "elk.direction": "RIGHT",
                "elk.spacing.nodeNode": "30",
                "elk.spacing.edgeEdge": "40",
                "elk.padding": `[top=${topPadding},left=${MATCH_NODE_INNER_PADDING},bottom=${bottomPadding},right=${MATCH_NODE_INNER_PADDING}]`
            },
            children: childNodes,
            edges: childEdges
        };
    }
}
