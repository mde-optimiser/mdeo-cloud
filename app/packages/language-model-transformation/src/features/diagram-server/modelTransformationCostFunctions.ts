import { ModelTransformationElementType } from "@mdeo/protocol-model-transformation";
import type { NodeAttributes, EdgeAttributes } from "@mdeo/language-shared";

/**
 * Standalone cost functions for the model-transformation GED worker.
 * These are extracted from ModelTransformationMetadataManager so they can be
 * used both in the main class and in the dedicated GED sub-worker.
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

    if (type1 === ModelTransformationElementType.NODE_MATCH) {
        const similarity = calculateMatchSimilarity(node1, node2);
        return 2 - similarity;
    }

    if (type1 === ModelTransformationElementType.NODE_PATTERN_INSTANCE) {
        const similarity = calculatePatternInstanceSimilarity(node1, node2);
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

    return 1;
}

function calculateMatchSimilarity(node1: NodeAttributes, node2: NodeAttributes): number {
    const label1 = node1.label as string | undefined;
    const label2 = node2.label as string | undefined;

    if (label1 === label2) {
        return 1;
    }
    return 0;
}

function calculatePatternInstanceSimilarity(node1: NodeAttributes, node2: NodeAttributes): number {
    const name1 = node1.name as string | undefined;
    const name2 = node2.name as string | undefined;
    const type1Attr = node1.typeName as string | undefined;
    const type2Attr = node2.typeName as string | undefined;

    if (name1 === name2 && type1Attr === type2Attr) {
        return 1;
    }

    if (name1 === name2 || type1Attr === type2Attr) {
        return 0.5;
    }

    return 0;
}
