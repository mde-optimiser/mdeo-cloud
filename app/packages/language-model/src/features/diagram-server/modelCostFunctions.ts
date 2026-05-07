import { ModelElementType } from "@mdeo/protocol-model";
import type { NodeAttributes, EdgeAttributes } from "@mdeo/language-shared";

/**
 * Standalone cost functions for the model GED worker.
 * These are extracted from ModelMetadataManager so they can be used
 * both in the main class and in the dedicated GED sub-worker.
 */

export function calculateNodeCost(node1: NodeAttributes | undefined, node2: NodeAttributes | undefined): number {
    if (node1 == undefined || node2 == undefined) {
        return 1;
    }
    if (node1.id === node2.id) {
        return 0;
    }

    const type1 = node1.type as string;
    const type2 = node2.type as string;

    if (type1 !== type2) {
        return 2;
    }

    if (type1 === ModelElementType.NODE_OBJECT) {
        const similarity = calculateObjectSimilarity(node1, node2);
        return 2 - similarity;
    }

    return 1;
}

export function calculateEdgeCost(edge1: EdgeAttributes | undefined, edge2: EdgeAttributes | undefined): number {
    if (edge1 == undefined || edge2 == undefined) {
        return 1;
    }
    if (edge1.id === edge2.id) {
        return 0;
    }

    const type1 = edge1.type as string;
    const type2 = edge2.type as string;

    if (type1 !== type2) {
        return 2;
    }

    if (type1 === ModelElementType.EDGE_LINK) {
        const similarity = calculateLinkSimilarity(edge1, edge2);
        return 2 - similarity;
    }

    return 1;
}

function calculateObjectSimilarity(node1: NodeAttributes, node2: NodeAttributes): number {
    const attrs1 = node1 as Record<string, unknown>;
    const attrs2 = node2 as Record<string, unknown>;
    const name1 = attrs1.name as string | undefined;
    const name2 = attrs2.name as string | undefined;
    const type1 = attrs1.typeName as string | undefined;
    const type2 = attrs2.typeName as string | undefined;

    let score = 0;

    if (name1 === name2) {
        score += 0.5;
    }

    if (type1 === type2) {
        score += 0.5;
    }

    return score;
}

function calculateLinkSimilarity(edge1: EdgeAttributes, edge2: EdgeAttributes): number {
    const attrs1 = edge1 as Record<string, unknown>;
    const attrs2 = edge2 as Record<string, unknown>;
    const source1 = attrs1.sourceId as string | undefined;
    const source2 = attrs2.sourceId as string | undefined;
    const target1 = attrs1.targetId as string | undefined;
    const target2 = attrs2.targetId as string | undefined;

    let score = 0;

    if (source1 === source2) {
        score += 0.5;
    }

    if (target1 === target2) {
        score += 0.5;
    }

    return score;
}
