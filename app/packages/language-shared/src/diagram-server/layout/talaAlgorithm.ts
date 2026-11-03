import type {
    LayoutInput,
    LayoutInputEdge,
    LayoutMarker,
    LayoutPoint,
    LayoutResult,
    LayoutResultNode
} from "./layoutModel.js";
import { endLabelFootprint, footprintToBox, middleLabelFootprint } from "./edgeLabelGeometry.js";
import { uniformEndLabelMargins } from "./labelMargins.js";

/**
 * Options of a TALA run.
 */
export interface TalaOptions {
    /**
     * The seeds to try (the best result is used). Defaults to orthoflow's default seeds, reduced for big graphs.
     */
    seeds?: number[];
    /**
     * Whether end labels are reserved as margins around the nodes (default) instead of TALA arrowhead labels.
     */
    endLabelMargins?: boolean;
    /**
     * How end labels are reserved as arrowhead labels (without end label margins), see {@link TalaEndLabelBox}.
     */
    endLabelBox?: TalaEndLabelBox;
}

/**
 * The part of the orthoflow API used here.
 */
export interface OrthoflowApi {
    /**
     * Lays out a TALA graphjson graph, trying all seeds, and returns the report as JSON.
     */
    layoutJson(graph: string, options?: { seeds?: number[] }): string;
}

/**
 * Graph size (nodes + edges) above which fewer seeds are tried, to keep the layout interactive.
 */
export const TALA_LARGE_GRAPH_SIZE = 120;

/**
 * Margin added around each node so that TALA keeps edges and labels at a readable distance.
 */
const NODE_MARGIN = 0;

/**
 * How the (rotated, segment aligned) end labels are reserved as TALA's axis aligned arrowhead labels.
 */
export type TalaEndLabelBox = "horizontal" | "vertical" | "square";

/**
 * The default end label reservation.
 */
const DEFAULT_END_LABEL_BOX: TalaEndLabelBox = "horizontal";

/**
 * Serialized TALA label.
 */
interface TalaLabel {
    Text: string;
    Position: string;
    Width: number;
    Height: number;
}

/**
 * Returns the orthoflow API from the plugin context.
 *
 * @returns the API
 */
function orthoflow(): OrthoflowApi {
    const api = (globalThis as { pluginContext?: { orthoflow?: OrthoflowApi } }).pluginContext?.orthoflow;
    if (api == undefined) {
        throw new Error("The TALA layout (orthoflow) is not available in this environment.");
    }
    return api;
}

/**
 * Maps a marker to a TALA arrowhead, so that TALA reserves space for it.
 */
function arrowhead(marker: LayoutMarker | undefined): string {
    if (marker == undefined) {
        return "";
    }
    return marker.length >= 18 ? "filled-diamond" : "triangle";
}

/**
 * Creates the TALA label of one end of an edge (all end labels combined).
 */
function endLabel(edge: LayoutInputEdge, end: "source" | "target", mode: TalaEndLabelBox): TalaLabel | null {
    const labels = edge.labels.filter((label) => label.end === end);
    if (labels.length === 0) {
        return null;
    }
    const marker = end === "source" ? edge.sourceMarker : edge.targetMarker;
    const footprint = endLabelFootprint(labels, "horizontal", marker?.length);
    const horizontal = footprintToBox(footprint, "horizontal", false);
    const size = Math.max(horizontal.width, horizontal.height);
    const box =
        mode === "horizontal"
            ? horizontal
            : mode === "vertical"
              ? { width: horizontal.height, height: horizontal.width }
              : { width: size, height: size };
    if (box.width <= 0 || box.height <= 0) {
        return null;
    }
    return {
        Text: labels.map((label) => label.text).join(" "),
        Position: "",
        Width: Math.ceil(box.width),
        Height: Math.ceil(box.height)
    };
}

/**
 * Creates the TALA label in the middle of an edge.
 */
function middleLabel(edge: LayoutInputEdge): TalaLabel | null {
    const labels = edge.labels.filter((label) => label.end === "middle");
    if (labels.length === 0) {
        return null;
    }
    const footprint = middleLabelFootprint(labels, "horizontal");
    const width = footprint.along;
    const height = 2 * Math.max(footprint.left, footprint.right);
    if (width <= 0 || height <= 0) {
        return null;
    }
    return {
        Text: labels.map((label) => label.text).join(" "),
        Position: "INSIDE_MIDDLE_CENTER",
        Width: Math.ceil(width),
        Height: Math.ceil(height)
    };
}

/**
 * Converts a flat level into TALA graphjson. Ids are mapped to small integers.
 *
 * @param input the flat level
 * @returns the graph json and the id mappings
 */
export function toTalaGraph(
    input: LayoutInput,
    endLabelBox: TalaEndLabelBox = DEFAULT_END_LABEL_BOX
): {
    graph: object;
    nodeIds: Map<number, string>;
    edgeIds: Map<number, string>;
} {
    const nodeIds = new Map<number, string>();
    const nodeNumbers = new Map<string, number>();
    input.nodes.forEach((node, i) => {
        nodeIds.set(i + 1, node.id);
        nodeNumbers.set(node.id, i + 1);
    });
    const edgeIds = new Map<number, string>();
    const edges: object[] = [];
    input.edges.forEach((edge, i) => {
        const from = nodeNumbers.get(edge.sourceId);
        const to = nodeNumbers.get(edge.targetId);
        if (from == undefined || to == undefined) {
            return;
        }
        const id = input.nodes.length + i + 1;
        edgeIds.set(id, edge.id);
        const label = middleLabel(edge);
        edges.push({
            id,
            from,
            to,
            points: [],
            sourceArrowhead: arrowhead(edge.sourceMarker),
            targetArrowhead: arrowhead(edge.targetMarker),
            minWidth: 0,
            minHeight: 0,
            labelPercentage: 0,
            ...(label != null ? { label } : {}),
            ...(endLabel(edge, "source", endLabelBox) != null
                ? { sourceArrowheadLabel: endLabel(edge, "source", endLabelBox) }
                : {}),
            ...(endLabel(edge, "target", endLabelBox) != null
                ? { targetArrowheadLabel: endLabel(edge, "target", endLabelBox) }
                : {})
        });
    });
    const graph = {
        nodes: input.nodes.map((node, i) => ({
            id: i + 1,
            width: Math.max(1, Math.ceil(node.width + 2 * NODE_MARGIN)),
            height: Math.max(1, Math.ceil(node.height + 2 * NODE_MARGIN)),
            topLeft: null,
            shapeType: "Square",
            label: null
        })),
        edges,
        CellSize: 0,
        containers: { "0": input.nodes.map((_, i) => i + 1) },
        crossingCost: 0,
        turnCost: 0,
        nonCenterPortCost: 0
    };
    return { graph, nodeIds, edgeIds };
}

/**
 * Converts a TALA report into a layout result.
 *
 * @param report the report json
 * @param nodeIds the node id mapping
 * @param edgeIds the edge id mapping
 * @returns the layout result
 */
export function fromTalaReport(
    report: string,
    nodeIds: Map<number, string>,
    edgeIds: Map<number, string>,
    sizes?: Map<string, { width: number; height: number }>
): LayoutResult {
    const parsed = JSON.parse(report) as {
        graph: {
            nodes: { id: number; width: number; height: number; topLeft: LayoutPoint | null }[];
            edges: { id: number; points: LayoutPoint[] | null }[];
        } | null;
        seeds?: { error?: string }[];
    };
    if (parsed.graph == null) {
        const error = parsed.seeds?.find((seed) => seed.error != undefined)?.error ?? "unknown error";
        throw new Error(`TALA layout failed: ${error}`);
    }
    const result: LayoutResult = { nodes: new Map(), edges: new Map() };
    for (const node of parsed.graph.nodes) {
        const id = nodeIds.get(node.id);
        if (id == undefined) continue;
        const topLeft = node.topLeft ?? { x: 0, y: 0 };
        // TALA may grow nodes to fit their edge ports; the real node is centered in the grown box
        const size = sizes?.get(id) ?? { width: node.width - 2 * NODE_MARGIN, height: node.height - 2 * NODE_MARGIN };
        result.nodes.set(id, {
            x: topLeft.x + (node.width - size.width) / 2,
            y: topLeft.y + (node.height - size.height) / 2,
            width: size.width,
            height: size.height
        });
    }
    for (const edge of parsed.graph.edges) {
        const id = edgeIds.get(edge.id);
        if (id == undefined || edge.points == null || edge.points.length < 2) continue;
        result.edges.set(id, { points: edge.points.map((p) => ({ x: p.x, y: p.y })) });
    }
    return result;
}

/**
 * Lays out a flat level with TALA (orthoflow).
 *
 * @param input the flat level
 * @param options the options
 * @returns the layout result
 */
export function layoutTala(input: LayoutInput, options: TalaOptions = {}): LayoutResult {
    const inflate = options.endLabelMargins ?? true;
    const margins = inflate ? uniformEndLabelMargins(input) : new Map<string, number>();
    const inflated: LayoutInput = {
        ...input,
        nodes: input.nodes.map((node) => {
            const margin = margins.get(node.id) ?? 0;
            return { ...node, width: node.width + 2 * margin, height: node.height + 2 * margin };
        }),
        // labels are reserved by the margins
        edges: inflate
            ? input.edges.map((edge) => ({ ...edge, labels: edge.labels.filter((l) => l.end === "middle") }))
            : input.edges
    };
    const { graph, nodeIds, edgeIds } = toTalaGraph(inflated, options.endLabelBox);
    const size = input.nodes.length + input.edges.length;
    const preferred = options.seeds ?? (size > TALA_LARGE_GRAPH_SIZE ? [1] : [1, 2, 3]);
    const attempts = [preferred, ...[[2], [3], [4]].filter((s) => !preferred.includes(s[0]))];
    let lastError: unknown;
    for (const seeds of attempts) {
        try {
            const report = orthoflow().layoutJson(JSON.stringify(graph), { seeds });
            // TALA's own boxes (inflated by the margins and possibly grown for ports) are shrunk to the real nodes
            const result = fromTalaReport(report, nodeIds, edgeIds);
            return deflateUniform(result, input, margins);
        } catch (error) {
            // all seeds failed (e.g. TALA's routing work limit): try other seeds
            lastError = error;
        }
    }
    throw lastError;
}

/**
 * Connects an end on the inflated box to the real node. Returns the points (from the node outwards) replacing the end
 * point. Ends whose coordinate along the side lies within the real node are extended straight to the real border;
 * ends in a corner zone of the inflated box turn into the adjacent side of the real node instead of being clamped
 * (which would make them run along the node border).
 *
 * @param end the end point on the inflated box
 * @param next the neighbouring route point
 * @param real the real node bounds
 * @param box the inflated box
 * @returns the replacement points, starting at the real border
 */
function endToReal(
    end: LayoutPoint,
    next: LayoutPoint,
    real: LayoutResultNode | undefined,
    box: LayoutResultNode | undefined
): LayoutPoint[] {
    if (real == undefined || box == undefined) {
        return [end];
    }
    const eps = 0.5;
    const inset = Math.min(CORNER_INSET, real.width / 2, real.height / 2);
    const vertical = Math.abs(end.x - next.x) < eps;
    if (vertical) {
        // the end segment is vertical: the end lies on the top or bottom side of the box
        const top = Math.abs(end.y - box.y) <= Math.abs(end.y - (box.y + box.height));
        const y = top ? real.y : real.y + real.height;
        if (end.x >= real.x + eps && end.x <= real.x + real.width - eps) {
            return [{ x: end.x, y }];
        }
        // corner zone: enter the left / right side instead
        const left = end.x < real.x;
        const x = left ? real.x : real.x + real.width;
        const yIn = top ? real.y + inset : real.y + real.height - inset;
        return [
            { x, y: yIn },
            { x: end.x, y: yIn }
        ];
    }
    const left = Math.abs(end.x - box.x) <= Math.abs(end.x - (box.x + box.width));
    const x = left ? real.x : real.x + real.width;
    if (end.y >= real.y + eps && end.y <= real.y + real.height - eps) {
        return [{ x, y: end.y }];
    }
    const top = end.y < real.y;
    const y = top ? real.y : real.y + real.height;
    const xIn = left ? real.x + inset : real.x + real.width - inset;
    return [
        { x: xIn, y },
        { x: xIn, y: end.y }
    ];
}

/**
 * Moves an end segment whose coordinate along the node side lies outside the real node into it (together with the
 * neighbouring point, which keeps the route orthogonal). Only used for nodes without label margins, where the
 * overshoot comes from TALA growing the node for its ports.
 *
 * @param points the route points (modified)
 * @param index the index of the end point
 * @param neighbour the index of its neighbour
 * @param real the real node
 * @param margin the label margin of the node
 */
function shiftIntoSpan(
    points: LayoutPoint[],
    index: number,
    neighbour: number,
    real: LayoutResultNode | undefined,
    margin: number
): void {
    if (real == undefined || margin > 0 || points.length < 2) {
        return;
    }
    const end = points[index];
    const next = points[neighbour];
    const inset = Math.min(CORNER_INSET, real.width / 2, real.height / 2);
    if (Math.abs(end.x - next.x) < 0.5) {
        const x = Math.min(real.x + real.width - inset, Math.max(real.x + inset, end.x));
        if (x !== end.x) {
            points[index] = { x, y: end.y };
            points[neighbour] = { x, y: next.y };
        }
    } else if (Math.abs(end.y - next.y) < 0.5) {
        const y = Math.min(real.y + real.height - inset, Math.max(real.y + inset, end.y));
        if (y !== end.y) {
            points[index] = { x: end.x, y };
            points[neighbour] = { x: next.x, y };
        }
    }
}

/**
 * Distance from the corner at which corner zone ends enter the real node.
 */
const CORNER_INSET = 10;

/**
 * Shrinks the inflated nodes back to their real size (centered) and extends the routes from the inflated border
 * straight to the real border.
 *
 * @param result the result with inflated nodes
 * @param input the real input
 * @param margins the end label margins added to the nodes
 * @returns the result with real node sizes
 */
function deflateUniform(result: LayoutResult, input: LayoutInput, margins: Map<string, number>): LayoutResult {
    const boxes = new Map(result.nodes);
    for (const node of input.nodes) {
        const box = boxes.get(node.id);
        if (box == undefined) continue;
        result.nodes.set(node.id, {
            x: box.x + (box.width - node.width) / 2,
            y: box.y + (box.height - node.height) / 2,
            width: node.width,
            height: node.height
        });
    }
    for (const edge of input.edges) {
        const route = result.edges.get(edge.id);
        if (route == undefined || route.points.length < 2) continue;
        const points = [...route.points];
        // nodes without label margins were only grown by TALA to fit their ports: shift such ends into the node
        shiftIntoSpan(points, 0, 1, result.nodes.get(edge.sourceId), margins.get(edge.sourceId) ?? 0);
        shiftIntoSpan(
            points,
            points.length - 1,
            points.length - 2,
            result.nodes.get(edge.targetId),
            margins.get(edge.targetId) ?? 0
        );
        const sourceEnd = endToReal(points[0], points[1], result.nodes.get(edge.sourceId), boxes.get(edge.sourceId));
        const targetEnd = endToReal(
            points[points.length - 1],
            points[points.length - 2],
            result.nodes.get(edge.targetId),
            boxes.get(edge.targetId)
        );
        points.splice(0, 1, ...sourceEnd);
        points.splice(points.length - 1, 1, ...targetEnd.reverse());
        result.edges.set(edge.id, { points });
    }
    return result;
}
