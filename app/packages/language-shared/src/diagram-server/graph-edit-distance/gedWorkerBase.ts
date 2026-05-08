/**
 * Base infrastructure for running GED computation in a dedicated sub-worker.
 *
 * Layout of the shared buffer (all offsets in bytes):
 *   [0..3]   Int32  – signal: 0 = worker still running, 1 = done
 *   [4..7]   Int32  – result byte length (-1 on error, 0 if no result)
 *   [8..]    Uint8  – UTF-8-encoded JSON of the GED result
 *
 * The parent worker calls {@link runGEDInWorker}, which blocks for up to
 * {@link GED_WORKER_TIMEOUT_MS} milliseconds using Atomics.wait.
 * The sub-worker calls {@link runGEDWorker} which performs the computation
 * and writes the result into the shared buffer before signalling.
 */

import { MultiGraph } from "./multiGraph.js";
import { optimizeEditPaths, type GEDOptions, type NodeEditPath, type EdgeEditPath } from "./graphEditDistance.js";
import type { NodeAttributes, EdgeAttributes } from "./multiGraph.js";
import { linearSumAssignment } from "./hungarian.js";

export { MultiGraph };

/** Offset of the signal Int32 within the shared buffer. */
const SIGNAL_OFFSET = 0;
/** Offset of the result-length Int32 within the shared buffer. */
const LENGTH_OFFSET = 4;
/** Offset of the result bytes within the shared buffer. */
const DATA_OFFSET = 8;
/** Total size of the shared buffer in bytes (signal + length + 512 KB for JSON). */
const SHARED_BUFFER_SIZE = DATA_OFFSET + 512 * 1024;
/** Timeout in milliseconds before the GED worker is terminated. */
export const GED_WORKER_TIMEOUT_MS = 1000;

/**
 * Minimal shape of a self-loop edge entry stored inside node attributes.
 * Used when nodes carry their self-loops as an attribute map.
 */
interface LoopEdge {
    type: string;
    attrs?: Record<string, unknown>;
}

/**
 * A serializable snapshot of a {@link MultiGraph} that can be transferred to a sub-worker.
 */
export interface SerializedGraph {
    nodes: Array<{ id: string; attributes: NodeAttributes }>;
    edges: Array<{ from: string; to: string; key: string | number; attributes: EdgeAttributes }>;
}

/**
 * The message posted to the GED sub-worker.
 */
export interface GEDWorkerRequest {
    sharedBuffer: SharedArrayBuffer;
    currentGraph: SerializedGraph;
    newGraph: SerializedGraph;
}

/**
 * Loop-edge assignment for a single matched node pair, computed inside the sub-worker.
 *
 * Each entry describes how the self-loop edges of an old node map to those of
 * the corresponding new node after Hungarian-algorithm matching.  Only pairs
 * where a new edge is present are included; deletions (no new counterpart) are
 * omitted since the caller only needs to know where to transfer metadata.
 */
export interface GEDLoopAssignment {
    /** ID of the matched node in the current (old) graph. */
    oldNodeId: string;
    /** ID of the matched node in the new graph. */
    newNodeId: string;
    /**
     * Assignment pairs `[oldEdgeId | null, newEdgeId]`.
     *
     * A `null` old-edge ID means the new loop edge has no match in the old
     * graph and should be treated as an insertion (default metadata).
     */
    pairs: Array<[string | null, string]>;
}

/**
 * The full result returned by the GED sub-worker.
 *
 * Combines the standard GED edit-path information with pre-computed loop-edge
 * assignments so that the caller never has to invoke the language-specific cost
 * functions outside the worker.
 */
export interface GEDResult {
    /** Node edit path: `[oldNodeId | null, newNodeId | null]` pairs. */
    nodePath: NodeEditPath;
    /** Edge edit path: `[oldEdgeTuple | null, newEdgeTuple | null]` pairs. */
    edgePath: EdgeEditPath;
    /** Total GED cost of the best edit path found. */
    cost: number;
    /**
     * Loop-edge assignments for each matched node pair (both IDs non-null).
     * Stable (pre-matched) node pairs are not included; the caller handles
     * those directly by ID.
     */
    loopAssignments: GEDLoopAssignment[];
}

/**
 * Serializes a MultiGraph into a plain transferable object.
 *
 * @param graph The graph to serialize.
 * @returns A plain-object representation of the graph.
 */
export function serializeGraph(graph: MultiGraph): SerializedGraph {
    const nodes = graph.nodes.map((id) => ({ id, attributes: graph.getNodeData(id) }));
    const edges = [...graph.edges].map(([from, to, key]) => ({
        from,
        to,
        key,
        attributes: graph.getEdgeData(from, to, key)
    }));
    return { nodes, edges };
}

/**
 * Deserializes a {@link SerializedGraph} back into a MultiGraph.
 *
 * @param serialized The serialized graph.
 * @returns A new MultiGraph populated with the serialized data.
 */
function deserializeGraph(serialized: SerializedGraph): MultiGraph {
    const graph = new MultiGraph();
    for (const { id, attributes } of serialized.nodes) {
        graph.addNode(id, attributes);
    }
    for (const { from, to, key, attributes } of serialized.edges) {
        graph.addEdge(from, to, key, attributes);
    }
    return graph;
}

/**
 * Reads the `loops` map stored as a node attribute (cast helper).
 *
 * @param graph The graph to read from.
 * @param nodeId The node whose loop map should be returned.
 * @returns The loop map, or an empty object when no loops are present.
 */
function getLoops(graph: MultiGraph, nodeId: string): Record<string, LoopEdge> {
    return (graph.getNodeData(nodeId) as NodeAttributes & { loops?: Record<string, LoopEdge> }).loops ?? {};
}

/**
 * Computes Hungarian-algorithm-based loop-edge assignments for every matched
 * node pair in the given node edit path.
 *
 * Only pairs where both node IDs are non-null are processed; insertions and
 * deletions are skipped.  Node pairs whose loop sets are both empty are also
 * skipped (no assignment entry is emitted for them).
 *
 * @param nodePath The node edit path produced by {@link optimizeEditPaths}.
 * @param currentGraph The current (old) graph.
 * @param newGraph The new graph.
 * @param costOptions The language-specific edge cost functions.
 * @returns An array of loop assignments, one per matched node pair with loops.
 */
function computeLoopAssignments(
    nodePath: NodeEditPath,
    currentGraph: MultiGraph,
    newGraph: MultiGraph,
    costOptions: Omit<GEDOptions, "upperBound">
): GEDLoopAssignment[] {
    const edgeSubstCost = costOptions.edgeSubstCost ?? ((e1, e2) => (e1.type === e2.type ? 0 : 1));
    const edgeDelCost = costOptions.edgeDelCost ?? (() => 1);
    const edgeInsCost = costOptions.edgeInsCost ?? (() => 1);

    const assignments: GEDLoopAssignment[] = [];

    for (const [u, v] of nodePath) {
        if (u == null || v == null) {
            continue;
        }

        const oldLoops = getLoops(currentGraph, u);
        const newLoops = getLoops(newGraph, v);

        const oldIds = Object.keys(oldLoops);
        const newIds = Object.keys(newLoops);

        if (oldIds.length === 0 && newIds.length === 0) {
            continue;
        }

        const n = oldIds.length;
        const m = newIds.length;
        const size = n + m;

        const matrix: number[][] = Array(size)
            .fill(0)
            .map(() => Array(size).fill(0));

        const oldAttrs = oldIds.map((id) => ({ ...oldLoops[id].attrs, id, type: oldLoops[id].type }) as EdgeAttributes);
        const newAttrs = newIds.map((id) => ({ ...newLoops[id].attrs, id, type: newLoops[id].type }) as EdgeAttributes);

        for (let i = 0; i < n; i++) {
            for (let j = 0; j < m; j++) {
                matrix[i][j] = edgeSubstCost(oldAttrs[i], newAttrs[j]);
            }
            for (let k = 0; k < n; k++) {
                matrix[i][m + k] = i === k ? edgeDelCost(oldAttrs[i]) : Number.MAX_VALUE;
            }
        }
        for (let k = 0; k < m; k++) {
            for (let j = 0; j < m; j++) {
                matrix[n + k][j] = k === j ? edgeInsCost(newAttrs[j]) : Number.MAX_VALUE;
            }
        }

        const [rowInd, colInd] = linearSumAssignment(matrix);

        const pairs: Array<[string | null, string]> = [];
        for (let idx = 0; idx < rowInd.length; idx++) {
            const i = rowInd[idx];
            const j = colInd[idx];
            if (j < m) {
                // New edge appears in the result; record which old edge (if any) maps to it.
                pairs.push([i < n ? oldIds[i] : null, newIds[j]]);
            }
            // j >= m means deletion — no new edge, nothing to carry over.
        }

        assignments.push({ oldNodeId: u, newNodeId: v, pairs });
    }

    return assignments;
}

/**
 * Installs the `onmessage` handler inside a GED sub-worker.
 *
 * Performs the full GED computation and, afterwards, Hungarian-algorithm loop
 * matching for every matched node pair.  Both results are serialised into the
 * shared buffer as a {@link GEDResult} JSON object.
 *
 * Call this function once at the top level of each language-specific GED worker
 * script, providing the language-specific cost callbacks.
 *
 * @param costOptions Language-specific node/edge cost functions (without `upperBound`).
 */
export function runGEDWorker(costOptions: Omit<GEDOptions, "upperBound">): void {
    (self as any).onmessage = (event: MessageEvent<GEDWorkerRequest>) => {
        const { sharedBuffer, currentGraph: serializedCurrent, newGraph: serializedNew } = event.data;

        const signalArray = new Int32Array(sharedBuffer, SIGNAL_OFFSET, 1);
        const lengthArray = new Int32Array(sharedBuffer, LENGTH_OFFSET, 1);

        try {
            const currentGraph = deserializeGraph(serializedCurrent);
            const newGraph = deserializeGraph(serializedNew);

            const generator = optimizeEditPaths(currentGraph, newGraph, {
                ...costOptions,
                upperBound: 1000
            });

            let lastResult: [NodeEditPath, EdgeEditPath, number] | undefined;
            for (const result of generator) {
                lastResult = result;
            }

            if (lastResult !== undefined) {
                const [nodePath, edgePath, cost] = lastResult;
                const loopAssignments = computeLoopAssignments(nodePath, currentGraph, newGraph, costOptions);
                const gedResult: GEDResult = { nodePath, edgePath, cost, loopAssignments };
                const json = JSON.stringify(gedResult);
                const encoded = new TextEncoder().encode(json);
                const dataArray = new Uint8Array(sharedBuffer, DATA_OFFSET, encoded.length);
                dataArray.set(encoded);
                Atomics.store(lengthArray, 0, encoded.length);
            } else {
                Atomics.store(lengthArray, 0, 0);
            }
        } catch {
            Atomics.store(lengthArray, 0, -1);
        }

        Atomics.store(signalArray, 0, 1);
        Atomics.notify(signalArray, 0);
    };
}

/**
 * Runs the GED algorithm inside a dedicated sub-worker and synchronously waits
 * up to {@link GED_WORKER_TIMEOUT_MS} for a result.
 *
 * Requires `SharedArrayBuffer` to be available (cross-origin isolation headers
 * `Cross-Origin-Opener-Policy: same-origin` and
 * `Cross-Origin-Embedder-Policy: require-corp` must be set).
 *
 * @param workerUrl Absolute URL of the language-specific GED worker script.
 * @param currentGraph Pruned current graph to pass to the worker.
 * @param newGraph Pruned new graph to pass to the worker.
 * @returns A {@link GEDResult} with the best edit paths and loop assignments found
 *          within the timeout, or `undefined` if the worker timed out or
 *          SharedArrayBuffer is unavailable.
 */
export function runGEDInWorker(
    workerUrl: string,
    currentGraph: MultiGraph,
    newGraph: MultiGraph
): GEDResult | undefined {
    if (typeof SharedArrayBuffer === "undefined") {
        return undefined;
    }

    const sharedBuffer = new SharedArrayBuffer(SHARED_BUFFER_SIZE);
    const signalArray = new Int32Array(sharedBuffer, SIGNAL_OFFSET, 1);
    const lengthArray = new Int32Array(sharedBuffer, LENGTH_OFFSET, 1);

    Atomics.store(signalArray, 0, 0);
    Atomics.store(lengthArray, 0, 0);

    const worker = new Worker(workerUrl, { type: "module" });
    const request: GEDWorkerRequest = {
        sharedBuffer,
        currentGraph: serializeGraph(currentGraph),
        newGraph: serializeGraph(newGraph)
    };
    worker.postMessage(request);

    const waitResult = Atomics.wait(signalArray, 0, 0, GED_WORKER_TIMEOUT_MS);

    if (waitResult === "timed-out") {
        worker.terminate();
        return undefined;
    }

    worker.terminate();

    const byteLength = Atomics.load(lengthArray, 0);
    if (byteLength <= 0) {
        return undefined;
    }

    const dataBytes = new Uint8Array(sharedBuffer, DATA_OFFSET, byteLength);
    const json = new TextDecoder().decode(dataBytes);
    return JSON.parse(json) as GEDResult;
}
