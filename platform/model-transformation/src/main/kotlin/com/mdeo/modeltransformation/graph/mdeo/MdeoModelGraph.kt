package com.mdeo.modeltransformation.graph.mdeo

import com.mdeo.metamodel.Metamodel
import com.mdeo.metamodel.Model
import com.mdeo.metamodel.ModelBinarySerializer
import com.mdeo.metamodel.ModelInstance
import com.mdeo.metamodel.SerializedModel
import com.mdeo.metamodel.data.ModelData
import com.mdeo.modeltransformation.graph.ModelGraph
import com.mdeo.modeltransformation.graph.ModelMetadata
import com.mdeo.modeltransformation.graph.VertexRef
import com.mdeo.modeltransformation.runtime.InstanceNameRegistry
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource
import org.apache.tinkerpop.gremlin.process.traversal.strategy.optimization.LazyBarrierStrategy
import org.apache.tinkerpop.gremlin.structure.Vertex
import java.lang.ref.WeakReference
import java.util.IdentityHashMap

/**
 * Thin [ModelGraph] wrapper over [MdeoGraph].
 *
 * Graph construction, deep-copy mechanics, and model ownership are implemented
 * directly by [MdeoGraph]. This wrapper owns the [InstanceNameRegistry] so match
 * execution, export, and deep-copy behavior can control logical instance names
 * independently from the backing graph's internal placeholder instance names.
 */
class MdeoModelGraph private constructor(
    private var graph: MdeoGraph,
    private val instanceNameRegistry: InstanceNameRegistry
) : ModelGraph {

    /**
     * Weak references to all [VertexRef] instances created by this graph.
     *
     * Using weak references prevents this list from keeping [VertexRef] objects
     * alive after their owning scope has been garbage-collected.
     * Dead references are pruned during [resetNondeterminism].
     */
    private val trackedRefs = mutableListOf<WeakReference<VertexRef>>()

    override var metadata: ModelMetadata? = null

    override val metamodel: Metamodel
        get() = graph.metamodel

    override val nameRegistry: InstanceNameRegistry
        get() = instanceNameRegistry

    override fun createVertexRef(rawId: Any): VertexRef {
        val ref = VertexRef(rawId)
        trackedRefs.add(WeakReference(ref))
        return ref
    }

    override fun traversal(): GraphTraversalSource =
        graph.traversal().withoutStrategies(LazyBarrierStrategy::class.java)

    override fun deepCopy(): MdeoModelGraph {
        return MdeoModelGraph(graph.deepCopy(), instanceNameRegistry.copy()).also {
            it.metadata = metadata?.deepCopy()
        }
    }

    /**
     * Shuffles the vertex iteration list to provide nondeterministic traversal order.
     *
     * Since vertex IDs remain stable (no graph rebuild), this always returns an empty map.
     * This method also prunes dead [VertexRef] weak references.
     */
    override fun resetNondeterminism(): Map<Any, Any> {
        graph.shuffleVertices()

        val iter = trackedRefs.iterator()
        while (iter.hasNext()) {
            if (iter.next().get() == null) {
                iter.remove()
            }
        }

        return emptyMap()
    }

    override fun toModelData(): ModelData {
        return toModel().toModelData()
    }

    /**
     * Returns a [SerializedModel.AsBinary] by serializing the backing model instances
     * directly via [ModelBinarySerializer], avoiding the overhead of constructing
     * intermediate [ModelData] objects.
     */
    override fun toSerializedModel(): SerializedModel {
        val model = toModel()
        val serializer = ModelBinarySerializer(metamodel)
        val nameByInstance = IdentityHashMap<ModelInstance, String>(model.instancesByName.size)
        for ((name, instance) in model.instancesByName) {
            nameByInstance[instance] = name
        }
        return SerializedModel.AsBinary(serializer.serialize(model, nameByInstance))
    }

    override fun toModel(): Model = graph.toModel(instanceNameRegistry)

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

    companion object {
        /**
         * Creates an [MdeoModelGraph] by loading [ModelData] into a fresh [MdeoGraph].
         *
         * @param modelData The model data to load.
         * @param metamodel The compiled metamodel governing model structure.
         * @return A new [MdeoModelGraph] containing the loaded model.
         */
        fun create(modelData: ModelData, metamodel: Metamodel): MdeoModelGraph {
            val model = metamodel.loadModel(modelData)
            val registry = InstanceNameRegistry()
            val graph = MdeoGraph.open(metamodel, model, registry)
            return MdeoModelGraph(graph, registry)
        }

        /**
         * Creates an [MdeoModelGraph] from a [SerializedModel], choosing the fastest
         * deserialization path for the backing format.
         *
         * For [SerializedModel.AsBinary], uses [ModelBinarySerializer] to reconstruct
         * instances directly without going through [ModelData].
         * For [SerializedModel.AsModelData], falls back to the standard [create] path.
         *
         * @param serializedModel The serialized model to load.
         * @param metamodel The compiled metamodel governing model structure.
         * @return A new [MdeoModelGraph] containing the deserialized model.
         */
        fun create(serializedModel: SerializedModel, metamodel: Metamodel): MdeoModelGraph {
            return when (serializedModel) {
                is SerializedModel.AsBinary -> {
                    val serializer = ModelBinarySerializer(metamodel)
                    val (model, nameByInstance) = serializer.deserialize(serializedModel.data)
                    val registry = InstanceNameRegistry()
                    val graph = MdeoGraph.open(metamodel, model, registry)
                    MdeoModelGraph(graph, registry)
                }
                is SerializedModel.AsModelData -> {
                    create(serializedModel.modelData, metamodel)
                }
            }
        }
    }
}
