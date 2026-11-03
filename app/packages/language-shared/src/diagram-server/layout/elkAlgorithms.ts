import type { ELK, ElkExtendedEdge, ElkLabel, ElkNode } from "elkjs";
import type {
    EdgeLabelSpec,
    LayoutInput,
    LayoutInputEdge,
    LayoutPoint,
    LayoutResult,
    LayoutResultNode
} from "./layoutModel.js";
import { endLabelFootprint, footprintToBox, middleLabelFootprint } from "./edgeLabelGeometry.js";
import type { SegmentOrientation } from "./edgeLabelGeometry.js";
import { uniformEndLabelMargins } from "./labelMargins.js";

/**
 * Layout options of a single ELK run, e.g. spacings, merged over the defaults.
 */
export type ElkOptions = Record<string, string>;

/**
 * Separator for ids of the combined end label of an edge.
 */
const LABEL_ID_SEPARATOR = "\u0000";

/**
 * Pseudo option: reserve end labels as node margins (before / after the node in the layout direction) and port
 * spacing instead of passing them to ELK as end labels. ELK's end label placement stacks the labels of neighbouring
 * ports, which wastes a lot of space between the layers.
 */
export const END_LABELS_AS_MARGINS = "mdeo.endLabelsAsMargins";

/**
 * Space reserved around a node for the end labels of its edges.
 */
interface EndLabelMargin {
    /**
     * Margin before the node in layout direction (top for DOWN, left for RIGHT): ends of incoming edges.
     */
    before: number;
    /**
     * Margin after the node in layout direction: ends of outgoing edges.
     */
    after: number;
    /**
     * Distance between the ports of the node, so that the labels of neighbouring ends fit next to each other.
     */
    portSpacing: number;
}

/**
 * Computes the end label margins of all nodes: incoming edges usually end at the side before the node, outgoing
 * edges start at the side after it.
 *
 * @param input the flat layout input
 * @param orientation orientation of the end segments
 * @returns the margins by node id
 */
function endLabelMargins(input: LayoutInput, orientation: SegmentOrientation): Map<string, EndLabelMargin> {
    const margins = new Map<string, EndLabelMargin>();
    const get = (id: string) => {
        let margin = margins.get(id);
        if (margin == undefined) {
            margin = { before: 0, after: 0, portSpacing: MIN_PORT_SPACING };
            margins.set(id, margin);
        }
        return margin;
    };
    for (const edge of input.edges) {
        for (const end of ["source", "target"] as const) {
            const labels = edge.labels.filter((label) => label.end === end);
            if (labels.length === 0) continue;
            const marker = end === "source" ? edge.sourceMarker : edge.targetMarker;
            const footprint = endLabelFootprint(labels, orientation, marker?.length);
            const margin = get(end === "source" ? edge.sourceId : edge.targetId);
            const along = Math.ceil(footprint.along + END_LABEL_MARGIN_GAP);
            const isFirst = (end === "source") !== (edge.reversed === true);
            if (isFirst) {
                margin.after = Math.max(margin.after, along);
            } else {
                margin.before = Math.max(margin.before, along);
            }
            margin.portSpacing = Math.max(margin.portSpacing, Math.ceil(footprint.across + PORT_LABEL_GAP));
        }
    }
    return margins;
}

/**
 * Minimal distance between two ports of a node.
 */
const MIN_PORT_SPACING = 10;

/**
 * Gap between the end labels of neighbouring ports.
 */
const PORT_LABEL_GAP = 4;

/**
 * Gap between end labels and the edge routing channel between the layers.
 */
const END_LABEL_MARGIN_GAP = 4;

/**
 * Default options of ELK layered.
 */
export const DEFAULT_LAYERED_OPTIONS: ElkOptions = {
    "elk.algorithm": "layered",
    "elk.edgeRouting": "ORTHOGONAL",
    "elk.spacing.nodeNode": "40",
    "elk.layered.spacing.nodeNodeBetweenLayers": "50",
    "elk.spacing.edgeEdge": "20",
    "elk.spacing.edgeNode": "20",
    "elk.layered.spacing.edgeEdgeBetweenLayers": "15",
    "elk.layered.spacing.edgeNodeBetweenLayers": "20",
    "elk.spacing.edgeLabel": "4",
    "elk.spacing.labelLabel": "4",
    "elk.spacing.labelPortHorizontal": "4",
    "elk.spacing.labelPortVertical": "4",
    "elk.edgeLabels.inline": "true",
    "elk.layered.nodePlacement.strategy": "BRANDES_KOEPF",
    "elk.layered.unnecessaryBendpoints": "false",
    "elk.layered.mergeEdges": "false",
    "elk.padding": "[top=0,left=0,bottom=0,right=0]",
    [END_LABELS_AS_MARGINS]: "true"
};

/**
 * Space around each node (in addition to the end labels) for the orthogonal routes of the force layout.
 */
const FORCE_ROUTING_MARGIN = 20;

/**
 * Default options of the force / stress layouts.
 */
export const DEFAULT_FORCE_OPTIONS: ElkOptions = {
    "elk.algorithm": "force",
    "elk.force.iterations": "300",
    "elk.spacing.nodeNode": "40",
    "elk.padding": "[top=0,left=0,bottom=0,right=0]"
};

/**
 * Converts the flow direction into the ELK direction option.
 */
function elkDirection(direction: LayoutInput["direction"]): string {
    return direction === "right" ? "RIGHT" : "DOWN";
}

/**
 * Creates the ELK labels of an edge: one combined label per end (so that e.g. property and multiplicity of one end
 * are reserved together), sized by the shared label geometry, and one combined middle label.
 *
 * @param edge the edge
 * @param endOrientation orientation of the end segments in this layout (vertical for DOWN, horizontal for RIGHT)
 * @param middleOrientation orientation of the segment through the middle label dummy
 * @returns the ELK labels
 */
function elkLabels(
    edge: LayoutInputEdge,
    endOrientation: SegmentOrientation,
    middleOrientation: SegmentOrientation
): ElkLabel[] {
    const labels: ElkLabel[] = [];
    for (const end of ["source", "target"] as const) {
        const endLabels = edge.labels.filter((label) => label.end === end);
        if (endLabels.length === 0) {
            continue;
        }
        const marker = end === "source" ? edge.sourceMarker : edge.targetMarker;
        const footprint = endLabelFootprint(endLabels, endOrientation, marker?.length);
        // the footprint spans both sides of the line, ELK places the box next to it: reserve the wider side twice
        const box = footprintToBox(
            { ...footprint, across: 2 * Math.max(footprint.left, footprint.right) },
            endOrientation,
            true
        );
        if (box.width <= 0 || box.height <= 0) {
            continue;
        }
        labels.push({
            id: `${edge.id}${LABEL_ID_SEPARATOR}${end}`,
            text: endLabels.map((label) => label.text).join(" "),
            width: box.width,
            height: box.height,
            layoutOptions: {
                // HEAD is the end at the target in ELK
                "elk.edgeLabels.placement": end === "target" ? "HEAD" : "TAIL"
            }
        });
    }
    const middle = edge.labels.filter((label) => label.end === "middle");
    if (middle.length > 0) {
        const footprint = middleLabelFootprint(middle, middleOrientation);
        // inline labels are centered on the line: reserve the wider side on both sides
        const across = 2 * Math.max(footprint.left, footprint.right);
        const width = middleOrientation === "horizontal" ? footprint.along : across;
        const height = middleOrientation === "horizontal" ? across : footprint.along;
        if (width > 0 && height > 0) {
            labels.push({
                id: `${edge.id}${LABEL_ID_SEPARATOR}middle`,
                text: middle.map((label) => label.text).join(" "),
                width,
                height,
                layoutOptions: { "elk.edgeLabels.placement": "CENTER" }
            });
        }
    }
    return labels;
}

/**
 * Converts the flat layout input into an ELK graph.
 *
 * @param input the flat layout input
 * @param options the ELK options
 * @param withLabels whether labels are passed to ELK
 * @returns the ELK graph
 */
function toElkGraph(
    input: LayoutInput,
    options: ElkOptions,
    withLabels: boolean
): ElkNode & { inflation?: Map<string, EndLabelMargin> } {
    const direction = elkDirection(input.direction);
    const endOrientation: SegmentOrientation = direction === "DOWN" ? "vertical" : "horizontal";
    const nodeIds = new Set(input.nodes.map((node) => node.id));
    const endLabelsAsMargins = withLabels && options[END_LABELS_AS_MARGINS] === "true";
    const margins = endLabelsAsMargins ? endLabelMargins(input, endOrientation) : undefined;
    const elkOptions = { ...options };
    delete elkOptions[END_LABELS_AS_MARGINS];
    const graph: ElkNode & { inflation?: Map<string, EndLabelMargin> } = {
        id: "\u0000root",
        layoutOptions: { "elk.direction": direction, ...elkOptions },
        children: input.nodes.map((node) => {
            const margin = margins?.get(node.id);
            // the margins are added to the node (ELK layered computes node margins itself)
            const extra = margin != undefined ? margin.before + margin.after : 0;
            return {
                id: node.id,
                width: Math.max(1, node.width) + (direction === "DOWN" ? 0 : extra),
                height: Math.max(1, node.height) + (direction === "DOWN" ? extra : 0),
                layoutOptions: margin != undefined ? { "elk.spacing.portPort": String(margin.portSpacing) } : undefined
            };
        }),
        edges: input.edges
            .filter((edge) => nodeIds.has(edge.sourceId) && nodeIds.has(edge.targetId))
            .map((edge): ElkExtendedEdge => {
                const target = edge.targetMergeKey != undefined ? mergePort(edge) : edge.targetId;
                return {
                    id: edge.id,
                    sources: [edge.reversed ? target : edge.sourceId],
                    targets: [edge.reversed ? edge.sourceId : target],
                    labels: withLabels
                        ? elkLabels(edge, endOrientation, endOrientation).filter(
                              (label) => !endLabelsAsMargins || label.id?.endsWith(`${LABEL_ID_SEPARATOR}middle`)
                          )
                        : []
                };
            })
    };
    // shared ports of merged edge ends
    const ports = new Map<string, Map<string, string>>();
    for (const edge of input.edges) {
        if (edge.targetMergeKey != undefined && nodeIds.has(edge.targetId)) {
            if (!ports.has(edge.targetId)) ports.set(edge.targetId, new Map());
            // the shared end lies after the node in flow direction if the edges are reversed, before it otherwise
            const after = edge.reversed === true;
            const side = direction === "DOWN" ? (after ? "SOUTH" : "NORTH") : after ? "EAST" : "WEST";
            ports.get(edge.targetId)!.set(mergePort(edge), side);
        }
    }
    for (const child of graph.children ?? []) {
        const nodePorts = ports.get(child.id);
        if (nodePorts == undefined) continue;
        child.layoutOptions = { ...child.layoutOptions, "elk.portConstraints": "FIXED_SIDE" };
        child.ports = [...nodePorts].map(([id, side]) => ({
            id,
            width: 0,
            height: 0,
            layoutOptions: { "elk.port.side": side }
        }));
    }
    graph.inflation = margins;
    return graph;
}

/**
 * Returns the id of the shared port of a merged edge end.
 */
function mergePort(edge: LayoutInputEdge): string {
    return `${edge.targetId}${LABEL_ID_SEPARATOR}merge${LABEL_ID_SEPARATOR}${edge.targetMergeKey}`;
}

/**
 * Restores the direction of reversed edges (routes run from source to target).
 *
 * @param result the result
 * @param input the flat input
 */
function unreverse(result: LayoutResult, input: LayoutInput): void {
    for (const edge of input.edges) {
        const route = result.edges.get(edge.id);
        if (edge.reversed && route != undefined) {
            result.edges.set(edge.id, { points: [...route.points].reverse() });
        }
    }
}

/**
 * Removes the end label margins added to the nodes again: the nodes shrink to their real size, and the routes are
 * extended from the inflated border to the real border, keeping them orthogonal.
 *
 * @param result the result with inflated nodes
 * @param input the flat input
 * @param inflation the margins added to the nodes
 * @param vertical whether the margins were added vertically (DOWN) or horizontally (RIGHT)
 */
function deflate(
    result: LayoutResult,
    input: LayoutInput,
    inflation: Map<string, EndLabelMargin>,
    vertical: boolean
): void {
    const inflated = new Map(result.nodes);
    for (const node of input.nodes) {
        const margin = inflation.get(node.id);
        const placed = result.nodes.get(node.id);
        if (margin == undefined || placed == undefined) continue;
        result.nodes.set(node.id, {
            x: placed.x + (vertical ? 0 : margin.before),
            y: placed.y + (vertical ? margin.before : 0),
            width: node.width,
            height: node.height
        });
    }
    for (const edge of input.edges) {
        const route = result.edges.get(edge.id);
        if (route == undefined || route.points.length < 2) continue;
        const points = [...route.points];
        const source = result.nodes.get(edge.sourceId);
        const target = result.nodes.get(edge.targetId);
        const sourceBox = inflated.get(edge.sourceId);
        const targetBox = inflated.get(edge.targetId);
        if (source != undefined && sourceBox != undefined) {
            points.splice(0, 1, ...connectToReal(points[0], points[1], source, sourceBox, vertical));
        }
        if (target != undefined && targetBox != undefined) {
            const n = points.length;
            points.splice(
                n - 1,
                1,
                ...connectToReal(points[n - 1], points[n - 2], target, targetBox, vertical).reverse()
            );
        }
        result.edges.set(edge.id, { points });
    }
}

/**
 * Connects an end point on the inflated node box to the real node: returns the points (from the node outwards)
 * that replace the end point.
 */
function connectToReal(
    end: LayoutPoint,
    next: LayoutPoint,
    real: LayoutResultNode,
    box: LayoutResultNode,
    vertical: boolean
): LayoutPoint[] {
    const eps = 0.5;
    if (vertical) {
        const onTop = Math.abs(end.y - box.y) < eps && Math.abs(next.x - end.x) < eps;
        const onBottom = Math.abs(end.y - (box.y + box.height)) < eps && Math.abs(next.x - end.x) < eps;
        if (onTop) return [{ x: end.x, y: real.y }];
        if (onBottom) return [{ x: end.x, y: real.y + real.height }];
        // attached to the left / right side
        if (end.y >= real.y && end.y <= real.y + real.height) return [end];
        const y =
            end.y < real.y
                ? real.y + Math.min(10, real.height / 2)
                : real.y + real.height - Math.min(10, real.height / 2);
        const offset = Math.abs(end.x - box.x) < eps ? -10 : 10;
        return [
            { x: end.x, y },
            { x: end.x + offset, y },
            { x: end.x + offset, y: end.y }
        ];
    }
    const onLeft = Math.abs(end.x - box.x) < eps && Math.abs(next.y - end.y) < eps;
    const onRight = Math.abs(end.x - (box.x + box.width)) < eps && Math.abs(next.y - end.y) < eps;
    if (onLeft) return [{ x: real.x, y: end.y }];
    if (onRight) return [{ x: real.x + real.width, y: end.y }];
    if (end.x >= real.x && end.x <= real.x + real.width) return [end];
    const x =
        end.x < real.x ? real.x + Math.min(10, real.width / 2) : real.x + real.width - Math.min(10, real.width / 2);
    const offset = Math.abs(end.y - box.y) < eps ? -10 : 10;
    return [
        { x, y: end.y },
        { x, y: end.y + offset },
        { x: end.x, y: end.y + offset }
    ];
}

/**
 * Converts the ELK result back into a layout result.
 *
 * @param graph the laid out ELK graph
 * @returns the layout result
 */
function fromElkGraph(graph: ElkNode): LayoutResult {
    const result: LayoutResult = { nodes: new Map(), edges: new Map() };
    for (const child of graph.children ?? []) {
        result.nodes.set(child.id, {
            x: child.x ?? 0,
            y: child.y ?? 0,
            width: child.width ?? 0,
            height: child.height ?? 0
        });
    }
    for (const edge of graph.edges ?? []) {
        const section = edge.sections?.[0];
        if (section == undefined) {
            continue;
        }
        const points: LayoutPoint[] = [
            { x: section.startPoint.x, y: section.startPoint.y },
            ...(section.bendPoints ?? []).map((p) => ({ x: p.x, y: p.y })),
            { x: section.endPoint.x, y: section.endPoint.y }
        ];
        result.edges.set(edge.id, { points });
    }
    return result;
}

/**
 * Lays out a flat level with ELK layered. Routes are orthogonal and include ELK's start and end points.
 *
 * @param elk the ELK instance
 * @param input the flat layout input
 * @param options additional ELK options
 * @returns the layout result
 */
export async function layoutLayered(elk: ELK, input: LayoutInput, options: ElkOptions = {}): Promise<LayoutResult> {
    const graph = toElkGraph(input, { ...DEFAULT_LAYERED_OPTIONS, ...options }, true);
    const inflation = graph.inflation;
    delete graph.inflation;
    const laidOut = await elk.layout(graph);
    const result = fromElkGraph(laidOut);
    unreverse(result, input);
    if (inflation != undefined) {
        deflate(result, input, inflation, elkDirection(input.direction) === "DOWN");
    }
    return result;
}

/**
 * Lays out a flat level with an ELK force based algorithm (stress or force). Only node positions are used,
 * edges are returned without routes.
 *
 * @param elk the ELK instance
 * @param input the flat layout input
 * @param options additional ELK options
 * @returns the layout result, without edges
 */
export async function layoutForcePositions(
    elk: ELK,
    input: LayoutInput,
    options: ElkOptions = {}
): Promise<LayoutResult> {
    // reserve the end labels (and room for the orthogonal routes) around every node
    const margins = uniformEndLabelMargins(input);
    const inflated: LayoutInput = {
        ...input,
        nodes: input.nodes.map((node) => {
            const margin = (margins.get(node.id) ?? 0) + FORCE_ROUTING_MARGIN;
            return { ...node, width: node.width + 2 * margin, height: node.height + 2 * margin };
        })
    };
    const graph = toElkGraph(inflated, { ...DEFAULT_FORCE_OPTIONS, ...options }, false);
    delete graph.inflation;
    const laidOut = await elk.layout(graph);
    const result = fromElkGraph(laidOut);
    result.edges.clear();
    for (const node of input.nodes) {
        const box = result.nodes.get(node.id);
        if (box == undefined) continue;
        result.nodes.set(node.id, {
            x: box.x + (box.width - node.width) / 2,
            y: box.y + (box.height - node.height) / 2,
            width: node.width,
            height: node.height
        });
    }
    return result;
}

/**
 * Collects all labels of the input, by end.
 *
 * @param edge the edge
 * @param end the end
 * @returns the labels at that end
 */
export function labelsAt(edge: LayoutInputEdge, end: EdgeLabelSpec["end"]): EdgeLabelSpec[] {
    return edge.labels.filter((label) => label.end === end);
}
