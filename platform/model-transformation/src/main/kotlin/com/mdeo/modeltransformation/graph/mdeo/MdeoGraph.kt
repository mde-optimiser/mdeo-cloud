package com.mdeo.modeltransformation.graph.mdeo

import com.mdeo.metamodel.Metamodel
import com.mdeo.metamodel.Model
import com.mdeo.metamodel.ModelInstance
import com.mdeo.modeltransformation.ast.EdgeLabelUtils
import com.mdeo.modeltransformation.runtime.InstanceNameRegistry
import org.apache.tinkerpop.gremlin.process.traversal.TraversalStrategies
import org.apache.tinkerpop.gremlin.process.traversal.strategy.optimization.LazyBarrierStrategy
import org.apache.tinkerpop.gremlin.structure.Edge
import org.apache.tinkerpop.gremlin.structure.Graph
import org.apache.tinkerpop.gremlin.structure.Transaction
import org.apache.tinkerpop.gremlin.structure.Vertex
import org.apache.tinkerpop.gremlin.structure.VertexProperty
import org.apache.tinkerpop.gremlin.structure.util.ElementHelper
import org.apache.tinkerpop.gremlin.structure.util.StringFactory
import org.apache.commons.configuration2.BaseConfiguration
import org.apache.commons.configuration2.Configuration
import java.util.IdentityHashMap
import java.util.LinkedHashMap

/**
 * A simplified in-memory graph implementation optimized for model transformation.
 *
 * This is a fork of Apache TinkerPop's TinkerGraph with the following simplifications:
 * - No transaction support
 * - No GraphComputer support
 * - No index support
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
            TraversalStrategies.GlobalCache.registerStrategies(
                MdeoGraph::class.java,
                TraversalStrategies.GlobalCache.getStrategies(Graph::class.java).clone()
                    .addStrategies(MdeoGraphStepStrategy.instance())
                    .removeStrategies(LazyBarrierStrategy::class.java)
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
            val classMeta = metamodel.metadata.classes
            for ((_, instance) in model.instancesByName) {
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
                                graph.addEdgeInternal(outVertex, inVertex, edgeLabel, updateAssociations = false)
                            }
                        }
                        rawValue is ModelInstance -> {
                            val inVertex = vertexByInstance[rawValue] ?: continue
                            graph.addEdgeInternal(outVertex, inVertex, edgeLabel, updateAssociations = false)
                        }
                    }
                }
            }
            return graph
        }
    }

    private val graphFeatures = MdeoGraphFeatures()

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
        vertexList.add(vertex)

        ElementHelper.attachProperties(vertex, VertexProperty.Cardinality.list, *keyValues)

        return vertex
    }

    /**
     * Adds a vertex backed by an existing [ModelInstance].
     *
     * Used internally during graph construction from an existing [Model].
     */
    private fun addVertexBacked(instance: ModelInstance): MdeoVertex {
        val id = nextVertexId++
        val className = metamodel.classNameOf(instance)
        val metadata = metamodel.metadata.classes[className]
        val vertex = MdeoVertex(id, className, this, instance, metadata)
        vertexMap[id] = vertex
        vertexList.add(vertex)
        return vertex
    }

    /**
     * Removes a vertex by its integer ID.
     */
    internal fun removeVertex(vertexId: Int) {
        val vertex = vertexMap.remove(vertexId) ?: return
        vertexList.remove(vertex)
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
        edge.outVertex.outEdges?.get(edge.label())?.removeIf { it.id() == edgeId }
        edge.inVertex.inEdges?.get(edge.label())?.removeIf { it.id() == edgeId }
        updateModelAssociationOnRemove(edge.outVertex, edge.inVertex, edge.label())
    }

    private fun addEdgeInternal(
        outVertex: MdeoVertex,
        inVertex: MdeoVertex,
        label: String,
        updateAssociations: Boolean,
        vararg keyValues: Any
    ): Edge {
        val id = nextEdgeId++
        val edge = MdeoEdge(id, outVertex, label, inVertex)

        edgeMap[id] = edge

        if (outVertex.outEdges == null) outVertex.outEdges = HashMap()
        outVertex.outEdges!!.getOrPut(label) { mutableSetOf() }.add(edge)

        if (inVertex.inEdges == null) inVertex.inEdges = HashMap()
        inVertex.inEdges!!.getOrPut(label) { mutableSetOf() }.add(edge)

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

        val oldToNew = IdentityHashMap<ModelInstance, ModelInstance>(vertexCount)

        for (vertex in vertexList) {
            val old = vertex.backingInstance
            val copiedInstance = old.copy()
            oldToNew[old] = copiedInstance
            val copiedVertex = MdeoVertex(vertex.id, vertex.label, copy, copiedInstance, vertex.classMetadata)
            newVertexMap[vertex.id] = copiedVertex
            newVertexList.add(copiedVertex)
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
            copiedOutVertex.outEdges!!.getOrPut(edge.label) { mutableSetOf() }.add(copiedEdge)
            if (copiedInVertex.inEdges == null) copiedInVertex.inEdges = HashMap()
            copiedInVertex.inEdges!!.getOrPut(edge.label) { mutableSetOf() }.add(copiedEdge)
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
     * @return A fully populated [Model] reflecting the current graph state.
     */
    internal fun toModel(registry: InstanceNameRegistry): Model {
        val newInstancesByName = LinkedHashMap<String, ModelInstance>(vertexList.size * 2)
        for (vertex in vertexList) {
            val logicalName = registry.getName(vertex.id) ?: vertex.id.toString()
            newInstancesByName[logicalName] = vertex.backingInstance
        }
        return Model(metamodel, metamodel.path, newInstancesByName)
    }

    override fun vertices(vararg vertexIds: Any): Iterator<Vertex> {
        if (vertexIds.isEmpty()) {
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
     * Shuffles the vertex iteration list and all per-vertex edge adjacency sets to
     * provide nondeterministic traversal order.
     *
     * Shuffling only the vertex list is insufficient: when a vertex has multiple outgoing
     * edges with the same label (e.g. a Sprint with several committedItems), the
     * adjacency set preserves its original insertion order and would always yield the
     * same neighbour first during an edge-walk traversal.  Shuffling those sets ensures
     * every edge in the neighbourhood has an equal chance of being the first hit.
     */
    internal fun shuffleVertices() {
        vertexList.shuffle()
        for (vertex in vertexList) {
            vertex.outEdges?.forEach { (_, edgeSet) ->
                val shuffled = edgeSet.shuffled()
                edgeSet.clear()
                edgeSet.addAll(shuffled)
            }
            vertex.inEdges?.forEach { (_, edgeSet) ->
                val shuffled = edgeSet.shuffled()
                edgeSet.clear()
                edgeSet.addAll(shuffled)
            }
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
