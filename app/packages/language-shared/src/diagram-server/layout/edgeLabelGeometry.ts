/**
 * Re-export of the shared edge label geometry (the implementation lives in protocol-common so that the client edge
 * views can use exactly the same code).
 */
export {
    EDGE_LABEL_SPACING,
    ROTATE_END_LABELS,
    ROTATE_MIDDLE_LABELS,
    endLabelFootprint,
    endLabelKey,
    endLabelStart,
    endSegment,
    findMiddleSegment,
    footprintToBox,
    middleLabelFootprint,
    placeEdgeLabels,
    segmentOrientation
} from "@mdeo/protocol-common";
export type {
    EdgeLabelFootprint,
    EdgeLabelGeometrySpec,
    EdgeLabelMarkers,
    LabelGeometryPoint,
    LabelGeometryRect,
    PlacedEdgeLabel,
    SegmentOrientation
} from "@mdeo/protocol-common";
