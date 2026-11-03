/**
 * Shared, pure geometry of edge labels.
 *
 * Used by the client edge views (rendering) AND by the layout engines (space reservation), so that the space
 * reserved during layout equals the space used when rendering.
 *
 * Conventions:
 * - Labels run along their segment: on horizontal segments the text is horizontal, on vertical segments it is
 *   rotated by -90 degrees (reads bottom-to-top, never upside-down).
 * - `side` is screen based and matches the text frame: "left" is above a horizontal segment and left of a vertical
 *   segment (which is "above" the rotated text), "right" is below / right of it. This does not depend on the edge
 *   direction, so a label never flips when an edge is reversed.
 * - End labels start `distanceFromElement` away from the node border, or after the marker if the marker is longer,
 *   and extend along the end segment away from the node. All labels of one end form ONE ROW along the segment on the
 *   left side of the line (above / left): first the labels marked "right" (e.g. the multiplicity, closest to the
 *   node), then the labels marked "left" (e.g. the role name). Keeping every end's labels on the same side of its line
 *   halves the space needed across the line, so neighbouring ends on one node side can be close together without
 *   their labels facing (and overlapping) each other.
 * - Middle labels are centered on the middle segment of the route (see {@link findMiddleSegment}). They are NOT
 *   rotated: they are sparse (one per edge, away from crowded node sides) and often long (conditions), which read
 *   badly when rotated. Several middle labels on the same side are stacked outwards.
 */

/**
 * A 2D point.
 */
export interface LabelGeometryPoint {
    x: number;
    y: number;
}

/**
 * An axis aligned rectangle.
 */
export interface LabelGeometryRect {
    x: number;
    y: number;
    width: number;
    height: number;
}

/**
 * Specification of one edge label, structurally compatible with the layout engines' `EdgeLabelSpec`.
 */
export interface EdgeLabelGeometrySpec {
    /**
     * The id of the label element.
     */
    id: string;
    /**
     * Where along the edge the label is placed.
     */
    end: "source" | "target" | "middle";
    /**
     * Screen based side: "left" = above a horizontal / left of a vertical segment, "right" = below / right of it.
     */
    side: "left" | "right";
    /**
     * Measured, unrotated width of the text box.
     */
    width: number;
    /**
     * Measured, unrotated height of the text box.
     */
    height: number;
    /**
     * The text (only used to detect identical labels).
     */
    text?: string;
}

/**
 * Extent of the markers at the edge ends. Only the length along the edge matters for label placement.
 */
export interface EdgeLabelMarkers {
    source?: { length: number };
    target?: { length: number };
}

/**
 * Orientation of a segment.
 */
export type SegmentOrientation = "horizontal" | "vertical";

/**
 * A label placed on a route.
 */
export interface PlacedEdgeLabel {
    /**
     * The label specification.
     */
    label: EdgeLabelGeometrySpec;
    /**
     * Center of the label (identical for the rotated and the unrotated box).
     */
    center: LabelGeometryPoint;
    /**
     * Rotation in degrees around the center: 0 or -90 (bottom-to-top).
     */
    rotation: 0 | -90;
    /**
     * The axis aligned bounding box of the (possibly rotated) label in route coordinates.
     */
    bounds: LabelGeometryRect;
    /**
     * SVG transform that maps the unrotated label box (origin at its top left corner, size width x height)
     * onto its placed position.
     */
    transform: string;
}

/**
 * Space occupied by labels relative to a segment.
 */
export interface EdgeLabelFootprint {
    /**
     * Extent along the segment. For end labels this is measured from the node border and includes the
     * marker / element distance in front of the labels. 0 if there are no labels.
     */
    along: number;
    /**
     * Total extent across the segment: left + right (the line itself has no width).
     */
    across: number;
    /**
     * Extent on the left side (above a horizontal / left of a vertical segment), incl. the distance to the line.
     */
    left: number;
    /**
     * Extent on the right side (below a horizontal / right of a vertical segment), incl. the distance to the line.
     */
    right: number;
    /**
     * For end labels: distance from the node border to where the labels begin (marker / element distance).
     * For middle labels: 0.
     */
    start: number;
}

/**
 * Spacing constants of the label placement.
 */
export const EDGE_LABEL_SPACING = {
    /**
     * Minimum distance between the node border and the first end label.
     */
    distanceFromElement: 6,
    /**
     * Distance between the line and the label next to it.
     */
    distanceToLine: 2,
    /**
     * Distance between labels stacked on the same side.
     */
    distanceBetween: 2,
    /**
     * Gap between the end of a marker and the first label.
     */
    distanceAfterMarker: 0,
    /**
     * Gap between consecutive labels in the row of an end.
     */
    distanceAlong: 4
} as const;

/**
 * Whether middle labels are rotated on vertical segments as well. They are not (see the module documentation).
 */
export const ROTATE_MIDDLE_LABELS = false;

/**
 * The side of the line on which the row of end labels is placed.
 */
export const END_LABEL_ROW_SIDE: "left" | "right" = "left";

/**
 * Whether end labels are rotated on vertical segments.
 */
export const ROTATE_END_LABELS = true;

const EPSILON = 1e-6;

/**
 * Determines the orientation of a segment (vertical if it is exactly as long in x as in y and both are 0).
 *
 * @param from the start of the segment
 * @param to the end of the segment
 * @returns the orientation
 */
export function segmentOrientation(from: LabelGeometryPoint, to: LabelGeometryPoint): SegmentOrientation {
    return Math.abs(to.x - from.x) >= Math.abs(to.y - from.y) && Math.abs(to.x - from.x) > EPSILON
        ? "horizontal"
        : "vertical";
}

/**
 * Computes the distance from the node border to where the end labels begin.
 *
 * @param markerLength the length of the marker at that end (0 if there is none)
 * @returns the start distance
 */
export function endLabelStart(markerLength: number | undefined): number {
    const length = markerLength ?? 0;
    if (length <= 0) {
        return EDGE_LABEL_SPACING.distanceFromElement;
    }
    return Math.max(EDGE_LABEL_SPACING.distanceFromElement, length + EDGE_LABEL_SPACING.distanceAfterMarker);
}

/**
 * Computes the extent across the line of a stack of labels on one side.
 *
 * @param labels the labels on that side
 * @returns the extent incl. the distance to the line, 0 if there are no labels
 */
function stackExtent(labels: readonly EdgeLabelGeometrySpec[]): number {
    if (labels.length === 0) {
        return 0;
    }
    let total = EDGE_LABEL_SPACING.distanceToLine;
    labels.forEach((label, index) => {
        total += sanitize(label.height);
        if (index > 0) {
            total += EDGE_LABEL_SPACING.distanceBetween;
        }
    });
    return total;
}

/**
 * Returns the label extent along the line (width) or across it (height) for a label, taking an optional disabled
 * rotation into account.
 *
 * @param label the label
 * @param rotated whether the label runs along its segment
 * @param orientation the segment orientation
 * @returns the extents along and across the segment
 */
function labelExtents(
    label: EdgeLabelGeometrySpec,
    rotated: boolean,
    orientation: SegmentOrientation
): { along: number; across: number } {
    const width = sanitize(label.width);
    const height = sanitize(label.height);
    if (rotated || orientation === "horizontal") {
        return { along: width, across: height };
    }
    return { along: height, across: width };
}

/**
 * Replaces invalid (negative / NaN) sizes by 0.
 *
 * @param value the value
 * @returns the sanitized value
 */
function sanitize(value: number): number {
    return Number.isFinite(value) && value > 0 ? value : 0;
}

/**
 * Computes the space that the labels at ONE end of an edge occupy, along and across the end segment.
 * Engines use this to reserve space next to the node: the labels occupy `along` (from the node border) times
 * `left` (left / above the line) and `right` (right / below the line).
 *
 * Because labels run along their segment, the result is the same for both orientations (unless rotation is
 * disabled).
 *
 * @param labels the labels at that end (labels with another `end` are ignored)
 * @param endSegmentOrientation the orientation of the end segment
 * @param markerLength the length of the marker at that end (0 / undefined if there is none)
 * @returns the footprint
 */
export function endLabelFootprint(
    labels: readonly EdgeLabelGeometrySpec[],
    endSegmentOrientation: SegmentOrientation = "horizontal",
    markerLength?: number
): EdgeLabelFootprint {
    const endLabels = orderEndRow(labels.filter((label) => label.end !== "middle"));
    const start = endLabelStart(markerLength);
    if (endLabels.length === 0) {
        return { along: 0, across: 0, left: 0, right: 0, start };
    }
    let along = start;
    let across = 0;
    endLabels.forEach((label, index) => {
        const extents = labelExtents(label, ROTATE_END_LABELS, endSegmentOrientation);
        along += extents.along + (index > 0 ? EDGE_LABEL_SPACING.distanceAlong : 0);
        across = Math.max(across, extents.across);
    });
    const sideExtent = EDGE_LABEL_SPACING.distanceToLine + across;
    const left = END_LABEL_ROW_SIDE === "left" ? sideExtent : 0;
    const right = END_LABEL_ROW_SIDE === "right" ? sideExtent : 0;
    return { along, across: left + right, left, right, start };
}

/**
 * Orders the labels of one end for the row along the end segment: labels marked "right" first (closest to the node),
 * then labels marked "left", each in input order.
 *
 * @param labels the labels of one end
 * @returns the ordered labels
 */
function orderEndRow(labels: readonly EdgeLabelGeometrySpec[]): EdgeLabelGeometrySpec[] {
    return [...labels.filter((label) => label.side === "right"), ...labels.filter((label) => label.side !== "right")];
}

/**
 * Computes the space that the middle labels of an edge occupy, along and across the middle segment.
 *
 * @param labels the labels (labels that are not middle labels are ignored)
 * @param segmentOrientation the orientation of the middle segment
 * @returns the footprint (start is 0)
 */
export function middleLabelFootprint(
    labels: readonly EdgeLabelGeometrySpec[],
    segmentOrientation: SegmentOrientation = "horizontal"
): EdgeLabelFootprint {
    const middleLabels = labels.filter((label) => label.end === "middle");
    return footprint(middleLabels, segmentOrientation, ROTATE_MIDDLE_LABELS, 0);
}

/**
 * Computes a footprint of labels that share one anchor.
 *
 * @param labels the labels
 * @param orientation the segment orientation
 * @param rotated whether the labels run along the segment
 * @param start the distance in front of the labels
 * @returns the footprint
 */
function footprint(
    labels: readonly EdgeLabelGeometrySpec[],
    orientation: SegmentOrientation,
    rotated: boolean,
    start: number
): EdgeLabelFootprint {
    if (labels.length === 0) {
        return { along: 0, across: 0, left: 0, right: 0, start };
    }
    const asAlongAcross = labels.map((label) => {
        const extents = labelExtents(label, rotated, orientation);
        return { ...label, width: extents.along, height: extents.across };
    });
    const left = stackExtent(asAlongAcross.filter((label) => label.side === "left"));
    const right = stackExtent(asAlongAcross.filter((label) => label.side === "right"));
    const along = Math.max(...asAlongAcross.map((label) => label.width));
    return { along: start + along, across: left + right, left, right, start };
}

/**
 * Converts an end footprint into a screen aligned box size, for an end segment that leaves the node in the given
 * orientation (e.g. for engines that reserve space with axis aligned label boxes).
 *
 * @param footprint the footprint
 * @param endSegmentOrientation the orientation of the end segment
 * @param includeStart whether the distance in front of the labels is part of the box
 * @returns width and height of the box
 */
export function footprintToBox(
    footprint: EdgeLabelFootprint,
    endSegmentOrientation: SegmentOrientation,
    includeStart = true
): { width: number; height: number } {
    const along = includeStart ? footprint.along : Math.max(0, footprint.along - footprint.start);
    if (endSegmentOrientation === "horizontal") {
        return { width: along, height: footprint.across };
    }
    return { width: footprint.across, height: along };
}

/**
 * Finds the segment of a route on which middle labels are placed: the segment that contains the midpoint of the
 * route by length. If that segment is too short for the labels (`minLength`), the longest segment is used instead
 * (ties are resolved towards the middle of the route).
 *
 * @param route the route
 * @param minLength the length the segment should have
 * @returns the index i of the segment route[i] -> route[i + 1], or -1 if the route has no segment
 */
export function findMiddleSegment(route: readonly LabelGeometryPoint[], minLength = 0): number {
    if (route.length < 2) {
        return -1;
    }
    const lengths: number[] = [];
    let total = 0;
    for (let i = 0; i < route.length - 1; i++) {
        const length = Math.abs(route[i + 1].x - route[i].x) + Math.abs(route[i + 1].y - route[i].y);
        lengths.push(length);
        total += length;
    }
    let midIndex = lengths.length - 1;
    let walked = 0;
    for (let i = 0; i < lengths.length; i++) {
        if (walked + lengths[i] >= total / 2 - EPSILON) {
            midIndex = i;
            break;
        }
        walked += lengths[i];
    }
    if (lengths[midIndex] >= minLength - EPSILON) {
        return midIndex;
    }
    let best = midIndex;
    const center = (lengths.length - 1) / 2;
    for (let i = 0; i < lengths.length; i++) {
        if (
            lengths[i] > lengths[best] + EPSILON ||
            (Math.abs(lengths[i] - lengths[best]) <= EPSILON && Math.abs(i - center) < Math.abs(best - center))
        ) {
            best = i;
        }
    }
    return best;
}

/**
 * Finds the end segment of a route: the first segment with non-zero length starting at the given end.
 *
 * @param route the route
 * @param end the end
 * @returns the anchor (endpoint) and the unit direction pointing away from the node, or undefined
 */
export function endSegment(
    route: readonly LabelGeometryPoint[],
    end: "source" | "target"
): { anchor: LabelGeometryPoint; direction: LabelGeometryPoint; orientation: SegmentOrientation } | undefined {
    if (route.length < 2) {
        return undefined;
    }
    const points = end === "source" ? route : [...route].reverse();
    const anchor = points[0];
    for (let i = 1; i < points.length; i++) {
        const dx = points[i].x - anchor.x;
        const dy = points[i].y - anchor.y;
        if (Math.abs(dx) > EPSILON || Math.abs(dy) > EPSILON) {
            return { anchor, ...unitDirection(dx, dy) };
        }
    }
    return undefined;
}

/**
 * Converts a vector into the axis direction it is closest to.
 *
 * @param dx the x component
 * @param dy the y component
 * @returns the unit axis direction and its orientation
 */
function unitDirection(dx: number, dy: number): { direction: LabelGeometryPoint; orientation: SegmentOrientation } {
    if (Math.abs(dx) >= Math.abs(dy)) {
        return { direction: { x: dx >= 0 ? 1 : -1, y: 0 }, orientation: "horizontal" };
    }
    return { direction: { x: 0, y: dy >= 0 ? 1 : -1 }, orientation: "vertical" };
}

/**
 * Places all labels of an edge on its route.
 *
 * @param route the full route of the edge (first point on the source border, last on the target border)
 * @param labels the labels
 * @param markers the markers at the ends (only their length matters)
 * @returns the placed labels, in the order of `labels` (labels that cannot be placed are omitted)
 */
export function placeEdgeLabels(
    route: readonly LabelGeometryPoint[],
    labels: readonly EdgeLabelGeometrySpec[],
    markers: EdgeLabelMarkers = {}
): PlacedEdgeLabel[] {
    const placed = new Map<EdgeLabelGeometrySpec, PlacedEdgeLabel>();
    for (const end of ["source", "target"] as const) {
        const endLabels = labels.filter((label) => label.end === end);
        if (endLabels.length === 0) {
            continue;
        }
        const segment = endSegment(route, end);
        if (segment == undefined) {
            continue;
        }
        let offset = endLabelStart(markers[end]?.length);
        for (const label of orderEndRow(endLabels)) {
            const extents = labelExtents(label, ROTATE_END_LABELS, segment.orientation);
            const alongCenter = offset + extents.along / 2;
            offset += extents.along + EDGE_LABEL_SPACING.distanceAlong;
            const [entry] = placeStacks(
                [{ ...label, side: END_LABEL_ROW_SIDE }],
                segment.anchor,
                segment.direction,
                segment.orientation,
                ROTATE_END_LABELS,
                () => alongCenter
            );
            placed.set(label, { ...entry, label });
        }
    }

    const middleLabels = labels.filter((label) => label.end === "middle");
    if (middleLabels.length > 0) {
        const placedMiddle = placeMiddleLabels(route, middleLabels);
        placedMiddle.forEach((label) => placed.set(label.label, label));
    }

    const result: PlacedEdgeLabel[] = [];
    for (const label of labels) {
        const entry = placed.get(label);
        if (entry != undefined) {
            result.push(entry);
        }
    }
    return result;
}

/**
 * Places the middle labels of a route.
 *
 * @param route the route
 * @param labels the middle labels
 * @returns the placed labels
 */
function placeMiddleLabels(
    route: readonly LabelGeometryPoint[],
    labels: readonly EdgeLabelGeometrySpec[]
): PlacedEdgeLabel[] {
    const neededLength =
        Math.max(...labels.map((label) => sanitize(label.width))) + 2 * EDGE_LABEL_SPACING.distanceToLine;
    const index = findMiddleSegment(route, neededLength);
    if (index < 0) {
        return [];
    }
    const from = route[index];
    const to = route[index + 1];
    const center = { x: (from.x + to.x) / 2, y: (from.y + to.y) / 2 };
    const { direction, orientation } = unitDirection(to.x - from.x, to.y - from.y);
    return placeStacks(labels, center, direction, orientation, ROTATE_MIDDLE_LABELS, () => 0);
}

/**
 * Places labels that share an anchor, stacked per side.
 *
 * @param labels the labels
 * @param anchor the anchor on the line
 * @param direction the unit direction along the line in which the labels extend
 * @param orientation the orientation of the line
 * @param rotate whether labels run along the line (rotated on vertical lines)
 * @param alongCenter computes the distance from the anchor to the label center along the line from the label's
 * extent along the line
 * @returns the placed labels
 */
function placeStacks(
    labels: readonly EdgeLabelGeometrySpec[],
    anchor: LabelGeometryPoint,
    direction: LabelGeometryPoint,
    orientation: SegmentOrientation,
    rotate: boolean,
    alongCenter: (along: number) => number
): PlacedEdgeLabel[] {
    const result: PlacedEdgeLabel[] = [];
    const rotated = rotate && orientation === "vertical";
    for (const side of ["left", "right"] as const) {
        const sideSign = side === "left" ? -1 : 1;
        let offset = EDGE_LABEL_SPACING.distanceToLine;
        for (const label of labels.filter((label) => label.side === side)) {
            const width = sanitize(label.width);
            const height = sanitize(label.height);
            const extents = labelExtents(label, rotate, orientation);
            const alongDistance = alongCenter(extents.along);
            const acrossDistance = offset + extents.across / 2;
            offset += extents.across + EDGE_LABEL_SPACING.distanceBetween;

            const normal = orientation === "horizontal" ? { x: 0, y: 1 } : { x: 1, y: 0 };
            const center = {
                x: anchor.x + direction.x * alongDistance + normal.x * sideSign * acrossDistance,
                y: anchor.y + direction.y * alongDistance + normal.y * sideSign * acrossDistance
            };
            const boxWidth = rotated ? height : width;
            const boxHeight = rotated ? width : height;
            result.push({
                label,
                center,
                rotation: rotated ? -90 : 0,
                bounds: {
                    x: center.x - boxWidth / 2,
                    y: center.y - boxHeight / 2,
                    width: boxWidth,
                    height: boxHeight
                },
                transform: rotated
                    ? `translate(${fmt(center.x)}, ${fmt(center.y)}) rotate(-90) translate(${fmt(-width / 2)}, ${fmt(-height / 2)})`
                    : `translate(${fmt(center.x - width / 2)}, ${fmt(center.y - height / 2)})`
            });
        }
    }
    return result;
}

/**
 * Formats a number for an SVG transform.
 *
 * @param value the number
 * @returns the formatted number
 */
function fmt(value: number): string {
    return String(Math.round(value * 1000) / 1000);
}

/**
 * Builds a key that identifies the set of label texts at one end, for detecting identical end labels.
 * Returns undefined if there are no labels at that end (ends without labels are never considered identical).
 *
 * @param labels all labels of the edge
 * @param end the end
 * @returns the key or undefined
 */
export function endLabelKey(labels: readonly EdgeLabelGeometrySpec[], end: "source" | "target"): string | undefined {
    const texts = labels
        .filter((label) => label.end === end)
        .map((label) => `${label.side}:${label.text ?? ""}`)
        .sort();
    if (texts.length === 0) {
        return undefined;
    }
    return JSON.stringify(texts);
}
