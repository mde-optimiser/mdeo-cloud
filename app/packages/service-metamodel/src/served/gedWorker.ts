/**
 * GED sub-worker entry point for the metamodel language.
 *
 * This file is compiled as a standalone worker bundle by Vite and served at
 * /static/gedWorker.js from the metamodel plugin service.
 * The MetamodelMetadataManager refers to it via the path
 * /plugin/metamodel/static/gedWorker.js.
 */
import { runGEDWorker, type NodeAttributes, type EdgeAttributes } from "@mdeo/language-common";
import { MetamodelElementType } from "@mdeo/protocol-metamodel";

function calculateNodeCost(node1: NodeAttributes | undefined, node2: NodeAttributes | undefined): number {
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
        const props1 = (node1.properties as string[]) || [];
        const props2 = (node2.properties as string[]) || [];
        const maxProps = Math.max(props1.length, props2.length);
        if (maxProps === 0) {
            return 0;
        }
        const props1Set = new Set(props1);
        const sharedCount = props2.filter((p) => props1Set.has(p)).length;
        return 2 - sharedCount / maxProps;
    }

    return 1;
}

function calculateEdgeCost(edge1: EdgeAttributes | undefined, edge2: EdgeAttributes | undefined): number {
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
        const start1 = (edge1.startProperty as string) || "";
        const target1 = (edge1.targetProperty as string) || "";
        const start2 = (edge2.startProperty as string) || "";
        const target2 = (edge2.targetProperty as string) || "";

        let matches = 0;
        let total = 0;

        if (start1 || start2) {
            total++;
            if (start1 === start2) matches++;
        }
        if (target1 || target2) {
            total++;
            if (target1 === target2) matches++;
        }

        const similarity = total === 0 ? 0.5 : matches / total;
        return 2 - similarity;
    }

    return 1;
}

runGEDWorker({
    nodeSubstCost: (a, b) => calculateNodeCost(a, b),
    nodeDelCost: (a) => calculateNodeCost(a, undefined),
    nodeInsCost: (a) => calculateNodeCost(undefined, a),
    edgeSubstCost: (a, b) => calculateEdgeCost(a, b),
    edgeDelCost: (a) => calculateEdgeCost(a, undefined),
    edgeInsCost: (a) => calculateEdgeCost(undefined, a)
});
