import type { EdgeLabelSpec, LayoutPoint, LayoutResult, LayoutResultNode } from "./layoutModel.js";
import { endLabelFootprint, endLabelKey } from "./edgeLabelGeometry.js";
import {
    fromSegmentRoute,
    sideSpan,
    simplifyPoints,
    toSegmentRoute,
    type RouteSide,
    type SegmentRoute
} from "./orthogonalRoute.js";

/**
 * Alignment of a node, determining which point of the node is snapped to the grid.
 */
export interface GridAlignment {
    vAlign: "top" | "center" | "bottom";
    hAlign: "left" | "center" | "right";
}

/**
 * An edge to snap: its id and the ids of its end nodes.
 */
export interface SnapEdge {
    id: string;
    sourceId: string;
    targetId: string;
    /**
     * The labels of the edge: ends that share their position only stay joined if their end labels are identical.
     */
    labels?: EdgeLabelSpec[];
}

/**
 * Options for {@link snapLayout}.
 */
export interface SnapOptions {
    /**
     * The grid size.
     */
    grid: number;
    /**
     * Returns the alignment of a node.
     */
    alignment: (nodeId: string) => GridAlignment;
    /**
     * Minimal distance between a node border and the first bend of an edge leaving it.
     */
    minClearance?: number;
}

/**
 * Rounds a value to the grid.
 */
function roundTo(value: number, grid: number): number {
    return Math.round(value / grid) * grid;
}

/**
 * Snaps the alignment point of the node to the grid.
 *
 * @param node the node bounds
 * @param alignment the alignment
 * @param grid the grid size
 * @returns the snapped node bounds
 */
export function snapNode(node: LayoutResultNode, alignment: GridAlignment, grid: number): LayoutResultNode {
    const dx = alignment.hAlign === "center" ? node.width / 2 : alignment.hAlign === "right" ? node.width : 0;
    const dy = alignment.vAlign === "center" ? node.height / 2 : alignment.vAlign === "bottom" ? node.height : 0;
    return {
        x: roundTo(node.x + dx, grid) - dx,
        y: roundTo(node.y + dy, grid) - dy,
        width: node.width,
        height: node.height
    };
}

/**
 * Grid coordinates strictly inside the given span (corners are avoided, because the client router treats
 * anchors at a corner as ambiguous).
 */
function gridCandidates(min: number, max: number, grid: number): number[] {
    const result: number[] = [];
    const start = Math.ceil((min + 0.5) / grid) * grid;
    for (let c = start; c <= max - 0.5; c += grid) {
        result.push(c);
    }
    return result;
}

/**
 * One edge end attached to a node side.
 */
interface EndRef {
    route: SegmentRoute;
    isSource: boolean;
    original: number;
    /**
     * Identifies the end labels; only ends with the same key may share a position.
     */
    labelKey: string;
    /**
     * Extent of the end labels towards smaller coordinates along the side (above / left of the end segment).
     */
    before: number;
    /**
     * Extent of the end labels towards larger coordinates along the side.
     */
    after: number;
}

/**
 * Snaps the nodes of a layout result to the grid and adapts the edge routes, such that
 * - every route stays exactly orthogonal,
 * - the coordinate of every end segment along the node side lies on the grid (the client router projects
 *   the first/last bend onto the node and snaps that projection, so only grid anchors are reproduced exactly),
 * - ends at the same node side stay distinct unless they were identical before (joined ends),
 * - routes do not start inside their nodes after the nodes moved.
 *
 * Non-orthogonal routes are returned unchanged. The function is idempotent.
 *
 * @param result the layout result
 * @param edges the edges of the result
 * @param options the snap options
 * @returns the snapped result
 */
export function snapLayout(result: LayoutResult, edges: SnapEdge[], options: SnapOptions): LayoutResult {
    const { grid } = options;
    const clearance = options.minClearance ?? grid;
    const nodes = new Map<string, LayoutResultNode>();
    for (const [id, node] of result.nodes) {
        nodes.set(id, snapNode(node, options.alignment(id), grid));
    }

    const routes = new Map<string, SegmentRoute>();
    const resultEdges = new Map(result.edges);
    for (const edge of edges) {
        const routeResult = result.edges.get(edge.id);
        const source = result.nodes.get(edge.sourceId);
        const target = result.nodes.get(edge.targetId);
        if (routeResult == undefined || source == undefined || target == undefined) {
            continue;
        }
        const route = toSegmentRoute(routeResult.points, source, target);
        if (route != undefined) {
            routes.set(edge.id, route);
        }
    }

    const ends = new Map<string, EndRef[]>();
    const used = new Map<string, Set<number>>();
    const keyOf = (nodeId: string, side: RouteSide) => `${nodeId}\u0000${side}`;
    const markUsed = (key: string, coord: number) => {
        let set = used.get(key);
        if (set == undefined) {
            set = new Set();
            used.set(key, set);
        }
        set.add(coord);
    };

    // ends of straight edges per node side; ends joined with them (same original coordinate and labels) follow them
    const straightEnds = new Map<string, { original: number; coord: number; labelKey: string }[]>();
    const addStraightEnd = (key: string, original: number, coord: number, labelKey: string | undefined) => {
        if (labelKey == undefined) {
            return;
        }
        const list = straightEnds.get(key) ?? [];
        list.push({ original, coord, labelKey });
        straightEnds.set(key, list);
    };

    // straight edges first: the coordinate must lie in both spans
    for (const edge of edges) {
        const route = routes.get(edge.id);
        if (route == undefined) {
            continue;
        }
        const source = nodes.get(edge.sourceId)!;
        const target = nodes.get(edge.targetId)!;
        if (route.segments.length === 1) {
            const [sMin, sMax] = sideSpan(route.sourceSide, source);
            const [tMin, tMax] = sideSpan(route.targetSide, target);
            const original = route.segments[0].coord;
            const candidates = gridCandidates(Math.max(sMin, tMin), Math.min(sMax, tMax), grid);
            const sourceKey = keyOf(edge.sourceId, route.sourceSide);
            const targetKey = keyOf(edge.targetId, route.targetSide);
            const free = candidates.filter((c) => !used.get(sourceKey)?.has(c) && !used.get(targetKey)?.has(c));
            const pool = free.length > 0 ? free : candidates;
            if (pool.length > 0) {
                const coord = pool.reduce((a, b) => (Math.abs(a - original) <= Math.abs(b - original) ? a : b));
                route.segments[0].coord = coord;
                markUsed(sourceKey, coord);
                markUsed(targetKey, coord);
                const labels = edge.labels ?? [];
                addStraightEnd(sourceKey, original, coord, endLabelKey(labels, "source"));
                addStraightEnd(targetKey, original, coord, endLabelKey(labels, "target"));
                continue;
            }
            // no common grid coordinate any more: turn the straight edge into a Z shape
            const vertical = route.segments[0].orientation === "v";
            let middle: number;
            if (vertical) {
                middle =
                    route.sourceSide === "bottom"
                        ? (source.y + source.height + target.y) / 2
                        : (target.y + target.height + source.y) / 2;
            } else {
                middle =
                    route.sourceSide === "right"
                        ? (source.x + source.width + target.x) / 2
                        : (target.x + target.width + source.x) / 2;
            }
            route.segments = [
                { orientation: vertical ? "v" : "h", coord: original },
                { orientation: vertical ? "h" : "v", coord: middle },
                { orientation: vertical ? "v" : "h", coord: original }
            ];
        }
    }

    for (const edge of edges) {
        const route = routes.get(edge.id);
        if (route == undefined || route.segments.length === 1) {
            continue;
        }
        const sourceKey = keyOf(edge.sourceId, route.sourceSide);
        const targetKey = keyOf(edge.targetId, route.targetSide);
        if (!ends.has(sourceKey)) ends.set(sourceKey, []);
        if (!ends.has(targetKey)) ends.set(targetKey, []);
        const labels = edge.labels ?? [];
        ends.get(sourceKey)!.push({
            route,
            isSource: true,
            original: route.segments[0].coord,
            labelKey: endLabelKey(labels, "source") ?? "",
            ...labelExtents(labels, "source")
        });
        ends.get(targetKey)!.push({
            route,
            isSource: false,
            original: route.segments.at(-1)!.coord,
            labelKey: endLabelKey(labels, "target") ?? "",
            ...labelExtents(labels, "target")
        });
    }

    for (const [key, allRefs] of ends) {
        // ends joined with a straight edge keep sharing its end segment
        const refs: EndRef[] = [];
        for (const ref of allRefs) {
            const straight = straightEnds
                .get(key)
                ?.find((end) => Math.abs(end.original - ref.original) < 0.5 && end.labelKey === ref.labelKey);
            if (straight != undefined) {
                setEndCoord(ref, straight.coord);
            } else {
                refs.push(ref);
            }
        }
        if (refs.length === 0) {
            continue;
        }
        const [nodeId, side] = key.split("\u0000") as [string, RouteSide];
        const node = nodes.get(nodeId)!;
        const [min, max] = sideSpan(side, node);
        const candidates = gridCandidates(min, max, grid);
        if (candidates.length === 0) {
            const center = (min + max) / 2;
            for (const ref of refs) {
                setEndCoord(ref, center);
            }
            continue;
        }
        const usedHere = used.get(key) ?? new Set<number>();
        refs.sort((a, b) => a.original - b.original || a.labelKey.localeCompare(b.labelKey));
        // group ends that were identical and carry the same labels (joined ends share their end segment)
        const groups: EndRef[][] = [];
        for (const ref of refs) {
            const same = groups.find(
                (group) => Math.abs(group[0].original - ref.original) < 0.5 && group[0].labelKey === ref.labelKey
            );
            if (same != undefined) {
                same.push(ref);
            } else {
                groups.push([ref]);
            }
        }
        const free = candidates.filter((c) => !usedHere.has(c));
        // coordinates taken by straight edges are obstacles, keep a label row's distance from them
        const taken = [...usedHere];
        const clearance = Math.max(grid, ...refs.map((r) => r.before + r.after + END_LABEL_SPACING));
        const far = candidates.filter((c) => taken.every((u) => Math.abs(u - c) >= clearance));
        const pool = far.length >= groups.length ? far : free.length >= groups.length ? free : candidates;
        let assigned = assignWithGaps(groups, pool, grid);
        if (assigned != undefined && assigned.some((v) => taken.some((u) => Math.abs(u - v) < clearance))) {
            assigned = undefined;
        }
        assigned ??= assignDistinct(
            groups.map((g) => g[0].original),
            pool
        );
        groups.forEach((group, i) => {
            for (const ref of group) {
                setEndCoord(ref, assigned[i]);
            }
        });
    }

    for (const edge of edges) {
        const route = routes.get(edge.id);
        if (route == undefined) {
            continue;
        }
        const source = nodes.get(edge.sourceId)!;
        const target = nodes.get(edge.targetId)!;
        ensureClearance(route, source, target, clearance);
        const points = simplifyPoints(fromSegmentRoute(route, source, target));
        resultEdges.set(edge.id, { points: points.map(roundPoint) });
    }

    return { nodes, edges: resultEdges };
}

/**
 * Rounds a point to 1/100 px, keeping equal coordinates equal.
 */
function roundPoint(point: LayoutPoint): LayoutPoint {
    return { x: Math.round(point.x * 100) / 100, y: Math.round(point.y * 100) / 100 };
}

/**
 * Sets the coordinate of the end segment of the referenced end.
 */
function setEndCoord(ref: EndRef, coord: number): void {
    if (ref.isSource) {
        ref.route.segments[0].coord = coord;
    } else {
        ref.route.segments[ref.route.segments.length - 1].coord = coord;
    }
}

/**
 * Computes how far the labels of an end extend along the node side, on both sides of the end segment.
 */
function labelExtents(labels: EdgeLabelSpec[], end: "source" | "target"): { before: number; after: number } {
    const atEnd = labels.filter((label) => label.end === end);
    if (atEnd.length === 0) {
        return { before: 0, after: 0 };
    }
    const footprint = endLabelFootprint(atEnd);
    return { before: footprint.left, after: footprint.right };
}

/**
 * Gap between the labels of neighbouring ends.
 */
const END_LABEL_SPACING = 4;

/**
 * Assigns the (sorted) groups of ends candidate coordinates such that the labels of neighbouring ends do not overlap,
 * staying close to the original coordinates (greedy forward pass, then a backward pass if the side end is exceeded).
 *
 * @param groups the sorted groups of ends
 * @param candidates the sorted candidate coordinates
 * @param grid the grid size
 * @returns the assigned coordinates, undefined if they do not fit
 */
function assignWithGaps(groups: EndRef[][], candidates: number[], grid: number): number[] | undefined {
    if (groups.length === 0 || candidates.length === 0) {
        return [];
    }
    const before = groups.map((g) => Math.max(...g.map((r) => r.before)));
    const after = groups.map((g) => Math.max(...g.map((r) => r.after)));
    const gap = (i: number) =>
        Math.max(
            grid,
            Math.ceil((after[i - 1] + before[i] + (after[i - 1] + before[i] > 0 ? END_LABEL_SPACING : 0)) / grid) * grid
        );
    const min = candidates[0];
    const max = candidates.at(-1)!;
    const nearest = (v: number) => Math.min(max, Math.max(min, Math.round(v / grid) * grid));
    const result: number[] = [];
    for (let i = 0; i < groups.length; i++) {
        const wanted = nearest(groups[i][0].original);
        result.push(i === 0 ? wanted : Math.max(wanted, result[i - 1] + gap(i)));
    }
    if (result.at(-1)! > max) {
        result[result.length - 1] = max;
        for (let i = result.length - 2; i >= 0; i--) {
            result[i] = Math.min(result[i], result[i + 1] - gap(i + 1));
        }
        if (result[0] < min) {
            return undefined;
        }
    }
    return result;
}

/**
 * Assigns each (sorted) value a distinct candidate, preserving the order and staying close to the values.
 *
 * @param values the sorted original values
 * @param candidates the sorted candidates
 * @returns the assigned candidates
 */
function assignDistinct(values: number[], candidates: number[]): number[] {
    if (values.length > candidates.length) {
        // not enough room: nearest candidate, duplicates allowed
        return values.map((v) => candidates.reduce((a, b) => (Math.abs(a - v) <= Math.abs(b - v) ? a : b)));
    }
    // dynamic programming: minimise the total displacement with an order preserving injective assignment
    const n = values.length;
    const m = candidates.length;
    const cost: number[][] = Array.from({ length: n + 1 }, () => new Array<number>(m + 1).fill(Infinity));
    const choice: boolean[][] = Array.from({ length: n + 1 }, () => new Array<boolean>(m + 1).fill(false));
    for (let j = 0; j <= m; j++) cost[0][j] = 0;
    for (let i = 1; i <= n; i++) {
        for (let j = i; j <= m; j++) {
            const skip = cost[i][j - 1];
            const take = cost[i - 1][j - 1] + Math.abs(values[i - 1] - candidates[j - 1]);
            if (take <= skip) {
                cost[i][j] = take;
                choice[i][j] = true;
            } else {
                cost[i][j] = skip;
            }
        }
    }
    const result = new Array<number>(n);
    let i = n;
    let j = m;
    while (i > 0) {
        if (choice[i][j]) {
            result[i - 1] = candidates[j - 1];
            i--;
        }
        j--;
    }
    return result;
}

/**
 * Makes sure that the first and last bends lie outside the end nodes (they may have moved while snapping).
 * Only interior segments are moved, end segments are fixed by their anchors.
 */
function ensureClearance(route: SegmentRoute, source: LayoutResultNode, target: LayoutResultNode, clearance: number) {
    const segments = route.segments;
    if (segments.length < 3) {
        return;
    }
    pushOut(segments[1], route.sourceSide, source, clearance);
    pushOut(segments[segments.length - 2], route.targetSide, target, clearance);
}

/**
 * Moves the segment out of the node on the given side if it lies inside or too close.
 */
function pushOut(segment: { coord: number }, side: RouteSide, bounds: LayoutResultNode, clearance: number): void {
    switch (side) {
        case "top":
            if (segment.coord > bounds.y - clearance / 2) segment.coord = bounds.y - clearance;
            break;
        case "bottom":
            if (segment.coord < bounds.y + bounds.height + clearance / 2)
                segment.coord = bounds.y + bounds.height + clearance;
            break;
        case "left":
            if (segment.coord > bounds.x - clearance / 2) segment.coord = bounds.x - clearance;
            break;
        case "right":
            if (segment.coord < bounds.x + bounds.width + clearance / 2)
                segment.coord = bounds.x + bounds.width + clearance;
            break;
    }
}
