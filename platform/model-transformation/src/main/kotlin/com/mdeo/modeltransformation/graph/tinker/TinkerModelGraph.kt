package com.mdeo.modeltransformation.graph.tinker

import com.mdeo.metamodel.Metamodel
import com.mdeo.metamodel.SerializedModel
import com.mdeo.metamodel.data.MetamodelData
import com.mdeo.metamodel.data.ModelData
import com.mdeo.modeltransformation.graph.ModelGraph
import com.mdeo.modeltransformation.graph.ModelMetadata
import com.mdeo.modeltransformation.graph.VertexRef
import com.mdeo.modeltransformation.runtime.InstanceNameRegistry
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource
import org.apache.tinkerpop.gremlin.structure.T
import org.apache.tinkerpop.gremlin.structure.Vertex
import org.apache.tinkerpop.gremlin.structure.VertexProperty
import org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerGraph
import java.lang.ref.WeakReference

/**
 * In-process TinkerGraph implementation of [ModelGraph].
 *
 * Vertices store model instance properties under `prop_X` graph keys matching
 * the compiled metamodel field indices. Edges represent associations using
 * computed edge labels.
 *
 * Deep copies always shuffle vertex insertion order and generate new auto-IDs,
 * providing nondeterministic traversal behavior for search-based optimization.
 *
 * @param graph The underlying TinkerGraph instance.
 * @param metamodel The compiled metamodel for graph key resolution.
 * @param nameRegistry Registry mapping vertex IDs to instance names.
 * @param nondeterminismResetCount Tracks how many times [resetNondeterminism] has been called.
 */
class TinkerModelGraph private constructor(
    private var graph: TinkerGraph,
    override val metamodel: Metamodel,
    override var nameRegistry: InstanceNameRegistry,
    private val metamodelPath: String,
    private var nondeterminismResetCount: Int = 0
) : ModelGraph {

    /**
     * Weak references to all [VertexRef] instances created by this graph.
     *
     * Using weak references prevents this list from keeping [VertexRef] objects
     * alive after their owning [InstanceBinding] (and its scope) have been
     * garbage-collected. Dead references are pruned during [resetNondeterminism].
     */
    private val trackedRefs = mutableListOf<WeakReference<VertexRef>>()

    override var metadata: ModelMetadata? = null

    override fun createVertexRef(rawId: Any): VertexRef {
        val ref = VertexRef(rawId)
        trackedRefs.add(WeakReference(ref))
        return ref
    }

    override fun traversal(): GraphTraversalSource = graph.traversal()

    override fun deepCopy(): TinkerModelGraph {
        val (newGraph, newRegistry) = copyGraphShuffled()
        return TinkerModelGraph(newGraph, metamodel, newRegistry, metamodelPath).also {
            it.metadata = metadata?.deepCopy()
        }
    }

    override fun resetNondeterminism(): Map<Any, Any> {
        if (nondeterminismResetCount++ == 0) return emptyMap()

        val (newGraph, newRegistry, vertexIdMap) = copyGraphShuffledWithIdMap()
        graph.close()
        graph = newGraph
        nameRegistry = newRegistry

        // Update all live VertexRef instances to their new IDs, and prune dead refs.
        val iter = trackedRefs.iterator()
        while (iter.hasNext()) {
            val weakRef = iter.next()
            val ref = weakRef.get()
            if (ref == null) {
                iter.remove()
            } else {
                val newId = vertexIdMap[ref.rawId]
                if (newId != null) {
                    ref.rawId = newId
                }
            }
        }

        return vertexIdMap
    }

    override fun toModelData(): ModelData {
        val converter = GraphToModelDataConverter(metamodel)
        return converter.convert(traversal(), metamodelPath, nameRegistry)
    }

    /**
     * Returns a [SerializedModel.AsModelData] wrapping the [ModelData] produced
     * by [toModelData], since TinkerGraph-backed models do not benefit from the
     * binary serialization path.
     */
    override fun toSerializedModel(): SerializedModel = SerializedModel.AsModelData(toModelData())

    override fun toModel(): com.mdeo.metamodel.Model = metamodel.loadModel(toModelData())

    @Suppress("UNCHECKED_CAST")
    override fun addVertexStep(
        traversal: GraphTraversal<Vertex, Map<String, Any>>,
        className: String,
        stepLabel: String
    ): GraphTraversal<Vertex, Map<String, Any>> =
        traversal.addV(className).`as`(stepLabel) as GraphTraversal<Vertex, Map<String, Any>>

    override fun close() {
        graph.close()
    }

    /**
     * Copies the graph with shuffled vertex insertion order and auto-generated IDs.
     * Returns the new graph and an updated name registry.
     */
    private fun copyGraphShuffled(): Pair<TinkerGraph, InstanceNameRegistry> {
        val (newGraph, newRegistry, _) = copyGraphShuffledWithIdMap()
        return newGraph to newRegistry
    }

    /**
     * Copies the graph with shuffled vertex order, returning the new graph,
     * updated name registry, and old-to-new vertex ID mapping.
     */
    private fun copyGraphShuffledWithIdMap(): Triple<TinkerGraph, InstanceNameRegistry, Map<Any, Any>> {
        val copy = TinkerGraph.open()
        val vertices = graph.vertices().asSequence().toList().shuffled()
        val vertexIdMap = mutableMapOf<Any, Any>()
        val vertexMap = mutableMapOf<Any, Vertex>()

        for (vertex in vertices) {
            val newVertex = copy.addVertex(T.label, vertex.label())
            vertex.properties<Any>().forEachRemaining { vp ->
                newVertex.property(VertexProperty.Cardinality.list, vp.key(), vp.value())
            }
            vertexIdMap[vertex.id()] = newVertex.id()
            vertexMap[vertex.id()] = newVertex
        }

        graph.edges().asSequence().toMutableList().shuffled().forEach { edge ->
            val fromVertex = vertexMap[edge.outVertex().id()]
                ?: error("Source vertex ${edge.outVertex().id()} not found in copy")
            val toVertex = vertexMap[edge.inVertex().id()]
                ?: error("Target vertex ${edge.inVertex().id()} not found in copy")
            fromVertex.addEdge(edge.label(), toVertex)
        }

        val newRegistry = rebuildNameRegistry(vertexIdMap)
        return Triple(copy, newRegistry, vertexIdMap)
    }

    /**
     * Rebuilds the name registry with updated vertex IDs from a vertex ID mapping.
     */
    private fun rebuildNameRegistry(vertexIdMap: Map<Any, Any>): InstanceNameRegistry {
        val newRegistry = InstanceNameRegistry()
        for ((oldId, name) in nameRegistry.getAllMappings()) {
            val newId = vertexIdMap[oldId] ?: oldId
            newRegistry.register(newId, name)
        }
        return newRegistry
    }

    companion object {

        /**
         * Creates a [TinkerModelGraph] by loading [ModelData] into a fresh TinkerGraph.
         *
         * @param modelData The model data to load.
         * @param metamodel The compiled metamodel for graph key resolution.
         * @return A new [TinkerModelGraph] containing the loaded model.
         */
        fun create(modelData: ModelData, metamodel: Metamodel): TinkerModelGraph {
            val graph = TinkerGraph.open()
            val g = graph.traversal()
            val nameRegistry = InstanceNameRegistry()
            ModelDataGraphLoader().load(g, modelData, nameRegistry, metamodel)
            return TinkerModelGraph(graph, metamodel, nameRegistry, modelData.metamodelPath)
        }

        /**
         * Creates a [TinkerModelGraph] by wrapping an existing [TinkerGraph].
         *
         * The given graph is used as-is without copying. This is primarily intended for
         * testing scenarios where vertices are added directly to the underlying graph via a
         * [org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource]
         * obtained from it. Nondeterminism resetting via [resetNondeterminism] is supported,
         * but the first call is always a no-op.
         *
         * @param graph The TinkerGraph to wrap.
         * @param metamodel The compiled metamodel for graph key resolution. Defaults to an empty metamodel.
         * @return A new [TinkerModelGraph] wrapping the given graph.
         */
        fun wrap(
            graph: TinkerGraph,
            metamodel: Metamodel = Metamodel.compile(MetamodelData.empty())
        ): TinkerModelGraph {
            return TinkerModelGraph(graph, metamodel, InstanceNameRegistry(), "")
        }
    }
}
