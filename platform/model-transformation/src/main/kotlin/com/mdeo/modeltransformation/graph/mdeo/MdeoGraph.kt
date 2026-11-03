package com.mdeo.modeltransformation.graph.mdeo

import com.mdeo.metamodel.Metamodel
import com.mdeo.metamodel.Model
import com.mdeo.metamodel.ModelInstance
import com.mdeo.modeltransformation.graph.CountingModelStatistics
import com.mdeo.modeltransformation.graph.ModelGraph
import com.mdeo.modeltransformation.graph.ModelStatistics
import com.mdeo.modeltransformation.ast.EdgeLabelUtils
import com.mdeo.modeltransformation.runtime.InstanceNameRegistry
import com.mdeo.modeltransformation.graph.ModelGraphStrategies
import org.apache.tinkerpop.gremlin.process.traversal.TraversalStrategies
import org.apache.tinkerpop.gremlin.structure.Edge
import org.apache.tinkerpop.gremlin.structure.Graph
import org.apache.tinkerpop.gremlin.structure.Transaction
import org.apache.tinkerpop.gremlin.structure.Vertex
import org.apache.tinkerpop.gremlin.structure.VertexProperty
import org.apache.tinkerpop.gremlin.structure.util.ElementHelper
import org.apache.tinkerpop.gremlin.structure.util.StringFactory
import org.apache.commons.configuration2.BaseConfiguration
import org.apache.commons.configuration2.Configuration
import java.util.Collections
import java.util.IdentityHashMap
import java.util.LinkedHashMap
import java.util.concurrent.ThreadLocalRandom

/**
 * The vertices of one label, in iteration order, with the shuffle epoch of their graph in which
 * their order was last randomised; see [MdeoGraph.shuffleVertices].
 */
internal class VertexBucket(capacity: Int = 10) : ArrayList<MdeoVertex>(capacity) {
    /** The [MdeoGraph] shuffle epoch whose permutation this bucket already has. */
    @JvmField var shuffledIn: Long = 0
}

/**
 * A simplified in-memory graph implementation optimized for model transformation.
 *
 * This is a fork of Apache TinkerPop's TinkerGraph with the following simplifications:
 * - No transaction support
 * - No GraphComputer support
 * - Vertices indexed by label only, not by arbitrary properties
 * - No persistence
 * - Integer-only IDs with simple incrementing counter
 * - No edge properties (metadata encoded in edge labels)
 * - Properties backed directly by [ModelInstance] fields for lazy access
 *
 * Owns the graph structure (vertices and edges) with their backing [ModelInstance] objects.
 * The model is not stored directly; instead, [toModel] reconstructs it on demand from the
 * edge structure and an [InstanceNameRegistry] that provides logical instance names.
 *
 * @param metamodel The compiled metamodel for property resolution.
 */
class MdeoGraph private constructor(
    val metamodel: Metamodel,
    private val vertexMap: HashMap<Int, MdeoVertex>,
    internal val vertexList: ArrayList<MdeoVertex>,
    private val edgeMap: LinkedHashMap<Int, MdeoEdge>,
    private var nextVertexId: Int,
    private var nextEdgeId: Int,
    private var nextVpId: Int
) : Graph {

    /**
     * Secondary constructor for a fresh empty graph. 
     */
    private constructor(metamodel: Metamodel) : this(
        metamodel,
        HashMap(),
        ArrayList(),
        LinkedHashMap(),
        0, 0, 0
    )

    companion object {
        private val EMPTY_CONFIGURATION = BaseConfiguration().apply {
            setProperty(Graph.GRAPH, MdeoGraph::class.java.name)
        }

        init {
            // No lazy barriers, no path retraction, no filter ranking: a match traversal is built
            // and run once, so a strategy is paid per match; it selects every pattern label at its
            // end, so path retraction can drop almost nothing while its label analysis grows with
            // the square of the traversal's length, and the match planner already orders its
            // constraints (TraversalStrategiesTest). MDEO_MT_DEFAULT_STRATEGIES=1 keeps the last
            // two (ModelGraphStrategies).
            TraversalStrategies.GlobalCache.registerStrategies(
                MdeoGraph::class.java,
                TraversalStrategies.GlobalCache.getStrategies(Graph::class.java).clone()
                    .addStrategies(MdeoGraphStepStrategy.instance())
                    .let { ModelGraphStrategies.configure(it) }
            )
        }

        /**
         * Creates a new [MdeoGraph] from an already-built [Model].
         *
         * Registers each instance as a vertex and wires graph edges from the outgoing
         * link fields already set on the instances.
         *
         * @param metamodel The compiled metamodel.
         * @param model The model whose instances become graph vertices.
         * @return A fully populated [MdeoGraph].
         */
        @JvmStatic
        fun open(metamodel: Metamodel, model: Model, registry: InstanceNameRegistry? = null): MdeoGraph {
            val graph = MdeoGraph(metamodel)
            val vertexByInstance = IdentityHashMap<ModelInstance, MdeoVertex>(model.instancesByName.size)
            var maxNumericInstanceName = -1
            for ((name, instance) in model.instancesByName) {
                val vertex = graph.addVertexBacked(instance)
                vertexByInstance[instance] = vertex
                registry?.register(vertex.id, name)
                maxNumericInstanceName = maxOf(maxNumericInstanceName, name.toIntOrNull() ?: -1)
            }
            graph.nextVertexId = maxOf(graph.nextVertexId, maxNumericInstanceName + 1)
            graph.wireEdges(model.instancesByName.values, vertexByInstance) { graph.nextEdgeId++ }
            return graph
        }
    }

    /**
     * Adds the edges the outgoing link fields of [instances] describe, between their vertices in
     * [vertexByInstance], in the order of [instances]; a link to an instance without a vertex
     * there is skipped.
     *
     * @param instances The instances whose links become edges, in order.
     * @param vertexByInstance The vertices, by backing instance.
     * @param nextId Supplies the ID of each new edge.
     */
    private fun wireEdges(
        instances: Collection<ModelInstance>,
        vertexByInstance: Map<ModelInstance, MdeoVertex>,
        nextId: () -> Int
    ) {
        val classMeta = metamodel.metadata.classes
        for (instance in instances) {
            val outVertex = vertexByInstance[instance] ?: continue
            val meta = classMeta[outVertex.label] ?: continue
            for ((roleName, linkMapping) in meta.linkFields) {
                if (!linkMapping.isOutgoing) continue
                val rawValue = instance.getPropertyByKey(roleName) ?: continue
                val edgeLabel = EdgeLabelUtils.computeEdgeLabel(roleName, linkMapping.oppositeFieldName)
                when {
                    linkMapping.isMultiple && rawValue is Set<*> -> {
                        for (target in rawValue) {
                            val inVertex = vertexByInstance[target] ?: continue
                            addEdgeInternal(outVertex, inVertex, edgeLabel, updateAssociations = false, id = nextId())
                        }
                    }
                    rawValue is ModelInstance -> {
                        val inVertex = vertexByInstance[rawValue] ?: continue
                        addEdgeInternal(outVertex, inVertex, edgeLabel, updateAssociations = false, id = nextId())
                    }
                }
            }
        }
    }

    // ---- Model inputs ----

    /**
     * The ID of the next vertex of a model input. Model inputs count downwards from -1, apart from
     * the implicit model's IDs, so that loading and removing them leaves [nextVertexId] — and with
     * it the IDs of the vertices a transformation creates — exactly as without them.
     */
    private var nextModelInputVertexId: Int = -1

    /** The ID of the next edge of a model input; see [nextModelInputVertexId]. */
    private var nextModelInputEdgeId: Int = -1

    /**
     * Adds the instances of a model input as read-only vertices of [modelSlot], with the edges
     * their link fields describe among them.
     *
     * @param modelSlot The model-input slot, at least 1.
     * @param instances The input's instances by name, owned by this graph from now on (a copy,
     *   never the instances of another live graph).
     * @return The vertices, by instance name.
     */
    internal fun loadModelInput(modelSlot: Int, instances: Map<String, ModelInstance>): Map<String, MdeoVertex> {
        require(modelSlot != ModelGraph.IMPLICIT_MODEL_SLOT) { "Slot $modelSlot is the implicit model's" }
        val vertexByInstance = IdentityHashMap<ModelInstance, MdeoVertex>(instances.size)
        val vertexByName = LinkedHashMap<String, MdeoVertex>(instances.size * 2)
        for ((name, instance) in instances) {
            val vertex = addVertexBacked(instance, nextModelInputVertexId--, modelSlot)
            vertexByInstance[instance] = vertex
            vertexByName[name] = vertex
        }
        wireEdges(instances.values, vertexByInstance) { nextModelInputEdgeId-- }
        return vertexByName
    }

    /**
     * Removes the vertices of every model input ([loadModelInput]) and their edges, leaving the
     * implicit model's vertices, edges, iteration order and label counts as they were.
     *
     * A model input's edges only connect its own vertices (its vertices refuse any other edge,
     * see [MdeoVertex.checkWritable]), so no implicit vertex's adjacency or backing instance has
     * to be updated. One pass over [vertexList] and over the removed vertices' edges.
     *
     * @return The IDs of the removed vertices.
     */
    internal fun removeModelInputs(): List<Int> {
        val removed = vertexList.filter { it.isModelInput }
        if (removed.isEmpty()) return emptyList()
        vertexList.removeIf { it.isModelInput }
        for (vertex in removed) {
            vertexMap.remove(vertex.id)
            vertexLabelCounts.computeIfPresent(vertex.label) { _, n -> if (n <= 1) null else n - 1 }
            vertex.removed = true
            for (adjacency in listOfNotNull(vertex.outEdges, vertex.inEdges)) {
                for (edges in adjacency.values) {
                    for (edge in edges) {
                        edge as MdeoEdge
                        if (edgeMap.remove(edge.id) == null) continue
                        edgeLabelCounts.computeIfPresent(edge.label) { _, n -> if (n <= 1) null else n - 1 }
                        edge.removed = true
                    }
                }
            }
        }
        for (label in removed.mapTo(HashSet()) { it.label }) {
            val bucket = verticesByLabel[label] ?: continue
            bucket.removeIf { it.isModelInput }
            if (bucket.isEmpty()) verticesByLabel.remove(label)
        }
        invalidateStatistics()
        return removed.map { it.id }
    }

    private val graphFeatures = MdeoGraphFeatures()

    /** Number of vertices currently in the graph. `O(1)`. */
    internal val vertexCount: Int
        get() = vertexList.size

    /**
     * Live vertex count per label, maintained incrementally by [addVertex], [addVertexBacked]
     * and [removeVertex].
     *
     * The match planner reads these on every match to estimate step selectivity, so they must
     * be `O(1)` to obtain; recomputing them by scanning [vertexList] would add a pass over the
     * whole model to every match.
     */
    private val vertexLabelCounts = HashMap<String, Int>()

    /** Live edge count per label; the edge-side counterpart of [vertexLabelCounts]. */
    private val edgeLabelCounts = HashMap<String, Int>()

    /**
     * Vertices grouped by label, maintained alongside [vertexList] by [addVertex],
     * [addVertexBacked] and [removeVertex].
     *
     * Every pattern match starts with a `V().hasLabel(...)` step. Without this index that
     * step has to walk the whole model and test a label predicate per vertex, which for a
     * search that runs thousands of matches over the same graph dominates everything else.
     * The buckets hold the same vertex objects as [vertexList], not copies.
     *
     * Iteration order within a bucket is shuffled like [vertexList] (see [shuffleVertices]),
     * so a label-restricted scan is as unbiased as a full one.
     */
    private val verticesByLabel = HashMap<String, VertexBucket>()

    /**
     * Returns the vertices carrying [label], in current iteration order.
     *
     * The returned list is the live bucket — callers that iterate while the graph may be
     * modified must snapshot it first. A pending shuffle is applied to it first.
     *
     * @param label The vertex label to look up.
     * @return The bucket for [label], or an empty list if no vertex carries it.
     */
    internal fun verticesWithLabel(label: String): List<MdeoVertex> =
        verticesByLabel[label]?.let { settle(it) } ?: emptyList()

    /**
     * Whether [shuffleVertices] has run on this graph, so that traversal order is meant to be
     * random. A deep copy starts unshuffled.
     */
    internal var isShuffled: Boolean = false
        private set

    /**
     * Counts the calls of [shuffleVertices]. Every ordered structure — [vertexList], each label
     * bucket, each adjacency set — records the epoch whose permutation it has; one that is
     * behind is shuffled the first time it is read or appended to.
     */
    private var shuffleEpoch: Long = 0

    /** The epoch whose permutation [vertexList] has; see [shuffleEpoch]. */
    private var vertexListShuffledIn: Long = 0

    /**
     * Gives [bucket] the pending shuffle of the current epoch, if it does not have it yet.
     *
     * @return [bucket], now in the epoch's order.
     */
    private fun settle(bucket: VertexBucket): VertexBucket {
        if (isShuffled && bucket.shuffledIn != shuffleEpoch) {
            Collections.shuffle(bucket, ThreadLocalRandom.current())
            bucket.shuffledIn = shuffleEpoch
        }
        return bucket
    }

    /**
     * Gives [edges] the pending shuffle of the current epoch, if it does not have it yet.
     *
     * @return [edges], now in the epoch's order.
     */
    internal fun settle(edges: MdeoEdgeSet): MdeoEdgeSet {
        if (isShuffled && edges.shuffledIn != shuffleEpoch) {
            if (edges.size > 1) {
                val shuffled = ArrayList(edges)
                Collections.shuffle(shuffled, ThreadLocalRandom.current())
                edges.clear()
                edges.addAll(shuffled)
            }
            edges.shuffledIn = shuffleEpoch
        }
        return edges
    }

    /** Gives [vertexList] the pending shuffle of the current epoch, if it does not have it yet. */
    private fun settleVertexList() {
        if (isShuffled && vertexListShuffledIn != shuffleEpoch) {
            Collections.shuffle(vertexList, ThreadLocalRandom.current())
            vertexListShuffledIn = shuffleEpoch
        }
    }

    /** A new, empty bucket; it counts as shuffled in the current epoch, as an empty list would be. */
    private fun newBucket(capacity: Int = 10) = VertexBucket(capacity).also { it.shuffledIn = shuffleEpoch }

    /** A new, empty adjacency set; it counts as shuffled in the current epoch. */
    private fun newEdgeSet() = MdeoEdgeSet().also { it.shuffledIn = shuffleEpoch }

    /** Cached immutable view of [vertexLabelCounts] / [edgeLabelCounts]; see [labelStatistics]. */
    private var cachedStatistics: ModelStatistics? = null

    /** Discards [cachedStatistics] after a structural change. */
    private fun invalidateStatistics() {
        cachedStatistics = null
    }

    /**
     * Returns the current label distribution.
     *
     * The counts themselves are maintained incrementally, so this only has to package them.
     * The wrapper is cached and rebuilt only after a vertex or edge is added or removed, and
     * it reads the counter maps directly rather than copying them: the totals are captured at
     * construction, so a wrapper handed out before a later mutation reports per-label counts
     * from after it. That is the same staleness the planner already tolerates — statistics
     * only ever order steps — and it keeps this off the allocation path of every match.
     */
    internal fun labelStatistics(): ModelStatistics = cachedStatistics ?: CountingModelStatistics(
        vertexCount = vertexList.size,
        edgeCount = edgeMap.size,
        vertexLabelCounts = vertexLabelCounts,
        edgeLabelCounts = edgeLabelCounts
    ).also { cachedStatistics = it }

    /**
     * Generates the next unique vertex property ID. 
     */
    internal fun nextVertexPropertyId(): Int = nextVpId++

    override fun addVertex(vararg keyValues: Any): Vertex {
        ElementHelper.legalPropertyKeyValueArray(*keyValues)
        val label = ElementHelper.getLabelValue(*keyValues).orElse(Vertex.DEFAULT_LABEL)

        val id = nextVertexId++
        val metadata = metamodel.metadata.classes[label]
        val backingInstance = metamodel.createInstance(label)
        val vertex = MdeoVertex(id, label, this, backingInstance, metadata)

        vertexMap[id] = vertex
        settleVertexList()
        vertexList.add(vertex)
        settle(verticesByLabel.getOrPut(label) { newBucket() }).add(vertex)
        vertexLabelCounts.merge(label, 1, Int::plus)
        invalidateStatistics()

        ElementHelper.attachProperties(vertex, VertexProperty.Cardinality.list, *keyValues)

        return vertex
    }

    /**
     * Adds a vertex backed by an existing [ModelInstance].
     *
     * Used internally during graph construction from an existing [Model], and for the vertices
     * of a model input ([loadModelInput]).
     *
     * @param instance The backing instance, owned by this graph from now on.
     * @param id The vertex ID.
     * @param modelSlot The vertex's model slot.
     */
    private fun addVertexBacked(
        instance: ModelInstance,
        id: Int = nextVertexId++,
        modelSlot: Int = ModelGraph.IMPLICIT_MODEL_SLOT
    ): MdeoVertex {
        val className = metamodel.classNameOf(instance)
        val metadata = metamodel.metadata.classes[className]
        val vertex = MdeoVertex(id, className, this, instance, metadata, modelSlot)
        vertexMap[id] = vertex
        settleVertexList()
        vertexList.add(vertex)
        settle(verticesByLabel.getOrPut(className) { newBucket() }).add(vertex)
        vertexLabelCounts.merge(className, 1, Int::plus)
        invalidateStatistics()
        return vertex
    }

    /**
     * Removes a vertex by its integer ID.
     */
    internal fun removeVertex(vertexId: Int) {
        val vertex = vertexMap.remove(vertexId) ?: return
        vertexList.remove(vertex)
        verticesByLabel[vertex.label]?.let { bucket ->
            bucket.remove(vertex)
            if (bucket.isEmpty()) verticesByLabel.remove(vertex.label)
        }
        vertexLabelCounts.computeIfPresent(vertex.label) { _, n -> if (n <= 1) null else n - 1 }
        invalidateStatistics()
    }

    /**
     * Adds an edge between two vertices, syncing the backing model instances' association fields.
     *
     * The edge label is parsed to determine which association ends are affected,
     * and the corresponding link fields are updated on both backing instances.
     */
    internal fun addEdge(outVertex: MdeoVertex, inVertex: MdeoVertex, label: String, vararg keyValues: Any): Edge {
        return addEdgeInternal(outVertex, inVertex, label, updateAssociations = true, *keyValues)
    }

    /**
     * Removes an edge by its integer ID, syncing the backing model instances' association fields.
     */
    internal fun removeEdge(edgeId: Int) {
        val edge = edgeMap.remove(edgeId) ?: return
        edgeLabelCounts.computeIfPresent(edge.label()) { _, n -> if (n <= 1) null else n - 1 }
        invalidateStatistics()
        edge.outVertex.outEdges?.get(edge.label())?.removeIf { it.id() == edgeId }
        edge.inVertex.inEdges?.get(edge.label())?.removeIf { it.id() == edgeId }
        updateModelAssociationOnRemove(edge.outVertex, edge.inVertex, edge.label())
    }

    private fun addEdgeInternal(
        outVertex: MdeoVertex,
        inVertex: MdeoVertex,
        label: String,
        updateAssociations: Boolean,
        vararg keyValues: Any,
        id: Int = nextEdgeId++
    ): Edge {
        val edge = MdeoEdge(id, outVertex, label, inVertex)

        edgeMap[id] = edge
        edgeLabelCounts.merge(label, 1, Int::plus)
        invalidateStatistics()

        if (outVertex.outEdges == null) outVertex.outEdges = HashMap()
        settle(outVertex.outEdges!!.getOrPut(label) { newEdgeSet() }).add(edge)

        if (inVertex.inEdges == null) inVertex.inEdges = HashMap()
        settle(inVertex.inEdges!!.getOrPut(label) { newEdgeSet() }).add(edge)

        if (updateAssociations) {
            updateModelAssociationOnAdd(outVertex, inVertex, label)
        }

        return edge
    }

    /**
     * Creates a deep copy of this graph.
     *
     * Phase 1: for every vertex, [ModelInstance.copy] creates a new backing instance via the
     * generated copy constructor, which copies all scalar property fields and pre-allocates
     * presized link (association) [java.util.HashSet] instances. Phase 2:
     * [ModelInstance.copyReferences] populates those Sets by remapping references through the
     * old-to-new instance map. Edges are then reconstructed from the original edge structure.
     * Vertex and edge IDs are preserved so higher layers can remap external metadata (like
     * instance-name registries) without graph involvement.
     *
     * Pre-sized [HashMap] and [ArrayList] instances (capacity ≥ ⌈n / 0.75⌉) are passed
     * directly to the primary constructor to avoid any resizing during population.
     */
    internal fun deepCopy(): MdeoGraph {
        settleVertexList()
        val vertexCount = vertexMap.size
        val edgeCount = edgeMap.size
        val vertexCapacity = (vertexCount * 4 + 2) / 3
        val edgeCapacity = (edgeCount * 4 + 2) / 3

        val newVertexMap = HashMap<Int, MdeoVertex>(vertexCapacity)
        val newVertexList = ArrayList<MdeoVertex>(vertexCount)
        val newEdgeMap = LinkedHashMap<Int, MdeoEdge>(edgeCapacity)

        val copy = MdeoGraph(
            metamodel, newVertexMap, newVertexList, newEdgeMap,
            nextVertexId, nextEdgeId, nextVpId
        )
        copy.nextModelInputVertexId = nextModelInputVertexId
        copy.nextModelInputEdgeId = nextModelInputEdgeId
        // A deep copy has exactly this graph's label distribution; copying the counters is
        // O(labels) and saves the copy a full pass over its own vertices and edges.
        copy.vertexLabelCounts.putAll(vertexLabelCounts)
        copy.edgeLabelCounts.putAll(edgeLabelCounts)

        val oldToNew = IdentityHashMap<ModelInstance, ModelInstance>(vertexCount)

        for (vertex in vertexList) {
            val old = vertex.backingInstance
            val copiedInstance = old.copy()
            oldToNew[old] = copiedInstance
            val copiedVertex = MdeoVertex(vertex.id, vertex.label, copy, copiedInstance, vertex.classMetadata, vertex.modelSlot)
            newVertexMap[vertex.id] = copiedVertex
            newVertexList.add(copiedVertex)
            copy.verticesByLabel
                .getOrPut(vertex.label) { VertexBucket(vertexLabelCounts[vertex.label] ?: 0) }
                .add(copiedVertex)
        }

        for ((old, new) in oldToNew) {
            old.copyReferences(new, oldToNew)
        }

        for (edge in edgeMap.values) {
            val copiedOutVertex = newVertexMap[edge.outVertex.id]
                ?: error("Copied graph missing out-vertex ${edge.outVertex.id}")
            val copiedInVertex = newVertexMap[edge.inVertex.id]
                ?: error("Copied graph missing in-vertex ${edge.inVertex.id}")
            val copiedEdge = MdeoEdge(edge.id, copiedOutVertex, edge.label, copiedInVertex)
            newEdgeMap[edge.id] = copiedEdge
            if (copiedOutVertex.outEdges == null) copiedOutVertex.outEdges = HashMap()
            copiedOutVertex.outEdges!!.getOrPut(edge.label) { MdeoEdgeSet() }.add(copiedEdge)
            if (copiedInVertex.inEdges == null) copiedInVertex.inEdges = HashMap()
            copiedInVertex.inEdges!!.getOrPut(edge.label) { MdeoEdgeSet() }.add(copiedEdge)
        }

        return copy
    }

    /**
     * Constructs a [Model] from the current graph state.
     *
     * For each vertex, a new instance is created with the logical name from [registry]
     * (falling back to the backing instance's own name for unregistered vertices). Property
     * fields are copied via [ModelInstance.copyProperties]; link fields are copied via
     * [ModelInstance.copyReferences], remapping references to the new logical-name instances.
     * The backing instances' link fields are already maintained in-sync by
     * [updateModelAssociationOnAdd] / [updateModelAssociationOnRemove], so no edge traversal
     * is needed here.
     *
     * @param registry Maps vertex IDs to logical instance names.
     * @param settle Whether to apply a pending shuffle to the vertex order first (see
     *   [shuffleVertices]). Without it the graph is only read, never changed, so that several
     *   threads may read it at once — as when it is loaded as a model input of other graphs, which
     *   does not depend on the order.
     * @return A fully populated [Model] reflecting the current graph state.
     */
    internal fun toModel(registry: InstanceNameRegistry, settle: Boolean = true): Model {
        if (settle) settleVertexList()
        val newInstancesByName = LinkedHashMap<String, ModelInstance>(vertexList.size * 2)
        for (vertex in vertexList) {
            val logicalName = registry.getName(vertex.id) ?: vertex.id.toString()
            newInstancesByName[logicalName] = vertex.backingInstance
        }
        return Model(metamodel, metamodel.path, newInstancesByName)
    }

    override fun vertices(vararg vertexIds: Any): Iterator<Vertex> {
        if (vertexIds.isEmpty()) {
            settleVertexList()
            return ArrayList<Vertex>(vertexList).iterator()
        }
        return vertexIds.mapNotNull { id ->
            val intId = when (id) {
                is Int -> id
                is Long -> id.toInt()
                is Vertex -> id.id() as Int
                is Number -> id.toInt()
                else -> null
            }
            intId?.let { vertexMap[it] }
        }.iterator()
    }

    override fun edges(vararg edgeIds: Any): Iterator<Edge> {
        if (edgeIds.isEmpty()) {
            return ArrayList<Edge>(edgeMap.values).iterator()
        }
        return edgeIds.mapNotNull { id ->
            val intId = when (id) {
                is Int -> id
                is Long -> id.toInt()
                is Edge -> id.id() as Int
                is Number -> id.toInt()
                else -> null
            }
            intId?.let { edgeMap[it] }
        }.iterator()
    }

    /**
     * Returns a single vertex by ID without creating an iterator.
     *
     * @param vertexId The vertex ID.
     * @return The vertex, or null if not found.
     */
    internal fun vertex(vertexId: Any): Vertex? {
        val intId = when (vertexId) {
            is Int -> vertexId
            is Long -> vertexId.toInt()
            is Number -> vertexId.toInt()
            else -> return null
        }
        return vertexMap[intId]
    }

    /**
     * Returns a single edge by ID without creating an iterator.
     *
     * @param edgeId The edge ID.
     * @return The edge, or null if not found.
     */
    internal fun edge(edgeId: Any): Edge? {
        val intId = when (edgeId) {
            is Int -> edgeId
            is Long -> edgeId.toInt()
            is Number -> edgeId.toInt()
            else -> return null
        }
        return edgeMap[intId]
    }

    /**
     * Returns the number of vertices in this graph.
     *
     * @return The vertex count.
     */
    internal fun getVerticesCount(): Int = vertexMap.size

    /**
     * Returns the number of edges in this graph.
     *
     * @return The edge count.
     */
    internal fun getEdgesCount(): Int = edgeMap.size

    /**
     * Shuffles the vertex iteration list, the per-label buckets and all per-vertex edge
     * adjacency sets to provide nondeterministic traversal order.
     *
     * The shuffle is lazy: this only starts a new epoch, in O(1). Each ordered structure —
     * [vertexList], a label bucket, an adjacency set — gets its uniformly random permutation the
     * first time in the epoch it is read (a scan of its label, a walk over its edges, a full
     * vertex scan, [toModel], [deepCopy]) or appended to; one that is never touched is never
     * shuffled. Each structure is shuffled independently, at most once per epoch, so everything a
     * traversal can observe has exactly the distribution an eager shuffle of the whole graph would
     * give it — every structure an independent uniform permutation, fixed until the next call —
     * while a match pays only for what it touches instead of for the whole graph. (Shuffling at
     * the first append keeps an element added later in the epoch at the end, as it would be after
     * an eager shuffle; a removal needs nothing, as a uniform permutation of a set minus one
     * element is a uniform permutation of the rest.)
     *
     * The per-label buckets are shuffled independently of [vertexList] rather than rebuilt
     * from it: a label-restricted scan only needs its own bucket to be a uniformly random
     * permutation, which an independent shuffle gives just as well as filtering a shuffled
     * whole, and at a fraction of the cost. A scan over several labels (a class with
     * subclasses) joins their buckets and, once the graph [isShuffled], shuffles the result,
     * so that the first candidate is not always of the first label.
     *
     * Shuffling only the vertex list is insufficient: when a vertex has multiple outgoing
     * edges with the same label (e.g. a Sprint with several committedItems), the
     * adjacency set preserves its original insertion order and would always yield the
     * same neighbour first during an edge-walk traversal.  Shuffling those sets ensures
     * every edge in the neighbourhood has an equal chance of being the first hit.
     */
    internal fun shuffleVertices() {
        isShuffled = true
        shuffleEpoch++
    }

    /**
     * Applies the pending shuffle of the current epoch to every structure at once.
     *
     * Directly after [shuffleVertices] this is the eager whole-graph shuffle the engine used
     * before the shuffle became lazy; tests use it as the reference the lazy version is compared
     * with.
     */
    internal fun settleAll() {
        settleVertexList()
        verticesByLabel.values.forEach { settle(it) }
        for (vertex in vertexList) {
            vertex.outEdges?.values?.forEach { settle(it) }
            vertex.inEdges?.values?.forEach { settle(it) }
        }
    }

    // ---- Model association management ----

    /**
     * Updates model instance fields when an edge is added.
     *
     * Parses the edge label to determine which association ends exist,
     * then sets or adds to the corresponding link fields on the backing instances.
     *
     * Both ends are always attempted independently: a missing/unknown property on one
     * end does not suppress the update of the other end.
     *
     * @param outVertex The source vertex.
     * @param inVertex The target vertex.
     * @param edgeLabel The edge label.
     */
    private fun updateModelAssociationOnAdd(outVertex: MdeoVertex, inVertex: MdeoVertex, edgeLabel: String) {
        val outInstance = outVertex.backingInstance
        val inInstance = inVertex.backingInstance
        val (sourceProperty, targetProperty) = EdgeLabelUtils.parseEdgeLabel(edgeLabel)

        if (sourceProperty != null) {
            val outMeta = outVertex.classMetadata
            val linkMapping = outMeta?.linkFields?.get(sourceProperty)
            if (outMeta != null && linkMapping != null) {
                if (linkMapping.upper != 1) {
                    @Suppress("UNCHECKED_CAST")
                    val set = outInstance.getPropertyByKey(sourceProperty) as? MutableSet<ModelInstance>
                        ?: LinkedHashSet()
                    set.add(inInstance)
                    outInstance.setPropertyByKey(sourceProperty, set)
                } else {
                    outInstance.setPropertyByKey(sourceProperty, inInstance)
                }
            }
        }

        if (targetProperty != null) {
            val inMeta = inVertex.classMetadata
            val linkMapping = inMeta?.linkFields?.get(targetProperty)
            if (inMeta != null && linkMapping != null) {
                if (linkMapping.upper != 1) {
                    @Suppress("UNCHECKED_CAST")
                    val set = inInstance.getPropertyByKey(targetProperty) as? MutableSet<ModelInstance>
                        ?: LinkedHashSet()
                    set.add(outInstance)
                    inInstance.setPropertyByKey(targetProperty, set)
                } else {
                    inInstance.setPropertyByKey(targetProperty, outInstance)
                }
            }
        }
    }

    /**
     * Updates model instance fields when an edge is removed.
     *
     * Parses the edge label to determine which association ends exist,
     * then removes or nullifies the corresponding link fields on the backing instances.
     *
     * Both ends are always attempted independently: a missing/unknown property on one
     * end does not suppress the update of the other end.
     *
     * For single-valued fields ([upper][com.mdeo.metamodel.LinkFieldMapping.upper] == 1), the
     * field is only cleared if its current value still refers to the vertex being disconnected.
     * This prevents a preceding add-edge step in the same transformation from having its
     * assignment silently overwritten: when a transformation does "create new link, then delete
     * old link" (e.g. moveItemBetweenSprints), the delete must not nullify a field that was
     * already reassigned by the create step.
     *
     * @param outVertex The source vertex.
     * @param inVertex The target vertex.
     * @param edgeLabel The edge label.
     */
    private fun updateModelAssociationOnRemove(outVertex: MdeoVertex, inVertex: MdeoVertex, edgeLabel: String) {
        val outInstance = outVertex.backingInstance
        val inInstance = inVertex.backingInstance
        val (sourceProperty, targetProperty) = EdgeLabelUtils.parseEdgeLabel(edgeLabel)

        if (sourceProperty != null) {
            val outMeta = outVertex.classMetadata
            val linkMapping = outMeta?.linkFields?.get(sourceProperty)
            if (outMeta != null && linkMapping != null) {
                if (linkMapping.upper != 1) {
                    @Suppress("UNCHECKED_CAST")
                    val set = outInstance.getPropertyByKey(sourceProperty) as? MutableSet<ModelInstance>
                    set?.remove(inInstance)
                } else {
                    val currentValue = outInstance.getPropertyByKey(sourceProperty) as? ModelInstance
                    if (currentValue === inInstance) {
                        outInstance.setPropertyByKey(sourceProperty, null)
                    }
                }
            }
        }

        if (targetProperty != null) {
            val inMeta = inVertex.classMetadata
            val linkMapping = inMeta?.linkFields?.get(targetProperty)
            if (inMeta != null && linkMapping != null) {
                if (linkMapping.upper != 1) {
                    @Suppress("UNCHECKED_CAST")
                    val set = inInstance.getPropertyByKey(targetProperty) as? MutableSet<ModelInstance>
                    set?.remove(outInstance)
                } else {
                    val currentValue = inInstance.getPropertyByKey(targetProperty) as? ModelInstance
                    if (currentValue === outInstance) {
                        inInstance.setPropertyByKey(targetProperty, null)
                    }
                }
            }
        }
    }


    override fun tx(): Transaction = throw Graph.Exceptions.transactionsNotSupported()

    override fun close() {
        // No resources to release
    }

    override fun variables(): Graph.Variables = throw UnsupportedOperationException("Graph variables not supported")

    override fun configuration(): Configuration = EMPTY_CONFIGURATION

    override fun features(): Graph.Features = graphFeatures

    override fun toString(): String =
        StringFactory.graphString(this, "vertices:${vertexMap.size} edges:${edgeMap.size}")

    override fun <C : org.apache.tinkerpop.gremlin.process.computer.GraphComputer> compute(
        graphComputerClass: Class<C>
    ): C = throw Graph.Exceptions.graphComputerNotSupported()

    override fun compute(): org.apache.tinkerpop.gremlin.process.computer.GraphComputer =
        throw Graph.Exceptions.graphComputerNotSupported()


    /**
     * Feature set for MdeoGraph declaring unsupported capabilities.
     */
    private inner class MdeoGraphFeatures : Graph.Features {
        private val graphFeat = MdeoGraphGraphFeatures()
        private val vertexFeat = MdeoGraphVertexFeatures()
        private val edgeFeat = MdeoGraphEdgeFeatures()

        override fun graph() = graphFeat
        override fun vertex() = vertexFeat
        override fun edge() = edgeFeat
    }

    /**
     * Graph-level features declaring no concurrency, transactions, or threaded transactions.
     */
    private inner class MdeoGraphGraphFeatures : Graph.Features.GraphFeatures {
        override fun supportsConcurrentAccess() = false
        override fun supportsTransactions() = false
        override fun supportsThreadedTransactions() = false
    }

    /**
     * Vertex-level features declaring no null properties, no custom IDs, and list cardinality.
     */
    private inner class MdeoGraphVertexFeatures : Graph.Features.VertexFeatures {
        override fun supportsNullPropertyValues() = false
        override fun supportsCustomIds() = false
        override fun getCardinality(key: String?) = VertexProperty.Cardinality.list
    }

    /**
     * Edge-level features declaring no null properties and no custom IDs.
     */
    private inner class MdeoGraphEdgeFeatures : Graph.Features.EdgeFeatures {
        override fun supportsNullPropertyValues() = false
        override fun supportsCustomIds() = false
    }
}
