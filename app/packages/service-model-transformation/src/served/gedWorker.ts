/**
 * GED sub-worker entry point for the model-transformation language.
 *
 * This file is compiled as a standalone worker bundle by Vite and served at
 * /static/gedWorker.js from the model-transformation plugin service.
 * The ModelTransformationMetadataManager refers to it via the path
 * /plugin/model-transformation/static/gedWorker.js.
 */
import { runGEDWorker, type NodeAttributes, type EdgeAttributes } from "@mdeo/language-common";
import { ModelTransformationElementType } from "@mdeo/protocol-model-transformation";

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

    if (type1 === ModelTransformationElementType.NODE_MATCH) {
        const label1 = node1.label as string | undefined;
        const label2 = node2.label as string | undefined;
        return 2 - (label1 === label2 ? 1 : 0);
    }

    if (type1 === ModelTransformationElementType.NODE_PATTERN_INSTANCE) {
        const name1 = node1.name as string | undefined;
        const name2 = node2.name as string | undefined;
        const typeName1 = node1.typeName as string | undefined;
        const typeName2 = node2.typeName as string | undefined;
        const nameMatch = name1 === name2;
        const typeMatch = typeName1 === typeName2;
        const similarity = nameMatch && typeMatch ? 1 : nameMatch || typeMatch ? 0.5 : 0;
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
