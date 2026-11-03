package com.mdeo.modeltransformation.graph

import org.apache.tinkerpop.gremlin.structure.Graph

/**
 * Cardinality statistics of a model, used by the match planner to estimate how large the
 * intermediate result of a partially built match becomes.
 *
 * The statistics are *exact counts of the model as it was when the snapshot was taken* —
 * not metamodel-derived upper bounds. They are the "model-sensitive" input that turns a
 * purely structural search-plan heuristic into a cost-based one (Varró, Friedl & Varró,
 * *Adaptive Graph Pattern Matching for Model Transformations using Model-sensitive Search
 * Plans*, ENTCS 152, 2006).
 *
 * Statistics are only ever used to *order* plan steps, never to decide which matches exist,
 * so a snapshot that lags a few vertices behind the live graph changes performance but
 * never results. Implementations may therefore cache aggressively; see
 * [GraphStatisticsCache].
 */
interface ModelStatistics {

    /** Total number of vertices in the model. */
    val vertexCount: Int

    /** Total number of edges in the model. */
    val edgeCount: Int

    /**
     * Returns the number of vertices whose label is exactly [label].
     *
     * Subtypes are **not** included; callers that need the size of a class including its
     * subclasses must sum over the class hierarchy themselves.
     */
    fun verticesWithLabel(label: String): Int

    /**
     * Returns the number of edges whose label is exactly [edgeLabel].
     *
     * Edge labels are the composite `` `sourceRole`_`targetRole` `` strings produced by
     * [com.mdeo.modeltransformation.ast.EdgeLabelUtils.computeEdgeLabel].
     */
    fun edgesWithLabel(edgeLabel: String): Int

    companion object {
        /**
         * Statistics for an empty model.
         *
         * Reports zero for every count, which the planner treats as "no instances exist".
         * Do **not** use this as a stand-in for *unknown* statistics — pass `null` where a
         * cost model is optional instead.
         */
        val EMPTY: ModelStatistics = CountingModelStatistics(0, 0, emptyMap(), emptyMap())

        /**
         * Computes an exact snapshot of [graph] in a single pass over its vertices and a
         * single pass over its edges.
         *
         * @param graph The TinkerPop graph to measure.
         * @return An immutable snapshot of the label distribution.
         */
        fun snapshotOf(graph: Graph): ModelStatistics {
            val vertexLabels = HashMap<String, Int>()
            var vertices = 0
            val vertexIterator = graph.vertices()
            while (vertexIterator.hasNext()) {
                vertices++
                vertexLabels.merge(vertexIterator.next().label(), 1, Int::plus)
            }

            val edgeLabels = HashMap<String, Int>()
            var edges = 0
            val edgeIterator = graph.edges()
            while (edgeIterator.hasNext()) {
                edges++
                edgeLabels.merge(edgeIterator.next().label(), 1, Int::plus)
            }

            return CountingModelStatistics(vertices, edges, vertexLabels, edgeLabels)
        }
    }
}

/**
 * Immutable [ModelStatistics] backed by two label-to-count maps.
 *
 * @property vertexCount Total vertex count.
 * @property edgeCount Total edge count.
 * @property vertexLabelCounts Vertex count per exact label.
 * @property edgeLabelCounts Edge count per exact label.
 */
internal class CountingModelStatistics(
    override val vertexCount: Int,
    override val edgeCount: Int,
    private val vertexLabelCounts: Map<String, Int>,
    private val edgeLabelCounts: Map<String, Int>
) : ModelStatistics {
    override fun verticesWithLabel(label: String): Int = vertexLabelCounts[label] ?: 0
    override fun edgesWithLabel(edgeLabel: String): Int = edgeLabelCounts[edgeLabel] ?: 0
}

/**
 * Caches a [ModelStatistics] snapshot for a graph and refreshes it when the graph's vertex
 * count changes.
 *
 * Used by backends that cannot maintain label counts incrementally because they do not own
 * their mutation entry points ([com.mdeo.modeltransformation.graph.tinker.TinkerModelGraph]).
 * [com.mdeo.modeltransformation.graph.mdeo.MdeoGraph] keeps exact counters instead and does
 * not need this.
 *
 * Recomputing is `O(V + E)`, so it must not happen per match step. The vertex count is the
 * invalidation signal because it is the cheapest available proxy for "the model has been
 * structurally modified": every create and every delete changes it. Edge-only and
 * property-only changes do **not** invalidate the snapshot — they can shift an estimate
 * slightly, which affects the chosen step order but never the match semantics.
 *
 * @property graph Supplies the graph to measure. Called only when a refresh is needed.
 * @property liveVertexCount Supplies the graph's current vertex count. Called on every
 *           access, so it must be cheap.
 */
internal class GraphStatisticsCache(
    private val graph: () -> Graph,
    private val liveVertexCount: () -> Int
) {
    private var snapshot: ModelStatistics? = null

    /**
     * Returns the current snapshot, recomputing it if the graph's vertex count has changed
     * since the last snapshot was taken.
     */
    fun get(): ModelStatistics {
        val cached = snapshot
        if (cached != null && cached.vertexCount == liveVertexCount()) return cached
        return ModelStatistics.snapshotOf(graph()).also { snapshot = it }
    }

    /**
     * Seeds this cache with a snapshot already computed for an identical graph.
     *
     * A deep copy has exactly the statistics of its source, so the copy can start from the
     * source's snapshot instead of paying for a fresh `O(V + E)` pass. If the copy is then
     * modified, the vertex-count check in [get] still forces a refresh.
     */
    fun seedFrom(other: GraphStatisticsCache) {
        snapshot = other.snapshot
    }

    /**
     * Discards the snapshot, so that the next [get] recomputes it. For a change the vertex count
     * does not reveal, such as removing as many vertices as were created since the snapshot.
     */
    fun invalidate() {
        snapshot = null
    }
}
