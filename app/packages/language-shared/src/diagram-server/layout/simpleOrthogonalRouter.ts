import type { LayoutInput, LayoutPoint, LayoutResult, LayoutResultNode } from "./layoutModel.js";
import type { RouteSide } from "./orthogonalRoute.js";

/**
 * A candidate route: its segments (alternating, see SegmentRoute) and the end sides.
 */
interface Candidate {
    /**
     * Orientations and coordinates of the segments, end segment coordinates are filled in later.
     */
    shape: "straight-v" | "straight-h" | "l-vh" | "l-hv" | "z-vhv" | "z-hvh";
    sourceSide: RouteSide;
    targetSide: RouteSide;
    /**
     * Coordinate of the middle segment for z shapes.
     */
    middle?: number;
}

const BEND_PENALTY = 30;
const CROSSING_PENALTY = 10000;

/**
 * Center of a node.
 */
function center(node: LayoutResultNode): LayoutPoint {
    return { x: node.x + node.width / 2, y: node.y + node.height / 2 };
}

/**
 * Whether the axis aligned segment a-b intersects the interior of the node.
 */
function segmentHitsNode(a: LayoutPoint, b: LayoutPoint, node: LayoutResultNode, margin = 2): boolean {
    const minX = Math.min(a.x, b.x);
    const maxX = Math.max(a.x, b.x);
    const minY = Math.min(a.y, b.y);
    const maxY = Math.max(a.y, b.y);
    return (
        maxX > node.x + margin &&
        minX < node.x + node.width - margin &&
        maxY > node.y + margin &&
        minY < node.y + node.height - margin
    );
}

/**
 * Computes the points of a candidate with the given end coordinates.
 */
function candidatePoints(
    candidate: Candidate,
    source: LayoutResultNode,
    target: LayoutResultNode,
    sourceCoord: number,
    targetCoord: number
): LayoutPoint[] {
    const border = (side: RouteSide, coord: number, node: LayoutResultNode): LayoutPoint => {
        switch (side) {
            case "top":
                return { x: coord, y: node.y };
            case "bottom":
                return { x: coord, y: node.y + node.height };
            case "left":
                return { x: node.x, y: coord };
            case "right":
                return { x: node.x + node.width, y: coord };
        }
    };
    const start = border(candidate.sourceSide, sourceCoord, source);
    const end = border(candidate.targetSide, targetCoord, target);
    switch (candidate.shape) {
        case "straight-v":
        case "straight-h":
            return [start, end];
        case "l-vh":
            return [start, { x: start.x, y: end.y }, end];
        case "l-hv":
            return [start, { x: end.x, y: start.y }, end];
        case "z-vhv":
            return [start, { x: start.x, y: candidate.middle! }, { x: end.x, y: candidate.middle! }, end];
        case "z-hvh":
            return [start, { x: candidate.middle!, y: start.y }, { x: candidate.middle!, y: end.y }, end];
    }
}

/**
 * Enumerates the candidate shapes for an edge between two nodes.
 */
function candidates(source: LayoutResultNode, target: LayoutResultNode): Candidate[] {
    const s = center(source);
    const t = center(target);
    const result: Candidate[] = [];
    const down = t.y >= s.y;
    const right = t.x >= s.x;
    const xOverlap = Math.min(source.x + source.width, target.x + target.width) - Math.max(source.x, target.x);
    const yOverlap = Math.min(source.y + source.height, target.y + target.height) - Math.max(source.y, target.y);
    const vGap = down ? target.y - (source.y + source.height) : source.y - (target.y + target.height);
    const hGap = right ? target.x - (source.x + source.width) : source.x - (target.x + target.width);
    if (xOverlap > 20 && vGap > 0) {
        result.push({ shape: "straight-v", sourceSide: down ? "bottom" : "top", targetSide: down ? "top" : "bottom" });
    }
    if (yOverlap > 20 && hGap > 0) {
        result.push({
            shape: "straight-h",
            sourceSide: right ? "right" : "left",
            targetSide: right ? "left" : "right"
        });
    }
    if (vGap > 20) {
        const middle = down ? source.y + source.height + vGap / 2 : target.y + target.height + vGap / 2;
        result.push({
            shape: "z-vhv",
            sourceSide: down ? "bottom" : "top",
            targetSide: down ? "top" : "bottom",
            middle
        });
    }
    if (hGap > 20) {
        const middle = right ? source.x + source.width + hGap / 2 : target.x + target.width + hGap / 2;
        result.push({
            shape: "z-hvh",
            sourceSide: right ? "right" : "left",
            targetSide: right ? "left" : "right",
            middle
        });
    }
    // L shapes: leave vertically, enter horizontally (corner must be outside both nodes)
    if (vGap > -target.height && hGap > 0) {
        result.push({ shape: "l-vh", sourceSide: down ? "bottom" : "top", targetSide: right ? "left" : "right" });
    }
    if (hGap > -target.width && vGap > 0) {
        result.push({ shape: "l-hv", sourceSide: right ? "right" : "left", targetSide: down ? "top" : "bottom" });
    }
    return result;
}

/**
 * Default end coordinate (center of the side).
 */
function sideCenter(side: RouteSide, node: LayoutResultNode): number {
    return side === "top" || side === "bottom" ? node.x + node.width / 2 : node.y + node.height / 2;
}

/**
 * Length of a polyline.
 */
function length(points: LayoutPoint[]): number {
    let total = 0;
    for (let i = 0; i < points.length - 1; i++) {
        total += Math.abs(points[i].x - points[i + 1].x) + Math.abs(points[i].y - points[i + 1].y);
    }
    return total;
}

/**
 * Scores a route: crossings of other nodes dominate, then bends and length.
 */
function score(points: LayoutPoint[], obstacles: LayoutResultNode[], self: LayoutResultNode[]): number {
    let crossings = 0;
    for (let i = 0; i < points.length - 1; i++) {
        for (const obstacle of obstacles) {
            if (segmentHitsNode(points[i], points[i + 1], obstacle)) {
                crossings++;
            }
        }
        // the inner segments must not run through the end nodes either
        if (i > 0 && i < points.length - 2) {
            for (const node of self) {
                if (segmentHitsNode(points[i], points[i + 1], node)) {
                    crossings++;
                }
            }
        }
    }
    if (points.length > 2) {
        for (const node of self) {
            for (const point of points.slice(1, -1)) {
                if (
                    point.x > node.x &&
                    point.x < node.x + node.width &&
                    point.y > node.y &&
                    point.y < node.y + node.height
                ) {
                    crossings++;
                }
            }
        }
    }
    return crossings * CROSSING_PENALTY + (points.length - 2) * BEND_PENALTY + length(points);
}

/**
 * Routes the edges of a level with fixed node positions orthogonally (straight, L or Z shapes, avoiding nodes where
 * one of these shapes can), and spreads the ends attached to the same node side.
 * Used for layouts that only position nodes (force / stress).
 *
 * @param input the flat level
 * @param positions the node positions
 * @returns the result with orthogonal routes
 */
export function routeOrthogonally(input: LayoutInput, positions: LayoutResult): LayoutResult {
    const nodes = positions.nodes;
    const chosen = new Map<string, { candidate: Candidate; source: LayoutResultNode; target: LayoutResultNode }>();
    for (const edge of input.edges) {
        const source = nodes.get(edge.sourceId);
        const target = nodes.get(edge.targetId);
        if (source == undefined || target == undefined || edge.sourceId === edge.targetId) {
            continue;
        }
        const obstacles = [...nodes.entries()]
            .filter(([id]) => id !== edge.sourceId && id !== edge.targetId)
            .map(([, node]) => node);
        let best: Candidate | undefined;
        let bestScore = Infinity;
        for (const candidate of candidates(source, target)) {
            const points = candidatePoints(
                candidate,
                source,
                target,
                sideCenter(candidate.sourceSide, source),
                sideCenter(candidate.targetSide, target)
            );
            const value = score(points, obstacles, [source, target]);
            if (value < bestScore) {
                bestScore = value;
                best = candidate;
            }
        }
        if (best != undefined) {
            chosen.set(edge.id, { candidate: best, source, target });
        }
    }

    // spread the ends on every node side, ordered by the position of the other end
    interface EndRef {
        edgeId: string;
        isSource: boolean;
        order: number;
    }
    const ends = new Map<string, EndRef[]>();
    for (const edge of input.edges) {
        const route = chosen.get(edge.id);
        if (route == undefined) continue;
        const { candidate, source, target } = route;
        const addEnd = (nodeId: string, side: RouteSide, other: LayoutResultNode, isSource: boolean) => {
            const otherCenter = center(other);
            const order = side === "top" || side === "bottom" ? otherCenter.x : otherCenter.y;
            const key = `${nodeId}\u0000${side}`;
            if (!ends.has(key)) ends.set(key, []);
            ends.get(key)!.push({ edgeId: edge.id, isSource, order });
        };
        addEnd(edge.sourceId, candidate.sourceSide, target, true);
        addEnd(edge.targetId, candidate.targetSide, source, false);
    }
    const coords = new Map<string, { source?: number; target?: number }>();
    for (const [key, refs] of ends) {
        const [nodeId, side] = key.split("\u0000") as [string, RouteSide];
        const node = nodes.get(nodeId)!;
        const [min, max] =
            side === "top" || side === "bottom" ? [node.x, node.x + node.width] : [node.y, node.y + node.height];
        refs.sort((a, b) => a.order - b.order);
        refs.forEach((ref, i) => {
            const coord = min + ((i + 1) * (max - min)) / (refs.length + 1);
            const entry = coords.get(ref.edgeId) ?? {};
            if (ref.isSource) entry.source = coord;
            else entry.target = coord;
            coords.set(ref.edgeId, entry);
        });
    }

    const edges = new Map<string, { points: LayoutPoint[] }>();
    for (const [edgeId, { candidate, source, target }] of chosen) {
        const entry = coords.get(edgeId)!;
        let sourceCoord = entry.source!;
        let targetCoord = entry.target!;
        let shape = candidate;
        if (candidate.shape === "straight-v" || candidate.shape === "straight-h") {
            const vertical = candidate.shape === "straight-v";
            const [lo, hi] = vertical
                ? [Math.max(source.x, target.x), Math.min(source.x + source.width, target.x + target.width)]
                : [Math.max(source.y, target.y), Math.min(source.y + source.height, target.y + target.height)];
            const common = (sourceCoord + targetCoord) / 2;
            if (common > lo + 5 && common < hi - 5) {
                sourceCoord = common;
                targetCoord = common;
            } else {
                const middle = vertical
                    ? candidate.sourceSide === "bottom"
                        ? (source.y + source.height + target.y) / 2
                        : (target.y + target.height + source.y) / 2
                    : candidate.sourceSide === "right"
                      ? (source.x + source.width + target.x) / 2
                      : (target.x + target.width + source.x) / 2;
                shape = { ...candidate, shape: vertical ? "z-vhv" : "z-hvh", middle };
            }
        }
        edges.set(edgeId, { points: candidatePoints(shape, source, target, sourceCoord, targetCoord) });
    }
    return { nodes, edges };
}
