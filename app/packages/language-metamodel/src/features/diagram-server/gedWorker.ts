/**
 * GED sub-worker entry point for the metamodel language.
 *
 * This file is compiled as a standalone worker bundle by Vite and served at
 * /static/gedWorker.js from the metamodel plugin service.
 * The MetamodelMetadataManager refers to it via the path
 * /plugin/metamodel/static/gedWorker.js.
 */
import { runGEDWorker } from "@mdeo/language-shared";
import { calculateNodeCost, calculateEdgeCost } from "./metamodelCostFunctions.js";

runGEDWorker({
    nodeSubstCost: (a, b) => calculateNodeCost(a, b),
    nodeDelCost: (a) => calculateNodeCost(a, undefined),
    nodeInsCost: (a) => calculateNodeCost(undefined, a),
    edgeSubstCost: (a, b) => calculateEdgeCost(a, b),
    edgeDelCost: (a) => calculateEdgeCost(a, undefined),
    edgeInsCost: (a) => calculateEdgeCost(undefined, a)
});
