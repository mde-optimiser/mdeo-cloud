import type { LayoutInput } from "./layoutModel.js";
import { endLabelFootprint } from "./edgeLabelGeometry.js";

/**
 * Computes the margin to reserve around every node for the end labels of its edges: the longest end label
 * footprint along the end segment.
 *
 * @param input the flat layout input
 * @returns the margins by node id
 */
export function uniformEndLabelMargins(input: LayoutInput): Map<string, number> {
    const margins = new Map<string, number>();
    for (const edge of input.edges) {
        for (const end of ["source", "target"] as const) {
            const labels = edge.labels.filter((label) => label.end === end);
            if (labels.length === 0) continue;
            const marker = end === "source" ? edge.sourceMarker : edge.targetMarker;
            const along = endLabelFootprint(labels, "horizontal", marker?.length).along + END_LABEL_GAP;
            const nodeId = end === "source" ? edge.sourceId : edge.targetId;
            margins.set(nodeId, Math.max(margins.get(nodeId) ?? 0, Math.ceil(along)));
        }
    }
    return margins;
}

/**
 * Gap between end labels and other edges / nodes.
 */
export const END_LABEL_GAP = 6;
