import type { ELK, ElkExtendedEdge, ElkNode } from "elkjs";
import { sharedImport } from "../sharedImport.js";
import type { ActionDispatcher, GModelRoot } from "@eclipse-glsp/server";
import type { ModelState } from "./modelState.js";
import type { EdgeLayoutMetadata, LayoutOperation, NodePositionMetadata } from "@mdeo/protocol-common";
import type { MetadataEdits } from "./handler/operationHandlerCommand.js";

/**
 * The grid size used for rounding positions and routing points during layout.
 */
const GRID_SIZE = 10;

/**
 * Rounds a value to the nearest grid position.
 */
function roundToGrid(value: number): number {
    return Math.round(value / GRID_SIZE) * GRID_SIZE;
}

/**
 * Alignment options for a node, determining which point of the node is snapped to the grid.
 */
export interface NodeAlignment {
    /**
     * Vertical alignment: "top" snaps the top edge, "center" snaps the middle, "bottom" snaps the bottom edge.
     */
    vAlign: "top" | "center" | "bottom";
    /**
     * Horizontal alignment: "left" snaps the left edge, "center" snaps the middle, "right" snaps the right edge.
     */
    hAlign: "left" | "center" | "right";
}

const { injectable, inject } = sharedImport("inversify");
const { ModelState: ModelStateKey, ActionDispatcher: ActionDispatcherKey } = sharedImport("@eclipse-glsp/server");
const { Point } = sharedImport("@eclipse-glsp/protocol");
const elkjs = sharedImport("elkjs");

/**
 * Workaround to obtain the Elk class from the elkjs module
 */
const Elk = elkjs.default as unknown as typeof elkjs.default.default;

/**
 * Base implementation of a layout engine using ELK
 */
@injectable()
export abstract class BaseLayoutEngine {
    protected readonly elk: ELK;

    /**
     * The current model state
     */
    @inject(ModelStateKey)
    protected readonly modelState!: ModelState;

    /**
     * The action dispatcher
     */
    @inject(ActionDispatcherKey)
    protected readonly actionDispatcher!: ActionDispatcher;

    constructor() {
        this.elk = new Elk();
    }

    /**
     * Performs layouting for the current model state based on the given operation
     *
     * @param operation the layout operation
     * @returns the metadata edits resulting from the layout
     */
    async layout(operation: LayoutOperation): Promise<MetadataEdits> {
        const root = this.modelState.root;
        const elkNode = this.transformToElk(root, operation);
        const elkLayout = await this.elk.layout(elkNode);
        return this.extractMetadata(elkLayout);
    }

    /**
     * Transforms the given model to an ELK node for layouting
     *
     * @param model the model to transform
     * @param operation the layout operation, provides layout options and bounds
     * @return the transformed ELK node
     */
    protected abstract transformToElk(model: GModelRoot, operation: LayoutOperation): ElkNode;

    /**
     * Extracts layout metadata from the given ELK graph after layouting
     *
     * @param graph the ELK graph to extract metadata from
     * @returns the extracted metadata edits
     */
    protected async extractMetadata(graph: ElkNode): Promise<MetadataEdits> {
        const currentMetadata = await this.modelState.getValidatedMetadata();
        const edits: Required<MetadataEdits> = {
            nodes: {},
            edges: {}
        };
        const traverse = (node: ElkNode) => {
            if (node.id in currentMetadata.nodes) {
                const nodeMeta = this.extractNodeMetadata(node);
                if (nodeMeta != undefined) {
                    edits.nodes[node.id] = { meta: nodeMeta };
                }
            }
            for (const edge of node.edges ?? []) {
                if (!(edge.id in currentMetadata.edges)) {
                    continue;
                }
                const edgeMeta = this.extractEdgeMetadata(edge as ElkExtendedEdge);
                edits.edges[edge.id] = { meta: edgeMeta };
            }
            for (const child of node.children ?? []) {
                traverse(child);
            }
        };
        traverse(graph);
        return edits;
    }

    /**
     * Returns the grid alignment for the given node.
     * The alignment determines which point of the node is snapped to the grid.
     * Defaults to top-left alignment.
     *
     * @param _nodeId the id of the node
     * @returns the alignment for the node
     */
    protected getNodeAlignment(_nodeId: string): NodeAlignment {
        return { vAlign: "top", hAlign: "left" };
    }

    /**
     * Extracts position metadata from the given ELK node, rounding the position to the grid.
     * The point that is rounded is determined by the node's alignment (see {@link getNodeAlignment}).
     *
     * @param node the ELK node to extract metadata from
     * @returns the extracted position metadata, or undefined if no metadata is available
     */
    protected extractNodeMetadata(node: ElkNode): NodePositionMetadata | undefined {
        if (node.x == undefined || node.y == undefined) {
            return undefined;
        }
        const { vAlign, hAlign } = this.getNodeAlignment(node.id);
        const width = node.width ?? 0;
        const height = node.height ?? 0;

        let anchorX = node.x;
        if (hAlign === "center") {
            anchorX += width / 2;
        } else if (hAlign === "right") {
            anchorX += width;
        }

        let anchorY = node.y;
        if (vAlign === "center") {
            anchorY += height / 2;
        } else if (vAlign === "bottom") {
            anchorY += height;
        }

        const roundedAnchorX = roundToGrid(anchorX);
        const roundedAnchorY = roundToGrid(anchorY);

        let x = roundedAnchorX;
        if (hAlign === "center") {
            x -= width / 2;
        } else if (hAlign === "right") {
            x -= width;
        }

        let y = roundedAnchorY;
        if (vAlign === "center") {
            y -= height / 2;
        } else if (vAlign === "bottom") {
            y -= height;
        }

        return { position: { x, y } };
    }

    /**
     * Extracts layout metadata from the given ELK edge, rounding all routing points to the grid.
     *
     * @param edge the ELK edge to extract metadata from
     * @returns the extracted edge layout metadata
     */
    protected extractEdgeMetadata(edge: ElkExtendedEdge): EdgeLayoutMetadata {
        const section = edge.sections?.[0];
        if (section?.bendPoints == undefined) {
            return {
                routingPoints: [],
                sourceAnchor: undefined,
                targetAnchor: undefined
            };
        }
        const points =
            section.bendPoints.length > 0
                ? section.bendPoints.map((pt) => ({ x: roundToGrid(pt.x), y: roundToGrid(pt.y) }))
                : [Point.linear(section.startPoint, section.endPoint, 0.5)].map((pt) => ({
                      x: roundToGrid(pt.x),
                      y: roundToGrid(pt.y)
                  }));
        return {
            routingPoints: points,
            sourceAnchor: undefined,
            targetAnchor: undefined
        };
    }
}
