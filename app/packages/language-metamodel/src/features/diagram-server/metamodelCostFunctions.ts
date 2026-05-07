import { MetamodelElementType } from "@mdeo/protocol-metamodel";
import type { NodeAttributes, EdgeAttributes } from "@mdeo/language-shared";

/**
 * Standalone cost functions for the metamodel GED worker.
 * These are extracted from MetamodelMetadataManager so they can be used
 * both in the main class and in the dedicated GED sub-worker.
 */

export function calculateNodeCost(node1: NodeAttributes | undefined, node2: NodeAttributes | undefined): number {
    if (node1 == undefined || node2 == undefined) {
        return 1;
    }
    if (node1.id === node2.id) {
        return 0;
    }

    const type1 = node1.type;
    const type2 = node2.type;

    if (type1 !== type2) {
        return 2;
    }

    if (type1 === MetamodelElementType.NODE_CLASS) {
        const similarity = calculateClassSimilarity(node1, node2);
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

    if (type1 === MetamodelElementType.EDGE_INHERITANCE) {
        return 0;
    }

    if (type1 === MetamodelElementType.EDGE_ASSOCIATION) {
        const similarity = calculateAssociationSimilarity(edge1, edge2);
        return 2 - similarity;
    }

    return 1;
}

function calculateClassSimilarity(node1: NodeAttributes, node2: NodeAttributes): number {
    const props1 = (node1.properties as string[]) || [];
    const props2 = (node2.properties as string[]) || [];

    const maxProps = Math.max(props1.length, props2.length);
    if (maxProps === 0) {
        return 1;
    }

    const props1Set = new Set(props1);
    const sharedCount = props2.filter((p) => props1Set.has(p)).length;

    return sharedCount / maxProps;
}

function calculateAssociationSimilarity(edge1: EdgeAttributes, edge2: EdgeAttributes): number {
    const start1 = (edge1.startProperty as string) || "";
    const target1 = (edge1.targetProperty as string) || "";
    const start2 = (edge2.startProperty as string) || "";
    const target2 = (edge2.targetProperty as string) || "";

    let matches = 0;
    let total = 0;

    if (start1 || start2) {
        total++;
        if (start1 === start2) {
            matches++;
        }
    }

    if (target1 || target2) {
        total++;
        if (target1 === target2) {
            matches++;
        }
    }

    if (total === 0) {
        return 0.5;
    }

    return matches / total;
}
