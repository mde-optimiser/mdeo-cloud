import type { ELK } from "elkjs";
import { sharedImport } from "../sharedImport.js";
import type { ActionDispatcher, GModelElement, GModelRoot } from "@eclipse-glsp/server";
import type { ModelState } from "./modelState.js";
import type { EdgeLayoutMetadata, LayoutOperation, NodePositionMetadata } from "@mdeo/protocol-common";
import type { MetadataEdits } from "./handler/operationHandlerCommand.js";
import type {
    EdgeLabelSpec,
    LayoutAlgorithm,
    LayoutInput,
    LayoutInputEdge,
    LayoutPoint,
    LayoutResult
} from "./layout/layoutModel.js";
import { layoutHierarchy, type HierarchicalLayoutResult } from "./layout/hierarchicalLayout.js";
import { snapLayout, type SnapOptions } from "./layout/layoutSnapping.js";
import { anchorValue, toSegmentRoute } from "./layout/orthogonalRoute.js";
import { layoutForcePositions, layoutLayered, type ElkOptions } from "./layout/elkAlgorithms.js";
import { routeOrthogonally } from "./layout/simpleOrthogonalRouter.js";
import { layoutTala, type TalaOptions } from "./layout/talaAlgorithm.js";
import { joinSharedEdgeEnds } from "./layout/joinSharedEdgeEnds.js";

/**
 * The grid size used for rounding positions and routing points during layout.
 */
export const LAYOUT_GRID_SIZE = 10;

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
const elkjs = sharedImport("elkjs");

/**
 * Workaround to obtain the Elk class from the elkjs module
 */
const Elk = elkjs.default as unknown as typeof elkjs.default.default;

/**
 * Base implementation of a layout engine.
 *
 * Pipeline: {@link buildInput} (language specific) → algorithm (layered / tala / force, applied bottom up per
 * container level) → {@link joinSharedEdgeEnds} → write-back into metadata edits (node positions relative to their
 * container, edge anchors plus orthogonal interior routing points, everything snapped consistently to the grid).
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
        const input = this.buildInput(root, operation);
        const algorithm: LayoutAlgorithm = operation.algorithm ?? "layered";
        const hierarchical = await layoutHierarchy(
            input,
            (level, containerId) => this.layoutLevel(algorithm, level, containerId),
            this.snapOptions()
        );
        const joined = this.postProcess(input, hierarchical.result);
        return this.writeBack(input, hierarchical, joined);
    }

    /**
     * Builds the neutral layout input from the model.
     *
     * @param model the model to transform
     * @param operation the layout operation, provides the client-measured bounds
     * @returns the layout input
     */
    protected abstract buildInput(model: GModelRoot, operation: LayoutOperation): LayoutInput;

    /**
     * Additional ELK layered options for a level, e.g. spacings.
     *
     * @param _containerId the container whose children are laid out, undefined for the top level
     * @returns the options
     */
    protected layeredOptions(_containerId: string | undefined): ElkOptions {
        return {};
    }

    /**
     * Additional options for the force layout of a level.
     *
     * @param _containerId the container whose children are laid out, undefined for the top level
     * @returns the options
     */
    protected forceOptions(_containerId: string | undefined): ElkOptions {
        return {};
    }

    /**
     * Additional options for TALA of a level.
     *
     * @param _containerId the container whose children are laid out, undefined for the top level
     * @returns the options
     */
    protected talaOptions(_containerId: string | undefined): TalaOptions {
        return {};
    }

    /**
     * Lays out one flat level with the given algorithm.
     *
     * @param algorithm the algorithm
     * @param level the flat level
     * @param containerId the container whose children are laid out, undefined for the top level
     * @returns the layout result of the level
     */
    protected async layoutLevel(
        algorithm: LayoutAlgorithm,
        level: LayoutInput,
        containerId: string | undefined
    ): Promise<LayoutResult> {
        if (level.nodes.length === 0) {
            return { nodes: new Map(), edges: new Map() };
        }
        switch (algorithm) {
            case "tala":
                try {
                    return layoutTala(level, this.talaOptions(containerId));
                } catch (error) {
                    // orthoflow unavailable, or TALA gave up on every seed: fall back to the layered layout
                    // eslint-disable-next-line no-console
                    console.error("TALA layout failed, using the layered layout instead", error);
                    return layoutLayered(this.elk, level, this.layeredOptions(containerId));
                }
            case "force": {
                const positions = await layoutForcePositions(this.elk, level, this.forceOptions(containerId));
                return routeOrthogonally(level, positions);
            }
            case "layered":
            default:
                return layoutLayered(this.elk, level, this.layeredOptions(containerId));
        }
    }

    /**
     * Post-processes the absolute layout result: joins edge ends with identical end labels.
     *
     * @param input the layout input
     * @param result the absolute layout result
     * @returns the post-processed result
     */
    protected postProcess(input: LayoutInput, result: LayoutResult): LayoutResult {
        return joinSharedEdgeEnds(input, result, { grid: LAYOUT_GRID_SIZE });
    }

    /**
     * Returns the snap options used for all levels.
     *
     * @returns the snap options
     */
    protected snapOptions(): SnapOptions {
        return {
            grid: LAYOUT_GRID_SIZE,
            alignment: (nodeId) => this.getNodeAlignment(nodeId)
        };
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
     * Converts the absolute result into metadata edits. Every level is snapped (again, idempotently) in its own local
     * coordinates, which are the coordinates the client uses for the metadata of the level's nodes and edges.
     *
     * @param input the layout input
     * @param hierarchical the hierarchical layout result (frames and levels)
     * @param result the final absolute result
     * @returns the metadata edits
     */
    protected async writeBack(
        input: LayoutInput,
        hierarchical: HierarchicalLayoutResult,
        result: LayoutResult
    ): Promise<MetadataEdits> {
        const currentMetadata = await this.modelState.getValidatedMetadata();
        const edits: Required<MetadataEdits> = { nodes: {}, edges: {} };
        const snap = this.snapOptions();

        const levels = new Map<string, { nodeIds: string[]; edges: LayoutInputEdge[] }>();
        const levelOf = (id: string) => {
            if (!levels.has(id)) levels.set(id, { nodeIds: [], edges: [] });
            return levels.get(id)!;
        };
        for (const [nodeId, level] of hierarchical.nodeLevel) {
            levelOf(level).nodeIds.push(nodeId);
        }
        for (const edge of input.edges) {
            const level = hierarchical.edgeLevel.get(edge.id);
            if (level != undefined) {
                levelOf(level).edges.push(edge);
            }
        }

        for (const [levelId, level] of levels) {
            const origin = hierarchical.frames.get(levelId)?.origin ?? { x: 0, y: 0 };
            const toLocal = (p: LayoutPoint) => ({ x: p.x - origin.x, y: p.y - origin.y });
            const local: LayoutResult = { nodes: new Map(), edges: new Map() };
            for (const nodeId of level.nodeIds) {
                const node = result.nodes.get(nodeId);
                if (node != undefined) {
                    local.nodes.set(nodeId, { ...node, ...toLocal(node) });
                }
            }
            for (const edge of level.edges) {
                const route = result.edges.get(edge.id);
                if (route != undefined) {
                    local.edges.set(edge.id, { points: route.points.map(toLocal) });
                }
            }
            const snapped = snapLayout(local, level.edges, snap);
            for (const [nodeId, node] of snapped.nodes) {
                if (nodeId in currentMetadata.nodes) {
                    const meta: NodePositionMetadata = { position: { x: node.x, y: node.y } };
                    edits.nodes[nodeId] = { meta };
                }
            }
            for (const edge of level.edges) {
                if (!(edge.id in currentMetadata.edges)) {
                    continue;
                }
                edits.edges[edge.id] = {
                    meta: this.edgeMetadata(snapped, edge.id, edge.sourceId, edge.targetId)
                };
            }
        }

        // edges that were not laid out (e.g. crossing container borders) are routed by the client
        for (const edge of input.edges) {
            if (!hierarchical.edgeLevel.has(edge.id) && edge.id in currentMetadata.edges) {
                edits.edges[edge.id] = {
                    meta: { routingPoints: [], sourceAnchor: undefined, targetAnchor: undefined }
                };
            }
        }
        return edits;
    }

    /**
     * Creates the edge metadata of a laid out edge: explicit anchors plus the interior points of the route.
     *
     * @param result the (local, snapped) result
     * @param edgeId the edge id
     * @param sourceId the source node id
     * @param targetId the target node id
     * @returns the edge metadata
     */
    protected edgeMetadata(
        result: LayoutResult,
        edgeId: string,
        sourceId: string,
        targetId: string
    ): EdgeLayoutMetadata {
        const points = result.edges.get(edgeId)?.points;
        const source = result.nodes.get(sourceId);
        const target = result.nodes.get(targetId);
        const empty: EdgeLayoutMetadata = { routingPoints: [], sourceAnchor: undefined, targetAnchor: undefined };
        if (points == undefined || points.length < 2 || source == undefined || target == undefined) {
            return empty;
        }
        const route = toSegmentRoute(points, source, target);
        if (route == undefined) {
            return empty;
        }
        const first = route.segments[0].coord;
        const last = route.segments.at(-1)!.coord;
        return {
            routingPoints: points.slice(1, -1).map((p) => ({ x: p.x, y: p.y })),
            sourceAnchor: { side: route.sourceSide, value: anchorValue(route.sourceSide, first, source) },
            targetAnchor: { side: route.targetSide, value: anchorValue(route.targetSide, last, target) }
        };
    }

    /**
     * Returns the text of a label node: the text of the first descendant with a text.
     *
     * @param element the label node
     * @returns the text, empty if there is none
     */
    protected labelText(element: GModelElement): string {
        const stack: GModelElement[] = [element];
        while (stack.length > 0) {
            const current = stack.shift()!;
            const text = (current as { text?: unknown }).text;
            if (typeof text === "string") {
                return text;
            }
            stack.push(...current.children);
        }
        return "";
    }

    /**
     * Creates the label spec of a label node from the client-measured bounds.
     *
     * @param element the label node
     * @param end where on the edge the label is placed
     * @param side the side of the line the client renders the label on
     * @param bounds the client-measured bounds
     * @returns the label spec
     */
    protected labelSpec(
        element: GModelElement,
        end: EdgeLabelSpec["end"],
        side: EdgeLabelSpec["side"],
        bounds: LayoutOperation["bounds"]
    ): EdgeLabelSpec {
        const size = bounds[element.id];
        return {
            id: element.id,
            end,
            side,
            width: Math.max(0, size?.width ?? 0),
            height: Math.max(0, size?.height ?? 0),
            text: this.labelText(element)
        };
    }

    /**
     * Returns the client-measured size of an element (0 if unknown).
     *
     * @param id the element id
     * @param bounds the bounds of the layout operation
     * @returns the size
     */
    protected sizeOf(id: string, bounds: LayoutOperation["bounds"]): { width: number; height: number } {
        const size = bounds[id];
        return { width: Math.max(0, size?.width ?? 0), height: Math.max(0, size?.height ?? 0) };
    }
}
