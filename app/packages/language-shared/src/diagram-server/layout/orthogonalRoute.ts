import type { LayoutPoint, LayoutResultNode } from "./layoutModel.js";

/**
 * Tolerance used to decide whether two coordinates are equal.
 */
const EPSILON = 0.01;

/**
 * Side of a node bounds an edge end attaches to.
 */
export type RouteSide = "top" | "bottom" | "left" | "right";

/**
 * A segment of an orthogonal route: vertical segments have a constant x coordinate,
 * horizontal segments a constant y coordinate.
 */
export interface RouteSegment {
    /**
     * "v" for vertical, "h" for horizontal.
     */
    orientation: "v" | "h";
    /**
     * x for vertical, y for horizontal segments.
     */
    coord: number;
}

/**
 * An orthogonal route described by its alternating segments. The perpendicular extent of the first
 * and last segment is given by the source and target node borders (see {@link sideOfEnd}).
 * Describing a route by one coordinate per segment keeps it exactly orthogonal under any rounding.
 */
export interface SegmentRoute {
    segments: RouteSegment[];
    sourceSide: RouteSide;
    targetSide: RouteSide;
}

/**
 * Removes duplicate consecutive points and collinear intermediate points.
 *
 * @param points the points
 * @returns the simplified points
 */
export function simplifyPoints(points: LayoutPoint[]): LayoutPoint[] {
    const dedup: LayoutPoint[] = [];
    for (const point of points) {
        const last = dedup.at(-1);
        if (last == undefined || Math.abs(last.x - point.x) > EPSILON || Math.abs(last.y - point.y) > EPSILON) {
            dedup.push(point);
        }
    }
    if (dedup.length <= 2) {
        return dedup;
    }
    const result: LayoutPoint[] = [dedup[0]];
    for (let i = 1; i < dedup.length - 1; i++) {
        const prev = result.at(-1)!;
        const curr = dedup[i];
        const next = dedup[i + 1];
        const sameX = Math.abs(prev.x - curr.x) <= EPSILON && Math.abs(curr.x - next.x) <= EPSILON;
        const sameY = Math.abs(prev.y - curr.y) <= EPSILON && Math.abs(curr.y - next.y) <= EPSILON;
        if (!sameX && !sameY) {
            result.push(curr);
        }
    }
    result.push(dedup.at(-1)!);
    return result;
}

/**
 * Checks whether all segments of the polyline are horizontal or vertical.
 *
 * @param points the points
 * @returns true if the polyline is orthogonal
 */
export function isOrthogonal(points: LayoutPoint[]): boolean {
    for (let i = 0; i < points.length - 1; i++) {
        const dx = Math.abs(points[i].x - points[i + 1].x);
        const dy = Math.abs(points[i].y - points[i + 1].y);
        if (dx > 0.5 && dy > 0.5) {
            return false;
        }
    }
    return true;
}

/**
 * Determines the side of the node an end segment attaches to.
 *
 * @param orientation the orientation of the end segment
 * @param nodeBounds the node bounds
 * @param awayCoord the coordinate (perpendicular to the side) of the next point along the route
 * @param endCoord the coordinate (perpendicular to the side) of the end point itself
 * @returns the side
 */
function sideOfEnd(
    orientation: "v" | "h",
    nodeBounds: LayoutResultNode,
    awayCoord: number,
    endCoord: number
): RouteSide {
    // the next bend lies outside the node: the end leaves towards it; otherwise (a degenerate route whose first bend
    // lies inside the node) keep the side the end point is closest to, which keeps snapping idempotent
    if (orientation === "v") {
        if (awayCoord <= nodeBounds.y) return "top";
        if (awayCoord >= nodeBounds.y + nodeBounds.height) return "bottom";
        return Math.abs(endCoord - nodeBounds.y) <= Math.abs(endCoord - (nodeBounds.y + nodeBounds.height))
            ? "top"
            : "bottom";
    } else {
        if (awayCoord <= nodeBounds.x) return "left";
        if (awayCoord >= nodeBounds.x + nodeBounds.width) return "right";
        return Math.abs(endCoord - nodeBounds.x) <= Math.abs(endCoord - (nodeBounds.x + nodeBounds.width))
            ? "left"
            : "right";
    }
}

/**
 * Converts an orthogonal polyline (first point on the source border, last on the target border)
 * into a {@link SegmentRoute}.
 *
 * @param points the orthogonal points (at least two)
 * @param source the source node bounds
 * @param target the target node bounds
 * @returns the segment route, or undefined if the points are not orthogonal
 */
export function toSegmentRoute(
    points: LayoutPoint[],
    source: LayoutResultNode,
    target: LayoutResultNode
): SegmentRoute | undefined {
    const simplified = simplifyPoints(points);
    if (simplified.length < 2 || !isOrthogonal(simplified)) {
        return undefined;
    }
    const segments: RouteSegment[] = [];
    for (let i = 0; i < simplified.length - 1; i++) {
        const a = simplified[i];
        const b = simplified[i + 1];
        if (Math.abs(a.x - b.x) <= Math.abs(a.y - b.y)) {
            segments.push({ orientation: "v", coord: (a.x + b.x) / 2 });
        } else {
            segments.push({ orientation: "h", coord: (a.y + b.y) / 2 });
        }
    }
    const first = segments[0];
    const last = segments.at(-1)!;
    const p0 = simplified[0];
    const p1 = simplified[1];
    const pn = simplified.at(-1)!;
    const pn1 = simplified.at(-2)!;
    const sourceAway = first.orientation === "v" ? p1.y : p1.x;
    const sourceEnd = first.orientation === "v" ? p0.y : p0.x;
    const targetAway = last.orientation === "v" ? pn1.y : pn1.x;
    const targetEnd = last.orientation === "v" ? pn.y : pn.x;
    let sourceSide: RouteSide;
    let targetSide: RouteSide;
    if (segments.length === 1) {
        // straight edge: the side follows from the relative position of the nodes
        if (first.orientation === "v") {
            const down = target.y + target.height / 2 >= source.y + source.height / 2;
            sourceSide = down ? "bottom" : "top";
            targetSide = down ? "top" : "bottom";
        } else {
            const right = target.x + target.width / 2 >= source.x + source.width / 2;
            sourceSide = right ? "right" : "left";
            targetSide = right ? "left" : "right";
        }
    } else {
        sourceSide = sideOfEnd(first.orientation, source, sourceAway, sourceEnd);
        targetSide = sideOfEnd(last.orientation, target, targetAway, targetEnd);
    }
    return { segments, sourceSide, targetSide };
}

/**
 * Returns the point where the given side's perpendicular line at coord meets the node border.
 *
 * @param side the side
 * @param coord the coordinate along the side
 * @param bounds the node bounds
 * @returns the border point
 */
function borderPoint(side: RouteSide, coord: number, bounds: LayoutResultNode): LayoutPoint {
    switch (side) {
        case "top":
            return { x: coord, y: bounds.y };
        case "bottom":
            return { x: coord, y: bounds.y + bounds.height };
        case "left":
            return { x: bounds.x, y: coord };
        case "right":
            return { x: bounds.x + bounds.width, y: coord };
    }
}

/**
 * Converts a segment route back into points: first point on the source border, last on the target border.
 *
 * @param route the segment route
 * @param source the source node bounds
 * @param target the target node bounds
 * @returns the points
 */
export function fromSegmentRoute(
    route: SegmentRoute,
    source: LayoutResultNode,
    target: LayoutResultNode
): LayoutPoint[] {
    const { segments } = route;
    const points: LayoutPoint[] = [borderPoint(route.sourceSide, segments[0].coord, source)];
    for (let i = 0; i < segments.length - 1; i++) {
        const a = segments[i];
        const b = segments[i + 1];
        points.push(a.orientation === "v" ? { x: a.coord, y: b.coord } : { x: b.coord, y: a.coord });
    }
    points.push(borderPoint(route.targetSide, segments.at(-1)!.coord, target));
    return points;
}

/**
 * Returns the span (min, max) along the side of the node.
 *
 * @param side the side
 * @param bounds the node bounds
 * @returns the span
 */
export function sideSpan(side: RouteSide, bounds: LayoutResultNode): [number, number] {
    if (side === "top" || side === "bottom") {
        return [bounds.x, bounds.x + bounds.width];
    }
    return [bounds.y, bounds.y + bounds.height];
}

/**
 * Converts a coordinate along the side into the relative anchor value (0..1).
 *
 * @param side the side
 * @param coord the coordinate
 * @param bounds the node bounds
 * @returns the anchor value
 */
export function anchorValue(side: RouteSide, coord: number, bounds: LayoutResultNode): number {
    const [min, max] = sideSpan(side, bounds);
    if (max - min <= 0) {
        return 0.5;
    }
    return Math.min(1, Math.max(0, (coord - min) / (max - min)));
}
