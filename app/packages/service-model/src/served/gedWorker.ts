/**
 * GED sub-worker entry point for the model language.
 *
 * This file is compiled as a standalone worker bundle by Vite and served at
 * /static/gedWorker.js from the model plugin service.
 * The ModelMetadataManager refers to it via the path
 * /plugin/model/static/gedWorker.js.
 */
import { runGEDWorker, type NodeAttributes, type EdgeAttributes } from "@mdeo/language-common";
import { ModelElementType } from "@mdeo/protocol-model";

function calculateNodeCost(node1: NodeAttributes | undefined, node2: NodeAttributes | undefined): number {
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
        const attrs1 = node1 as Record<string, unknown>;
        const attrs2 = node2 as Record<string, unknown>;
        const name1 = attrs1.name as string | undefined;
        const name2 = attrs2.name as string | undefined;
        const typeName1 = attrs1.typeName as string | undefined;
        const typeName2 = attrs2.typeName as string | undefined;
        const similarity = (name1 === name2 ? 0.5 : 0) + (typeName1 === typeName2 ? 0.5 : 0);
        return 2 - similarity;
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

    if (type1 === ModelElementType.EDGE_LINK) {
        const attrs1 = edge1 as Record<string, unknown>;
        const attrs2 = edge2 as Record<string, unknown>;
        const source1 = attrs1.sourceId as string | undefined;
        const source2 = attrs2.sourceId as string | undefined;
        const target1 = attrs1.targetId as string | undefined;
        const target2 = attrs2.targetId as string | undefined;
        const similarity = (source1 === source2 ? 0.5 : 0) + (target1 === target2 ? 0.5 : 0);
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
