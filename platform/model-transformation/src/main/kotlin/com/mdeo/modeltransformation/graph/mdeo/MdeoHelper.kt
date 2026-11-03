package com.mdeo.modeltransformation.graph.mdeo

import org.apache.tinkerpop.gremlin.structure.Direction
import org.apache.tinkerpop.gremlin.structure.Edge
import org.apache.tinkerpop.gremlin.structure.Vertex

/**
 * Utility methods for navigating [MdeoGraph] elements.
 *
 * Provides efficient edge and vertex traversal without transactional or computer-mode indirection.
 */
object MdeoHelper {

    /**
     * Returns an iterator over edges adjacent to the given vertex in the specified direction.
     *
     * @param vertex The vertex to query.
     * @param direction The edge direction (OUT, IN, or BOTH).
     * @param edgeLabels Edge labels to filter by. If empty, returns all edges.
     * @return An iterator over matching edges.
     */
    @JvmStatic
    fun getEdges(vertex: MdeoVertex, direction: Direction, vararg edgeLabels: String): Iterator<Edge> {
        val edges = mutableListOf<Edge>()
        if (direction == Direction.OUT || direction == Direction.BOTH) {
            vertex.outEdges?.let { collectEdges(vertex.graph, it, edgeLabels, edges) }
        }
        if (direction == Direction.IN || direction == Direction.BOTH) {
            vertex.inEdges?.let { collectEdges(vertex.graph, it, edgeLabels, edges) }
        }
        return edges.iterator()
    }

    /**
     * Returns an iterator over vertices adjacent to the given vertex via edges in the specified direction.
     *
     * @param vertex The vertex to query.
     * @param direction The traversal direction.
     * @param edgeLabels Edge labels to filter by. If empty, follows all edges.
     * @return An iterator over adjacent vertices.
     */
    @JvmStatic
    fun getVertices(vertex: MdeoVertex, direction: Direction, vararg edgeLabels: String): Iterator<Vertex> {
        val vertices = mutableListOf<Vertex>()
        if (direction == Direction.OUT || direction == Direction.BOTH) {
            vertex.outEdges?.let { outEdges ->
                collectEdges(vertex.graph, outEdges, edgeLabels).forEach { edge ->
                    vertices.add((edge as MdeoEdge).inVertex)
                }
            }
        }
        if (direction == Direction.IN || direction == Direction.BOTH) {
            vertex.inEdges?.let { inEdges ->
                collectEdges(vertex.graph, inEdges, edgeLabels).forEach { edge ->
                    vertices.add((edge as MdeoEdge).outVertex)
                }
            }
        }
        return vertices.iterator()
    }

    /**
     * Collects edges from an edge map into a result list, optionally filtering by labels.
     *
     * Every set read is first given the pending shuffle of [graph]'s current epoch
     * ([MdeoGraph.settle]), so a walk sees each neighbourhood in a random order fixed for the
     * epoch, as if the whole graph had been shuffled.
     *
     * @param graph The graph owning the edge map.
     * @param edgeMap The map from label to edge sets.
     * @param edgeLabels Labels to filter by. If empty, collects from all labels.
     * @param result The list to collect edges into.
     */
    private fun collectEdges(
        graph: MdeoGraph,
        edgeMap: Map<String, MdeoEdgeSet>,
        edgeLabels: Array<out String>,
        result: MutableList<Edge>
    ) {
        if (edgeLabels.isEmpty()) {
            edgeMap.values.forEach { result.addAll(graph.settle(it)) }
        } else if (edgeLabels.size == 1) {
            edgeMap[edgeLabels[0]]?.let { result.addAll(graph.settle(it)) }
        } else {
            for (label in edgeLabels) {
                edgeMap[label]?.let { result.addAll(graph.settle(it)) }
            }
        }
    }

    /**
     * Collects edges from an edge map, optionally filtering by labels.
     *
     * @param graph The graph owning the edge map.
     * @param edgeMap The map from label to edge sets.
     * @param edgeLabels Labels to filter by. If empty, collects from all labels.
     * @return A list of matching edges.
     */
    private fun collectEdges(
        graph: MdeoGraph,
        edgeMap: Map<String, MdeoEdgeSet>,
        edgeLabels: Array<out String>
    ): List<Edge> {
        val result = mutableListOf<Edge>()
        collectEdges(graph, edgeMap, edgeLabels, result)
        return result
    }
}
