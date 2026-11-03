import type { LayoutInput, LayoutInputEdge, LayoutPoint, LayoutResult, LayoutResultNode } from "./layoutModel.js";
import { endLabelFootprint, endLabelKey, placeEdgeLabels } from "./edgeLabelGeometry.js";

/**
 * Side of a node.
 */
type NodeSide = "top" | "bottom" | "left" | "right";

/**
 * Options of the joining post-process.
 */
export interface JoinSharedEdgeEndsOptions {
    /**
     * Margin between the end labels and the junction point (along the trunk).
     */
    margin?: number;
    /**
     * Minimal distance of the junction point from the node border.
     */
    minJunctionDistance?: number;
    /**
     * The junction distance is rounded up to a multiple of this value (0 disables rounding).
     */
    grid?: number;
}

/**
 * One edge end that is a candidate for joining.
 */
interface EndCandidate {
    edgeId: string;
    end: "source" | "target";
    nodeId: string;
    side: NodeSide;
    /**
     * Coordinate of the endpoint along the side (x for top / bottom, y for left / right).
     */
    lateral: number;
    edge: LayoutInputEdge;
}

/**
 * Number of route points at the start and at the end of a route that must not be changed any more because they belong
 * to a trunk that was already joined.
 */
interface LockedPoints {
    start: number;
    end: number;
}

const EPSILON = 1e-6;
const DEFAULT_MARGIN = 10;
const DEFAULT_MIN_JUNCTION_DISTANCE = 20;
const DEFAULT_GRID = 10;
/**
 * Free space required next to the labels of a joined end towards ends that are not part of the group.
 */
const NEIGHBOUR_MARGIN = 6;
/**
 * How much further out than the minimal distance the junction may be placed to reuse an existing segment as the bus.
 */
const MAX_EXTRA_JUNCTION_DISTANCE = 40;

/**
 * Joins edge ends that arrive at the same side of the same node and carry an identical set of end labels, so that
 * the labels coincide (and the client renders them only once).
 *
 * For each group of two or more such ends, one shared anchor on that side is chosen (the side's center if it is free,
 * otherwise the median of the current anchors), plus a junction point straight out from the anchor at a distance of
 * the label footprint along the segment plus a margin. Every route of the group keeps its course up to the point
 * where it reaches the junction's distance from the node, then runs parallel to the side to the junction, and the
 * final segment junction -> anchor is shared. Ends whose rewrite would run through other nodes or would be a long
 * detour stay as they are; if fewer than two ends remain, the group is not joined.
 *
 * Ends on different sides of a node are never joined. Ends without labels never join.
 *
 * @param input the layout input (labels, markers, node hierarchy)
 * @param result the layout result (absolute coordinates)
 * @param options optional tuning
 * @returns a new layout result with rewritten edge routes (nodes are unchanged)
 */
export function joinSharedEdgeEnds(
    input: LayoutInput,
    result: LayoutResult,
    options: JoinSharedEdgeEndsOptions = {}
): LayoutResult {
    const margin = options.margin ?? DEFAULT_MARGIN;
    const minJunctionDistance = options.minJunctionDistance ?? DEFAULT_MIN_JUNCTION_DISTANCE;
    const grid = options.grid ?? DEFAULT_GRID;

    const routes = new Map<string, LayoutPoint[]>();
    for (const [id, edge] of result.edges) {
        routes.set(
            id,
            edge.points.map((point) => ({ x: point.x, y: point.y }))
        );
    }
    const locked = new Map<string, LockedPoints>();
    const parents = new Map<string, string | undefined>();
    for (const node of input.nodes) {
        parents.set(node.id, node.parentId);
    }

    const groups = collectGroups(input, result, routes);
    for (const group of groups) {
        joinGroup(group, input, result, routes, locked, parents, { margin, minJunctionDistance, grid });
    }

    const edges = new Map(result.edges);
    for (const [id, points] of routes) {
        const original = result.edges.get(id);
        if (original != undefined && points !== original.points) {
            edges.set(id, { ...original, points });
        }
    }
    return { nodes: result.nodes, edges };
}

/**
 * Collects all groups of edge ends with identical end labels on the same node side.
 *
 * @param input the layout input
 * @param result the layout result
 * @param routes the current routes
 * @returns the groups (each with at least two ends), in a deterministic order
 */
function collectGroups(input: LayoutInput, result: LayoutResult, routes: Map<string, LayoutPoint[]>): EndCandidate[][] {
    const groups = new Map<string, EndCandidate[]>();
    const sortedEdges = [...input.edges].sort((a, b) => compareIds(a.id, b.id));
    for (const edge of sortedEdges) {
        const route = routes.get(edge.id);
        if (route == undefined || route.length < 2 || edge.sourceId === edge.targetId) {
            continue;
        }
        for (const end of ["source", "target"] as const) {
            const labelKey = endLabelKey(edge.labels, end);
            if (labelKey == undefined) {
                continue;
            }
            const nodeId = end === "source" ? edge.sourceId : edge.targetId;
            const node = result.nodes.get(nodeId);
            if (node == undefined) {
                continue;
            }
            const oriented = orientToNode(route, end);
            const side = detectSide(oriented, node);
            if (side == undefined) {
                continue;
            }
            const endpoint = oriented[oriented.length - 1];
            const marker = (end === "source" ? edge.sourceMarker : edge.targetMarker)?.length ?? 0;
            const key = JSON.stringify([nodeId, side, labelKey, marker]);
            const candidate: EndCandidate = {
                edgeId: edge.id,
                end,
                nodeId,
                side,
                lateral: isVerticalSide(side) ? endpoint.x : endpoint.y,
                edge
            };
            const group = groups.get(key) ?? [];
            group.push(candidate);
            groups.set(key, group);
        }
    }
    return [...groups.entries()]
        .filter(([, group]) => group.length >= 2)
        .sort(([a], [b]) => compareIds(a, b))
        .map(([, group]) => group);
}

/**
 * Joins one group of edge ends, if possible.
 *
 * @param group the ends
 * @param input the layout input
 * @param result the layout result
 * @param routes the current routes (updated in place)
 * @param locked the locked route points (updated in place)
 * @param parents the parent of each node
 * @param options the options
 */
function joinGroup(
    group: EndCandidate[],
    input: LayoutInput,
    result: LayoutResult,
    routes: Map<string, LayoutPoint[]>,
    locked: Map<string, LockedPoints>,
    parents: Map<string, string | undefined>,
    options: Required<JoinSharedEdgeEndsOptions>
): void {
    const first = group[0];
    const node = result.nodes.get(first.nodeId)!;
    const side = first.side;
    const markerLength = (first.end === "source" ? first.edge.sourceMarker : first.edge.targetMarker)?.length ?? 0;
    const endLabels = first.edge.labels.filter((label) => label.end === first.end);
    const footprint = endLabelFootprint(endLabels, isVerticalSide(side) ? "vertical" : "horizontal", markerLength);
    let distance = Math.max(options.minJunctionDistance, footprint.along + options.margin);
    if (options.grid > 0) {
        distance = Math.ceil(distance / options.grid - EPSILON) * options.grid;
    }

    const memberKeys = new Set(group.map((member) => `${member.edgeId}:${member.end}`));
    const otherEnds = collectOtherEnds(input, result, routes, first.nodeId, side, memberKeys);
    const otherLaterals = otherEnds.map((other) => other.lateral);
    const labelObstacles = collectLabelObstacles(input, routes, memberKeys);
    const obstacles = [...collectObstacles(result, parents, first.nodeId), ...labelObstacles];
    const foreignSegments = collectSegments(input, routes, new Set(group.map((member) => member.edgeId)));
    const container = parentRect(result, parents, first.nodeId);

    const sideMin = isVerticalSide(side) ? node.x : node.y;
    const sideMax = isVerticalSide(side) ? node.x + node.width : node.y + node.height;
    const laterals = group.map((member) => member.lateral).sort((a, b) => a - b);
    const median = laterals[Math.floor((laterals.length - 1) / 2)];
    const center = Math.round((sideMin + sideMax) / 2);

    // the center and the median first; then each member's anchor and the positions right next to other ends, so a
    // foreign end between the members (whose labels block the center and the median) can be avoided
    const candidates: number[] = [];
    const addCandidate = (value: number) => {
        if (
            value >= sideMin - EPSILON &&
            value <= sideMax + EPSILON &&
            !candidates.some((existing) => Math.abs(existing - value) < EPSILON) &&
            isLateralFree(value, footprint.left, footprint.right, otherLaterals)
        ) {
            candidates.push(value);
        }
    };
    addCandidate(center);
    if (!candidates.some((value) => Math.abs(value - median) < EPSILON)) {
        candidates.push(median);
    }
    for (const lateral of laterals) {
        addCandidate(lateral);
    }
    for (const other of otherLaterals) {
        for (const direction of [-1, 1]) {
            const clearance = (direction < 0 ? footprint.right : footprint.left) + 3 * NEIGHBOUR_MARGIN;
            let value = other + direction * clearance;
            if (options.grid > 0) {
                value = (direction < 0 ? Math.floor : Math.ceil)(value / options.grid) * options.grid;
            }
            addCandidate(value);
        }
    }

    const distances = junctionDistanceCandidates(group, routes, node, distance);
    // distances that let the bus run beyond the labels of neighbouring ends
    for (const other of otherEnds) {
        if (other.along > 0) {
            let clear = other.along + options.margin;
            if (options.grid > 0) {
                clear = Math.ceil(clear / options.grid - EPSILON) * options.grid;
            }
            if (clear > distance && !distances.includes(clear)) {
                distances.push(clear);
            }
        }
    }
    distances.sort((a, b) => a - b);

    let best: { rewrites: Map<EndCandidate, LayoutPoint[]>; score: number[] } | undefined;
    for (const lateral of candidates) {
        if (lateral < sideMin - EPSILON || lateral > sideMax + EPSILON) {
            continue;
        }
        const anchor = pointOnSide(node, side, lateral);
        const normal = sideNormal(side);
        for (const junctionDistance of distances) {
            const junction = { x: anchor.x + normal.x * junctionDistance, y: anchor.y + normal.y * junctionDistance };
            if (!isSegmentFree(anchor, junction, obstacles) || !isInside(junction, container)) {
                continue;
            }
            // the joined labels must not be crossed by other edges
            const trunk = first.end === "source" ? [anchor, junction] : [junction, anchor];
            const joinedRects = placeEdgeLabels(trunk, endLabels, { [first.end]: { length: markerLength } }).map(
                (label) => label.bounds
            );
            if (foreignSegments.some(([a, b]) => !isSegmentFree(a, b, joinedRects))) {
                continue;
            }
            const rewrites = new Map<EndCandidate, LayoutPoint[]>();
            let added = 0;
            let bends = 0;
            for (const member of group) {
                const route = routes.get(member.edgeId)!;
                const lock = locked.get(member.edgeId) ?? { start: 0, end: 0 };
                const rewritten = rewriteEnd(
                    route,
                    member.end,
                    anchor,
                    junction,
                    normal,
                    junctionDistance,
                    lock,
                    obstacles,
                    container
                );
                if (rewritten != undefined) {
                    rewrites.set(member, rewritten.points);
                    added += rewritten.added;
                    bends += rewritten.points.length;
                }
            }
            if (rewrites.size < 2) {
                continue;
            }
            // prefer joining more ends, then fewer bends, then shorter routes
            const score = [-rewrites.size, bends, added];
            if (best == undefined || compareScores(score, best.score) < 0) {
                best = { rewrites, score };
            }
        }
    }

    if (best == undefined) {
        return;
    }
    for (const [member, points] of best.rewrites) {
        routes.set(member.edgeId, points);
        const lock = locked.get(member.edgeId) ?? { start: 0, end: 0 };
        // the trunk consists of the last two points (junction, anchor) at that end
        if (member.end === "source") {
            lock.start = 2;
        } else {
            lock.end = 2;
        }
        locked.set(member.edgeId, lock);
    }
}

/**
 * Computes the candidate distances of the junction from the node border: the minimal distance, plus the distances of
 * segments of the members that run parallel to the side slightly further out (using such a segment as the bus
 * avoids small jogs).
 *
 * @param group the ends
 * @param routes the current routes
 * @param node the node
 * @param minDistance the minimal junction distance
 * @returns the candidate distances, ascending
 */
function junctionDistanceCandidates(
    group: EndCandidate[],
    routes: Map<string, LayoutPoint[]>,
    node: LayoutResultNode,
    minDistance: number
): number[] {
    const side = group[0].side;
    const normal = sideNormal(side);
    const border = pointOnSide(node, side, 0);
    const depth = (point: LayoutPoint) => (point.x - border.x) * normal.x + (point.y - border.y) * normal.y;
    const result = new Set<number>([minDistance]);
    for (const member of group) {
        const oriented = orientToNode(routes.get(member.edgeId)!, member.end);
        for (let i = 0; i < oriented.length - 1; i++) {
            const a = oriented[i];
            const b = oriented[i + 1];
            const parallel = isVerticalSide(side) ? Math.abs(a.y - b.y) < EPSILON : Math.abs(a.x - b.x) < EPSILON;
            const value = depth(a);
            if (parallel && value > minDistance + EPSILON && value <= minDistance + MAX_EXTRA_JUNCTION_DISTANCE) {
                result.add(Math.round(value * 1000) / 1000);
            }
        }
    }
    return [...result].sort((a, b) => a - b);
}

/**
 * Compares two scores lexicographically.
 *
 * @param a the first score
 * @param b the second score
 * @returns negative if a is better
 */
function compareScores(a: number[], b: number[]): number {
    for (let i = 0; i < a.length; i++) {
        if (Math.abs(a[i] - b[i]) > EPSILON) {
            return a[i] - b[i];
        }
    }
    return 0;
}

/**
 * Rewrites the route of one edge so that its end at the node runs through the junction and ends at the anchor.
 *
 * @param route the current route (source -> target)
 * @param end the end to rewrite
 * @param anchor the shared anchor on the node border
 * @param junction the junction point
 * @param normal the outward normal of the node side
 * @param distance the distance of the junction from the border
 * @param lock the locked points of the route
 * @param obstacles rectangles the new segments must not cross
 * @param container the rectangle the new points must stay in (the parent of the node), if any
 * @returns the new route (source -> target) and the added length, or undefined if the end cannot be rewritten
 */
function rewriteEnd(
    route: LayoutPoint[],
    end: "source" | "target",
    anchor: LayoutPoint,
    junction: LayoutPoint,
    normal: LayoutPoint,
    distance: number,
    lock: LockedPoints,
    obstacles: Rect[],
    container: Rect | undefined
): { points: LayoutPoint[]; added: number } | undefined {
    const oriented = orientToNode(route, end);
    // points at the start of `oriented` that must be kept (the trunk at the other end)
    const keep = end === "source" ? lock.end : lock.start;
    const depth = (point: LayoutPoint) => (point.x - anchor.x) * normal.x + (point.y - anchor.y) * normal.y;

    let k = -1;
    for (let i = oriented.length - 2; i >= 0; i--) {
        if (depth(oriented[i]) >= distance - EPSILON) {
            k = i;
            break;
        }
    }
    if (k < 0 || k < keep - 1) {
        return undefined;
    }
    const from = oriented[k];
    const to = oriented[k + 1];
    const depthFrom = depth(from);
    const depthTo = depth(to);
    const t = depthFrom - depthTo > EPSILON ? (depthFrom - distance) / (depthFrom - depthTo) : 0;
    const crossing = {
        x: from.x + (to.x - from.x) * t,
        y: from.y + (to.y - from.y) * t
    };
    // snap the crossing exactly onto the junction line to keep the bus segment orthogonal
    if (Math.abs(normal.y) > 0) {
        crossing.y = junction.y;
        if (Math.abs(to.x - from.x) < EPSILON) {
            crossing.x = from.x;
        }
    } else {
        crossing.x = junction.x;
        if (Math.abs(to.y - from.y) < EPSILON) {
            crossing.y = from.y;
        }
    }

    if (!isSegmentFree(crossing, junction, obstacles) || !isInside(crossing, container)) {
        return undefined;
    }

    const rewritten = simplify([...oriented.slice(0, k + 1), crossing, junction, anchor]);
    if (rewritten.length < 2) {
        return undefined;
    }
    const added = routeLength(rewritten) - routeLength(oriented);
    const maxDetour = Math.max(2 * distance + 40, 0.3 * routeLength(oriented));
    if (added > maxDetour) {
        return undefined;
    }
    return { points: end === "source" ? rewritten.reverse() : rewritten, added };
}

/**
 * An axis aligned rectangle.
 */
interface Rect {
    x: number;
    y: number;
    width: number;
    height: number;
}

/**
 * Collects the rectangles that new segments at the given node must not cross: all nodes except the node itself and
 * its ancestors.
 *
 * @param result the layout result
 * @param parents the parent of each node
 * @param nodeId the node
 * @returns the obstacles
 */
function collectObstacles(result: LayoutResult, parents: Map<string, string | undefined>, nodeId: string): Rect[] {
    const excluded = new Set<string>([nodeId]);
    let parent = parents.get(nodeId);
    while (parent != undefined && !excluded.has(parent)) {
        excluded.add(parent);
        parent = parents.get(parent);
    }
    const obstacles: Rect[] = [];
    for (const [id, node] of result.nodes) {
        if (!excluded.has(id)) {
            obstacles.push(node);
        }
    }
    return obstacles;
}

/**
 * Returns the rectangle of the parent of a node, if it has one.
 *
 * @param result the layout result
 * @param parents the parent of each node
 * @param nodeId the node
 * @returns the parent rectangle or undefined
 */
function parentRect(result: LayoutResult, parents: Map<string, string | undefined>, nodeId: string): Rect | undefined {
    const parentId = parents.get(nodeId);
    return parentId != undefined ? result.nodes.get(parentId) : undefined;
}

/**
 * Collects the edge ends on the given node side that are not part of the group: their lateral position and the
 * extent of their labels along the end segment.
 *
 * @param input the layout input
 * @param result the layout result
 * @param routes the current routes
 * @param nodeId the node
 * @param side the side
 * @param memberKeys keys (`edgeId:end`) of the group members
 * @returns the other ends
 */
function collectOtherEnds(
    input: LayoutInput,
    result: LayoutResult,
    routes: Map<string, LayoutPoint[]>,
    nodeId: string,
    side: NodeSide,
    memberKeys: Set<string>
): { lateral: number; along: number }[] {
    const node = result.nodes.get(nodeId)!;
    const ends: { lateral: number; along: number }[] = [];
    for (const edge of input.edges) {
        const route = routes.get(edge.id);
        if (route == undefined || route.length < 2) {
            continue;
        }
        for (const end of ["source", "target"] as const) {
            if ((end === "source" ? edge.sourceId : edge.targetId) !== nodeId || memberKeys.has(`${edge.id}:${end}`)) {
                continue;
            }
            const oriented = orientToNode(route, end);
            if (detectSide(oriented, node) !== side) {
                continue;
            }
            const endpoint = oriented[oriented.length - 1];
            const marker = (end === "source" ? edge.sourceMarker : edge.targetMarker)?.length;
            const labels = edge.labels.filter((label) => label.end === end);
            ends.push({
                lateral: isVerticalSide(side) ? endpoint.x : endpoint.y,
                along: labels.length > 0 ? endLabelFootprint(labels, "vertical", marker).along : 0
            });
        }
    }
    return ends;
}

/**
 * Collects the boxes of all edge labels as obstacles, except the labels of the group members' joined ends.
 *
 * @param input the layout input
 * @param routes the current routes
 * @param memberKeys keys (`edgeId:end`) of the group members
 * @returns the label boxes
 */
function collectLabelObstacles(
    input: LayoutInput,
    routes: Map<string, LayoutPoint[]>,
    memberKeys: Set<string>
): Rect[] {
    const rects: Rect[] = [];
    for (const edge of input.edges) {
        const route = routes.get(edge.id);
        if (route == undefined || route.length < 2) {
            continue;
        }
        const labels = edge.labels.filter(
            (label) => label.end === "middle" || !memberKeys.has(`${edge.id}:${label.end}`)
        );
        if (labels.length === 0) {
            continue;
        }
        const placed = placeEdgeLabels(route, labels, {
            source: edge.sourceMarker != undefined ? { length: edge.sourceMarker.length } : undefined,
            target: edge.targetMarker != undefined ? { length: edge.targetMarker.length } : undefined
        });
        placed.forEach((label) => rects.push(label.bounds));
    }
    return rects;
}

/**
 * Collects all route segments of the edges that are not in the given set.
 *
 * @param input the layout input
 * @param routes the current routes
 * @param excluded ids of edges whose segments are skipped
 * @returns the segments
 */
function collectSegments(
    input: LayoutInput,
    routes: Map<string, LayoutPoint[]>,
    excluded: Set<string>
): [LayoutPoint, LayoutPoint][] {
    const segments: [LayoutPoint, LayoutPoint][] = [];
    for (const edge of input.edges) {
        const route = routes.get(edge.id);
        if (route == undefined || excluded.has(edge.id)) {
            continue;
        }
        for (let i = 0; i < route.length - 1; i++) {
            segments.push([route[i], route[i + 1]]);
        }
    }
    return segments;
}

/**
 * Checks whether the labels of a joined end at the given lateral position keep clear of other ends on the side.
 *
 * @param lateral the lateral position of the anchor
 * @param left the label extent on the left side (towards smaller coordinates)
 * @param right the label extent on the right side (towards larger coordinates)
 * @param others the lateral positions of the other ends
 * @returns true if the position is free
 */
function isLateralFree(lateral: number, left: number, right: number, others: number[]): boolean {
    const min = lateral - Math.max(left, NEIGHBOUR_MARGIN) - NEIGHBOUR_MARGIN;
    const max = lateral + Math.max(right, NEIGHBOUR_MARGIN) + NEIGHBOUR_MARGIN;
    return others.every((other) => other <= min || other >= max);
}

/**
 * Returns the route so that it ends at the given end.
 *
 * @param route the route (source -> target)
 * @param end the end
 * @returns the route ending at that end (a copy if reversed)
 */
function orientToNode(route: LayoutPoint[], end: "source" | "target"): LayoutPoint[] {
    return end === "target" ? route : [...route].reverse();
}

/**
 * Detects on which side of the node a route (oriented to end at the node) arrives.
 *
 * @param oriented the route ending at the node
 * @param node the node
 * @returns the side or undefined if the route has no extent
 */
function detectSide(oriented: LayoutPoint[], node: LayoutResultNode): NodeSide | undefined {
    const endpoint = oriented[oriented.length - 1];
    const distances: [NodeSide, number][] = [
        ["top", Math.abs(endpoint.y - node.y)],
        ["bottom", Math.abs(endpoint.y - (node.y + node.height))],
        ["left", Math.abs(endpoint.x - node.x)],
        ["right", Math.abs(endpoint.x - (node.x + node.width))]
    ];
    distances.sort((a, b) => a[1] - b[1]);
    if (distances[1][1] - distances[0][1] > 0.5) {
        return distances[0][0];
    }
    // ambiguous (corner): use the direction of the end segment
    for (let i = oriented.length - 2; i >= 0; i--) {
        const dx = oriented[i].x - endpoint.x;
        const dy = oriented[i].y - endpoint.y;
        if (Math.abs(dx) > EPSILON || Math.abs(dy) > EPSILON) {
            if (Math.abs(dy) >= Math.abs(dx)) {
                return dy < 0 ? "top" : "bottom";
            }
            return dx < 0 ? "left" : "right";
        }
    }
    return undefined;
}

/**
 * Whether the side is the top or bottom side (the end segment is vertical).
 *
 * @param side the side
 * @returns true for top / bottom
 */
function isVerticalSide(side: NodeSide): boolean {
    return side === "top" || side === "bottom";
}

/**
 * The outward unit normal of a node side.
 *
 * @param side the side
 * @returns the normal
 */
function sideNormal(side: NodeSide): LayoutPoint {
    switch (side) {
        case "top":
            return { x: 0, y: -1 };
        case "bottom":
            return { x: 0, y: 1 };
        case "left":
            return { x: -1, y: 0 };
        case "right":
            return { x: 1, y: 0 };
    }
}

/**
 * The point on a node side at a lateral position.
 *
 * @param node the node
 * @param side the side
 * @param lateral x for top / bottom, y for left / right
 * @returns the point
 */
function pointOnSide(node: LayoutResultNode, side: NodeSide, lateral: number): LayoutPoint {
    switch (side) {
        case "top":
            return { x: lateral, y: node.y };
        case "bottom":
            return { x: lateral, y: node.y + node.height };
        case "left":
            return { x: node.x, y: lateral };
        case "right":
            return { x: node.x + node.width, y: lateral };
    }
}

/**
 * Checks that an axis aligned segment does not cross the interior of any obstacle.
 *
 * @param a the start
 * @param b the end
 * @param obstacles the obstacles
 * @returns true if the segment is free
 */
function isSegmentFree(a: LayoutPoint, b: LayoutPoint, obstacles: Rect[]): boolean {
    const minX = Math.min(a.x, b.x);
    const maxX = Math.max(a.x, b.x);
    const minY = Math.min(a.y, b.y);
    const maxY = Math.max(a.y, b.y);
    return obstacles.every(
        (rect) =>
            maxX <= rect.x + 0.5 ||
            minX >= rect.x + rect.width - 0.5 ||
            maxY <= rect.y + 0.5 ||
            minY >= rect.y + rect.height - 0.5
    );
}

/**
 * Checks that a point lies inside a rectangle (always true without a rectangle).
 *
 * @param point the point
 * @param rect the rectangle
 * @returns true if inside
 */
function isInside(point: LayoutPoint, rect: Rect | undefined): boolean {
    if (rect == undefined) {
        return true;
    }
    return (
        point.x >= rect.x - EPSILON &&
        point.x <= rect.x + rect.width + EPSILON &&
        point.y >= rect.y - EPSILON &&
        point.y <= rect.y + rect.height + EPSILON
    );
}

/**
 * Removes duplicate consecutive points and points in the middle of straight lines.
 *
 * @param points the points
 * @returns the simplified points
 */
function simplify(points: LayoutPoint[]): LayoutPoint[] {
    const deduped: LayoutPoint[] = [];
    for (const point of points) {
        const last = deduped[deduped.length - 1];
        if (last == undefined || Math.abs(last.x - point.x) > EPSILON || Math.abs(last.y - point.y) > EPSILON) {
            deduped.push(point);
        }
    }
    if (deduped.length <= 2) {
        return deduped;
    }
    const result: LayoutPoint[] = [deduped[0]];
    for (let i = 1; i < deduped.length - 1; i++) {
        const prev = result[result.length - 1];
        const current = deduped[i];
        const next = deduped[i + 1];
        const cross = (current.x - prev.x) * (next.y - current.y) - (current.y - prev.y) * (next.x - current.x);
        const dot = (current.x - prev.x) * (next.x - current.x) + (current.y - prev.y) * (next.y - current.y);
        if (Math.abs(cross) > EPSILON || dot < 0) {
            result.push(current);
        }
    }
    result.push(deduped[deduped.length - 1]);
    return result;
}

/**
 * Computes the length of a route.
 *
 * @param points the route
 * @returns the length
 */
function routeLength(points: LayoutPoint[]): number {
    let length = 0;
    for (let i = 0; i < points.length - 1; i++) {
        length += Math.hypot(points[i + 1].x - points[i].x, points[i + 1].y - points[i].y);
    }
    return length;
}

/**
 * Deterministic string comparison.
 *
 * @param a the first string
 * @param b the second string
 * @returns the comparison result
 */
function compareIds(a: string, b: string): number {
    return a < b ? -1 : a > b ? 1 : 0;
}
