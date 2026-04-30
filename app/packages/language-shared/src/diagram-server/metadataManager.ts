import { sharedImport } from "../sharedImport.js";
import type { EdgeMetadata, GraphMetadata, NodeMetadata } from "./metadata.js";
import { MultiGraph, type NodeAttributes, type EdgeAttributes } from "./graph-edit-distance/multiGraph.js";
import {
    optimizeEditPaths,
    type NodeEditPath,
    type EdgeEditPath,
    type EdgeTuple
} from "./graph-edit-distance/graphEditDistance.js";
import { linearSumAssignment } from "./graph-edit-distance/hungarian.js";
import type { AstNode } from "langium";
import { AstReflectionKey, LanguageServicesKey } from "./langiumServices.js";
import type { AstReflection, LanguageServices } from "@mdeo/language-common";

const { injectable, inject } = sharedImport("inversify");

/**
 * Node attributes with added loops property for self-referential edges.
 */
interface NodeAttributesWithLoops extends NodeAttributes {
    loops: Record<string, EdgeMetadata>;
}

/**
 * Attributes type excluding the 'type' property.
 */
export type Attributes = Omit<NodeAttributes | EdgeAttributes, "type">;

/**
 * Abstract base class for managing metadata validation and synchronization.
 * Works with domain-specific source models to extract and validate graph metadata.
 *
 * @template T The type of the source model, must extend AstNode
 */
@injectable()
export abstract class MetadataManager<T extends AstNode = AstNode> {
    /**
     * Injected language services for accessing workspace and file system operations.
     */
    @inject(LanguageServicesKey)
    protected languageServices!: LanguageServices;

    /**
     * Injected AST reflection service for type checking and model introspection.
     */
    @inject(AstReflectionKey)
    protected reflection!: AstReflection;

    /**
     * Verifies the metadata for a given model element.
     * If the metadata is invalid, return a corrected version.
     * If the metadata is valid, return undefined.
     *
     * @param model the model element (node or edge) the metadata belongs to
     * @return corrected metadata or undefined if valid
     */
    protected abstract verifyMetadata(model: NodeMetadata | EdgeMetadata): object | undefined;

    /**
     * Calculate the cost of transforming one node to another based on their metadata.
     * If node1 is undefined, it represents a node insertion.
     * If node2 is undefined, it represents a node deletion.
     * If both are defined, it represents a node substitution.
     *
     * @param node1 the first node's metadata or undefined
     * @param node2 the second node's metadata or undefined
     * @return the cost of the operation
     */
    protected abstract calculateNodeCost(node1: NodeAttributes | undefined, node2: NodeAttributes | undefined): number;

    /**
     * Calculate the cost of transforming one edge to another based on their metadata.
     * If edge1 is undefined, it represents an edge insertion.
     * If edge2 is undefined, it represents an edge deletion.
     * If both are defined, it represents an edge substitution.
     *
     * @param edge1 the first edge's metadata or undefined
     * @param edge2 the second edge's metadata or undefined
     * @return the cost of the operation
     */
    protected abstract calculateEdgeCost(edge1: EdgeAttributes | undefined, edge2: EdgeAttributes | undefined): number;

    /**
     * Extracts the graph metadata from the given source model.
     * Implementations should traverse the source model and generate metadata
     * for all nodes and edges that will be visualized.
     *
     * @param sourceModel The source model to extract metadata from
     * @returns The computed graph metadata
     */
    protected abstract extractGraphMetadata(sourceModel: T): GraphMetadata;

    /**
     * Validates the metadata against the current metadata based on the source model.
     * If discrepancies are found, returns the updated metadata.
     * If the metadata is valid, returns undefined.
     *
     * @param sourceModel The source model to validate metadata for
     * @param currentMetadata The current graph metadata
     * @param lastValidMetadata The last valid graph metadata for an error-free model
     * @returns Updated metadata or undefined if valid
     */
    validateMetadata(
        sourceModel: T,
        currentMetadata: GraphMetadata,
        lastValidMetadata: GraphMetadata
    ): GraphMetadata | undefined {
        const newMetadata = this.extractGraphMetadata(sourceModel);
        this.checkMetadataConsistency(newMetadata);

        if (this.isMetadataValid(newMetadata, currentMetadata)) {
            return this.getCleanMetadata(newMetadata, currentMetadata);
        }

        const mergedMetadata = this.mergeMetadata(currentMetadata, lastValidMetadata);

        const currentGraph = this.convertToMultiGraph(mergedMetadata);
        const newGraph = this.convertToMultiGraph(newMetadata);

        if (currentGraph.numberOfNodes === 0) {
            return newMetadata;
        }

        const lastResult = this.computeGED(currentGraph, newGraph);

        if (lastResult == undefined) {
            return newMetadata;
        }

        const [nodePath, edgePath] = lastResult;
        const { nodes: resultNodes, loopEdges } = this.processNodePaths(
            nodePath,
            newMetadata,
            mergedMetadata,
            currentGraph,
            newGraph
        );
        const resultEdges = this.processEdgePaths(edgePath, newMetadata, mergedMetadata);

        Object.assign(resultEdges, loopEdges);

        return {
            nodes: resultNodes,
            edges: resultEdges
        };
    }

    /**
     * Provides default metadata for a node or edge when none is specified.
     *
     * @param meta The metadata without the 'meta' property
     * @returns The default metadata object
     */
    getDefaultMetadata(meta: Omit<NodeMetadata | EdgeMetadata, "meta">): object {
        return this.verifyMetadata(meta) ?? {};
    }

    /**
     * Merges two graph metadata objects, with the first having precedence.
     *
     * @param first the primary metadata
     * @param second the secondary metadata
     * @returns the merged metadata
     */
    private mergeMetadata(first: GraphMetadata, second: GraphMetadata): GraphMetadata {
        return {
            nodes: { ...second.nodes, ...first.nodes },
            edges: { ...second.edges, ...first.edges }
        };
    }

    /**
     * Checks the consistency of the metadata.
     * Checks that all edges reference existing nodes.
     * Inconsistent metadata can cause errors down the line.
     *
     * @param metadata The graph metadata to check
     * @throws Error if inconsistencies are found
     */
    private checkMetadataConsistency(metadata: GraphMetadata) {
        for (const edge of Object.values(metadata.edges)) {
            if (!(edge.from in metadata.nodes)) {
                throw new Error(`Edge ${edge} has invalid 'from' reference to non-existent node ${edge.from}`);
            }
            if (!(edge.to in metadata.nodes)) {
                throw new Error(`Edge ${edge} has invalid 'to' reference to non-existent node ${edge.to}`);
            }
        }
    }

    /**
     * Checks if the new metadata is valid with respect to the current metadata.
     * To be considered valid, all nodes and edges in the new metadata
     * must exist in the current metadata with matching types and defined meta.
     *
     * @param newMetadata The new metadata extracted from the graph.
     * @param currentMetadata The current metadata.
     * @returns True if valid, false otherwise.
     */
    private isMetadataValid(newMetadata: GraphMetadata, currentMetadata: GraphMetadata): boolean {
        return (
            Object.entries(newMetadata.nodes).every(([id, newNodeMeta]) => {
                const currentNodeMeta = currentMetadata.nodes[id];
                return currentNodeMeta?.type === newNodeMeta.type && currentNodeMeta.meta != undefined;
            }) &&
            Object.entries(newMetadata.edges).every(([id, newEdgeMeta]) => {
                const currentEdgeMeta = currentMetadata.edges[id];
                return (
                    currentEdgeMeta?.type === newEdgeMeta.type &&
                    currentEdgeMeta?.from === newEdgeMeta.from &&
                    currentEdgeMeta?.to === newEdgeMeta.to &&
                    currentEdgeMeta.meta != undefined
                );
            })
        );
    }

    /**
     * Returns a cleaned version of the metadata if valid, or undefined if no changes are needed.
     *
     * @param newMetadata The new metadata.
     * @param currentMetadata The current metadata.
     * @returns The cleaned metadata or undefined.
     */
    private getCleanMetadata(newMetadata: GraphMetadata, currentMetadata: GraphMetadata): GraphMetadata | undefined {
        const modifiedNodes: Record<string, NodeMetadata> = {};
        const modifiedEdges: Record<string, EdgeMetadata> = {};
        let hasChanges = false;

        for (const [id, currentNodeMeta] of Object.entries(currentMetadata.nodes)) {
            if (id in newMetadata.nodes) {
                const newNodeMeta = newMetadata.nodes[id];
                const verified = this.verifyMetadata(currentNodeMeta);
                if (verified != undefined) {
                    modifiedNodes[id] = {
                        ...newNodeMeta,
                        meta: verified
                    };
                    hasChanges = true;
                } else if (this.areAttributesEqual(currentNodeMeta.attrs, newNodeMeta.attrs)) {
                    modifiedNodes[id] = currentNodeMeta;
                } else {
                    modifiedNodes[id] = {
                        ...newNodeMeta,
                        meta: currentNodeMeta.meta
                    };
                    hasChanges = true;
                }
            } else {
                hasChanges = true;
            }
        }
        for (const [id, currentEdgeMeta] of Object.entries(currentMetadata.edges)) {
            if (id in newMetadata.edges) {
                const newEdgeMeta = newMetadata.edges[id];
                const verified = this.verifyMetadata(currentEdgeMeta);
                if (verified != undefined) {
                    modifiedEdges[id] = {
                        ...newEdgeMeta,
                        meta: verified
                    };
                    hasChanges = true;
                } else if (this.areAttributesEqual(currentEdgeMeta.attrs, newEdgeMeta.attrs)) {
                    modifiedEdges[id] = currentEdgeMeta;
                } else {
                    modifiedEdges[id] = {
                        ...newEdgeMeta,
                        meta: currentEdgeMeta.meta
                    };
                    hasChanges = true;
                }
            } else {
                hasChanges = true;
            }
        }

        if (!hasChanges) {
            return undefined;
        }

        return {
            nodes: modifiedNodes,
            edges: modifiedEdges
        };
    }

    /**
     * Checks that two set of attributes are equal, considering nested objects and arrays.
     * Only works for JSON-like structures
     *
     * @param attrs1 the first attributes object
     * @param attrs2 the second attributes object
     * @return true if the attributes are equal, false otherwise
     */
    private areAttributesEqual(attrs1: any, attrs2: any): boolean {
        if (attrs1 === attrs2) {
            return true;
        }

        if (attrs1 == null || attrs2 == null) {
            return false;
        }

        if (typeof attrs1 !== typeof attrs2) {
            return false;
        }

        if (Array.isArray(attrs1) && Array.isArray(attrs2)) {
            if (attrs1.length !== attrs2.length) {
                return false;
            }
            return attrs1.every((item, index) => this.areAttributesEqual(item, attrs2[index]));
        }

        if (Array.isArray(attrs1) !== Array.isArray(attrs2)) {
            return false;
        }

        if (typeof attrs1 === "object") {
            const keys1 = Object.keys(attrs1);
            const keys2 = Object.keys(attrs2);
            if (keys1.length !== keys2.length) {
                return false;
            }
            return keys1.every((key) => this.areAttributesEqual(attrs1[key], attrs2[key]));
        }

        return false;
    }

    /**
     * Computes the Graph Edit Distance between two graphs.
     *
     * When either graph exceeds {@link GED_PRUNING_THRESHOLD} nodes, identical
     * node/edge pairs are pre-matched and removed from both graphs before
     * running the expensive combinatorial search on the remaining (smaller)
     * subgraphs.  The pre-matched pairs are merged back into the returned
     * paths so all downstream processing remains unchanged.
     *
     * @param currentGraph The current graph.
     * @param newGraph The new graph.
     * @returns The best edit path found or undefined.
     */
    private computeGED(
        currentGraph: MultiGraph,
        newGraph: MultiGraph
    ): [NodeEditPath, EdgeEditPath, number] | undefined {
        const { prunedCurrent, prunedNew, stableNodeIds, stableEdges } = this.pruneGraphs(currentGraph, newGraph);
        const preMatchedNodes: NodeEditPath = [...stableNodeIds].map((id) => [id, id]);
        const preMatchedEdges: EdgeEditPath = stableEdges.map((edge) => [edge, edge]);

        const generator = optimizeEditPaths(prunedCurrent, prunedNew, {
            nodeSubstCost: (a, b) => this.calculateNodeCost(a, b),
            nodeDelCost: (a) => this.calculateNodeCost(a, undefined),
            nodeInsCost: (a) => this.calculateNodeCost(undefined, a),
            edgeSubstCost: (a, b) => this.calculateEdgeCost(a, b),
            edgeDelCost: (a) => this.calculateEdgeCost(a, undefined),
            edgeInsCost: (a) => this.calculateEdgeCost(undefined, a),
            upperBound: 1000
        });

        let lastResult: [NodeEditPath, EdgeEditPath, number] | undefined;
        for (const result of generator) {
            lastResult = result;
        }

        if (lastResult === undefined) {
            return undefined;
        }

        const [nodePath, edgePath, cost] = lastResult;
        return [[...preMatchedNodes, ...nodePath], [...preMatchedEdges, ...edgePath], cost];
    }

    /**
     * Identifies nodes and edges that are structurally identical in both
     * graphs and returns pruned copies with those elements removed.
     *
     * A node is considered stable (identical) when:
     *  - It exists in both graphs with the same ID and type.
     *  - Every edge incident to it in either graph connects exclusively to
     *    other stable nodes, and each such edge has a matching counterpart
     *    (same key and type) in the other graph.
     *
     * The second condition is enforced iteratively: nodes whose incident
     * edges violate it are evicted from the stable set until convergence.
     * This guarantees the stable set forms a self-contained closed subgraph,
     * so removed nodes leave no dangling edges in the pruned graphs.
     *
     * @param currentGraph The current graph.
     * @param newGraph The new graph.
     * @returns Pruned graph copies, the set of stable node IDs, and the list
     *          of stable edge tuples.
     */
    private pruneGraphs(
        currentGraph: MultiGraph,
        newGraph: MultiGraph
    ): {
        prunedCurrent: MultiGraph;
        prunedNew: MultiGraph;
        stableNodeIds: Set<string>;
        stableEdges: Array<EdgeTuple>;
    } {
        const newGraphNodeSet = new Set(newGraph.nodes);
        const candidateStable = new Set<string>();
        for (const id of currentGraph.nodes) {
            if (newGraphNodeSet.has(id) && currentGraph.getNodeData(id).type === newGraph.getNodeData(id).type) {
                candidateStable.add(id);
            }
        }

        let changed = true;
        while (changed) {
            changed = false;
            for (const nodeId of [...candidateStable]) {
                if (this.hasCandidateEdgeMismatch(nodeId, candidateStable, currentGraph, newGraph)) {
                    candidateStable.delete(nodeId);
                    changed = true;
                }
            }
        }

        const stableEdges: Array<EdgeTuple> = [];
        for (const [from, to, key] of currentGraph.edges) {
            if (candidateStable.has(from) && candidateStable.has(to)) {
                stableEdges.push([from, to, key]);
            }
        }

        const prunedCurrent = new MultiGraph();
        for (const id of currentGraph.nodes) {
            if (!candidateStable.has(id)) {
                prunedCurrent.addNode(id, currentGraph.getNodeData(id));
            }
        }
        for (const [from, to, key] of currentGraph.edges) {
            if (!candidateStable.has(from) && !candidateStable.has(to)) {
                prunedCurrent.addEdge(from, to, key, currentGraph.getEdgeData(from, to, key));
            }
        }

        const prunedNew = new MultiGraph();
        for (const id of newGraph.nodes) {
            if (!candidateStable.has(id)) {
                prunedNew.addNode(id, newGraph.getNodeData(id));
            }
        }
        for (const [from, to, key] of newGraph.edges) {
            if (!candidateStable.has(from) && !candidateStable.has(to)) {
                prunedNew.addEdge(from, to, key, newGraph.getEdgeData(from, to, key));
            }
        }

        return { prunedCurrent, prunedNew, stableNodeIds: candidateStable, stableEdges };
    }

    /**
     * Returns true if the given candidate node has any edge mismatch that
     * prevents it from being in the stable set.
     *
     * A mismatch occurs when:
     *  - An edge incident to the node connects to a node outside the
     *    candidate set (edge would become dangling after pruning).
     *  - An edge present in one graph has no counterpart (same key) in the
     *    other graph.
     *  - A matching edge exists in both graphs but with different types.
     *
     * @param nodeId The candidate node to check.
     * @param candidates The current stable candidate set.
     * @param currentGraph The current graph.
     * @param newGraph The new graph.
     */
    private hasCandidateEdgeMismatch(
        nodeId: string,
        candidates: Set<string>,
        currentGraph: MultiGraph,
        newGraph: MultiGraph
    ): boolean {
        const currentAdj = currentGraph.adj(nodeId);
        for (const [neighbor, edgeMap] of currentAdj) {
            if (!candidates.has(neighbor)) {
                return true;
            }
            for (const [key, attrs] of edgeMap) {
                if (!newGraph.hasEdge(nodeId, neighbor, key)) {
                    return true;
                }
                if ((attrs as EdgeAttributes).type !== newGraph.getEdgeData(nodeId, neighbor, key).type) {
                    return true;
                }
            }
        }

        const newAdj = newGraph.adj(nodeId);
        for (const [neighbor, edgeMap] of newAdj) {
            if (!candidates.has(neighbor)) {
                return true;
            }
            for (const [key] of edgeMap) {
                if (!currentGraph.hasEdge(nodeId, neighbor, key)) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Processes the node edit paths to generate the new node metadata and resolve loops.
     *
     * @param nodePath The node edit path.
     * @param newMetadata The new metadata.
     * @param currentMetadata The current metadata.
     * @param currentGraph The current graph.
     * @param newGraph The new graph.
     * @returns The result nodes and loop edges.
     */
    private processNodePaths(
        nodePath: NodeEditPath,
        newMetadata: GraphMetadata,
        currentMetadata: GraphMetadata,
        currentGraph: MultiGraph,
        newGraph: MultiGraph
    ): { nodes: Record<string, NodeMetadata>; loopEdges: Record<string, EdgeMetadata> } {
        const resultNodes: Record<string, NodeMetadata> = {};
        const loopEdges: Record<string, EdgeMetadata> = {};
        for (const [u, v] of nodePath) {
            if (v != null) {
                const newNodeMeta = newMetadata.nodes[v];
                let candidateMeta: NodeMetadata;

                if (u != null) {
                    const oldNodeMeta = currentMetadata.nodes[u];
                    candidateMeta = {
                        ...newNodeMeta,
                        meta: oldNodeMeta.meta
                    };
                } else {
                    candidateMeta = newNodeMeta;
                }

                const correction = this.verifyMetadata(candidateMeta);
                const finalNodeMeta = {
                    ...candidateMeta,
                    meta: correction ?? candidateMeta.meta
                };
                resultNodes[v] = finalNodeMeta;

                const oldNodeAttrs = u != null ? (currentGraph.getNodeData(u) as NodeAttributesWithLoops) : undefined;
                const newNodeAttrs = newGraph.getNodeData(v) as NodeAttributesWithLoops;

                const oldLoopsMap = oldNodeAttrs?.loops || {};
                const newLoopsMap = newNodeAttrs?.loops || {};

                const processedLoops = this.resolveLoops(oldLoopsMap, newLoopsMap);
                Object.assign(loopEdges, processedLoops);
            }
        }
        return { nodes: resultNodes, loopEdges };
    }

    /**
     * Processes the edge edit paths to generate the new edge metadata.
     *
     * @param edgePath The edge edit path.
     * @param newMetadata The new metadata.
     * @param currentMetadata The current metadata.
     * @returns The result edges.
     */
    private processEdgePaths(
        edgePath: EdgeEditPath,
        newMetadata: GraphMetadata,
        currentMetadata: GraphMetadata
    ): Record<string, EdgeMetadata> {
        const resultEdges: Record<string, EdgeMetadata> = {};
        for (const [e1, e2] of edgePath) {
            if (e2 != null) {
                const [, , id2] = e2;
                const newEdgeMeta = newMetadata.edges[id2 as string];

                let candidateMeta: EdgeMetadata;
                if (e1 != null) {
                    const [, , id1] = e1;
                    const oldEdgeMeta = currentMetadata.edges[id1 as string];
                    candidateMeta = {
                        ...newEdgeMeta,
                        meta: oldEdgeMeta.meta
                    };
                } else {
                    candidateMeta = newEdgeMeta;
                }

                const correction = this.verifyMetadata(candidateMeta);
                resultEdges[id2 as string] = {
                    ...candidateMeta,
                    meta: correction ?? candidateMeta.meta
                };
            }
        }
        return resultEdges;
    }

    /**
     * Resolves the mapping between old and new loops using the Hungarian algorithm.
     *
     * @param oldLoops The old loops.
     * @param newLoops The new loops.
     * @returns The resolved loop metadata.
     */
    private resolveLoops(
        oldLoops: Record<string, EdgeMetadata>,
        newLoops: Record<string, EdgeMetadata>
    ): Record<string, EdgeMetadata> {
        const oldIds = Object.keys(oldLoops);
        const newIds = Object.keys(newLoops);
        const n = oldIds.length;
        const m = newIds.length;

        if (n === 0 && m === 0) {
            return {};
        }

        const matrix = this.buildLoopCostMatrix(oldLoops, newLoops, oldIds, newIds);
        const [rowInd, colInd] = linearSumAssignment(matrix);

        return this.processLoopAssignment(rowInd, colInd, oldIds, newIds, oldLoops, newLoops);
    }

    /**
     * Builds the cost matrix for loop assignment.
     *
     * @param oldLoops The old loops.
     * @param newLoops The new loops.
     * @param oldIds The IDs of the old loops.
     * @param newIds The IDs of the new loops.
     * @returns The cost matrix.
     */
    private buildLoopCostMatrix(
        oldLoops: Record<string, EdgeMetadata>,
        newLoops: Record<string, EdgeMetadata>,
        oldIds: string[],
        newIds: string[]
    ): number[][] {
        const n = oldIds.length;
        const m = newIds.length;
        const size = n + m;
        const matrix: number[][] = Array(size)
            .fill(0)
            .map(() => Array(size).fill(0));

        const oldLoopAttrs = oldIds.map((id) => ({ ...oldLoops[id].attrs, id, type: oldLoops[id].type }));
        const newLoopAttrs = newIds.map((id) => ({ ...newLoops[id].attrs, id, type: newLoops[id].type }));

        for (let i = 0; i < n; i++) {
            for (let j = 0; j < m; j++) {
                matrix[i][j] = this.calculateEdgeCost(oldLoopAttrs[i], newLoopAttrs[j]);
            }
            for (let k = 0; k < n; k++) {
                matrix[i][m + k] = i === k ? this.calculateEdgeCost(oldLoopAttrs[i], undefined) : Number.MAX_VALUE;
            }
        }

        for (let k = 0; k < m; k++) {
            for (let j = 0; j < m; j++) {
                matrix[n + k][j] = k === j ? this.calculateEdgeCost(undefined, newLoopAttrs[j]) : Number.MAX_VALUE;
            }
        }
        return matrix;
    }

    /**
     * Processes the loop assignment results.
     *
     * @param rowInd The row indices from the assignment.
     * @param colInd The column indices from the assignment.
     * @param oldIds The IDs of the old loops.
     * @param newIds The IDs of the new loops.
     * @param oldLoops The old loops.
     * @param newLoops The new loops.
     * @returns The resolved loop metadata.
     */
    private processLoopAssignment(
        rowInd: number[],
        colInd: number[],
        oldIds: string[],
        newIds: string[],
        oldLoops: Record<string, EdgeMetadata>,
        newLoops: Record<string, EdgeMetadata>
    ): Record<string, EdgeMetadata> {
        const result: Record<string, EdgeMetadata> = {};
        const n = oldIds.length;
        const m = newIds.length;

        for (let k = 0; k < rowInd.length; k++) {
            const i = rowInd[k];
            const j = colInd[k];

            if (i < n && j < m) {
                const oldId = oldIds[i];
                const newId = newIds[j];
                const oldMeta = oldLoops[oldId];
                const newMeta = newLoops[newId];

                const candidate = { ...newMeta, meta: oldMeta.meta };
                const correction = this.verifyMetadata(candidate);
                result[newId] = { ...candidate, meta: correction ?? candidate.meta };
            } else if (i >= n && j < m) {
                const newId = newIds[j];
                const newMeta = newLoops[newId];
                const correction = this.verifyMetadata(newMeta);
                result[newId] = { ...newMeta, meta: correction ?? newMeta.meta };
            }
        }
        return result;
    }

    /**
     * Converts the graph metadata to a MultiGraph for GED calculation.
     *
     * @param metadata The graph metadata.
     * @returns The MultiGraph representation.
     */
    private convertToMultiGraph(metadata: GraphMetadata): MultiGraph {
        const graph = new MultiGraph();
        const loops: Record<string, Record<string, EdgeMetadata>> = {};
        const regularEdges: Record<string, EdgeMetadata> = {};

        for (const [id, edge] of Object.entries(metadata.edges)) {
            if (edge.from === edge.to) {
                if (!loops[edge.from]) {
                    loops[edge.from] = {};
                }
                loops[edge.from][id] = edge;
            } else {
                regularEdges[id] = edge;
            }
        }

        for (const [id, node] of Object.entries(metadata.nodes)) {
            const nodeLoops = loops[id] || {};
            const attrs: NodeAttributesWithLoops = {
                ...node.attrs,
                id,
                type: node.type,
                loops: nodeLoops
            };
            graph.addNode(id, attrs);
        }
        for (const [id, edge] of Object.entries(regularEdges)) {
            graph.addEdge(edge.from, edge.to, id, {
                ...edge.attrs,
                id,
                type: edge.type
            });
        }
        return graph;
    }
}
