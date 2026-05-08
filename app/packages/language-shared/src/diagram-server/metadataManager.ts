import { sharedImport } from "../sharedImport.js";
import type { EdgeMetadata, GraphMetadata, NodeMetadata } from "./metadata.js";
import { MultiGraph, type NodeAttributes, type EdgeAttributes } from "./graph-edit-distance/multiGraph.js";
import { type NodeEditPath, type EdgeEditPath, type EdgeTuple } from "./graph-edit-distance/graphEditDistance.js";
import { runGEDInWorker, type GEDResult, type GEDLoopAssignment } from "./graph-edit-distance/gedWorkerBase.js";
import type { AstNode } from "langium";
import { AstReflectionKey, LanguageServicesKey } from "./langiumServices.js";
import type { AstReflection, LanguageServices } from "@mdeo/language-common";

const { injectable, inject } = sharedImport("inversify");

/**
 * Maximum absolute difference between the number of nodes in the two pruned
 * graphs above which the GED computation is skipped entirely.
 * When the graphs differ by more than this many nodes the combinatorial search
 * is unlikely to produce useful matches and would be prohibitively expensive.
 */
const GED_MAX_NODE_DIFFERENCE = 10;

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
     * Extracts the graph metadata from the given source model.
     * Implementations should traverse the source model and generate metadata
     * for all nodes and edges that will be visualized.
     *
     * @param sourceModel The source model to extract metadata from
     * @returns The computed graph metadata
     */
    protected abstract extractGraphMetadata(sourceModel: T): GraphMetadata;

    /**
     * Absolute URL of the language-specific GED sub-worker script.
     *
     * When defined, the GED computation is offloaded to a dedicated worker
     * and cancelled after {@link GED_WORKER_TIMEOUT_MS} if it does not finish
     * in time.  When undefined (the default), no worker is spawned and GED
     * is skipped entirely.
     *
     * Override in subclasses to enable worker-based GED, e.g.:
     * ```ts
     * protected override gedWorkerUrl = "/plugin/metamodel/static/gedWorker.js";
     * ```
     */
    protected gedWorkerUrl: string | undefined = undefined;

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

        const gedResult = this.computeGED(currentGraph, newGraph);

        if (gedResult == undefined) {
            return this.applyDefaultMetadata(newMetadata, mergedMetadata);
        }

        const { nodePath, edgePath, loopAssignments } = gedResult;
        const { nodes: resultNodes, loopEdges } = this.processNodePaths(
            nodePath,
            newMetadata,
            mergedMetadata,
            currentGraph,
            newGraph,
            loopAssignments
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
     * Identical node/edge pairs are first pruned from both graphs so the
     * expensive combinatorial search operates on a smaller subgraph only.
     * If the pruned graphs differ by more than {@link GED_MAX_NODE_DIFFERENCE}
     * nodes the computation is skipped and undefined is returned.
     *
     * When {@link gedWorkerUrl} is set the search runs inside a dedicated
     * sub-worker and is cancelled after one second; otherwise undefined is
     * returned and no synchronous GED is attempted.
     *
     * @param currentGraph The current graph.
     * @param newGraph The new graph.
     * @returns A {@link GEDResult} with merged edit paths and loop assignments,
     *          or `undefined` when skipped or timed out.
     */
    private computeGED(currentGraph: MultiGraph, newGraph: MultiGraph): GEDResult | undefined {
        const { prunedCurrent, prunedNew, stableNodeIds, stableEdges } = this.pruneGraphs(currentGraph, newGraph);

        const preMatchedNodes: NodeEditPath = [...stableNodeIds].map((id) => [id, id]);
        const preMatchedEdges: EdgeEditPath = stableEdges.map((edge) => [edge, edge]);

        // Skip GED when the pruned graphs differ too much in size – the search
        // space would be too large and no good matching is likely to exist.
        const nodeDifference = Math.abs(prunedCurrent.numberOfNodes - prunedNew.numberOfNodes);
        if (nodeDifference > GED_MAX_NODE_DIFFERENCE) {
            return undefined;
        }

        if (this.gedWorkerUrl == undefined) {
            return undefined;
        }

        const workerResult = runGEDInWorker(this.gedWorkerUrl, prunedCurrent, prunedNew);
        if (workerResult === undefined) {
            return undefined;
        }

        const { nodePath, edgePath, cost, loopAssignments } = workerResult;
        return {
            nodePath: [...preMatchedNodes, ...nodePath],
            edgePath: [...preMatchedEdges, ...edgePath],
            cost,
            // Loop assignments cover only the pruned (non-stable) node pairs.
            // Stable node pairs are handled separately in processNodePaths.
            loopAssignments
        };
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
     * Processes the node edit paths to generate new node metadata and carry over
     * loop-edge metadata using the pre-computed {@link GEDLoopAssignment} list.
     *
     * Three cases are handled per node pair `[u, v]`:
     * - **Non-stable matched pair** (`u != null`, `v != null`, assignment exists): the
     *   worker's Hungarian result is used to map old loop-edge metadata to new IDs.
     * - **New node** (`u == null`) or **stable matched pair** (no assignment because
     *   both sides have no loops): existing metadata is carried over by ID when
     *   possible, otherwise {@link verifyMetadata} supplies defaults.
     * - **Deleted node** (`v == null`): skipped entirely.
     *
     * @param nodePath The node edit path, including pre-matched stable pairs.
     * @param newMetadata The freshly extracted graph metadata.
     * @param currentMetadata The merged current+last-valid metadata used as
     *        the source of existing layout positions.
     * @param currentGraph The current (merged) graph, used to read loop data for
     *        stable node pairs.
     * @param newGraph The new graph, used to read loop data for new/stable nodes.
     * @param loopAssignments Loop-edge assignments from the sub-worker, covering
     *        only the non-stable node pairs in the pruned graph.
     * @returns An object with the resolved node metadata map and loop-edge metadata map.
     */
    private processNodePaths(
        nodePath: NodeEditPath,
        newMetadata: GraphMetadata,
        currentMetadata: GraphMetadata,
        currentGraph: MultiGraph,
        newGraph: MultiGraph,
        loopAssignments: GEDLoopAssignment[]
    ): { nodes: Record<string, NodeMetadata>; loopEdges: Record<string, EdgeMetadata> } {
        const resultNodes: Record<string, NodeMetadata> = {};
        const loopEdges: Record<string, EdgeMetadata> = {};

        const loopAssignmentMap = new Map<string, GEDLoopAssignment>(loopAssignments.map((la) => [la.newNodeId, la]));

        for (const [u, v] of nodePath) {
            if (v == null) {
                continue;
            }

            const newNodeMeta = newMetadata.nodes[v];
            const candidateMeta: NodeMetadata =
                u != null ? { ...newNodeMeta, meta: currentMetadata.nodes[u]?.meta } : newNodeMeta;

            const correction = this.verifyMetadata(candidateMeta);
            resultNodes[v] = { ...candidateMeta, meta: correction ?? candidateMeta.meta };

            // Resolve loop edges for this node.
            const loopAssignment = loopAssignmentMap.get(v);
            if (loopAssignment !== undefined) {
                // Non-stable matched pair: transfer metadata according to worker's assignment.
                for (const [oldEdgeId, newEdgeId] of loopAssignment.pairs) {
                    const newEdgeMeta = newMetadata.edges[newEdgeId];
                    if (newEdgeMeta === undefined) continue;
                    const candidate: EdgeMetadata =
                        oldEdgeId != null
                            ? { ...newEdgeMeta, meta: currentMetadata.edges[oldEdgeId]?.meta }
                            : newEdgeMeta;
                    const edgeCorrection = this.verifyMetadata(candidate);
                    loopEdges[newEdgeId] = { ...candidate, meta: edgeCorrection ?? candidate.meta };
                }
            } else {
                // Stable matched node (u == v) or matched node with no loops:
                // carry over metadata by ID, defaulting to verifyMetadata for new entries.
                const newLoops = (newGraph.getNodeData(v) as NodeAttributesWithLoops).loops ?? {};
                for (const edgeId of Object.keys(newLoops)) {
                    const newEdgeMeta = newMetadata.edges[edgeId];
                    if (newEdgeMeta === undefined) continue;
                    const existingMeta = currentMetadata.edges[edgeId]?.meta;
                    const candidate: EdgeMetadata = { ...newEdgeMeta, meta: existingMeta };
                    const edgeCorrection = this.verifyMetadata(candidate);
                    loopEdges[edgeId] = { ...candidate, meta: edgeCorrection ?? candidate.meta };
                }
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
     * Applies a best-effort metadata transfer when full GED computation is unavailable
     * (e.g. graphs differ by more than {@link GED_MAX_NODE_DIFFERENCE} nodes, or the
     * worker timed out).
     *
     * For each node and edge in `newMetadata`, the layout metadata from
     * `mergedMetadata` is carried over when an entry with the same ID exists.
     * Entries with no existing counterpart receive defaults via {@link verifyMetadata}.
     *
     * @param newMetadata The freshly extracted graph metadata (positions absent).
     * @param mergedMetadata The merged current+last-valid metadata used as the
     *        source of existing layout positions.
     * @returns A new {@link GraphMetadata} with layout metadata carried over where possible.
     */
    private applyDefaultMetadata(newMetadata: GraphMetadata, mergedMetadata: GraphMetadata): GraphMetadata {
        const nodes: Record<string, NodeMetadata> = {};
        for (const [id, newNode] of Object.entries(newMetadata.nodes)) {
            const existingMeta = mergedMetadata.nodes[id]?.meta;
            const candidate: NodeMetadata = { ...newNode, meta: existingMeta };
            const correction = this.verifyMetadata(candidate);
            nodes[id] = { ...newNode, meta: correction ?? existingMeta };
        }
        const edges: Record<string, EdgeMetadata> = {};
        for (const [id, newEdge] of Object.entries(newMetadata.edges)) {
            const existingMeta = mergedMetadata.edges[id]?.meta;
            const candidate: EdgeMetadata = { ...newEdge, meta: existingMeta };
            const correction = this.verifyMetadata(candidate);
            edges[id] = { ...newEdge, meta: correction ?? existingMeta };
        }
        return { nodes, edges };
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
