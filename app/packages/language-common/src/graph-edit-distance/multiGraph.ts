/**
 * MultiGraph implementation for Graph Edit Distance
 * Aligned with NetworkX's MultiGraph structure
 */

export type NodeId = string;
export type EdgeKey = string | number;
export interface NodeAttributes extends Record<string, unknown> {
    id: string;
    type: string;
}
export interface EdgeAttributes extends Record<string, unknown> {
    id: string;
    type: string;
}

export interface EdgeData {
    source: NodeId;
    target: NodeId;
    key: EdgeKey;
    attributes: EdgeAttributes;
}

/**
 * A MultiGraph that allows multiple edges between nodes.
 * Each edge is identified by (source, target, key).
 * This is an undirected graph implementation aligned with NetworkX's MultiGraph structure.
 */
export class MultiGraph {
    /**
     * Node storage: nodeId -> attributes
     */
    private _nodes: Map<NodeId, NodeAttributes> = new Map();

    /**
     * Edge storage: Map<source, Map<target, Map<key, attributes>>>
     */
    private _adj: Map<NodeId, Map<NodeId, Map<EdgeKey, EdgeAttributes>>> = new Map();

    /**
     * Edge list cache for iteration (invalidated on modification)
     */
    private _edgeCache: Array<[NodeId, NodeId, EdgeKey]> | null = null;

    constructor() {}

    /**
     * Add a node with optional attributes
     * @param nodeId The unique identifier for the node
     * @param attributes Optional attributes to attach to the node
     */
    addNode(nodeId: NodeId, attributes: NodeAttributes): void {
        this._nodes.set(nodeId, { ...attributes });
        if (!this._adj.has(nodeId)) {
            this._adj.set(nodeId, new Map());
        }
        this._edgeCache = null;
    }

    /**
     * Check if a node exists
     * @param nodeId The node identifier to check
     * @returns True if the node exists in the graph, false otherwise
     */
    hasNode(nodeId: NodeId): boolean {
        return this._nodes.has(nodeId);
    }

    /**
     * Get node attributes
     * @param nodeId The node identifier
     * @returns The attributes associated with the node
     * @throws Error if the node is not found
     */
    getNodeData(nodeId: NodeId): NodeAttributes {
        const data = this._nodes.get(nodeId);
        if (data === undefined) {
            throw new Error(`Node ${nodeId} not found`);
        }
        return data;
    }

    /**
     * Get all nodes
     * @returns Array of all node identifiers in the graph
     */
    get nodes(): NodeId[] {
        return Array.from(this._nodes.keys());
    }

    /**
     * Get number of nodes
     * @returns The total count of nodes in the graph
     */
    get numberOfNodes(): number {
        return this._nodes.size;
    }

    /**
     * Add an edge with optional key and attributes.
     * For MultiGraph, if key is not provided, auto-generates one.
     * If the source or target nodes don't exist, they will be created automatically.
     * @param source The source node identifier
     * @param target The target node identifier
     * @param key Optional edge key. If not provided, an auto-generated numeric key is used
     * @param attributes Optional attributes to attach to the edge
     * @returns The key of the added edge (either provided or auto-generated)
     */
    addEdge(source: NodeId, target: NodeId, key: EdgeKey, attributes: EdgeAttributes): EdgeKey {
        if (!this._nodes.has(source)) {
            throw new Error(`Source node ${source} does not exist`);
        }
        if (!this._nodes.has(target)) {
            throw new Error(`Target node ${target} does not exist`);
        }

        let sourceAdj = this._adj.get(source);
        if (!sourceAdj) {
            sourceAdj = new Map();
            this._adj.set(source, sourceAdj);
        }

        let targetAdj = this._adj.get(target);
        if (!targetAdj) {
            targetAdj = new Map();
            this._adj.set(target, targetAdj);
        }

        let edgeMap = sourceAdj.get(target);
        if (!edgeMap) {
            edgeMap = new Map();
            sourceAdj.set(target, edgeMap);
        }

        if (key === undefined) {
            let k = 0;
            while (edgeMap.has(k)) {
                k++;
            }
            key = k;
        }

        edgeMap.set(key, { ...attributes });

        if (source !== target) {
            let reverseEdgeMap = targetAdj.get(source);
            if (!reverseEdgeMap) {
                reverseEdgeMap = new Map();
                targetAdj.set(source, reverseEdgeMap);
            }
            reverseEdgeMap.set(key, edgeMap.get(key)!);
        }

        this._edgeCache = null;
        return key;
    }

    /**
     * Check if an edge exists
     * @param source The source node identifier
     * @param target The target node identifier
     * @param key Optional edge key. If not provided, checks if any edge exists between the nodes
     * @returns True if the edge exists, false otherwise
     */
    hasEdge(source: NodeId, target: NodeId, key?: EdgeKey): boolean {
        const sourceAdj = this._adj.get(source);
        if (!sourceAdj) {
            return false;
        }

        const edgeMap = sourceAdj.get(target);
        if (!edgeMap) {
            return false;
        }

        if (key === undefined) {
            return edgeMap.size > 0;
        }
        return edgeMap.has(key);
    }

    /**
     * Get edge attributes
     * @param source The source node identifier
     * @param target The target node identifier
     * @param key The edge key
     * @returns The attributes associated with the edge
     * @throws Error if the edge is not found
     */
    getEdgeData(source: NodeId, target: NodeId, key: EdgeKey): EdgeAttributes {
        const sourceAdj = this._adj.get(source);
        if (!sourceAdj) {
            throw new Error(`Edge (${source}, ${target}, ${key}) not found`);
        }

        const edgeMap = sourceAdj.get(target);
        if (!edgeMap) {
            throw new Error(`Edge (${source}, ${target}, ${key}) not found`);
        }

        const data = edgeMap.get(key);
        if (data === undefined) {
            throw new Error(`Edge (${source}, ${target}, ${key}) not found`);
        }

        return data;
    }

    /**
     * Get all edges as (source, target, key) tuples
     * For undirected MultiGraph, each edge appears once with source <= target (canonical form)
     * @returns Array of edge tuples, each containing [source, target, key]
     */
    get edges(): Array<[NodeId, NodeId, EdgeKey]> {
        if (this._edgeCache !== null) {
            return this._edgeCache;
        }

        const result: Array<[NodeId, NodeId, EdgeKey]> = [];
        const seen = new Set<string>();

        for (const [source, sourceAdj] of this._adj) {
            for (const [target, edgeMap] of sourceAdj) {
                for (const key of edgeMap.keys()) {
                    const canonicalKey = source <= target ? `${source}-${target}-${key}` : `${target}-${source}-${key}`;

                    if (!seen.has(canonicalKey)) {
                        seen.add(canonicalKey);
                        if (source <= target) {
                            result.push([source, target, key]);
                        } else {
                            result.push([target, source, key]);
                        }
                    }
                }
            }
        }

        this._edgeCache = result;
        return result;
    }

    /**
     * Get number of edges
     * @returns The total count of edges in the graph
     */
    get numberOfEdges(): number {
        return this.edges.length;
    }

    /**
     * Get the adjacency map for a node (for edge attribute access like G[u][v])
     * @param nodeId The node identifier
     * @returns A map of target nodes to their edge maps (key -> attributes)
     * @throws Error if the node is not found
     */
    adj(nodeId: NodeId): Map<NodeId, Map<EdgeKey, EdgeAttributes>> {
        const adj = this._adj.get(nodeId);
        if (!adj) {
            throw new Error(`Node ${nodeId} not found`);
        }
        return adj;
    }

    /**
     * Create a deep copy of the graph
     * @returns A new MultiGraph instance with all nodes and edges copied
     */
    copy(): MultiGraph {
        const newGraph = new MultiGraph();

        for (const [nodeId, attrs] of this._nodes) {
            newGraph.addNode(nodeId, { ...attrs });
        }

        for (const [source, target, key] of this.edges) {
            const attrs = this.getEdgeData(source, target, key);
            newGraph.addEdge(source, target, key, { ...attrs });
        }

        return newGraph;
    }
}
