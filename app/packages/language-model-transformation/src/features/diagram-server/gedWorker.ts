/**
 * GED sub-worker entry point for the model-transformation language.
 *
 * This file is compiled as a standalone worker bundle by Vite and served at
 * /static/gedWorker.js from the model-transformation plugin service.
 * The ModelTransformationMetadataManager refers to it via the path
 * /plugin/model-transformation/static/gedWorker.js.
 */
import { runGEDWorker } from "@mdeo/language-shared";
import { calculateNodeCost, calculateEdgeCost } from "./modelTransformationCostFunctions.js";

runGEDWorker({
    nodeSubstCost: (a, b) => calculateNodeCost(a, b),
    nodeDelCost: (a) => calculateNodeCost(a, undefined),
    nodeInsCost: (a) => calculateNodeCost(undefined, a),
    edgeSubstCost: (a, b) => calculateEdgeCost(a, b),
    edgeDelCost: (a) => calculateEdgeCost(a, undefined),
    edgeInsCost: (a) => calculateEdgeCost(undefined, a)
});
